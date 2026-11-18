package com.h6n.launcher.core

import android.content.Context

/**
 * 「全部应用」页里被用户隐藏掉的包名（6.0.105）。
 *
 * ## 为什么单独存一份而不是写进配置树
 * 隐藏是**用户在本机上的偏好**，跟主题、字号这些配置不是一类东西：
 * 配置会被「恢复默认 / 导入备份」整体覆盖，隐藏名单被顺手清掉会很突兀。
 * 所以用独立的 SharedPreferences，只按包名追加 / 清空，读写都是几次字符串操作。
 *
 * ## 边界
 * - 只影响「全部应用」页的显示，**不影响启动**：隐藏掉的应用照样能从停靠栏、
 *   自动化动作、别的应用里打开；
 * - 不卸载、不禁用，只是不让它占这一页的位置；
 * - 设置里「恢复全部隐藏」一键清空（页头也会出现一个入口，见 AppPageView）。
 */
object AppHide {

    private const val PREF = "h6_app_hide"
    private const val KEY = "pkgs"

    /** 当前隐藏的包名集合（返回副本，调用方随便改）。 */
    fun hidden(ctx: Context): Set<String> {
        val raw = runCatching {
            ctx.applicationContext.getSharedPreferences(PREF, Context.MODE_PRIVATE)
                .getString(KEY, "") ?: ""
        }.getOrDefault("")
        if (raw.isEmpty()) return emptySet()
        return raw.split('\n').map { it.trim() }.filter { it.isNotEmpty() }.toHashSet()
    }

    fun isHidden(ctx: Context, pkg: String): Boolean = hidden(ctx).contains(pkg)

    fun hide(ctx: Context, pkg: String) {
        if (pkg.isEmpty()) return
        val cur = hidden(ctx).toMutableSet()
        if (!cur.add(pkg)) return
        save(ctx, cur)
    }

    fun show(ctx: Context, pkg: String) {
        val cur = hidden(ctx).toMutableSet()
        if (!cur.remove(pkg)) return
        save(ctx, cur)
    }

    fun clear(ctx: Context) {
        runCatching {
            ctx.applicationContext.getSharedPreferences(PREF, Context.MODE_PRIVATE)
                .edit().remove(KEY).apply()
        }
    }

    private fun save(ctx: Context, set: Set<String>) {
        runCatching {
            ctx.applicationContext.getSharedPreferences(PREF, Context.MODE_PRIVATE)
                .edit().putString(KEY, set.joinToString("\n")).apply()
        }
    }
}
