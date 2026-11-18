package com.h6n.launcher.core

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import com.h6n.launcher.core.H6Log as Log

/**
 * 「返回原车桌面」。
 *
 * ## 目标是谁（2026-11-10 从 `system.img` 重新解包核对）
 * 原厂桌面 `com.beantechs.launcher` 0.5.6_062_00（targetSdk 27）整个 APK 只有两个 Activity：
 *
 * | 组件 | intent-filter | 说明 |
 * |---|---|---|
 * | `.activity.MainActivity` | 只有 `VIEW` + `jumpactivity://beantechs/launcher/applist` | 真正的桌面主界面，`launchMode=singleTask` |
 * | `.activity.SysGuideActivity` | 只有 `CATEGORY_HOME`，**没有 ACTION_MAIN** | 首启引导，`directBootAware` |
 *
 * 两个都**没有** `ACTION_MAIN`，所以 `ACTION_MAIN + CATEGORY_HOME` 的隐式 HOME 意图
 * 在这台车机上根本解析不到原厂桌面 —— 而本桌面装好就是默认 HOME，发隐式 HOME 只会绕回自己。
 * （旧版 `SidebarHomeService.goHome()` 就是这么写的，在设了默认桌面的场景下等于"回自己"。）
 *
 * 结论：**必须显式组件启动**。两个 Activity 都带 intent-filter 且未写 `exported`，
 * targetSdk < 31，默认导出，外部应用可以显式拉起。
 *
 * ## 兜底顺序
 * 1. 显式 `MainActivity` —— 正常路径；
 * 2. 显式 `SysGuideActivity` —— 个别固件把主界面挪进引导页；
 * 3. `setPackage` 的 HOME 意图 —— 万一以后固件补上了 ACTION_MAIN；
 * 4. 裸 HOME 意图 —— 交给系统选（本桌面可能接住，至少不是没反应）。
 */
object FactoryHome {

    private const val TAG = "H6FactoryHome"
    private const val PKG = "com.beantechs.launcher"
    private const val CLS_MAIN = "com.beantechs.launcher.activity.MainActivity"
    private const val CLS_GUIDE = "com.beantechs.launcher.activity.SysGuideActivity"

    /**
     * 尝试打开原车桌面。
     *
     * @return true = 至少有一次启动请求发出去了（不一定保证界面上来了）；
     *         false = 四种方式全部失败，调用方应该提示用户。
     */
    fun open(ctx: Context): Boolean {
        val flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED

        // ① 显式主界面（正常路径）
        if (launch(ctx, Intent().apply {
                component = ComponentName(PKG, CLS_MAIN)
                addFlags(flags)
            }, "MainActivity")) return true

        // ② 显式引导页（有的固件把桌面主界面挂在它上面）
        if (launch(ctx, Intent().apply {
                component = ComponentName(PKG, CLS_GUIDE)
                addFlags(flags)
            }, "SysGuideActivity")) return true

        // ③ 包名受限的 HOME（留给以后固件补上 ACTION_MAIN 的情况）
        if (launch(ctx, Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_HOME)
                .setPackage(PKG)
                .addFlags(flags), "setPackage HOME")) return true

        // ④ 交给系统选
        if (launch(ctx, Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_HOME)
                .addFlags(flags), "generic HOME")) return true

        Log.w(TAG, "四种方式都没能启动原车桌面")
        return false
    }

    private fun launch(ctx: Context, intent: Intent, tag: String): Boolean = runCatching {
        ctx.startActivity(intent)
        Log.i(TAG, "返回原车桌面：$tag")
        true
    }.getOrElse {
        Log.w(TAG, "$tag 启动失败: ${it.message}")
        false
    }
}
