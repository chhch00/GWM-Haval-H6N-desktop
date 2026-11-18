package com.h6n.launcher.core

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import com.h6n.launcher.core.H6Log as Log
import com.h6n.launcher.ui.main.H6A11yService

/**
 * 无障碍服务的开关状态与「前台是哪个应用」的单一来源。
 *
 * ## 为什么要它
 * 覆盖原厂导航栏的悬浮层要在**别的应用**上面显示、在**自家界面**上收起。
 * 之前只看 `Activity.onResume/onPause` 猜，缺点很明显：
 * - 从桌面点开导航，`onPause` 之后系统才真的切走，覆盖层会早半拍出现；
 * - 车机上偶尔有「Activity 没走 onPause 但界面已被盖住」的情况（弹出原厂全屏弹窗），
 *   覆盖层就一直藏着不出来了。
 *
 * 无障碍服务能拿到 `TYPE_WINDOW_STATE_CHANGED`，直接读前台包名，判定是准的。
 * 旧版（`SidebarHomeService` / `.perm.H6AccessService`）就是靠这个，这里只搬
 * 「看前台是谁」+「方向盘按键映射」两件事，不搬应用跳转 / 悬浮歌词
 * （按键映射见 [SteerKey]，做法参考车机应用管家的 `AccService.onKeyEvent`）。
 *
 * ## 隐私边界
 * `canRetrieveWindowContent="false"`：不读任何控件文本、不读窗口内容，
 * 只接收「窗口切换」这一个事件里的包名字段。
 *
 * ## 拿不到时怎么办
 * 服务没开 / 被系统停掉时 [foregroundPkg] 保持空，[NavOverlay] 会退回
 * Activity 生命周期那套判断，功能可用只是偶尔闪一下。
 */
object H6A11y {

    private const val TAG = "H6A11y"

    /** 无障碍服务当前是否被系统绑定着。 */
    @Volatile
    var connected: Boolean = false
        private set

    /** 最近一次前台应用的包名（只统计"像个应用"的包，系统弹窗 / 输入法不算）。 */
    @Volatile
    var foregroundPkg: String = ""
        private set

    /** 包名 → 是否已安装。窗口切换频率不高，缓存一下免得每次都问 PackageManager。 */
    private val installed = HashMap<String, Boolean>()

    // ---------------- 状态 ----------------

    /**
     * 系统里是否已开启本应用的无障碍服务。
     *
     * 比 [connected] 可靠：`connected` 只在服务被绑定后为真，刚在系统设置里
     * 打开、进程还没起来的那一瞬间还是 false，用它显示状态会闪一下「未开启」。
     *
     * 读 `enabled_accessibility_services` 不需要权限（旧版同样写法）。
     */
    fun enabledInSystem(ctx: Context): Boolean {
        return try {
            // 用字面量而不是 Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES：
            // 那个常量是 @hide，编译期访问不到（旧版 smali 里也是直接写的字符串）
            val v = Settings.Secure.getString(
                ctx.contentResolver, "enabled_accessibility_services"
            ) ?: return false
            val cn = ComponentName(ctx, H6A11yService::class.java)
            v.contains(cn.flattenToString()) || v.contains(cn.flattenToShortString())
        } catch (t: Throwable) {
            Log.w(TAG, "读无障碍开关失败: ${t.message}")
            false
        }
    }

    /** 跳系统无障碍设置页（车机上通常在「设置 → 无障碍 → 已下载的服务」里）。 */
    fun openSettings(ctx: Context) {
        val tries = arrayOf(
            Settings.ACTION_ACCESSIBILITY_SETTINGS,
            Settings.ACTION_SETTINGS
        )
        for (a in tries) {
            val ok = runCatching {
                ctx.startActivity(
                    Intent(a).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
                true
            }.getOrDefault(false)
            if (ok) return
        }
        Log.w(TAG, "打不开无障碍设置页")
    }

    /** 服务连上了（由 [H6A11yService.onServiceConnected] 调）。 */
    fun onConnected() {
        connected = true
        Log.i(TAG, "无障碍服务已连接")
    }

    /** 服务断开（用户关掉开关 / 被系统停掉）。 */
    fun onDisconnected() {
        connected = false
        foregroundPkg = ""
        // 前台包名没了：高德取数的暂停判定要换回 10019 那一路，重新判一次
        AmapNavi.onForegroundChanged()
        Log.i(TAG, "无障碍服务已断开")
    }

    // ---------------- 前台应用 ----------------

    /**
     * 服务收到窗口切换事件时调。
     *
     * 只做一件事：这个包算不算「用户真的切到了某个应用」，算就更新 [foregroundPkg]。
     *
     * ★ 前台是**自家包**时不碰覆盖层：同一个包里既有桌面（要显示覆盖层，因为
     *   自带 dock 已让位）又有设置页（要压住覆盖层，整屏面板），无障碍只拿得到包名，
     *   分不出是哪个 Activity —— 交给它们的 onResume 各自决定。
     */
    fun onForeground(ctx: Context, pkg: String) {
        if (pkg.isEmpty()) return
        val selfPkg = ctx.packageName

        // 自家包一定认（桌面、设置页），但不改覆盖层的显隐
        if (pkg == selfPkg) {
            foregroundPkg = pkg
            // 自家窗口（含我们自己挂的悬浮窗）不算「切走」：悬浮窗只更新"最近外来应用"
            NavInfoOverlay.onForegroundPkg(ctx, pkg)
            // 高德退到后台 → 立刻恢复取数，不用等下一次广播
            AmapNavi.onForegroundChanged()
            return
        }

        if (isSystemPopup(ctx, pkg)) return
        if (!isInstalled(ctx, pkg)) return

        if (pkg != foregroundPkg) foregroundPkg = pkg
        // 切到别的应用：覆盖层要出现（盖住原厂那条竖栏）。同值会被 setSuppressed 去重
        NavOverlay.setSuppressed(ctx, false)
        // 高德导航信息的悬浮窗按同一个前台包名判显隐（桌面上不挂、高德前台不挂）
        NavInfoOverlay.onForegroundPkg(ctx, pkg)
        // 切到高德 → 立刻暂停取数（解析、超时、索要车道全停），省电省 CPU
        AmapNavi.onForegroundChanged()
    }

    /**
     * 系统弹窗 / 输入法 / 权限框这类"临时窗口"不算前台应用切换。
     *
     * 车机上这些窗口弹出来时也会发 `TYPE_WINDOW_STATE_CHANGED`，包名却是输入法或
     * `com.android.systemui`。不排掉的话，设置页里一弹输入法，覆盖层就冒出来了。
     */
    private fun isSystemPopup(ctx: Context, pkg: String): Boolean {
        if (pkg == "android") return true
        if (pkg == "com.android.systemui") return true
        if (pkg.contains("permissioncontroller")) return true
        if (pkg == "com.android.packageinstaller") return true
        if (pkg.endsWith(".inputmethod") || pkg == "com.android.inputmethod.latin") return true
        // 当前默认输入法（各家车机装的不一样，动态取一次）
        val ime = runCatching {
            Settings.Secure.getString(
                ctx.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD
            )
        }.getOrNull()
        if (!ime.isNullOrEmpty()) {
            val imePkg = ime.substringBefore('/')
            if (pkg == imePkg) return true
        }
        return false
    }

    private fun isInstalled(ctx: Context, pkg: String): Boolean = synchronized(installed) {
        installed[pkg] ?: run {
            val ok = runCatching {
                ctx.packageManager.getPackageInfo(pkg, 0)
                true
            }.getOrDefault(false)
            // 包列表变化（装/卸应用）时会脏，但窗口切换的判据只要"是不是个真应用"，
            // 残留几条旧结果影响不大；超过 200 条整体清一次即可。
            if (installed.size > 200) installed.clear()
            installed[pkg] = ok
            ok
        }
    }
}
