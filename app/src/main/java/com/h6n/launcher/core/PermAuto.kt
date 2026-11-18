package com.h6n.launcher.core

import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.Looper
import com.h6n.launcher.core.H6Log as Log

/**
 * 权限自检（6.0.88 引入，6.0.89 改为「只在新装 / 更新后各跑一次」）。
 *
 * 6.0.89 起不再每次冷启动都检测、更不每次回桌面静默补漏：那套流程会重启 adbd、
 * 连本机端口、失败还弹长 Toast，用户反馈太打扰。现在按 [versionCode] 做持久化门控：
 * - 新装（从没检查过）→ 跑一次；
 * - 覆盖更新（versionCode 变大）→ 再跑一次；
 * - 同版本重启 / 转屏 / 从设置页回来 → 直接跳过，一次都不跑。
 *
 * 手动补授权入口不受影响：设置页权限面板的「一键授权全部」直接走 [AdbGrant.ensure]，
 * 由用户主动触发，不算自动检测。
 *
 * 自动检测流程：
 * 1. 现场读一遍 [PermGuide.blocking]（所有非可选权限），全齐就直接收工、不折腾 adbd；
 * 2. 缺任一项 → [AdbGrant.requestRetry] 清掉节流 → [AdbGrant.ensure] 后台静默授权；
 * 3. 授权结束再读一遍：补齐就静默结束；**仍缺**则把缺项和结果文案交给调用方，
 *    由桌面弹「打开应用管家给权限」的引导（见 PermPanel.promptManager）。
 *
 * 反复重试只发生在「另一条授权链正忙着」的情况（最多 3 次、每次隔 3 秒），
 * 避免和手动一键授权叠成两条 socket / su 进程。
 */
object PermAuto {

    private const val TAG = "H6PermAuto"

    private const val SP = "h6_perm_auto"
    /** 最近一次做过自动检测时的 versionCode；-1 表示从没检测过。 */
    private const val K_CHECKED_VERSION = "checked_version"

    /** 进程级一次性闸门：同进程多次 onResume（转屏 / 设置页返回）不会重入。 */
    @Volatile
    private var started = false

    /** 测试/排障用：清掉进程闸门（持久化的版本门控不受影响）。 */
    fun reset() {
        started = false
    }

    private fun currentVersion(ctx: Context): Int = runCatching {
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionCode
    }.getOrDefault(0)

    /** 新装（没记录）或覆盖更新（版本变大）才返回 true。 */
    private fun needCheck(ctx: Context): Boolean {
        val cur = currentVersion(ctx)
        val last = ctx.getSharedPreferences(SP, Context.MODE_PRIVATE)
            .getInt(K_CHECKED_VERSION, -1)
        return cur > 0 && cur > last
    }

    /** 记下本次检测对应的版本，之后同版本不再自动检测。 */
    private fun markChecked(ctx: Context) {
        val cur = currentVersion(ctx)
        if (cur <= 0) return
        runCatching {
            ctx.getSharedPreferences(SP, Context.MODE_PRIVATE).edit()
                .putInt(K_CHECKED_VERSION, cur).apply()
        }
    }

    /**
     * 回到桌面时调用。内部按 versionCode 门控：只有新装 / 更新后的第一次才真跑。
     *
     * @param onFailed 静默授权后仍缺权限时回调（运行在主线程，Activity 活着）
     */
    fun onColdStart(
        act: Activity,
        onFailed: (missing: List<PermGuide.Item>, detail: String) -> Unit
    ) {
        if (started) return
        started = true

        val app = act.applicationContext
        if (!needCheck(app)) {
            Log.i(TAG, "权限检测：本版本已检测过，跳过（只在新装 / 更新后跑）")
            return
        }
        // 决定跑就先标记：即使这次授权失败也不再自动重试，避免反复打扰；
        // 用户可随时用权限面板里的「一键授权全部」手动补。
        markChecked(app)

        val before = PermGuide.blocking(app)
        if (before.isEmpty()) {
            Log.i(TAG, "权限检测：全部已激活，不折腾 adbd")
            return
        }
        Log.i(TAG, "权限检测：缺 " + before.joinToString("、") { it.title })
        AdbGrant.requestRetry()
        attempt(app, act, onFailed, 0)
    }

    private fun attempt(
        app: Context,
        act: Activity,
        onFailed: (List<PermGuide.Item>, String) -> Unit,
        tries: Int
    ) {
        AdbGrant.ensure(app) { _, msg ->
            val still = PermGuide.blocking(app)
            when {
                still.isEmpty() -> Log.i(TAG, "冷启动静默授权完成：全部已激活")

                // 另一条授权链还占着 running 闸门：稍后再来，不要拿「正在授权」
                // 当失败去弹应用管家（用户明明正在等结果）。
                msg.startsWith("正在后台授权") && tries < 3 ->
                    Handler(Looper.getMainLooper()).postDelayed({
                        attempt(app, act, onFailed, tries + 1)
                    }, 3000L)

                act.isFinishing || act.isDestroyed ->
                    Log.w(TAG, "冷启动授权失败，但宿主界面已关闭，跳过弹窗")

                else -> {
                    Log.w(TAG, "冷启动静默授权后仍缺：" + still.joinToString("、") { it.title })
                    onFailed(still, msg)
                }
            }
        }
    }
}
