package com.h6n.launcher.ui.main

import android.app.AlertDialog
import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import com.h6n.launcher.core.EngineHours
import com.h6n.launcher.domain.ConfigSnapshot
import com.h6n.launcher.ui.common.dp
import com.h6n.launcher.ui.common.dpf

/**
 * 卡片长按后的弹窗。
 *
 * 「保养计划」弹窗（里程 / 上次保养累计 / 机油周期）已按需求整体删除：
 * 桌面长按机油卡、设置页维护分栏都直接打开 [showEngineHours]，
 * 改完累计运行时间，机油剩余时间按固定 200h 周期立刻重算。
 *
 * 弹窗只负责改数据，不直接碰 UI —— 保存后由调用方立刻重绑卡片。
 */
object CardDialogs {

    private val POS_NAMES = arrayOf("前左", "前右", "后左", "后右")

    // ---------------- 胎压位置 ----------------

    /**
     * 「调整胎压位置」：点两个轮位即互换（原版提示文案一致）。
     *
     * 轮胎换位后没做传感器重新匹配时用 —— 只改显示位置，不动数据。
     * 结果写 `page2.tirePosMap`（"0,1,2,3"，值是传感器序号）。
     */
    fun showTireSwap(ctx: Context, snap: ConfigSnapshot, onSave: (String) -> Unit) {
        val map = parseMap(snap.str("page2.tirePosMap", "0,1,2,3"))
        var picked = -1

        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ctx.dp(24), ctx.dp(8), ctx.dp(24), 0)
        }
        val hint = TextView(ctx).apply {
            text = "点两个轮位即可互换。\n（轮胎换位后没做传感器重新匹配时用 —— 只改显示位置，不动数据）"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setPadding(0, 0, 0, ctx.dp(12))
        }
        root.addView(hint)

        val cells = arrayOfNulls<TextView>(4)

        fun paint() {
            for (i in 0..3) {
                val tv = cells[i] ?: continue
                tv.text = "${POS_NAMES[i]}\n传感器 ${map[i] + 1}"
                tv.background = GradientDrawable().apply {
                    cornerRadius = ctx.dpf(14f)
                    setColor(if (i == picked) 0x332F7BF6 else 0x0F000000)
                    setStroke(ctx.dp(1), if (i == picked) 0xFF2F7BF6.toInt() else 0x33000000)
                }
            }
        }

        for (row in 0..1) {
            val line = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, ctx.dp(4), 0, ctx.dp(4))
            }
            for (col in 0..1) {
                val i = row * 2 + col
                val tv = TextView(ctx).apply {
                    gravity = Gravity.CENTER
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
                    setPadding(0, ctx.dp(14), 0, ctx.dp(14))
                    isClickable = true
                    setOnClickListener {
                        when {
                            picked == i -> picked = -1
                            picked < 0 -> picked = i
                            else -> {
                                val a = picked
                                val t = map[a]; map[a] = map[i]; map[i] = t
                                picked = -1
                            }
                        }
                        paint()
                    }
                }
                cells[i] = tv
                line.addView(tv, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    val m = ctx.dp(6)
                    setMargins(m, m, m, m)
                })
            }
            root.addView(line)
        }
        paint()

        AlertDialog.Builder(ctx)
            .setTitle("调整胎压位置")
            .setView(root)
            .setPositiveButton("完成") { _, _ -> onSave(map.joinToString(",")) }
            .setNegativeButton("恢复默认位置") { _, _ ->
                map[0] = 0; map[1] = 1; map[2] = 2; map[3] = 3
                onSave(map.joinToString(","))
            }
            .show()
    }

    // ---------------- 发动机累计运行时长 ----------------

    /**
     * 「发动机累计运行时长」：手动填仪表上看到的累计运行小时。
     *
     * 桌面刚装上车时累计是 0，机油寿命会显示"满"，所以必须能手动对齐；
     * 保存后走 [EngineHours.setTotal]，剩余时间按固定 200h 周期立刻重算。
     * 桌面长按机油卡和设置页「维护 → 保养」用的是同一个弹窗。
     */
    fun showEngineHours(ctx: Context, onSave: (hours: Int, minutes: Int) -> Unit) {
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ctx.dp(24), ctx.dp(8), ctx.dp(24), 0)
        }
        root.addView(TextView(ctx).apply {
            text = "填车上仪表看到的发动机累计运行时间（不是里程）。\n" +
                "当前：${EngineHours.text()}。保存后桌面「机油寿命」按 200h 周期重新计算。"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setPadding(0, 0, 0, ctx.dp(10))
        })

        val etH = numberFieldInt(ctx, "小时", EngineHours.hoursPart().toString())
        val etM = numberFieldInt(ctx, "分钟（0-59）", EngineHours.minutesPart().toString())
        root.addView(labeled(ctx, "累计小时（h）", etH))
        root.addView(labeled(ctx, "零头分钟（m）", etM))

        AlertDialog.Builder(ctx)
            .setTitle("发动机累计运行时长")
            .setView(root)
            .setPositiveButton("保存") { _, _ ->
                val h = (etH.text.toString().trim().toIntOrNull() ?: 0).coerceIn(0, 200_000)
                val m = (etM.text.toString().trim().toIntOrNull() ?: 0).coerceIn(0, 59)
                onSave(h, m)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun numberFieldInt(ctx: Context, hintText: String, value: String): EditText =
        EditText(ctx).apply {
            hint = hintText
            setText(value)
            inputType = InputType.TYPE_CLASS_NUMBER
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            setSingleLine()
        }

    private fun labeled(ctx: Context, label: String, field: EditText): LinearLayout =
        LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, ctx.dp(8), 0, 0)
            addView(TextView(ctx).apply {
                text = label
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            })
            addView(field)
        }

    /** "0,1,2,3" → IntArray；长度不对 / 越界 / 重复都回退默认。 */
    private fun parseMap(raw: String): IntArray {
        val arr = raw.split(",").mapNotNull { it.trim().toIntOrNull() }
        if (arr.size != 4 || arr.any { it !in 0..3 } || arr.toSet().size != 4) {
            return intArrayOf(0, 1, 2, 3)
        }
        return arr.toIntArray()
    }
}
