package com.h6n.launcher.domain

/**
 * 配置树的路径常量。
 *
 * ## 为什么是"路径"而不是"字段"
 * 现有配置（assets/ui_config.json + SharedPreferences h6_cfg 覆盖层）是
 * 一棵自由结构的 JSON 树，共 36 个顶层分组、数百个字段，用 `分组.字段`
 * 形式的扁平 key 存覆盖值。它不是一个固定 schema 的 Settings 对象，
 * 因此重写后仍然保留这棵树，只是换了承载方式和变更通知方式。
 *
 * 覆盖层 key 的生成规则（与 ConfigStore.applyOverrides 完全一致）：
 * ```
 * 顶层分组 "window" 下的 "mode"  ->  "window.mode"
 * 嵌套 "page2"."tirePosMap"      ->  "page2.tirePosMap"
 * ```
 * 以 `__` 开头的 key 是元数据（`__stamp`、`__migrated_v6`），不参与合并、
 * 也不参与 diff。
 */
object ConfigPath {

    /** 元数据 key 前缀，读写覆盖层时必须过滤掉。 */
    const val META_PREFIX = "__"

    /** 覆盖层写入代数，用于一次性迁移 SharedPreferences -> DataStore。 */
    const val META_STAMP = "__stamp"
    const val META_MIGRATED = "__migrated_v6"

    // ---- 顶层分组（与 ui_config.json 的实际分组名一致）----
    const val THEME = "theme"
    const val COLORS = "colors"
    const val SIZES = "sizes"
    const val PAGE2 = "page2"
    const val WINDOW = "window"
    const val WEATHER = "weather"
    const val MEDIA = "media"
    const val SCREEN = "screen"
    const val VEHICLE = "vehicle"
    const val ENGINE = "engine"
    const val DOOR = "door"
    const val STATUS = "status"
    const val STATUS_ITEMS = "statusItems"
    const val IMMERSIVE = "immersive"
    const val APP_PAGE = "appPage"
    const val SETTINGS = "settings"
    const val UPDATE = "update"
    const val LOG = "log"

    // ---- 常用字段（只列出重写期真正要用到的，其余直接写字符串）----
    const val THEME_MODE = "theme.mode"                 // day / night / auto
    const val WINDOW_MODE = "window.mode"               // 车窗判定来源
    const val WEATHER_CITY = "weather.city"
    const val WEATHER_ENABLED = "weather.enabled"
    const val SCREEN_BG = "screen.bgImage"
    const val PAGE2_TIRE_UNIT = "page2.tireUnit"
    const val PAGE2_FS_SCALE = "page2.fsScalePct"
    const val UPDATE_URL = "update.url"
    const val UPDATE_AUTO = "update.auto"

    /** 该 key 是否为内部元数据。 */
    fun isMeta(key: String): Boolean = key.startsWith(META_PREFIX)

    /** 取扁平 key 的分组前缀，例如 `page2.tireUnit` -> `page2.`；根级 key 返回空串。 */
    fun prefixOf(key: String): String {
        val dot = key.indexOf('.')
        return if (dot > 0) key.substring(0, dot + 1) else ""
    }
}
