package com.h6n.launcher.core

import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Base64
import com.h6n.launcher.core.H6Log as Log
import java.io.Closeable
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.math.BigInteger
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.Signature
import java.security.interfaces.RSAPrivateCrtKey
import java.security.spec.PKCS8EncodedKeySpec
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.CRC32

/**
 * 「不点任何系统开关、自动把权限全部给掉」的执行器。
 *
 * ## 为什么上一版没打通
 * 1. `WRITE_SECURE_SETTINGS` / `READ_LOGS` **没在 Manifest 声明**，`pm grant` 对未声明的
 *    权限一律拒绝 —— 这是最致命的一条，已在 6.0.51 补上声明；
 * 2. 判定「无线 ADB 已开」用的是 `Settings.Global.adb_wifi_enabled`，这个键是
 *    **Android 11 才有的**。车机是 8.1，恒为 0，所以哪怕 adbd 早已在 5555 监听，
 *    面板也永远显示「无线调试未开」；
 * 3. 旧 `work()` 只 grant 了一项权限，而且只 `settings put global`，从没执行
 *    `setprop service.adb.tcp.port 5555` + 重启 adbd —— 这才能让 8.1 的 adbd 真正监听 TCP。
 *
 * ## 现在做什么
 * 每次进桌面自动跑一遍：把「权限激活」面板 8 项里能用命令解决的 7 项一次性授予，
 * 顺带把 adbd 切到 TCP 5555。按代价从低到高选通道：
 *
 * 1. **本机属性直写**（6.0.56 新增，应用管家没有的通道）—— 不需要任何权限，
 *    反射 `SystemProperties.set` 直接写 `service.adb.tcp.port` / `persist.adb.tcp.port`。
 *    德赛 / 长城工程固件大量是 SELinux Permissive，届时 init 会按
 *    `on property:service.adb.tcp.port=*` 自动把 adbd 切到 5555，全程无弹窗；
 *    enforcing 系统写不进去，失败无害；
 * 2. `su`（root）—— 与应用管家同款：`setprop` 两个属性 + `ctl.restart adbd`，
 *    先切 TCP（命令串短、独立验证），再跑全量授权脚本，全程无弹窗；
 * 3. 本机 adbd 已在 5555 监听 —— 用内置 ADB 协议跑同一套命令，不需要电脑；
 * 4. 已有 `WRITE_SECURE_SETTINGS` —— 用 Java API 补写两个 global 键兜底。
 *
 * 8.1 工程机常见 `ro.adb.secure=0`，ADB 连接不会弹「允许 USB 调试」；
 * 只有系统开了 adb 认证且是首次连接时才会弹一次，那是系统强制的，
 * 我们没有（也不该有）绕过它的能力。su 若挂着 Magisk 之类弹框，别在超时里掐太狠 ——
 * 用户点一次「允许」，下一轮 auto() 就通了。
 *
 * ## 6.0.83：真正的失败原因不是「没 root」，是端口猜不到
 * 车机（含应用管家那套「无线调试」）给 adbd 分配的是**随机高位端口**，
 * 之前实测两次分别是 40261 / 39057 —— 而旧版只固定试 5555/5556/5557，
 * 于是哪怕用户已经在应用管家里把调试口打开了，桌面照样连不上，
 * 最后只能落到「无 root」那句死路上。
 *
 * 现在先把端口找出来再谈授权：
 * 1. 上次连通的端口（`h6_adb_grant/port`）；
 * 2. 常见口 5555～5557；
 * 3. `/proc/net/tcp{,6}` 里状态 LISTEN 且 uid 为 0/2000 的端口 —— adbd 就在里面；
 * 4. 前三步都空时，才用非阻塞 connect 扫一遍 30000～50100
 *    （厂商无线调试的随机端口就落在这一段），一次进程只扫一次。
 *
 * 打通之后立刻做「固化」：`setprop persist.adb.tcp.port 5555` +
 * `settings put global adb_enabled 1`，下次开机 adbd 自己就守在 5555，
 * 应用管家那一步只需要用户做一次，之后桌面永久免人工。
 *
 * ADB 协议那段（文件级私有类）保持不动，只跑 CNXN/AUTH/OPEN/WRTE/CLSE，
 * 够执行 `pm grant` / `appops set` / `settings put`，不引 bouncycastle。
 */
object AdbGrant {

    private const val TAG = "H6Adb"

    private const val SP = "h6_adb_grant"
    private const val K_WIRELESS_OK = "wireless_ok"

    /** 6.0.83：记住上次真正连通的端口，随机端口也不会再丢。 */
    private const val K_PORT = "port"

    /** RSA 私钥落在应用私有目录，生成一次、永远复用（换密钥会让车机重新弹授权）。 */
    private const val KEY_FILE = "adbkey.pk8"

    /**
     * 6.0.83：车机「应用管家」开无线调试时分配的是随机高位端口
     * （实测 40261 / 39057），固定端口列表只能当第一波便宜试探。
     */
    private const val SCAN_FROM = 30000
    private const val SCAN_TO = 50100

    /** 应用管家：车机上唯一能把无线调试打开的入口，我们自己没这个权限。 */
    const val MANAGER_PKG = "com.yunpan.appmanage"

    /** 本机 adbd 可能监听的端口。Android 11+ 无线调试是随机端口，猜不到。 */
    private val PORTS = intArrayOf(5555, 5556, 5557)

    private const val HOST = "127.0.0.1"

    /** 无障碍组件的完整类名（Manifest 里是 `.ui.main.H6A11yService`）。 */
    private const val A11Y_CLASS = "com.h6n.launcher.ui.main.H6A11yService"

    /**
     * 能用命令 / 静默写入解决的核心项，直接取 [PermGuide] 的「非可选」清单：
     * 6.0.88 起「桌面所有权限」= 权限面板里除 READ_LOGS 之外的全部项，
     * 所以 USAGE / BATTERY / INSTALL 这些以前不在 CORE 里的也纳入判定。
     * READ_LOGS 仍照发 grant 命令（见 [grantScript]），但不拿它卡完成，
     * 免得个别 ROM 永远拒绝导致每次回桌面都白跑一遍。
     * 6.0.87：加入 NOTIF（通知使用权）—— 音乐卡片读歌的唯一通道，
     * 之前漏判导致「setting 没写进去也算全部授权」。
     */
    private val CORE: List<String> = PermGuide.CORE.map { it.id }

    /** 同一时刻只跑一条授权链，避免 onResume 连点叠出多个 su / socket。 */
    private val running = AtomicBoolean(false)

    @Volatile
    private var lastTryMs = 0L

    /**
     * 最近一次「本机 adb 自连」的结果，给设置页诊断行用（6.0.106）。
     *
     * 实车上「连不上」有三种完全不同的原因 —— 找不到端口 / 握手没过（没授权）/
     * 连上了但命令被拒。以前只能看日志，现在这一行直接写清楚走到哪一步。
     */
    @Volatile
    private var authNote: String = ""

    fun authNote(): String = authNote

    // ================================================================== 状态

    fun adbEnabled(ctx: Context): Boolean = globalInt(ctx, "adb_enabled") == 1

    /** Android 11+ 才有的键；8.1 恒为 0，现在只作参考，不再拿它判定无线 ADB。 */
    fun adbWifiEnabled(ctx: Context): Boolean = globalInt(ctx, "adb_wifi_enabled") == 1

    fun secureGranted(ctx: Context): Boolean = runCatching {
        ctx.checkSelfPermission("android.permission.WRITE_SECURE_SETTINGS") ==
            PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)

    private fun globalInt(ctx: Context, key: String): Int =
        runCatching { Settings.Global.getInt(ctx.contentResolver, key, 0) }.getOrDefault(0)

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(SP, Context.MODE_PRIVATE)

    /** Android 8~10 的 TCP 无线 ADB 靠这个属性；反射可读（@hide 但读不受限）。 */
    fun tcpPort(ctx: Context): Int = runCatching {
        val c = Class.forName("android.os.SystemProperties")
        val m = c.getMethod("get", String::class.java, String::class.java)
        (m.invoke(null, "service.adb.tcp.port", "") as? String)?.trim()?.toIntOrNull() ?: -1
    }.getOrDefault(-1)

    private fun markWireless(ctx: Context, on: Boolean) {
        runCatching { prefs(ctx).edit().putBoolean(K_WIRELESS_OK, on).apply() }
    }

    /** 主线程安全：读属性 + 上次后台探测结果，不开 socket。 */
    fun wirelessOn(ctx: Context): Boolean {
        if (prefs(ctx).getBoolean(K_WIRELESS_OK, false)) return true
        return adbEnabled(ctx) && tcpPort(ctx) > 0
    }

    /** 最近一次真正跑通的端口（可能不是 5555，厂商随机分配）。 */
    fun lastPort(ctx: Context): Int = runCatching { prefs(ctx).getInt(K_PORT, -1) }.getOrDefault(-1)

    /** 「已开启 / 待打通」的一句话状态，给面板和设置页摘要行用。 */
    fun summary(ctx: Context): String {
        val p = lastPort(ctx).takeIf { it > 0 } ?: tcpPort(ctx)
        return if (wirelessOn(ctx)) {
            "调试口已通 · 端口 " + (if (p > 0) p.toString() else "5555") + " · 权限桌面自己补"
        } else if (adbEnabled(ctx)) {
            "USB 调试已开，网络调试口没开 —— 借应用管家的「无线调试」开一次即可"
        } else {
            "调试口没开：桌面没有 root，只能借应用管家的「无线调试」开一次"
        }
    }

    /** 后台线程用：真去连一次端口；成功就把结果记下来给面板显示。 */
    private fun probePort(): Int = openPorts().firstOrNull() ?: -1

    /** 一批候选里，哪些真的有人在听（被拒是秒回，不会拖时间）。 */
    private fun openPorts(cands: List<Int> = candidatePorts(null)): List<Int> {
        val out = ArrayList<Int>(4)
        for (p in cands) {
            val s = Socket()
            try {
                s.connect(InetSocketAddress(HOST, p), 300)
                out.add(p)
            } catch (t: Throwable) {
                // 端口没开，试下一个
            } finally {
                runCatching { s.close() }
            }
        }
        return out
    }

    // -------------------------------------------------------------- 端口发现

    /** 本进程是否已经扫过一遍（扫全段要几秒，别每轮回桌面都来一次）。 */
    @Volatile
    private var scanned = false

    /**
     * 候选端口，按「越可能越先试」排序：
     * 记住的 → 常见口 → `/proc/net/tcp` 的 adbd 监听口 → 超大范围扫描（仅当前面全空）。
     *
     * `ctx` 可以为 null（探测函数复用），此时不读记忆端口。
     */
    private fun candidatePorts(ctx: Context?): List<Int> {
        val out = LinkedHashSet<Int>()
        if (ctx != null) {
            val last = runCatching { prefs(ctx).getInt(K_PORT, -1) }.getOrDefault(-1)
            if (last in 1..65535) out.add(last)
        }
        for (p in PORTS) out.add(p)
        val fromProc = procListenPorts()
        out.addAll(fromProc)

        // /proc 读不到（SELinux 挡了）才退到扫描；adbd 常以 shell(2000) 身份跑
        if (fromProc.isEmpty() && !scanned) {
            scanned = true
            out.addAll(scanHighPorts())
        }
        return out.filter { it > 0 }.toList()
    }

    /**
     * 从 `/proc/net/tcp` 与 `/proc/net/tcp6` 里挑出「LISTEN 且 uid 为 0/2000」的端口。
     *
     * 列布局（空白分隔）：`sl local_address rem_address st ... uid timeout inode`，
     * 所以 `st` 是第 4 列（0A = LISTEN）、uid 是第 8 列；端口在 `IP:PORT` 的冒号后，十六进制。
     * 8.1 上普通 app 可读全量；读不到就返回空，交给扫描兜底。
     */
    private fun procListenPorts(): List<Int> {
        val out = ArrayList<Int>(8)
        for (f in arrayOf("/proc/net/tcp", "/proc/net/tcp6")) {
            val lines = runCatching { File(f).readLines() }.getOrNull() ?: continue
            for (i in 1 until lines.size) {
                val c = lines[i].trim().split(WHITESPACE)
                if (c.size < 8) continue
                if (!c[3].equals("0A", true)) continue
                val uid = c[7].toIntOrNull() ?: continue
                if (uid != 0 && uid != 2000) continue
                val port = c[1].substringAfterLast(':').toIntOrNull(16) ?: continue
                if (port > 0 && port !in out) out.add(port)
            }
        }
        return out
    }

    private val WHITESPACE = Regex("\\s+")

    /**
     * 非阻塞扫 SCAN_FROM..SCAN_TO。厂商无线调试的随机端口就在这一段，
     * 被拒的端口在本机 loopback 上是立即 RST，所以 2 万个口只要一两秒。
     * 只在 `/proc` 读不到时跑一次。
     */
    private fun scanHighPorts(): List<Int> {
        val found = ArrayList<Int>(2)
        val sel = runCatching { java.nio.channels.Selector.open() }.getOrNull() ?: return found
        val ports = HashMap<java.nio.channels.SocketChannel, Int>()
        var p = SCAN_FROM
        try {
            while (p <= SCAN_TO) {
                // 上一批没跑完的通道先关掉，否则 fd 会一路涨到被系统掐
                ports.keys.forEach { runCatching { it.close() } }
                ports.clear()
                while (p <= SCAN_TO && ports.size < 384) {
                    runCatching {
                        val ch = java.nio.channels.SocketChannel.open()
                        ch.configureBlocking(false)
                        ch.connect(InetSocketAddress(HOST, p))
                        ch.register(sel, java.nio.channels.SelectionKey.OP_CONNECT)
                        ports[ch] = p
                    }
                    p++
                }
                val end = System.currentTimeMillis() + 500
                while (ports.isNotEmpty() && System.currentTimeMillis() < end) {
                    sel.select(60)
                    val it = sel.selectedKeys().iterator()
                    while (it.hasNext()) {
                        val k = it.next()
                        it.remove()
                        val ch = k.channel() as java.nio.channels.SocketChannel
                        val port = ports.remove(ch) ?: continue
                        if (runCatching { ch.finishConnect() }.getOrDefault(false)) found.add(port)
                        runCatching { ch.close() }
                    }
                }
                ports.keys.forEach { runCatching { it.close() } }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "扫描端口异常: ${t.message}")
        } finally {
            runCatching { sel.close() }
        }
        Log.i(TAG, "扫描 ${SCAN_FROM}~${SCAN_TO} 发现监听口: $found")
        return found
    }

    /** adbd 重启 / 切 TCP 要几秒，轮询探测，别一次 probe 就放弃。 */
    private fun waitForPort(maxMs: Long): Int {
        val end = System.currentTimeMillis() + maxMs
        while (System.currentTimeMillis() < end) {
            val p = probePort()
            if (p > 0) return p
            try {
                Thread.sleep(400)
            } catch (t: InterruptedException) {
                return -1
            }
        }
        return -1
    }

    /**
     * [waitForPort] 的反向：**等端口彻底消失**（6.0.106 关无线调试时用）。
     *
     * `service.adb.tcp.port -1` 之后 adbd 要重起才会松开监听，
     * 立刻 probe 一定是「还在听」，直接判失败会误报。给 6 秒。
     */
    private fun waitNoPort(maxMs: Long): Boolean {
        val end = System.currentTimeMillis() + maxMs
        while (System.currentTimeMillis() < end) {
            if (probePort() <= 0) return true
            try {
                Thread.sleep(400)
            } catch (t: InterruptedException) {
                return false
            }
        }
        return probePort() <= 0
    }

    // ================================================================== 属性直写

    /**
     * 6.0.84：照抄车机「应用管家」实验室里那套「临时开启无线 adb」。
     *
     * 反编译 `com.yunpan.appmanage`（`g7.q2`，`ActivityLaboratory` 里那批开关的
     * 公共点击处理器）可以看到它**没有 root、没有系统签名**，就是
     * `Runtime.exec(new String[]{"sh","-c",cmd})` 把各厂商给调试留的属性全写一遍，
     * 谁生效算谁的，最后 `setprop ctl.restart adbd`。菜单原文
     * 「临时开启无线adb(适合大部分公签系统)」—— 公签版都能用，说明这些属性
     * 对 app 域是放行的（或车机 SELinux 本来就是 permissive）。
     *
     * 原样命令（去重后，顺序保持它的分组）：
     * ```
     * setprop persist.sv.debug.adb_enable 1          // 德赛西威 SV，长城这套车机的调试总开关
     * setprop persist.service.adb.enable 1
     * setprop persist.internet.adb_enable 1
     * setprop persist.sys.adb.wiress.enable 1
     * setprop persist.sys.adb.debug.wiress.enable 1
     * setprop persist.service.adb.button.visible ON
     * setprop persist.adb.tcpip 5555
     * setprop persist.adb.tcp.port 5555
     * setprop persist.adb.tcp.timeout 0
     * setprop sys.connect.adb.wiress 5555
     * setprop service.adb.tcp.port 5555
     * setprop ctl.restart adbd
     * settings put global adb_allowed_connection_time 0
     * ```
     *
     * ★ 故意不写它清单里的 `persist.adb.tls_server.enable` 与
     *   `service.adb.tls.port`：那是 Android 11 无线调试的 TLS 通道，
     *   开了会让 USB adb 也强制配对，可能把用户电脑上的 adb 弄坏；我们只要纯 TCP。
     * ★ 也不碰 `persist.vendor.bosch.usb2.mode`（改 USB 模式的，与本需求无关且有害）。
     * ★ 6.0.106 删掉 `persist.service.adb.enable`：那是 Android 4.x 的 init.rc 规则
     *   （`on property:persist.service.adb.enable=1 → start adbd`），AOSP 从 5.0 起
     *   adbd 就不读它了，改由 `Settings.Global.ADB_ENABLED` 接管。写它只会往
     *   `/data/property` 里多留一条死数据。废属性删掉，不代表少了一条通路。
     */
    private val PROP_GROUP = listOf(
        "persist.sv.debug.adb_enable" to "1",
        "persist.internet.adb_enable" to "1",
        "persist.sys.adb.wiress.enable" to "1",
        "persist.sys.adb.debug.wiress.enable" to "1",
        "persist.service.adb.button.visible" to "ON",
        "persist.adb.tcpip" to "5555",
        "persist.adb.tcp.port" to "5555",
        "persist.adb.tcp.timeout" to "0",
        "sys.connect.adb.wiress" to "5555",
        "service.adb.tcp.port" to "5555"
    )

    /**
     * 德赛西威（sv = Desay SV）给售后留的**无线调试总开关**。
     *
     * 6.0.106 取证：反编译「德赛ADB开关」（`com.desaysv.adbfree`，86 KB，零权限）——
     * 它整个 app 只有两行 `Runtime.exec`，开的写 1、关的写 0，**既不设端口也不重启
     * adbd、更不检查返回值**。能跑通说明固件里有服务盯着这条属性，一改就去起停
     * adbd 的 TCP。这正是它零权限也敢写的原因：`ctl.restart adbd` 那条它根本不需要。
     *
     * 所以在「快通道」之外单列一步：先只写这一条、等 4 秒探端口。
     * 单条就能通的话，就不必写 `persist.adb.tcp.timeout 0`、
     * `persist.service.adb.button.visible ON` 这类会**永久留在车上**、
     * 还会在开发者选项里冒出一个 ADB 按钮的属性。
     */
    private const val PROP_SV = "persist.sv.debug.adb_enable"

    /**
     * 跑一段 `sh -c`，返回合并后的输出。**不阻塞主线程**，调用方都在后台线程。
     * 用独立线程收流：`getprop` 输出几千行，先 waitFor 再读会把管道堵死。
     */
    private fun sh(script: String, timeoutSec: Long = 10L): String = runCatching {
        val p = ProcessBuilder("sh", "-c", script).redirectErrorStream(true).start()
        val sb = StringBuilder()
        val reader = Thread {
            runCatching { p.inputStream.bufferedReader().forEachLine { sb.appendLine(it) } }
        }.apply { isDaemon = true }
        reader.start()
        if (!p.waitFor(timeoutSec, TimeUnit.SECONDS)) p.destroyForcibly()
        reader.join(800)
        sb.toString()
    }.getOrDefault("")

    /** 从 `getprop` 的全量输出里取一个键（格式 `[key]: [value]`）。 */
    private fun propOf(dump: String, key: String): String {
        val tag = "[$key]: ["
        val i = dump.indexOf(tag)
        if (i < 0) return ""
        val s = i + tag.length
        val e = dump.indexOf(']', s)
        return if (e > s) dump.substring(s, e) else ""
    }

    /**
     * 进程内记录「属性通道彻底走不通」，失败一次就不再每轮白等 10 秒。
     * 只要有一条属性真写进去了就**不**标死 —— 车机可能只是还差别的触发条件，
     * 下次回桌面再试一次没坏处。
     */
    @Volatile
    private var propDead = false

    /** 最近一次属性通道真正写进去的键，给面板做一行诊断（不写文件，重启即失效）。 */
    @Volatile
    private var propHits: List<String> = emptyList()

    /** 最近一次属性通道的一句话诊断（含被拒那条的退出码），给面板一行显示。 */
    @Volatile
    private var propNote: String = ""

    /** 面板按它决定诊断行是「成功」色还是普通色。 */
    fun propOk(): Boolean = propHits.isNotEmpty()

    /** 给面板显示的一句话诊断：哪几条调试属性写成功了、被拒那条的退出码。 */
    fun propSummary(): String = when {
        propNote.isNotEmpty() -> propNote
        propHits.isEmpty() -> "还没写过调试属性"
        else -> "已写入 ${propHits.size} 条调试属性：" + propHits.joinToString("、") { it.substringAfterLast('.') }
    }

    /** 从 `sh` 输出里取 `前缀=数字` 那个退出码；没这行回 -1。 */
    private fun exitOf(out: String, prefix: String): Int {
        val i = out.indexOf(prefix)
        if (i < 0) return -1
        var j = i + prefix.length
        val start = j
        while (j < out.length && out[j].isDigit()) j++
        return if (j > start) out.substring(start, j).toIntOrNull() ?: -1 else -1
    }

    /**
     * 从 `sh` 输出里取 `前缀=任意值` 那一行的值（到行尾为止，去掉首尾空白）。
     *
     * 和 [propOf] 的区别：`propOf` 认的是 `getprop`（无参）整份 dump 的
     * `[key]: [value]` 格式；这里认的是自己 `echo "V=$(getprop key)"` 打出来的单行，
     * 因为单查一个 key 时 `getprop` 只输出值本身，不带 `[key]: [...]` 外壳。
     */
    private fun lineOf(out: String, prefix: String): String {
        val i = out.indexOf(prefix)
        if (i < 0) return ""
        val s = i + prefix.length
        val e = out.indexOf('\n', s).let { if (it < 0) out.length else it }
        return out.substring(s, e).trim()
    }

    /**
     * 通道 1：**照抄应用管家「临时开启无线adb(适合大部分公签系统)」**。
     *
     * 反编译确认（`g7/q2` case 4，就是菜单里「临时开启…公签系统」那一项）它只做三步：
     *   1. `Settings.Global.putInt("adb_enabled", 1)`（失败吞异常继续）
     *   2. `setprop service.adb.tcp.port 5555` —— **只有这条退出码为 0 才往下走**，
     *      否则直接提示「修改失败，不适合您的系统」
     *   3. `setprop ctl.restart adbd` → `Thread.sleep(5000)` → 自连 127.0.0.1:5555
     *
     * 所以先只跑这两条（短、快、失败能立刻定位是哪条被拒），
     * 不成再退回厂商那整组 `persist.*`（德赛西威认 `persist.sv.debug.adb_enable`）。
     */
    private fun tryPropChannel(): Boolean {
        if (propDead) return false

        // ⓪ 6.0.106：先只写德赛西威那条总开关，看固件会不会自己把 adbd 拉起来。
        //    单条就通的话，后面那 10 条 persist 属性一条都不写 —— 那是永久落在
        //    /data/property 里的，其中 persist.service.adb.button.visible 还会在
        //    开发者选项里给用户多冒一个 ADB 按钮出来。代价只有 4 秒。
        val sv = sh(
            "setprop $PROP_SV 1; echo \"S=\$?\"; echo \"V=\$(getprop $PROP_SV)\"",
            4L
        )
        val svExit = exitOf(sv, "S=")
        val svVal = lineOf(sv, "V=")
        Log.i(TAG, "德赛总开关：setprop $PROP_SV 1 → exit=$svExit，回读=\"$svVal\"")
        if (svExit == 0 && svVal == "1") {
            val p0 = waitForPort(4_000L)
            if (p0 > 0) {
                propHits = listOf(PROP_SV)
                propNote = "已写 $PROP_SV=1，端口 $p0（固件自己拉起的 adbd，没用 ctl.restart）"
                Log.i(TAG, propNote)
                return true
            }
            Log.i(TAG, "德赛总开关写进去了但 4 秒内没探到端口，继续走通用通道")
        }

        // ① 与应用管家完全相同的那两条：service.adb.tcp.port + ctl.restart adbd
        val quick = sh(
            "setprop service.adb.tcp.port 5555; echo \"Q_TCP=\$?\"; " +
                "setprop ctl.restart adbd; echo \"Q_CTL=\$?\"",
            5L
        )
        val qTcp = exitOf(quick, "Q_TCP=")
        val qCtl = exitOf(quick, "Q_CTL=")
        Log.i(TAG, "临时通道：service.adb.tcp.port exit=$qTcp，ctl.restart adbd exit=$qCtl")

        if (qTcp == 0) {
            val p = waitForPort(6_000L)
            if (p > 0) {
                propHits = listOf("service.adb.tcp.port")
                propNote = "已写入 service.adb.tcp.port=5555，端口 $p（ctl.restart exit=$qCtl）"
                Log.i(TAG, propNote)
                return true
            }
        }

        // ② 快通道没开出来：整组厂商属性再试一遍，并把每条退出码记下来
        val sb = StringBuilder()
        sb.append("settings put global adb_allowed_connection_time 0 2>/dev/null; ")
        for ((k, v) in PROP_GROUP) {
            sb.append("setprop ").append(k).append(' ').append(v)
                .append("; echo \"E_").append(k).append("=\$?\"; ")
        }
        sb.append("getprop")
        val out = sh(sb.toString(), 10L)
        val hit = PROP_GROUP.filter { (k, v) ->
            exitOf(out, "E_$k=") == 0 && propOf(out, k) == v
        }.map { it.first }
        propHits = hit
        val denied = PROP_GROUP.size - hit.size
        propNote = when {
            hit.isEmpty() ->
                "厂商调试属性全被拒（service.adb.tcp.port exit=$qTcp，整组 0/${PROP_GROUP.size}）"
            else -> "已写入 ${hit.size} 条调试属性：" +
                hit.joinToString("、") { it.substringAfterLast('.') } + "（还差 $denied 条）"
        }
        Log.i(TAG, propNote)

        if (waitForPort(8_000L) > 0) return true
        // 一条都没写进去 → 这台车机对 app 域不放行这些属性，别每轮白等
        if (hit.isEmpty()) propDead = true
        return false
    }

    // ================================================================== 授权脚本

    /**
     * 生成一整套 shell 命令：能用命令给的权限全给，不覆盖已启用的其它无障碍服务。
     *
     * `pm grant` 只能授予 Manifest 里声明过的权限，所以 6.0.51 补了两条声明；
     * `appops set` 管的是「特殊权限」那几项（悬浮窗 / 写设置 / 使用情况 / 安装应用），
     * 它们不是普通运行时权限，`pm grant` 对它们无效。
     * 不同 ROM 的命令前缀不同，`appops` 与 `cmd appops` 都试一遍，失败被 2>/dev/null 吞掉。
     */
    private fun grantScript(ctx: Context): String {
        val p = ctx.packageName
        val comp = "$p/$A11Y_CLASS"
        val cur = runCatching {
            Settings.Secure.getString(ctx.contentResolver, "enabled_accessibility_services")
        }.getOrNull().orEmpty()
        // 已有本服务就别动，免得把用户其它已启用的无障碍服务覆盖掉
        val a11y = if (cur.contains(comp) || cur.contains("$p/.ui.main.H6A11yService")) null
        else if (cur.isBlank() || cur == "null") comp else "$cur:$comp"

        val sb = StringBuilder()
        for (perm in listOf(
            "android.permission.WRITE_SECURE_SETTINGS",
            "android.permission.READ_LOGS",
            "android.permission.PACKAGE_USAGE_STATS",
            "android.permission.WRITE_SETTINGS",
            "android.permission.SYSTEM_ALERT_WINDOW",
            "android.permission.REQUEST_INSTALL_PACKAGES",
            // v6.0.73：U 盘播放器要读 /storage/udisk 下的音频文件。
            // 这两条是普通运行时权限，pm grant 能授；不授的话扫描时
            // File.canRead() 会把所有 U 盘目录过滤掉，表现成"没找到音乐文件"。
            "android.permission.READ_EXTERNAL_STORAGE",
            "android.permission.WRITE_EXTERNAL_STORAGE"
        )) sb.append("pm grant $p $perm 2>/dev/null; ")

        for (op in listOf(
            "SYSTEM_ALERT_WINDOW", "WRITE_SETTINGS", "GET_USAGE_STATS",
            "REQUEST_INSTALL_PACKAGES", "android:system_alert_window",
            "android:write_settings", "android:get_usage_stats",
            // Android 11+ 的「所有文件访问」；8.1 上这个 op 不存在，失败被吞掉
            "MANAGE_EXTERNAL_STORAGE"
        )) {
            sb.append("appops set $p $op allow 2>/dev/null; ")
            sb.append("cmd appops set $p $op allow 2>/dev/null; ")
        }

        sb.append("dumpsys deviceidle whitelist +$p 2>/dev/null; ")
        sb.append("cmd deviceidle whitelist +$p 2>/dev/null; ")
        sb.append("settings put global adb_enabled 1 2>/dev/null; ")
        sb.append("settings put global adb_wifi_enabled 1 2>/dev/null; ")
        if (a11y != null) {
            // 值里只有包名/类名/冒号，单引号足够；别用双引号，mksh 会再解析一次
            sb.append("settings put secure enabled_accessibility_services '$a11y' 2>/dev/null; ")
        }
        sb.append("settings put secure accessibility_enabled 1 2>/dev/null; ")

        // 音乐卡片的「通知使用权」（v6.0.73）。
        // 机油卡那条音乐条靠 MediaSessionManager.getActiveSessions 读歌，而它只放行
        // 「系统级 MEDIA_CONTENT_CONTROL」或「已启用的通知监听器」，后者是我们唯一的路。
        // 8.1 上 `cmd notification` 不存在，只能写 setting；11+ 两种都可以，两条都发，
        // 失败被 2>/dev/null 吞掉。
        val ncomp = "$p/com.h6n.launcher.core.H6MediaListener"
        val ncur = runCatching {
            Settings.Secure.getString(ctx.contentResolver, "enabled_notification_listeners")
        }.getOrNull().orEmpty()
        if (!ncur.contains(ncomp)) {
            val next = if (ncur.isBlank() || ncur == "null") ncomp else "$ncur:$ncomp"
            sb.append("settings put secure enabled_notification_listeners '$next' 2>/dev/null; ")
        }
        sb.append("cmd notification allow_listener $ncomp 2>/dev/null; ")
        return sb.toString()
    }

    /**
     * 只有 root 通道才执行：切 TCP 并重启 adbd。
     * 与应用管家同款：两个属性 + `ctl.restart adbd`（比 `stop/start` 标准且
     * 对被裁剪过 init.rc 的 ROM 更稳），末尾再补一对 stop/start 兜底。
     * ADB 通道**不能**跑这段 —— 重启会把自己正在用的连接掐断。
     */
    private val TCP_SCRIPT =
        "setprop service.adb.tcp.port 5555 2>/dev/null; " +
            "setprop persist.adb.tcp.port 5555 2>/dev/null; " +
            "setprop ctl.restart adbd 2>/dev/null; " +
            "stop adbd 2>/dev/null; start adbd 2>/dev/null; "

    private fun setAdbOn(ctx: Context) {
        // putInt 返回 Boolean，别把表达式体函数声明成 Unit（编译期会推断成 Boolean）
        runCatching {
            Settings.Global.putInt(ctx.contentResolver, "adb_enabled", 1)
            Settings.Global.putInt(ctx.contentResolver, "adb_wifi_enabled", 1)
        }.getOrElse {
            Log.w(TAG, "写 adb_enabled 失败: ${it.message}")
        }
    }

    // ================================================================== 对外入口

    /**
     * 后台打通，回调在主线程。
     *
     * @param onDone (是否成功, 给用户看的一句话)
     */
    fun ensure(ctx: Context, onDone: (Boolean, String) -> Unit) {
        if (!running.compareAndSet(false, true)) {
            Handler(Looper.getMainLooper()).post {
                runCatching { onDone(false, "正在后台授权，稍等一下") }
            }
            return
        }
        val app = ctx.applicationContext
        lastTryMs = SystemClock.elapsedRealtime()
        Thread({
            val r = runCatching { work(app) }.getOrElse { e ->
                Log.w(TAG, "授权异常: ${e.message}")
                false to ("授权失败：" + (e.message ?: e.javaClass.simpleName))
            }
            running.set(false)
            Handler(Looper.getMainLooper()).post {
                runCatching { onDone(r.first, r.second) }
            }
        }, "h6-adb").apply { priority = Thread.MIN_PRIORITY }.start()
    }

    /**
     * 用户刚从应用管家那边回来：把 20 秒节流清掉，让下一次 [auto] 立刻真跑一遍。
     * 不然「开了调试口回桌面」会撞上节流，面板还停在「没开」，用户以为没生效。
     */
    fun requestRetry() {
        lastTryMs = 0L
        scanned = false
        propDead = false
        suBin = null
    }

    /**
     * 进程刚起来（6.0.106）：把「上次已打通」这个记忆作废。
     *
     * 以前 [wirelessOn] 只要读得到 SP 里那个 `K_WIRELESS_OK` 就返回 true，
     * 而 adbd 的 TCP 端口是**不跨重启**的 —— 熄火断电再上电，
     * `service.adb.tcp.port` 回到空，端口没了，但 SP 里的 true 还在，
     * [auto] 于是每次都判「已经通好了」直接 return，永远不再自连。
     * 表现就是「桌面里的无线 adb 开机后一直连不上，非得手动点一次一键授权」。
     *
     * 这里只清标记，不动属性 —— 真去连由 [auto] 在后台线程做。
     */
    fun onProcessStart(ctx: Context) {
        lastTryMs = 0L
        scanned = false
        propDead = false
        suBin = null
        markWireless(ctx, false)
    }

    /**
     * 关闭无线调试（6.0.106）。后台跑，回调在主线程。
     *
     * 桌面以前**只有开没有关** —— 车主一旦连过 adb 就再也关不掉，调试口一直晾在
     * 局域网里。这里补上，按代价从低到高：
     *   1. 德赛总开关写 0（那个 86 KB 的「德赛ADB开关」关无线就是这一行）
     *   2. `service.adb.tcp.port -1` + `ctl.restart adbd`（`-1` 就是 `adb usb` 的语义）
     *   3. `su` 重做第 2 步，并补 `stop/start adbd`
     *
     * 判定一律**回读端口**。属性写成功不等于 adbd 真的松开了监听 ——
     * 那个 APK 就栽在「不查返回值，直接弹一句『adb已关闭』」上。
     */
    fun disableWireless(ctx: Context, onDone: (Boolean, String) -> Unit) {
        val app = ctx.applicationContext ?: ctx
        Thread({
            val r = runCatching { doDisable(app) }.getOrElse { e ->
                Log.w(TAG, "关闭无线调试异常: ${e.message}")
                false to ("关闭失败：" + (e.message ?: e.javaClass.simpleName))
            }
            Handler(Looper.getMainLooper()).post {
                runCatching { onDone(r.first, r.second) }
            }
        }, "h6-adb-off").apply { priority = Thread.MIN_PRIORITY }.start()
    }

    /**
     * 每次进桌面自动跑：所有非可选权限齐了且无线 ADB 已开就不再折腾；
     * 否则只要距上次尝试超过 20 秒就重试一次。不弹框、不打断用户。
     */
    fun auto(ctx: Context, onDone: (Boolean, String) -> Unit = { _, _ -> }) {
        if (running.get()) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastTryMs < 20_000L) return
        val done = CORE.all { PermGuide.granted(ctx, it) } && wirelessOn(ctx)
        if (done) return
        ensure(ctx, onDone)
    }

    // ================================================================== 干活

    /**
     * 关无线调试的实际动作，说明见 [disableWireless]。返回 (是否真关掉, 一句话)。
     */
    private fun doDisable(ctx: Context): Pair<Boolean, String> {
        val tried = ArrayList<String>(3)

        // ① 德赛总开关写 0：最轻的一条，那个 APK 关无线就靠它
        val sv = sh(
            "setprop $PROP_SV 0; echo \"S=\$?\"; echo \"V=\$(getprop $PROP_SV)\"",
            4L
        )
        val svOk = exitOf(sv, "S=") == 0 && lineOf(sv, "V=") == "0"
        tried += if (svOk) "德赛总开关=0" else "德赛总开关被拒"
        if (svOk && waitNoPort(3_000L)) {
            markWireless(ctx, false)
            propHits = listOf(PROP_SV)
            propNote = "已写 $PROP_SV=0，端口已关"
            Log.i(TAG, propNote)
            return true to "无线调试已关闭（德赛总开关）"
        }

        // ② 通用写法：-1 就是 `adb usb` 的语义，再重启 adbd 让它松口
        val off = sh(
            "setprop service.adb.tcp.port -1; echo \"C=\$?\"; " +
                "setprop ctl.restart adbd; echo \"R=\$?\"",
            5L
        )
        val cExit = exitOf(off, "C=")
        val rExit = exitOf(off, "R=")
        tried += "tcp.port=-1(exit=$cExit)/restart(exit=$rExit)"
        Log.i(TAG, "关无线调试：" + tried.joinToString("、"))
        if (waitNoPort(6_000L)) {
            markWireless(ctx, false)
            propHits = listOf("service.adb.tcp.port")
            propNote = "已写 service.adb.tcp.port=-1，端口已关"
            Log.i(TAG, propNote)
            return true to "无线调试已关闭"
        }

        // ③ root 再来一遍：只有 su 才真有 ctl. 权限，顺带 stop/start 兜底
        if (rootOk()) {
            tried += "su"
            su("setprop service.adb.tcp.port -1; setprop ctl.restart adbd; stop adbd; start adbd")
            if (waitNoPort(6_000L)) {
                markWireless(ctx, false)
                propNote = "su 关闭成功，端口已关"
                Log.i(TAG, propNote)
                return true to "无线调试已关闭（root）"
            }
        }

        // 全都没生效 —— 属性可能写进去了，但 adbd 收不到重启指令，端口还在听
        val port = probePort()
        markWireless(ctx, port > 0)
        val tail = if (port > 0) "，端口 $port 仍在监听：adbd 大概收不到重启指令，先用应用管家关一次" else ""
        return false to ("没关掉（" + tried.joinToString("、") + "）" + tail)
    }

    private fun work(ctx: Context): Pair<Boolean, String> {
        val before = CORE.count { !PermGuide.granted(ctx, it) }
        val script = grantScript(ctx)
        var via = ""

        // 0) 先自己找端口再连：应用管家开出来的随机端口也在这里被认出来（6.0.83）
        var port = viaLocalAdb(ctx, script)
        if (port > 0) via = "本机 adb:$port"

        // 1) 属性直写：零权限、零弹窗。Permissive 固件上 init 自己把 adbd 切到 5555
        if (port < 0 && tryPropChannel()) {
            port = viaLocalAdb(ctx, script)
            if (port > 0) via = "系统属性 + 本机 adb:$port"
        }

        // 2) root：与应用管家同款 setprop + ctl.restart。**分步执行**：
        //    先切 TCP（命令串短，不会因为 pm grant 慢而整体超时），验证端口后再给权限。
        //    su 若挂着授权框，等待要给足 —— 用户点一次允许，下一轮就通。
        if (port < 0 && rootOk()) {
            su(TCP_SCRIPT)
            waitForPort(6_000L)
            port = viaLocalAdb(ctx, script)
            if (port > 0) via = "root + 本机 adb:$port"
            su(script)
        }

        // 3) 已有写系统设置权限：Java API 兜底补写
        if (secureGranted(ctx)) setAdbOn(ctx)

        // 6.0.87 音乐卡片：grantScript 靠 adb shell 写 enabled_notification_listeners，
        // 但 8.1 的系统不会因为 setting 变化主动重绑监听器。必须回到本进程用
        // NotificationListenerService.requestRebind 补一枪（shell 无法代劳）。
        // 写在授权链末尾，无论走哪条通道打通，都保证「授权完＝监听器已重绑」。
        // 未打通时若本应用已有 WRITE_SECURE_SETTINGS，ensureGrant 也能静默写 setting。
        try { MediaWatcher.ensureGrant(ctx) } catch (t: Throwable) {
            Log.w(TAG, "通知使用权重绑失败: ${t.message}")
        }

        // 4) 端口活着但握手没过（多半在等「允许 USB 调试」）也要把状态显示对
        val p = if (port > 0) port else probePort()
        markWireless(ctx, p > 0)
        val after = CORE.count { !PermGuide.granted(ctx, it) }
        val wireless = p > 0
        val ch = if (via.isEmpty()) "系统权限" else via

        val msg = when {
            after == 0 && wireless -> "已全部授权，无线 ADB 已开（$ch）"
            after == 0 -> "已全部授权（$ch），无线 ADB 未开"
            after < before -> "已补授 ${before - after} 项，还缺 $after 项（$ch）"
            via.isNotEmpty() -> "已执行授权命令（$ch），但还缺 $after 项"
            wireless -> "调试口在 $p，但连接没拿到权限" +
                (if (authNote.isNotEmpty()) "（$authNote）" else "") +
                " —— 车机若弹「允许 USB 调试」，勾上「一律允许」再点一次一键授权"
            propHits.isNotEmpty() -> "调试属性写进去了，但 adbd 没起来" +
                "（系统可能拦了重启）。面板那行诊断记着被拒的退出码，发我；也可以先用应用管家开一次"
            else -> "这台车机对桌面不放行厂商调试属性（service.adb.tcp.port exit 非 0）。" +
                "打开「应用管家」→ 临时开启无线adb → 回桌面点一次「一键授权」即可，桌面会自己连上补齐"
        }
        Log.i(TAG, "$msg · TCP=$p")
        return (after == 0 || after < before) to msg
    }

    /**
     * 最后一个跑通的加固动作：把 adbd 的端口固定到 5555，下次开机不用再求应用管家。
     *
     * `service.adb.tcp.port` 就是 `adb tcpip 5555` 走的那条路 —— init.rc 里
     * `on property:service.adb.tcp.port=*` 会自己 restart adbd，所以不写
     * `ctl.restart`（那是 init/su 才有的权限，shell 根本设不动）。
     * 万一这台 ROM 没这条规则，adbd 不会重启、原端口照旧能用，不会有副作用；
     * `persist.*` 则负责下次开机。
     */
    private val HARDEN_SCRIPT =
        "settings put global adb_enabled 1 2>/dev/null; " +
            "setprop persist.adb.tcp.port 5555 2>/dev/null; " +
            // 放最后：init 若照它重启 adbd，这条连接会被掐断，前面的活必须先干完
            "setprop service.adb.tcp.port 5555 2>/dev/null; "

    /**
     * 本机 adb：找端口 → 握手 → 跑授权脚本 → 固化端口。
     * 返回真正跑通脚本的端口，全失败返回 -1。
     *
     * 6.0.106 起把每一步的原因写进 [authNote]：实车上「连不上」有三种完全不同的原因
     * （没端口 / 没授权 / 连上了但命令被拒），以前只能翻日志，现在设置页一行就看得到。
     */
    private fun viaLocalAdb(ctx: Context, script: String): Int {
        val key = loadKey(ctx) ?: run {
            authNote = "本机密钥不可用（生成/读取失败）"
            return -1
        }
        val cands = openPorts(candidatePorts(ctx))
        if (cands.isEmpty()) {
            authNote = "没找到正在监听的调试口（5555、/proc、全段扫描都没结果）"
            return -1
        }
        var why = ""
        for (port in cands) {
            val conn = AdbConn(HOST, port)
            val ok = runCatching { conn.connect(key) }.getOrDefault(false)
            if (ok) {
                runCatching { conn.shell(script) }
                runCatching { conn.shell(HARDEN_SCRIPT) }
                authNote = conn.note + "（$HOST:$port）"
                conn.close()
                // 加固可能让 init 把 adbd 重开到 5555：认一下新端口，别把旧的写进记忆
                val fixed = waitForPort(3_000L)
                val real = if (fixed > 0) fixed else port
                rememberPort(ctx, real)
                markWireless(ctx, true)
                Log.i(TAG, "本机 adb 通道打通: $port → 固定到 $real（${conn.note}）")
                return real
            }
            if (why.isEmpty() && conn.note.isNotEmpty()) why = conn.note
            conn.close()
        }
        authNote = if (why.isNotEmpty()) why else "端口在听，但握手没有任何回应（$cands）"
        Log.i(TAG, "本机 adb 全部尝试失败：$authNote")
        return -1
    }

    private fun rememberPort(ctx: Context, port: Int) {
        runCatching { prefs(ctx).edit().putInt(K_PORT, port).apply() }
    }

    /** 车机上 su 的几种叫法：`tclsu` 来自应用管家反编译里的探测（TCL / 德赛系）。 */
    private val SU_BINS = arrayOf("su", "tclsu", "/system/bin/su", "/system/xbin/su", "/vendor/bin/su")

    /** 探测到的 su 放到这里，后续 [su] 直接复用，不用每次从头试。 */
    @Volatile
    private var suBin: String? = null

    /**
     * 先确认 su 真的给了 uid 0，否则「有 su 二进制」会被误判成成功。
     * 先 waitFor 再读输出：Magisk 之类弹授权框时，先 readText() 会把后台线程永久挂住。
     * ★ 6.0.56 超时 4s→8s：车机 su 首次启动慢，弹授权框时也给用户机会点「允许」。
     * ★ 6.0.84 多试几种：桌面旧版只认 `su`，而这台车机可能把它叫 `tclsu`
     *   （应用管家反编译里就有 `tclsu` / `uid=0(root)` 这对字符串）。
     */
    private fun rootOk(): Boolean {
        suBin?.let { return true }
        for (bin in SU_BINS) {
            if (bin.startsWith("/") && !File(bin).exists()) continue
            val wait = if (bin == "su") 8L else 3L
            val ok = runCatching {
                val p = Runtime.getRuntime().exec(arrayOf(bin, "-c", "id -u"))
                if (!p.waitFor(wait, TimeUnit.SECONDS)) {
                    p.destroyForcibly()
                    return@runCatching false
                }
                p.inputStream.bufferedReader().use { it.readText() }.lines().any { it.trim() == "0" }
            }.getOrDefault(false)
            if (ok) {
                suBin = bin
                Log.i(TAG, "找到 root: $bin")
                return true
            }
        }
        return false
    }

    /** 有 root 就执行；没有 / 被拒 / 超时返回 null。同样先超时再读输出。 */
    private fun su(cmd: String): String? = runCatching {
        val bin = suBin ?: return@runCatching null
        val p = Runtime.getRuntime().exec(arrayOf(bin, "-c", cmd))
        if (!p.waitFor(15, TimeUnit.SECONDS)) {
            p.destroyForcibly()
            return@runCatching null
        }
        p.inputStream.bufferedReader().use { it.readText() }
    }.getOrNull()

    // ================================================================== 密钥

    private fun loadKey(ctx: Context): PrivateKey? = runCatching {
        val f = File(ctx.filesDir, KEY_FILE)
        val kf = KeyFactory.getInstance("RSA")
        if (f.exists()) {
            kf.generatePrivate(PKCS8EncodedKeySpec(f.readBytes()))
        } else {
            val kp = KeyPairGenerator.getInstance("RSA")
                .apply { initialize(2048) }
                .generateKeyPair()
            f.writeBytes(kp.private.encoded)
            kp.private
        }
    }.getOrElse {
        Log.w(TAG, "密钥不可用: ${it.message}")
        null
    }
}

// ====================================================================== ADB 协议
// 以下都是文件级私有：嵌套类 [AdbConn] 要能直接用，放 object 里会被可见性规则挡住。

// 报文头 6 个 uint32，小端
private const val A_CNXN = 0x4e584e43      // CNXN
private const val A_AUTH = 0x48545541      // AUTH
private const val A_OPEN = 0x4e45504f      // OPEN
private const val A_OKAY = 0x59414b4f      // OKAY
private const val A_CLSE = 0x45534c43      // CLSE
private const val A_WRTE = 0x45545257      // WRTE

private const val A_VERSION = 0x01000000
private const val MAX_PAYLOAD = 256 * 1024

private const val ADB_AUTH_TOKEN = 1          // 设备发来的待签名 token
private const val ADB_AUTH_SIGNATURE = 2      // 我们回签名
private const val ADB_AUTH_RSAPUBLICKEY = 3   // 我们回公钥（会让设备弹「允许调试」）

/**
 * 送出公钥后等用户点「允许」的时间。
 *
 * 车机上那个弹窗是盖在所有应用之上的系统框，用户常常得先找一下再点；
 * 给 45 秒（以前是 15 秒，签名一失败就整轮放弃，用户还没反应过来就超时了）。
 */
private const val AUTH_WAIT_MS = 45_000

private val EMPTY = ByteArray(0)

private fun leInt(b: ByteArray, off: Int): Int =
    (b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8) or
        ((b[off + 2].toInt() and 0xFF) shl 16) or ((b[off + 3].toInt() and 0xFF) shl 24)

private fun putInt(b: ByteArray, off: Int, v: Int) {
    b[off] = (v and 0xFF).toByte()
    b[off + 1] = ((v ushr 8) and 0xFF).toByte()
    b[off + 2] = ((v ushr 16) and 0xFF).toByte()
    b[off + 3] = ((v ushr 24) and 0xFF).toByte()
}

private fun crc(data: ByteArray): Int {
    if (data.isEmpty()) return 0
    val c = CRC32()
    c.update(data)
    return c.value.toInt()
}

/** BigInteger → 固定长度小端字节。高位前导零截掉，不足补 0。 */
private fun lePadded(v: BigInteger, size: Int): ByteArray {
    val be = v.toByteArray()
    val out = ByteArray(size)
    for (i in 0 until minOf(be.size, size)) out[i] = be[be.size - 1 - i]
    return out
}

/**
 * Android 版 ADB 公钥：`base64(524字节结构) + " 名字\0"`。
 *
 * 结构（小端）：
 * ```
 * [4]   64        modulus 的字数
 * [4]   n0inv     -1 / n[0] mod 2^32
 * [256] n         模数，小端
 * [256] rr        R^2 mod n（R = 2^2048），小端
 * [4]   exponent  65537
 * ```
 * 这个布局从 libadb-android 的 `AndroidPubkey.encode()` 字节码里核出来，
 * 和 AOSP `libcrypto_utils/android_pubkey.cpp` 一致。
 */
private fun pubKeyPayload(priv: PrivateKey): ByteArray? {
    val crt = priv as? RSAPrivateCrtKey ?: return null
    val n = crt.modulus
    val e = crt.publicExponent

    val r32 = BigInteger.ONE.shiftLeft(32)
    val n0inv = r32.subtract(n.mod(r32).modInverse(r32))

    val buf = ByteBuffer.allocate(524).order(ByteOrder.LITTLE_ENDIAN)
    buf.putInt(64)
    buf.putInt(n0inv.toInt())
    buf.put(lePadded(n, 256))

    val r = BigInteger.ONE.shiftLeft(2048)
    buf.put(lePadded(r.multiply(r).mod(n), 256))
    buf.putInt(e.toInt())

    val b64 = Base64.encodeToString(buf.array(), Base64.NO_WRAP).toByteArray()
    return b64 + " H6Launcher\u0000".toByteArray()
}

/** ADB 的 AUTH 签名就是 PKCS#1 v1.5 的 SHA1 签名，JDK 自带，不用 bouncycastle。 */
private fun signToken(priv: PrivateKey, token: ByteArray): ByteArray =
    Signature.getInstance("SHA1withRSA").run {
        initSign(priv)
        update(token)
        sign()
    }

private class Pkt(val cmd: Int, val a0: Int, val a1: Int, val data: ByteArray)

/**
 * 一条最简 ADB 连接。
 *
 * 只跑 `shell:` 服务：OPEN 里直接带命令，输出从 WRTE 里收。
 * 够 `pm grant` / `settings put` 用，不需要 sync / push / forward。
 */
private class AdbConn(private val host: String, private val port: Int) : Closeable {

    private val sock = Socket()
    private lateinit var out: OutputStream
    private lateinit var inp: InputStream
    private var seq = 1

    /** 握手走到哪一步的一句话说明，成功后由 [AdbGrant.authNote] 读给设置页看。 */
    var note: String = ""

    fun connect(key: PrivateKey): Boolean {
        sock.connect(InetSocketAddress(host, port), 900)
        sock.tcpNoDelay = true
        // 随机端口段里可能混着别的 app 的服务：先只等 3 秒。
        // 真 adbd 会立刻回 A_AUTH，那时再放宽到 15 秒 ——
        // 8.1 工程机多为 ro.adb.secure=0 不弹授权；开了认证的系统才需要用户点一次。
        sock.soTimeout = 3000
        out = sock.getOutputStream()
        inp = sock.getInputStream()

        val pub = pubKeyPayload(key) ?: return false
        send(A_CNXN, A_VERSION, MAX_PAYLOAD, "host::h6launcher\u0000".toByteArray())

        // ★ 6.0.106 的关键修复：**公钥一定要送出去**。
        //
        // adbd 的授权流程是「第一次要 token → 客户端回签名 → 设备验不过就再要 token」。
        // 以前这里只在 `a0 != ADB_AUTH_TOKEN` 时送公钥，但 adbd 重发的一直是 token，
        // 所以公钥永远发不出去 → 车机那头**从不弹「允许 USB 调试」** →
        // 我们只有一遍遍白签名，十轮后放弃。表现就是「桌面的无线 adb 怎么都连不上」，
        // 而应用管家能连 —— 因为它的公钥早就被用户允许过一次，早就在 adb_keys 里了。
        //
        // 正确顺序（与应用管家内置的 AdbLib 逐行一致）：
        //   ① token → 回签名
        //   ② 又来 token（说明签名不被认）→ 改送公钥，设备才会弹授权框
        //   ③ 之后再来 token → 继续签名：用户点「允许」的那一瞬间，这轮签名就过了
        var signed = false
        var sentPub = false
        var guard = 0
        while (guard++ < 24) {
            val p = readPkt() ?: return false
            when (p.cmd) {
                A_CNXN -> {
                    note = if (sentPub) "公钥已授权，握手成功" else "无需授权，直接握手成功"
                    return true
                }

                A_AUTH -> {
                    val token = p.a0 == ADB_AUTH_TOKEN
                    when {
                        token && !signed -> {
                            sock.soTimeout = 8_000
                            send(A_AUTH, ADB_AUTH_SIGNATURE, 0, signToken(key, p.data))
                            signed = true
                            note = "已回签名，等设备裁决"
                        }

                        token && !sentPub -> {
                            // 签名没被认 —— 把公钥交上去，剩下的等用户在车机上点「允许」
                            sock.soTimeout = AUTH_WAIT_MS
                            send(A_AUTH, ADB_AUTH_RSAPUBLICKEY, 0, pub)
                            sentPub = true
                            note = "公钥已送，等车机弹「允许 USB 调试」并勾「一律允许」"
                            Log.i("H6Adb", "本机 adb：签名未被接受，已改送公钥（$host:$port）")
                        }

                        token -> {
                            // 公钥已送出、设备还在要 token：继续签，用户一点允许这条就过
                            sock.soTimeout = AUTH_WAIT_MS
                            send(A_AUTH, ADB_AUTH_SIGNATURE, 0, signToken(key, p.data))
                        }

                        else -> {
                            sock.soTimeout = AUTH_WAIT_MS
                            send(A_AUTH, ADB_AUTH_RSAPUBLICKEY, 0, pub)
                            sentPub = true
                        }
                    }
                }

                else -> Unit   // 其它包（A_STLS 等）忽略，继续等 CNXN
            }
        }
        note = if (sentPub) "公钥送出了但没人点「允许」" else "握手超时（$host:$port 没回 CNXN）"
        return false
    }

    /** 跑一条 shell 命令，返回合并后的 stdout + stderr。 */
    fun shell(cmd: String): String {
        val id = seq++
        send(A_OPEN, id, 0, ("shell:" + cmd + "\u0000").toByteArray())

        val sb = StringBuilder()
        var remote = 0
        val end = System.currentTimeMillis() + 15000
        while (System.currentTimeMillis() < end) {
            val p = readPkt() ?: break
            when (p.cmd) {
                A_OKAY -> if (p.a1 == id && p.a0 != 0) remote = p.a0
                A_WRTE -> {
                    if (p.data.isNotEmpty()) sb.append(String(p.data, Charsets.UTF_8))
                    send(A_OKAY, id, if (p.a0 != 0) p.a0 else remote, EMPTY)
                }
                A_CLSE -> return sb.toString()
            }
        }
        return sb.toString()
    }

    private fun send(cmd: Int, a0: Int, a1: Int, data: ByteArray) {
        val h = ByteArray(24)
        putInt(h, 0, cmd)
        putInt(h, 4, a0)
        putInt(h, 8, a1)
        putInt(h, 12, data.size)
        putInt(h, 16, crc(data))
        putInt(h, 20, cmd.inv())
        runCatching {
            synchronized(sock) {
                out.write(h)
                if (data.isNotEmpty()) out.write(data)
                out.flush()
            }
        }
    }

    private fun readPkt(): Pkt? = runCatching {
        val h = ByteArray(24)
        if (!readFully(h)) return null
        val cmd = leInt(h, 0)
        val len = leInt(h, 12)
        val data = if (len in 1..(4 * 1024 * 1024)) {
            ByteArray(len).also { if (!readFully(it)) return null }
        } else EMPTY
        Pkt(cmd, leInt(h, 4), leInt(h, 8), data)
    }.getOrNull()

    private fun readFully(b: ByteArray): Boolean {
        var off = 0
        while (off < b.size) {
            val n = runCatching { inp.read(b, off, b.size - off) }.getOrElse { -1 }
            if (n <= 0) return false
            off += n
        }
        return true
    }

    override fun close() {
        runCatching { sock.close() }
    }
}
