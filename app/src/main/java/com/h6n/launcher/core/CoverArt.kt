package com.h6n.launcher.core

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.util.Locale

/**
 * U 盘曲目封面读取（6.0.104）。
 *
 * ## 为什么单独抽出来
 * 6.0.75 起封面只走 `MediaMetadataRetriever.getEmbeddedPicture()`，实车反馈
 * 「U 盘音乐一个封面都不显示」。这条路太单薄：
 * - 老 ROM 的 Stagefright 对 `setDataSource(fd)` 支持的容器有限（FLAC / WMA / APE 常直接抛）；
 * - 文件没内嵌图时只有「同名 jpg / folder.jpg」两级兜底，命名稍不规范就落空。
 *
 * 现在按「便宜到贵」四级挨个试，任何一级命中就返回：
 * ```
 * ① MediaMetadataRetriever（先路径，再 fd —— 播放走 fd，读封面走路径各自兜一遍）
 * ② 自己解析 ID3v2 的 APIC 帧（mp3）
 * ③ 自己解析 FLAC 的 PICTURE 块（type 6）
 * ④ 同目录图片：同名 → cover/folder/album/front → 目录里唯一一张图
 * ```
 * ②③ 是纯 Java 解析，不依赖平台解码器 —— 平台不认的容器照样能拿到图。
 *
 * ## 结果为什么分三种
 * 「文件里确实没有封面」和「现在读不出来」必须分开：前者要缓存（否则每次刷卡片都重读
 * 一遍几十 MB 的文件头），后者**绝不能缓存** —— U 盘刚插上、挂载还没就绪时会读失败，
 * 缓存成 null 就整趟开机再也不显示封面了（6.0.75 那个「永远没封面」的可疑成因之一）。
 */
object CoverArt {

    private const val TAG = "H6Art"

    /** 解码长边上限。卡片封面只有 100dp，256px 已经足够清晰。 */
    private const val MAX_PX = 256

    /** 只读这么多字节的标签区，防止把上百 MB 的文件整个读进内存。 */
    private const val MAX_TAG = 8 * 1024 * 1024

    private val PIC_EXTS = setOf("jpg", "jpeg", "png", "webp", "bmp")

    private const val NONE_WHY = "文件里没有内嵌封面，目录里也没有同名图"

    sealed class Result {
        /** 读到了。 */
        class Ok(val art: Bitmap, val from: String) : Result()

        /** 文件能打开、但确实没有封面 —— 可以缓存。 */
        class None(val why: String = NONE_WHY) : Result()

        /** 现在读不出来（U 盘没挂好 / IO 错）—— 不缓存，稍后重试。 */
        class Fail(val why: String) : Result()
    }

    // ------------------------------------------------------------------ 入口

    fun load(path: String): Result {
        val f = File(path)
        if (!f.isFile) return Result.Fail("文件不在（U 盘没挂上？）")
        if (!f.canRead()) return Result.Fail("文件不可读（存储权限？）")

        retriever(f)?.let { return Result.Ok(it, "平台解码") }
        id3(f)?.let { return Result.Ok(it, "ID3 内嵌") }
        flac(f)?.let { return Result.Ok(it, "FLAC 内嵌") }
        sidecar(f)?.let { return Result.Ok(it, "同目录图") }
        return Result.None()
    }

    // ------------------------------------------------------ ① 平台解码器

    private fun retriever(f: File): Bitmap? {
        // 路径：绕开 fd 那套兼容性问题，绝大多数 ROM 这条最稳
        val byPath = runCatching {
            val r = MediaMetadataRetriever()
            try {
                r.setDataSource(f.absolutePath)
                r.embeddedPicture?.let { decode(it) }
            } finally {
                runCatching { r.release() }
            }
        }.getOrNull()
        if (byPath != null) return byPath

        // fd：scoped storage 挡住路径时兜底（和播放走同一条路）
        return runCatching {
            val r = MediaMetadataRetriever()
            try {
                FileInputStream(f).use { fs ->
                    r.setDataSource(fs.fd)
                    r.embeddedPicture?.let { decode(it) }
                }
            } finally {
                runCatching { r.release() }
            }
        }.getOrNull()
    }

    // ------------------------------------------------------ ② ID3v2 APIC

    /**
     * mp3 的 ID3v2 标签：`ID3` + 版本 + 标志 + 4 字节同步安全长度，
     * 之后是一串帧（v2.2 是 3 字节 id + 3 字节长度，v2.3/2.4 是 4 + 4）。
     * 找 `APIC`（v2.2 叫 `PIC`），按编码字节决定描述串是 1 字节还是 2 字节结尾。
     */
    private fun id3(f: File): Bitmap? {
        val head = ByteArray(10)
        val n = runCatching { FileInputStream(f).use { it.read(head) } }.getOrDefault(-1)
        if (n < 10) return null
        if (head[0] != 'I'.code.toByte() || head[1] != 'D'.code.toByte() || head[2] != '3'.code.toByte()) return null

        val major = head[3].toInt() and 0xFF
        if (major < 2 || major > 4) return null
        val size = syncSafe(head, 6)
        if (size <= 0 || size > MAX_TAG) return null

        val tag = ByteArray(size)
        val got = runCatching {
            FileInputStream(f).use { ins ->
                var skip = 10L
                while (skip > 0) {
                    val s = ins.skip(skip)
                    if (s <= 0) break
                    skip -= s
                }
                var off = 0
                while (off < size) {
                    val r = ins.read(tag, off, size - off)
                    if (r <= 0) break
                    off += r
                }
                off
            }
        }.getOrDefault(0)
        if (got < 10) return null

        var p = 0
        // 扩展头（v2.3 的 4 字节长度不含自身这 4 字节，v2.4 是同步安全整数）
        if ((head[5].toInt() and 0x40) != 0 && major >= 3 && got >= 8) {
            p = if (major == 4) syncSafe(tag, 0) else be32(tag, 0) + 4
        }

        while (p >= 0 && p + 6 <= got) {
            val idLen = if (major == 2) 3 else 4
            val headLen = if (major == 2) 3 else 4
            if (p + idLen + headLen > got) break
            val id = String(tag, p, idLen, Charsets.ISO_8859_1)
            if (id[0] == '\u0000' || id.isBlank()) break
            val body = p + idLen + headLen
            val len = when {
                major == 2 -> ((tag[p + 3].toInt() and 0xFF) shl 16) or
                    ((tag[p + 4].toInt() and 0xFF) shl 8) or (tag[p + 5].toInt() and 0xFF)
                major == 4 -> syncSafe(tag, p + 4)
                else -> be32(tag, p + 4)
            }
            if (len <= 0 || len > MAX_TAG) break
            if (body + len > got) break

            val pic: ByteArray? = when {
                id == "APIC" -> apic(tag, body, len)
                id == "PIC" -> pic22(tag, body, len)
                else -> null
            }
            if (pic != null) decode(pic)?.let { return it }
            p = body + len
        }
        return null
    }

    /** `编码 1B | MIME 以 0 结尾 | 图片类型 1B | 描述 以 0 结尾 | 图数据` */
    private fun apic(b: ByteArray, off: Int, len: Int): ByteArray? {
        val end = off + len
        if (off + 4 > end) return null
        val enc = b[off].toInt() and 0xFF
        var p = off + 1
        // MIME
        while (p < end && b[p].toInt() != 0) p++
        p++
        if (p + 1 > end) return null
        p++ // 图片类型
        // 描述：UTF-16 带 BOM 时是 2 字节对齐的 0 结尾，其余是单字节 0
        if (enc == 1 || enc == 2) {
            while (p + 1 < end && !(b[p].toInt() == 0 && b[p + 1].toInt() == 0)) p += 2
            p += 2
        } else {
            while (p < end && b[p].toInt() != 0) p++
            p++
        }
        if (p >= end) return null
        return b.copyOfRange(p, end)
    }

    /** ID3v2.2 的 `PIC`：`编码 1B | 格式 3B | 图片类型 1B | 描述 | 图数据` */
    private fun pic22(b: ByteArray, off: Int, len: Int): ByteArray? {
        val end = off + len
        if (off + 5 > end) return null
        val enc = b[off].toInt() and 0xFF
        var p = off + 4 + 1
        if (enc == 1 || enc == 2) {
            while (p + 1 < end && !(b[p].toInt() == 0 && b[p + 1].toInt() == 0)) p += 2
            p += 2
        } else {
            while (p < end && b[p].toInt() != 0) p++
            p++
        }
        if (p >= end) return null
        return b.copyOfRange(p, end)
    }

    // ------------------------------------------------------ ③ FLAC PICTURE

    /**
     * FLAC：`fLaC` 之后是一串元数据块，头字节 = `最后一块标志(1) | 类型(7)` + 3 字节长度。
     * 类型 6 = PICTURE：`类型 | MIME 长度+MIME | 描述长度+描述 | 宽高深色数 | 图长度+图`。
     */
    private fun flac(f: File): Bitmap? = runCatching {
        FileInputStream(f).use { fs ->
            val ins = BufferedInputStream(fs, 64 * 1024)
            val magic = ByteArray(4)
            if (readFully(ins, magic) < 4) return null
            if (String(magic, Charsets.ISO_8859_1) != "fLaC") return null

            var last = false
            var guard = 0
            while (!last && guard++ < 64) {
                val b0 = ins.read()
                if (b0 < 0) return null
                last = (b0 and 0x80) != 0
                val type = b0 and 0x7F
                val len = ((ins.read() and 0xFF) shl 16) or
                    ((ins.read() and 0xFF) shl 8) or (ins.read() and 0xFF)
                if (len <= 0) continue
                if (type != 6) {
                    if (!skipFully(ins, len.toLong())) return null
                    continue
                }
                if (len > MAX_TAG) return null
                val data = ByteArray(len)
                if (readFully(ins, data) < len) return null
                return@use picture(data)?.let { decode(it) }
            }
            null
        }
    }.getOrNull()

    private fun picture(d: ByteArray): ByteArray? {
        if (d.size < 32) return null
        var p = 4                              // 图片类型
        val mimeLen = be32(d, p); p += 4 + mimeLen
        if (p + 4 > d.size) return null
        val descLen = be32(d, p); p += 4 + descLen
        p += 16                                // 宽 / 高 / 色深 / 索引色数
        if (p + 4 > d.size) return null
        val dataLen = be32(d, p); p += 4
        if (dataLen <= 0 || p + dataLen > d.size) return null
        return d.copyOfRange(p, p + dataLen)
    }

    // ------------------------------------------------------ ④ 同目录图片

    private fun sidecar(f: File): Bitmap? {
        val dir = f.parentFile ?: return null
        val pics = runCatching {
            dir.listFiles()?.toList().orEmpty().filter { c ->
                c.isFile && c.name.substringAfterLast('.', "").lowercase(Locale.ROOT) in PIC_EXTS
            }
        }.getOrDefault(emptyList())
        if (pics.isEmpty()) return null

        val base = f.name.substringBeforeLast('.', f.name)
        pics.firstOrNull { it.name.substringBeforeLast('.', it.name).equals(base, ignoreCase = true) }
            ?.let { decodeFile(it)?.let { b -> return b } }

        for (n in listOf("cover", "folder", "album", "front", "albumart")) {
            pics.firstOrNull { it.name.substringBeforeLast('.', it.name).equals(n, ignoreCase = true) }
                ?.let { decodeFile(it)?.let { b -> return b } }
        }

        // 目录里只有一张图：基本就是这张专辑的封面（U 盘常见的「一首歌 + 一张图」）
        if (pics.size == 1) decodeFile(pics[0])?.let { return it }
        return null
    }

    // ------------------------------------------------------ 解码

    private fun decodeFile(f: File): Bitmap? = sample(
        { o -> BitmapFactory.decodeFile(f.absolutePath, o) }
    )

    private fun decode(raw: ByteArray): Bitmap? = sample(
        { o -> BitmapFactory.decodeByteArray(raw, 0, raw.size, o) }
    )

    /** 先只解 bounds 算采样率，再解一张不超过 [MAX_PX] 的图（省内存，卡片够用）。 */
    private fun sample(real: (BitmapFactory.Options) -> Bitmap?): Bitmap? {
        val o1 = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        runCatching { real(o1) }
        val long = o1.outWidth.coerceAtLeast(o1.outHeight)
        if (long <= 0) return null
        var s = 1
        while (long / s > MAX_PX * 2) s *= 2
        val o2 = BitmapFactory.Options().apply {
            inSampleSize = s
            inPreferredConfig = Bitmap.Config.RGB_565
        }
        return runCatching { real(o2) }.getOrNull()
    }

    // ------------------------------------------------------ 字节工具

    private fun be32(b: ByteArray, off: Int): Int {
        if (off + 4 > b.size) return 0
        return ((b[off].toInt() and 0xFF) shl 24) or
            ((b[off + 1].toInt() and 0xFF) shl 16) or
            ((b[off + 2].toInt() and 0xFF) shl 8) or
            (b[off + 3].toInt() and 0xFF)
    }

    /** ID3 的「同步安全整数」：每字节只用低 7 位。 */
    private fun syncSafe(b: ByteArray, off: Int): Int {
        if (off + 4 > b.size) return 0
        return ((b[off].toInt() and 0x7F) shl 21) or
            ((b[off + 1].toInt() and 0x7F) shl 14) or
            ((b[off + 2].toInt() and 0x7F) shl 7) or
            (b[off + 3].toInt() and 0x7F)
    }

    private fun readFully(ins: InputStream, out: ByteArray): Int {
        var off = 0
        while (off < out.size) {
            val r = ins.read(out, off, out.size - off)
            if (r <= 0) break
            off += r
        }
        return off
    }

    private fun skipFully(ins: InputStream, n: Long): Boolean {
        var left = n
        while (left > 0) {
            val s = ins.skip(left)
            if (s <= 0) {
                if (ins.read() < 0) return false
                left -= 1
            } else {
                left -= s
            }
        }
        return true
    }
}
