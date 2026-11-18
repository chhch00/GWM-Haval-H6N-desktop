package com.h6n.launcher.core

import android.content.Context
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.util.LruCache
import android.widget.ImageView
import java.lang.ref.WeakReference
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory

/**
 * 应用图标内存缓存（v6.0.26）。
 *
 * ## 为什么需要
 * 重写版是「纯代码搭 UI」：应用墙 [com.h6n.launcher.ui.main.AppPageView]
 * 不是 RecyclerView，没有 ViewHolder 复用，而是 `ScrollView + LinearLayout` 一次性把
 * 所有格子建出来。原先每次 `applyTheme()` / `reload()`（换肤、切回桌面、翻到第 2 页）
 * 都会走 `DockSlots.listApps()`，逐个 `loadIcon()` 重新解析一遍图标 → 几十张 Bitmap
 * 反复分配/回收，正是滑动与切页时的 GC Churn 来源；停靠栏 [com.h6n.launcher.ui.main.DockView]
 * 每换一格也会 `getActivityIcon()` 一次。
 *
 * 这里用包名做 key 缓存 **Drawable 本身**（不是 Bitmap， adaptive icon / 矢量图标都能存），
 * 命中就是一次 O(1) 查表，不再碰 PackageManager。
 *
 * ## 生命周期 / 防泄漏
 * - 缓存是进程级单例，只存 Drawable。Drawable 可能持有 Resources，但绝不能持有 Activity：
 *   所有 loader 都必须用 `applicationContext.packageManager` 取图标（调用方保证）。
 * - [loadAsync] 对 ImageView 用 [WeakReference] + `isAttachedToWindow` 双检，
 *   View 摘下后不会再被后台线程或 post 出去的 Runnable 拽住。
 * - 淘汰条目**不**调用 recycle：被淘汰 ≠ 没人显示，ImageView 可能还引用着它。
 *
 * ## 为什么不一个图标开一个 Thread
 * 首次冷启动可能有几十个未命中，几十条并发线程反而把主线程挤爆。
 * 用一条 `MIN_PRIORITY` 守护线程串行补载；而且 [DockSlots.listApps] 已经在同步阶段
 * 把绝大多数图标放进缓存，这条通道基本只在「图标读取失败后重试」时才会走到。
 */
object AppIconCache {

    private const val MAX_BYTES = 5 * 1024 * 1024 // 5MB
    private const val THREAD_NAME = "h6-icon-load"

    /** key 统一用包名：同一个包在应用墙和停靠栏显示同一枚图标，也只解析一次。 */
    fun key(pkg: String): String = "app:$pkg"

    /** 只用来给 [BitmapDrawable] 带上正确的 density，由 Application 注入，绝不持有 Activity。 */
    private var res: Resources? = null

    fun attach(context: Context) {
        res = context.applicationContext.resources
    }

    /**
     * 自适应图标（v6.0.41）。
     *
     * `AdaptiveIconDrawable` 的固有尺寸是 **108dp 画布**，只有中间 72dp 是可见区，
     * 外层 18dp 是给各 launcher 遮罩预留的出血。launcher 正常会自己裁掉，
     * 而这里是 `ImageView` + `FIT_CENTER` 直接画 —— 整块 108dp 塞进格子，
     * 图形就只剩格子的 2/3，比旁边那些传统方形图标明显小一圈。
     *
     * 所以在这里一次性裁成中间 72dp 再转成 Bitmap，让两种图标在本桌面视觉一致。
     * 只动 `AdaptiveIconDrawable`，传统 PNG 图标原样返回。
     */
    private fun normalize(d: Drawable?): Drawable? {
        if (d !is AdaptiveIconDrawable) return d
        val r = res ?: return d
        val w = d.intrinsicWidth
        val h = d.intrinsicHeight
        if (w <= 0 || h <= 0) return d
        return try {
            val ix = w / 6f
            val iy = h / 6f
            val out = Bitmap.createBitmap(
                (w - ix * 2f).toInt().coerceAtLeast(1),
                (h - iy * 2f).toInt().coerceAtLeast(1),
                Bitmap.Config.ARGB_8888
            )
            val c = Canvas(out)
            c.translate(-ix, -iy)
            d.setBounds(0, 0, w, h)
            d.draw(c)
            BitmapDrawable(r, out)
        } catch (t: Throwable) {
            // 裁失败就退回原 Drawable：显示小一点，总好过没有图标
            d
        }
    }

    private val cache = object : LruCache<String, Drawable>(MAX_BYTES) {
        override fun sizeOf(key: String, value: Drawable): Int {
            // BitmapDrawable 能拿到真实字节数；自适应图标 / 矢量图标按固有尺寸估 ARGB_8888
            if (value is BitmapDrawable) {
                val b = value.bitmap
                if (!b.isRecycled) return b.allocationByteCount.coerceAtLeast(1)
            }
            val w = value.intrinsicWidth
            val h = value.intrinsicHeight
            return if (w > 0 && h > 0) (w * h * 4).coerceAtLeast(1024) else 1024
        }

        // 不 override entryRemoved 去 recycle：见类注释
    }

    /** 单线程、最低优先级、daemon：进程退出不阻塞，补载不与首屏抢 CPU。 */
    private val worker = Executors.newSingleThreadExecutor(ThreadFactory { r ->
        Thread(r, THREAD_NAME).apply {
            priority = Thread.MIN_PRIORITY
            isDaemon = true
        }
    })

    fun get(key: String): Drawable? = try {
        cache.get(key)
    } catch (t: Throwable) {
        null
    }

    fun put(key: String, icon: Drawable?) {
        if (key.isEmpty() || icon == null) return
        try {
            cache.put(key, icon)
        } catch (t: Throwable) {
            // 缓存写失败不影响显示
        }
    }

    /** 同步统一入口：命中直接返回，未命中调 [load] 并缓存。load 在调用线程执行。 */
    fun load(key: String, load: () -> Drawable?): Drawable? {
        get(key)?.let { return it }
        val d = try {
            normalize(load())
        } catch (t: Throwable) {
            null
        }
        put(key, d)
        return d
    }

    /**
     * 异步补载：命中直接上屏；未命中则后台加载 → 入缓存 → 回主线程上屏。
     * 调用方负责先放一枚占位图标。
     *
     * [load] 闭包会被后台线程持有到执行完，**只准捕获 applicationContext / PackageManager /
     * ComponentName 这类不带 Activity 的对象**，不要在里面引用 View 或 Activity。
     */
    fun loadAsync(key: String, target: ImageView, load: () -> Drawable?) {
        val hit = get(key)
        if (hit != null) {
            target.setImageDrawable(hit)
            return
        }
        val ref = WeakReference(target)
        worker.execute {
            val d = try {
                normalize(load())
            } catch (t: Throwable) {
                null
            } ?: return@execute
            put(key, d)
            val v = ref.get() ?: return@execute
            // 这里不能在后台线程先判 isAttachedToWindow：新建的格子可能还没 addView 到窗口，
            // 但 View.post 会把 Runnable 暂存到 View 自己的队列、attach 后再执行。
            // 若这种格子一直没挂上，队列随 View 一起被回收，Runnable 不会从单例泄漏出去。
            v.post {
                // 真正上屏前再判一次：View 已被摘下/复用就不写了
                if (v.isAttachedToWindow) v.setImageDrawable(d)
            }
        }
    }

    /** 装/卸应用或换主题后调用，避免显示旧包名的旧图标。 */
    fun remove(key: String) {
        try {
            cache.remove(key)
        } catch (t: Throwable) {
        }
    }

    fun clear() {
        try {
            cache.evictAll()
        } catch (t: Throwable) {
        }
    }

    /**
     * 内存紧张时收缩缓存（v6.0.45）。
     *
     * 桌面是常驻进程，退到后台被系统降级时（`onTrimMemory`）主动把图标缓存让出去：
     * 下次重建应用墙会重新解析图标（几十毫秒），但比被 LMK 整进程杀掉便宜得多。
     * 淘汰**不** recycle —— 被淘汰 ≠ 没人显示，ImageView 可能还引用着它。
     */
    fun trimTo(keepBytes: Int) {
        try {
            cache.trimToSize(keepBytes.coerceAtLeast(256 * 1024))
        } catch (t: Throwable) {
        }
    }

    /** 诊断用：`缓存 3.4MB / 上限 5MB`（`LruCache.size()` 就是 sizeOf 的累加，单位字节）。 */
    fun stats(): String = try {
        String.format(
            java.util.Locale.CHINA, "缓存 %.1fMB / 上限 %.0fMB",
            cache.size() / 1048576f, MAX_BYTES / 1048576f
        )
    } catch (t: Throwable) {
        "--"
    }
}
