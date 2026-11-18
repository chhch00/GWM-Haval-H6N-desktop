package com.h6n.launcher.core

import android.app.Activity
import android.app.AppOpsManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.os.Process
import android.provider.Settings
import com.h6n.launcher.core.H6Log as Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 「权限激活」的单一数据源：每一项权限的**检测 / 跳转 / 命令提示**。
 *
 * 照搬车机「应用管家」的 `ActivityPermissions` + `SpecialPermissionsBean`：
 * 一个自绘列表，逐项显示已激活 / 未激活，点一下跳到对应的系统授权页；
 * 系统不提供授权入口的（`WRITE_SECURE_SETTINGS` / `READ_LOGS`）给一条 adb 命令作为手动备用。
 *
 * 6.0.51 起这些命令由 [AdbGrant] 在每次回桌面时**自动批量执行**，
 * 用户不需要点任何系统开关；列表保留点击跳转，只是为了兜底和核对状态。
 *
 * ## 和「应用管家」的差异
 * - `通知使用权`（v6.0.73 起）：早期本桌面没有 `NotificationListenerService`，列出来是个
 *   永远点不亮的死项；音乐卡片需要它读 MediaSession，于是补回来。
 * - 描述文案按本桌面的用途重写（原版写的是「应用存储分析」「日志映射」）。
 * - 状态一律**现算**，不缓存：用户从系统页返回立刻就能看到变化（[PermPanel.onHostResume]）。
 *
 * ## 哪些是真「必需」
 * [Item.required] = true 的两项（无障碍、悬浮窗）缺一个，悬浮球 / 侧边手势 /
 * 按键映射就直接不可用，所以会在桌面首次回归时弹一次提示（一天一次）。
 * 其余都是可选能力，缺了只是某个功能降级。
 */
object PermGuide {

    private const val TAG = "H6Perm"

    private const val SP = "h6_perm"
    private const val K_PROMPT_DAY = "prompt_day"

    const val A11Y = "a11y"
    const val OVERLAY = "overlay"
    const val USAGE = "usage"
    const val BATTERY = "battery"
    const val WRITE_SETTINGS = "write_settings"
    const val INSTALL = "install"
    const val WRITE_SECURE = "write_secure"
    const val LOGS = "logs"
    const val NOTIF = "notif"

    /** 一条权限项。 */
    data class Item(
        val id: String,
        val title: String,
        val desc: String,
        /** 缺了核心功能就不可用。 */
        val required: Boolean = false,
        /** 系统里没有授权页，只能靠这条命令。 */
        val adb: String? = null,
        /**
         * 个别 ROM 死活不给授的权限（典型是 READ_LOGS）。命令照发，
         * 但失败不卡「全部授权完成」，也不因此弹「去应用管家」。
         */
        val optional: Boolean = false
    )

    val ALL: List<Item> = listOf(
        Item(
            A11Y, "无障碍服务",
            "悬浮球、侧边手势、按键映射、返回 / 主页动作全靠它。" +
                "路径：设置 → 无障碍 → 已下载的服务 → 哈弗国潮桌面。" +
                "若系统页写着「此服务出现故障」，把开关关掉再打开一次即可（Android 12 起系统不再自动恢复）",
            required = true
        ),
        Item(
            OVERLAY, "悬浮窗",
            "显示在其它应用上方的权限。无障碍没开时，悬浮球 / 手势条 / 导航信息窗靠它兜底",
            required = true
        ),
        Item(
            BATTERY, "忽略电池优化",
            "不被系统冻结，开机任务和后台计时才稳（一般车机不用给，除非频繁被杀）"
        ),
        Item(
            INSTALL, "安装应用",
            "允许安装第三方应用。在线更新下载完的 APK 要靠它才能拉起安装器"
        ),
        Item(
            WRITE_SETTINGS, "修改系统设置",
            "写亮度 / 音量 / 超时等普通系统设置"
        ),
        Item(
            NOTIF, "通知使用权",
            "机油卡中间那条音乐卡靠它读播放器上报的歌名 / 歌手 / 封面。" +
                "安卓只放行两类调用方：系统级权限（signature，普通应用拿不到）和已启用的通知监听器，" +
                "所以必须把「哈弗国潮桌面·音乐卡片」这个监听服务勾上。" +
                "本应用有修改系统安全设置的权限，进桌面时会自己写进去，一般不用手动开；" +
                "实在读不到歌再来这里点一下"
        ),
        Item(
            USAGE, "使用情况访问权限",
            "无障碍读不到前台包名时的兜底判定（比如导航信息窗该不该显示）"
        ),
        Item(
            WRITE_SECURE, "修改系统安全设置",
            "改隐藏系统项时用。系统设置里没有开关，自动授权会 pm grant；" +
                "6.0.51 起清单已声明此项，未声明时 pm grant 会被系统直接拒绝",
            adb = "pm grant %s android.permission.WRITE_SECURE_SETTINGS"
        ),
        Item(
            LOGS, "系统日志权限",
            "抓 logcat 排查问题用。系统设置里没有开关，自动授权会 pm grant；个别车机 ROM 不给，不影响桌面功能",
            adb = "pm grant %s android.permission.READ_LOGS",
            optional = true
        )
    )

    // ------------------------------------------------------------------ 检测

    fun item(id: String): Item? = ALL.firstOrNull { it.id == id }

    /** 单项是否已激活。任何异常一律按「未激活」，避免把状态显示反。 */
    fun granted(ctx: Context, id: String): Boolean = runCatching {
        when (id) {
            A11Y -> H6A11y.enabledInSystem(ctx) || H6A11y.connected
            OVERLAY -> Settings.canDrawOverlays(ctx)
            NOTIF -> MediaWatcher.isGranted(ctx)
            USAGE -> hasUsageAccess(ctx)
            BATTERY -> (ctx.getSystemService(Context.POWER_SERVICE) as PowerManager)
                .isIgnoringBatteryOptimizations(ctx.packageName)
            WRITE_SETTINGS -> Settings.System.canWrite(ctx)
            INSTALL -> ctx.packageManager.canRequestPackageInstalls()
            // 这两个不是 Manifest 的公开常量，只能写字面量
            WRITE_SECURE -> grantedPerm(ctx, "android.permission.WRITE_SECURE_SETTINGS")
            LOGS -> grantedPerm(ctx, "android.permission.READ_LOGS")
            else -> true
        }
    }.getOrElse {
        Log.w(TAG, "检测 $id 失败: ${it.message}")
        false
    }

    private fun grantedPerm(ctx: Context, p: String): Boolean =
        ctx.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    private fun hasUsageAccess(ctx: Context): Boolean {
        val ops = ctx.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager ?: return false
        val mode = if (Build.VERSION.SDK_INT >= 29) {
            ops.unsafeCheckOpNoThrow("android:get_usage_stats", Process.myUid(), ctx.packageName)
        } else {
            @Suppress("DEPRECATION")
            ops.checkOpNoThrow("android:get_usage_stats", Process.myUid(), ctx.packageName)
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    /** 参与「给全了没有」判定的项 = 全部减去 [Item.optional]。 */
    val CORE: List<Item> = ALL.filter { !it.optional }

    /** 未激活的项；[onlyRequired] = true 时只看关键项。 */
    fun missing(ctx: Context, onlyRequired: Boolean = false): List<Item> =
        ALL.filter { (!onlyRequired || it.required) && !granted(ctx, it.id) }

    fun criticalMissing(ctx: Context): List<Item> = missing(ctx, onlyRequired = true)

    /**
     * 冷启动静默授权「必须拿到」的项：排除 [Item.optional]（READ_LOGS 这类
     * 个别 ROM 永远不给、也不影响功能的项）。它才是「授权失败要不要弹应用管家」的判据。
     */
    fun blocking(ctx: Context): List<Item> = missing(ctx).filter { !it.optional }

    /**
     * 6.0.101：**摘要 / 完成判定一律用这个口径，不再算 [Item.optional]**。
     *
     * 之前 `summary()` 用的是全量 [missing]，而自动授权链（[AdbGrant.CORE]）与
     * [blocking] 早就把 READ_LOGS 排除了 —— 两套口径不一致的直接后果是：
     * 车机 ROM 不给 READ_LOGS 时，授权其实全部成功，面板却永远停在
     * 「8 / 9 已激活 · 缺 1 项」，用户以为怎么给都给不全。
     */
    fun summary(ctx: Context): String {
        val bad = blocking(ctx)
        val total = CORE.size
        return if (bad.isEmpty()) "$total 项全部已激活"
        else "${total - bad.size} / $total 已激活 · 差 ${bad.size} 项：" +
            bad.joinToString("、") { it.title }
    }

    /** 设置页「未激活项」：只列真正该给的（可选的那些不算，否则永远显示差一项）。 */
    fun missingNames(ctx: Context): String =
        blocking(ctx).joinToString("、") { it.title }.ifEmpty { "无" }

    /** 可选且当前未授的项（READ_LOGS 这类），面板里标「可跳过」用。 */
    fun skipped(ctx: Context): List<Item> = missing(ctx).filter { it.optional }

    // ------------------------------------------------------------------ 跳转

    /**
     * 跳到该项的系统授权页；没有系统页的（adb 项）返回命令提示。
     *
     * @return 给 Toast 用的说明文案
     */
    fun open(act: Activity, id: String): String {
        val me = act.packageName
        val pkgUri = Uri.parse("package:$me")

        val intent: Intent? = when (id) {
            A11Y -> Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            OVERLAY -> Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, pkgUri)
            NOTIF -> Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS")
            USAGE -> Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
            BATTERY -> Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
            WRITE_SETTINGS -> Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, pkgUri)
            INSTALL -> Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, pkgUri)
            else -> null        // 只能 adb
        }

        if (intent != null) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val ok = runCatching { act.startActivity(intent); true }.getOrDefault(false)
            if (ok) return "已打开系统设置，找到「哈弗国潮桌面」打开开关"
            // 有的车机把某一页裁掉了，退到设置首页让用户自己找
            val fallback = runCatching {
                act.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                true
            }.getOrDefault(false)
            return if (fallback) "这台车机没有该授权页，已打开设置，请手动查找"
            else "打不开系统设置"
        }

        val cmd = adbLine(act, id)
        if (cmd == null) return "这一项只能手动处理"
        val copied = copy(act, cmd)
        return if (copied) "命令已复制：$cmd" else "请用 adb 执行：$cmd"
    }

    /** 该项的 adb 命令（已填好包名）；非 adb 项返回 null。 */
    fun adbLine(ctx: Context, id: String): String? {
        val tpl = item(id)?.adb ?: return null
        val full = String.format(Locale.US, tpl, ctx.packageName)
        // 车上一般连着电脑跑，带上 adb shell 前缀更省事
        return "adb shell $full"
    }

    fun copy(ctx: Context, text: String): Boolean = runCatching {
        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("h6_perm", text))
        true
    }.getOrDefault(false)

    // ------------------------------------------------------------------ 提示节流

    /** 今天是否已经提示过（一天一次，避免每次回桌面都弹）。 */
    fun promptAllowed(ctx: Context): Boolean {
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        val last = ctx.getSharedPreferences(SP, Context.MODE_PRIVATE).getString(K_PROMPT_DAY, "")
        return last != today
    }

    fun markPrompted(ctx: Context) {
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        ctx.getSharedPreferences(SP, Context.MODE_PRIVATE).edit()
            .putString(K_PROMPT_DAY, today).apply()
    }

    /** 设置页里「去激活 / 已激活」的按钮文案。 */
    fun shortState(ctx: Context, id: String): String =
        if (granted(ctx, id)) "已激活" else "去激活"
}
