package com.h6n.launcher.core

import android.content.Context
import android.media.AudioManager
import android.view.KeyEvent
import com.h6n.launcher.ui.main.DockSlots
import com.h6n.launcher.ui.main.H6A11yService

/**
 * 自动化动作表 —— 悬浮按钮 / 侧边手势 / 按键映射 / 开机任务**共用一套动作**。
 *
 * ## 为什么抽出一层
 * 车机「应用管家」那套自动化（`com/yunpan/appmanage`）里，日志映射、事件映射、
 * 按键映射都指向同一份「可执行动作」清单，只是触发源不同。这里照同样的分层做：
 *
 * ```
 * 触发源：悬浮按钮单击 / 长按、三条边手势、物理按键、开机任务
 *              ↓ 都产出同一个字符串 action
 * 执行器：本文件 perform() —— 唯一的动作出口
 * ```
 *
 * 好处是加动作只改一处：四种触发方式自动都有。
 *
 * ## 各动作走哪条通道
 * - `back` / `home` / `recents`：无障碍 `performGlobalAction`（[H6A11yService.globalAction]）。
 *   这是第三方应用唯一正规的全局导航通道，不需要 root、不需要系统签名。
 * - `shade`：走 [SystemBar.expand]，先试无障碍 `GLOBAL_ACTION_NOTIFICATIONS`，
 *   再试 `StatusBarManager` 反射 —— 桌面是沉浸全屏，系统状态栏被藏着，
 *   只有这样才能把原车那条状态栏 / 通知面板拉下来。
 * - 媒体键：`AudioManager.dispatchMediaKeyEvent`（和物理按键同一条通道，
 *   车机的播放器 / 高德播报都能正确响应）。
 * - 音量：`adjustStreamVolume(STREAM_MUSIC)`，带系统音量条。
 * - `drive`：复用 [SteerMode.toggle]，也就是方向盘长按那一套驾驶模式切换。
 * - `acCycle`：空调内 / 外循环，走 [Hvac]（ICarInfo `ID_HVAC` 模块，下发后回读确认）。
 *   它要阻塞等回读，所以是**异步**执行、结果自己 Toast，[perform] 直接返回空串。
 * - `app:<pkg/.Act>`：复用 [DockSlots.launch]，与停靠栏点击同一条路径。
 *
 * 所有动作都 runCatching，失败只返回一句人话结果，绝不把异常抛给触发源
 * （触发源常常在无障碍服务线程或窗口回调里，一崩就是整桌面）。
 */
object AutoActions {

    const val BACK = "back"
    const val HOME = "home"
    const val RECENTS = "recents"
    const val SHADE = "shade"
    const val PLAY = "play"
    const val NEXT = "next"
    const val PREV = "prev"
    const val VOL_UP = "volUp"
    const val VOL_DOWN = "volDown"
    const val DRIVE = "drive"
    /** 空调内 / 外循环（6.0.100）。走 [Hvac.toggle]，可指到方向盘任意一颗键上。 */
    const val AC = "acCycle"
    const val NONE = "none"

    /** 「打开某个应用」的前缀，后面接 `pkg/.Act`（[DockSlots.key] 的格式）。 */
    const val APP = "app:"

    /** 选择器展示顺序 = 常用程度。 */
    val LIST: List<Pair<String, String>> = listOf(
        BACK to "返回键",
        HOME to "回桌面",
        RECENTS to "最近任务",
        SHADE to "车机状态栏",
        PLAY to "播放 / 暂停",
        NEXT to "下一首",
        PREV to "上一首",
        VOL_UP to "音量 +",
        VOL_DOWN to "音量 −",
        DRIVE to "切驾驶模式",
        AC to "空调内外循环"
    )

    /** 动作短名（列表 / 设置页显示）。 */
    fun label(action: String): String = when {
        action.startsWith(APP) -> "打开应用"
        action == NONE || action.isEmpty() -> "不动作"
        else -> LIST.firstOrNull { it.first == action }?.second ?: action
    }

    /** 带参数的动作名，例如「打开 高德地图」。 */
    fun labelWithArg(ctx: Context, action: String): String =
        if (action.startsWith(APP)) "打开 " + appName(ctx, action.removePrefix(APP)) else label(action)

    /** `pkg/.Act` → 应用名；取不到就把包名露出来，便于排查。 */
    fun appName(ctx: Context, value: String): String {
        if (value.isEmpty()) return "（未选应用）"
        val pkg = DockSlots.pkgOf(value)
        return runCatching {
            val pm = ctx.packageManager
            @Suppress("DEPRECATION")
            val ai = if (value.contains('/')) {
                DockSlots.component(value)?.let { pm.getActivityInfo(it, 0) }
            } else null
            val app = ai?.applicationInfo ?: pm.getApplicationInfo(pkg, 0)
            pm.getApplicationLabel(app).toString()
        }.getOrDefault(pkg)
    }

    /**
     * 执行一个动作。
     *
     * @return 给用户看的结果文案（Toast / 设置页诊断直接用），空串表示无需提示。
     */
    fun perform(ctx: Context, action: String): String {
        if (action.isEmpty() || action == NONE) return ""
        val app = ctx.applicationContext ?: ctx
        return runCatching {
            when {
                action == BACK -> global(app, 1, "已返回")
                action == HOME -> global(app, 2, "已回桌面")
                action == RECENTS -> global(app, 3, "已打开最近任务")
                action == SHADE ->
                    if (SystemBar.expand(app)) "已展开车机状态栏" else "状态栏通道不可用（先开无障碍）"
                action == PLAY -> media(app, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, "播放 / 暂停")
                action == NEXT -> media(app, KeyEvent.KEYCODE_MEDIA_NEXT, "下一首")
                action == PREV -> media(app, KeyEvent.KEYCODE_MEDIA_PREVIOUS, "上一首")
                action == VOL_UP -> volume(app, true)
                action == VOL_DOWN -> volume(app, false)
                action == DRIVE -> SteerMode.toggle(app)
                // 空调要下发后等 400ms 两次回读，不能压在按键 / 手势线程上：
                // 交给 Hvac 自己起线程，结果它自己 Toast，这里直接返回空串。
                action == AC -> {
                    Hvac.toggleAsync(app) { msg ->
                        runCatching {
                            android.widget.Toast.makeText(app, msg, android.widget.Toast.LENGTH_SHORT).show()
                        }
                    }
                    ""
                }
                action.startsWith(APP) -> {
                    val v = action.removePrefix(APP)
                    if (v.isEmpty()) "还没选应用"
                    else if (DockSlots.launch(app, v)) "已打开 " + appName(app, v)
                    else "打开失败：" + appName(app, v) + "（没装 / 被停用）"
                }
                else -> "未知动作：" + action
            }
        }.getOrElse { "动作失败：" + (it.javaClass.simpleName) }
    }

    /** 执行并 Toast。触发源（手势 / 悬浮球 / 按键）用这个，用户能看到反馈。 */
    fun performAndToast(ctx: Context, action: String) {
        val msg = perform(ctx, action)
        if (msg.isEmpty()) return
        runCatching {
            android.widget.Toast.makeText(ctx.applicationContext, msg, android.widget.Toast.LENGTH_SHORT)
                .show()
        }
    }

    // ------------------------------------------------------------------ 私有

    private fun global(ctx: Context, code: Int, okText: String): String =
        if (H6A11yService.globalAction(code)) okText else "无障碍服务没开，导航动作不可用"

    private fun media(ctx: Context, code: Int, okText: String): String {
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            ?: return "取不到 AudioManager"
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
        return okText
    }

    private fun volume(ctx: Context, up: Boolean): String {
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            ?: return "取不到 AudioManager"
        val dir = if (up) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER
        am.adjustStreamVolume(AudioManager.STREAM_MUSIC, dir, AudioManager.FLAG_SHOW_UI)
        return if (up) "音量 +" else "音量 −"
    }
}
