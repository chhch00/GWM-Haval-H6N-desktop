package com.h6n.launcher.ui.main

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import com.h6n.launcher.core.AppIconCache

/**
 * 停靠栏槽位存储 —— 与旧版（未重写前）的 `DockSlots` **完全同构**，可以直接读旧数据：
 *
 * ```
 * SharedPreferences 名: dock_slots
 * 键:  slot_0 / slot_1 / ...
 * 值:  ""           这一格是空的（用户主动清过，点击/长按都是"选应用"）
 *      "@home"      回桌面
 *      "@settings"  打开桌面设置
 *      "pkg/.Act"   某个应用（ComponentName.flattenToString 的格式）
 * ```
 *
 * 旧版把 `slot_i` 写成 `pkg/.Activity` 的扁平串，这里原样解析，所以从旧桌面升上来的
 * 用户自定义槽位不会丢。`raw()` 返回 null 表示"用户从没设过这一格"（要按默认布局补），
 * 返回 "" 表示"用户明确清空了"（要显示成空格子）—— 两者必须区分。
 */
object DockSlots {

    const val HOME = "@home"
    const val SETTINGS = "@settings"

    /**
     * 「夜间自动息屏」开关格（6.0.32）。
     *
     * 旧版这个开关是左侧一枚 `TYPE_APPLICATION_OVERLAY` 悬浮按钮（显示「月 / 暗」），
     * 重写版按用户要求**不再引入悬浮层**，改成停靠栏里的一个普通槽位：
     * 点一下切换开关，格子的底色跟着亮/暗，和悬浮按钮的观感一致但少一个窗口。
     */
    const val NIGHT = "@night"

    /** 应用商店（6.0.90）：点击打开在线应用商店面板，实时解析 AList 分享目录。 */
    const val STORE = "@store"

    private const val PREF = "dock_slots"
    private const val KEY = "slot_"

    /** 用户设过的值；null = 没设过（走默认布局）。 */
    fun raw(ctx: Context, slot: Int): String? = try {
        val sp = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        val k = KEY + slot
        if (sp.contains(k)) sp.getString(k, "") ?: "" else null
    } catch (t: Throwable) {
        null
    }

    fun put(ctx: Context, slot: Int, value: String) {
        try {
            ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
                .edit().putString(KEY + slot, value).apply()
        } catch (t: Throwable) {
            // 存不进去也不该让桌面崩
        }
    }

    /** `pkg/.Act` → ComponentName；只有包名（旧配置 / 兜底列表）时返回 null，交给包名启动。 */
    fun component(value: String?): ComponentName? {
        if (value.isNullOrEmpty()) return null
        if (value.startsWith("@")) return null
        return try {
            // "pkg/" 这种半截值也当包名处理，不然会拼出一个类名为空的 ComponentName
            if (value.contains('/') && value.substringAfter('/').isNotEmpty()) {
                ComponentName.unflattenFromString(value)
            } else {
                null
            }
        } catch (t: Throwable) {
            null
        }
    }

    /** 取包名：`pkg/.Act` → `pkg`。 */
    fun pkgOf(value: String): String =
        if (value.contains('/')) value.substringBefore('/') else value

    /**
     * 存进槽位/选择集里的字符串。
     *
     * 正常是 `pkg/.Act`（ComponentName.flattenToString）；兜底通道有时拿不到具体 Activity，
     * 这时只存包名 —— [launch] 会走 `getLaunchIntentForPackage` 一样能起来。
     */
    fun key(e: AppEntry): String =
        if (e.component.className.isNullOrEmpty()) e.component.packageName else e.component.flattenToString()

    /** 这一格绑定的应用是否真的能启动（没装 / 被停用都算 false）。 */
    fun exists(ctx: Context, value: String): Boolean {
        if (value.isEmpty() || value.startsWith("@")) return true
        return try {
            val pm = ctx.packageManager
            val cn = component(value)
            if (cn != null) {
                pm.getActivityInfo(cn, 0)
                true
            } else {
                pm.getLaunchIntentForPackage(pkgOf(value)) != null
            }
        } catch (t: Throwable) {
            false
        }
    }

    /** 启动这一格绑定的应用；返回 false 表示没装上 / 启动失败。 */
    fun launch(ctx: Context, value: String): Boolean {
        return try {
            val pm = ctx.packageManager
            val cn = component(value)
            val intent = if (cn != null) {
                Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setComponent(cn)
            } else {
                pm.getLaunchIntentForPackage(pkgOf(value))
            } ?: return false
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
            ctx.startActivity(intent)
            true
        } catch (t: Throwable) {
            false
        }
    }

    /**
     * 应用列表（含图标），按名称排序；同一个包只留一个入口。
     *
     * ★ 两条通道，缺一不可：
     * 1. 正规通道 `queryIntentActivities(MAIN/LAUNCHER)`；
     * 2. 它返回空时（这台车的 ROM 上出现过这种情况：选择器点「选择应用」是一个空列表），
     *    退回 `getInstalledPackages` 逐个 `getLaunchIntentForPackage`。第二条通道慢一些，
     *    但"列表是空的、加不了应用"这种死路必须有兜底。
     */
    fun listApps(ctx: Context): List<AppEntry> {
        val out = queryLauncherActivities(ctx)
        if (out.isNotEmpty()) return out
        return queryInstalledPackages(ctx)
    }

    private fun queryLauncherActivities(ctx: Context): List<AppEntry> {
        // 用 applicationContext 的 PackageManager：解析出来的 Drawable 会长期住在
        // AppIconCache 里，绝不能让它顺着 PackageManager 抓住 Activity。
        val pm = ctx.applicationContext.packageManager
        val out = ArrayList<AppEntry>(96)
        try {
            val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            val list = pm.queryIntentActivities(intent, 0)
            val seen = HashSet<String>()
            for (ri in list) {
                val ai = ri.activityInfo ?: continue
                val pkg = ai.packageName ?: continue
                // 桌面自己不出现在自己的停靠栏里
                if (pkg == ctx.packageName) continue
                if (!seen.add(pkg)) continue
                val cn = ComponentName(pkg, ai.name)
                val label = try {
                    ri.loadLabel(pm).toString()
                } catch (t: Throwable) {
                    pkg
                }
                // 命中缓存就不再 loadIcon（换肤 / 切页反复枚举时省掉整批 Bitmap 解析）
                val icon: Drawable? = AppIconCache.load(AppIconCache.key(pkg)) {
                    try {
                        ri.loadIcon(pm)
                    } catch (t: Throwable) {
                        null
                    }
                }
                out.add(AppEntry(label, cn, icon, isSystem(ai.applicationInfo)))
            }
        } catch (t: Throwable) {
            // 查询失败就返回空表，交给第二条通道
        }
        out.sortWith(appOrder())
        return out
    }

    /**
     * 「全部应用」的排序：**用户应用在前、系统应用在后**，各自按名称。
     *
     * 6.0.105 按用户要求改的：系统里预装的一堆（设置、日历、图库…）原来混在前面，
     * 自己装的应用要翻半天才找得到。
     */
    fun appOrder(): Comparator<AppEntry> =
        compareBy<AppEntry> { it.system }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.label }

    /** `FLAG_SYSTEM` 判定（applicationInfo 可能为 null）。 */
    private fun isSystem(ai: android.content.pm.ApplicationInfo?): Boolean =
        ai != null && (ai.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0

    /** 兜底通道：扫已安装包，只留"真的能启动"的（有 launcher intent 的才算）。 */
    private fun queryInstalledPackages(ctx: Context): List<AppEntry> {
        // 同 queryLauncherActivities：缓存里的 Drawable 不许抓住 Activity Context
        val pm = ctx.applicationContext.packageManager
        val out = ArrayList<AppEntry>(96)
        try {
            for (pi in pm.getInstalledPackages(0)) {
                val pkg = pi.packageName ?: continue
                if (pkg == ctx.packageName) continue
                val launch = try {
                    pm.getLaunchIntentForPackage(pkg)
                } catch (t: Throwable) {
                    null
                } ?: continue
                // 系统里有很多没图标的空壳包，没有 applicationInfo 的直接跳过
                val ai = pi.applicationInfo ?: continue
                val label = try {
                    ai.loadLabel(pm).toString()
                } catch (t: Throwable) {
                    pkg
                }
                if (label.isBlank()) continue
                val icon: Drawable? = AppIconCache.load(AppIconCache.key(pkg)) {
                    try {
                        ai.loadIcon(pm)
                    } catch (t: Throwable) {
                        null
                    }
                }
                val cn = launch.component ?: ComponentName(pkg, "")
                out.add(AppEntry(label, cn, icon, isSystem(ai)))
            }
        } catch (t: Throwable) {
            // 两条通道都失败：返回空表，UI 会提示"没读到应用"
        }
        out.sortWith(appOrder())
        return out
    }

    /**
     * 一个可启动的应用。
     *
     * [system] = 系统应用（`ApplicationInfo.FLAG_SYSTEM`）。「全部应用」页要求
     * **用户应用排在系统应用前面**（6.0.105），所以排序要用到它。
     */
    data class AppEntry(
        val label: String,
        val component: ComponentName,
        val icon: Drawable?,
        val system: Boolean = false
    )
}
