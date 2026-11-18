package com.h6n.launcher.ui.common

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import com.h6n.launcher.core.CarSignal
import com.h6n.launcher.core.SignalHub
import com.h6n.launcher.domain.ConfigSnapshot
import java.util.Calendar

/**
 * 尺寸/颜色/字体/形状的统一入口。
 *
 * ## 为什么单独抽一层
 * 旧工程里这些全部散在 `MainActivity` 的 5.8 万行 smali 中（p2t / p2tc / p2bg / cardR / dp…），
 * 改一个卡片就要动主类。这里按"取值(Theme) / 几何(dp, squircle) / 画笔(GlassBg)"拆开，
 * 卡片只声明自己要什么，不再关心配置从哪来。
 */

fun Context.dp(v: Int): Int = Math.round(v * resources.displayMetrics.density)

fun Context.dpf(v: Float): Float = v * resources.displayMetrics.density

/** 字体缓存：Typeface.createFromAsset 每次调用都会重新解析 ttf，必须缓存。 */
object Typo {
    private val cache = HashMap<String, Typeface>(4)

    fun of(ctx: Context, path: String): Typeface = cache.getOrPut(path) {
        try {
            Typeface.createFromAsset(ctx.assets, path)
        } catch (t: Throwable) {
            Typeface.DEFAULT
        }
    }
}

/**
 * 一次绘制所需的主题：配置快照 + 当前是否夜间。
 *
 * 颜色取值规则与原版 `UiConfig.color` 一致：夜间优先读 `<key>Night`，
 * 没有该键则回退到昼间值（例如 p2TextMain 就没有夜间变体）。
 */
class Theme(
    val ctx: Context,
    val snap: ConfigSnapshot,
    val night: Boolean
) {
    private val assetRegular = snap.str("fonts.regular", "fonts/MiSans-Medium.ttf")
    private val assetMedium = snap.str("fonts.medium", "fonts/MiSans-Semibold.ttf")
    private val assetBold = snap.str("fonts.bold", "fonts/MiSans-Bold.ttf")

    val regular: Typeface get() = Typo.of(ctx, assetRegular)
    val medium: Typeface get() = Typo.of(ctx, assetMedium)
    val bold: Typeface get() = Typo.of(ctx, assetBold)

    fun color(key: String, def: Int): Int {
        if (night) {
            val n = nightRaw(key)
            if (n.isNotEmpty()) return dimIfLabel(key, parse(n, def))
        }
        val d = snap.str("colors.$key", "")
        return if (d.isEmpty()) def else parse(d, def)
    }

    /**
     * 夜间取色。先按 `<key>Night` 找，找不到再走 [NIGHT_ALIAS]。
     *
     * 配置里的夜间键有两套命名：`textMainNight`（后缀式，多数）和
     * `screenNightTop` / `mediaNightTop` / `panelNightTop`（插入式）。
     * 早期只查后缀式，于是屏幕底色、音乐卡底色在夜间根本取不到夜间值 ——
     * 切夜间后背景还是白天那套浅蓝，只有文字变白，整屏糊成一片。
     */
    private fun nightRaw(key: String): String {
        val direct = snap.str("colors.${key}Night", "")
        if (direct.isNotEmpty()) return direct
        val alias = NIGHT_ALIAS[key] ?: return ""
        return snap.str("colors.$alias", "")
    }

    /**
     * 标签色夜间压到 70% 不透明度。
     *
     * `textLabelNight` / `p2TextTitle` 的夜间值都是纯白，和 `textMainNight` 完全一样，
     * 直接落地会让标题、副标题、主数值同级，卡片里看不出主次（原版是有层次的）。
     */
    private fun dimIfLabel(key: String, c: Int): Int {
        if (key !in LABEL_KEYS) return c
        if (Color.alpha(c) != 255) return c
        return (c and 0x00FFFFFF) or (0xB4 shl 24)
    }

    fun size(key: String, def: Int): Int = snap.int("sizes.$key", def)

    /**
     * 当前主文字是不是「浅色（白字）」。
     *
     * 不能只看 [night]：桌面主题允许用户改色，老版本导出的配置里白天也可能配成白字
     * （旧版桌面本来就是深底白字）。壁纸蒙层要按**实际文字明度**决定压暗还是提亮，
     * 否则白字 + 白蒙层会彻底看不见。
     */
    fun lightText(): Boolean = luminance(color("textMain", INK)) > 0.55f

    /** 相对亮度（0 黑 ~ 1 白），用于判断文字明暗。 */
    private fun luminance(c: Int): Float {
        val r = Color.red(c) / 255f
        val g = Color.green(c) / 255f
        val b = Color.blue(c) / 255f
        return 0.299f * r + 0.587f * g + 0.114f * b
    }

    fun text(key: String, def: String): String = snap.str("texts.$key", def)

    fun flag(key: String, def: Boolean): Boolean = snap.bool(key, def)

    private fun parse(raw: String, def: Int): Int = try {
        Color.parseColor(if (raw.startsWith("#")) raw else "#$raw")
    } catch (t: Throwable) {
        def
    }

    companion object {
        const val INK = 0xFF1F2937.toInt()
        const val LABEL = 0xFF5E6471.toInt()
        const val WARN = 0xFFC0392B.toInt()

        /** 配置里用「插入式」命名的夜间键：colors.<value>。 */
        private val NIGHT_ALIAS = mapOf(
            "screenTop" to "screenNightTop",
            "screenMid" to "screenNightMid",
            "screenBot" to "screenNightBot",
            "panelTop" to "panelNightTop",
            "panelMid" to "panelNightMid",
            "panelBot" to "panelNightBot",
            "mediaTop" to "mediaNightTop",
            "mediaMid" to "mediaNightMid",
            "mediaBot" to "mediaNightBot",
            "mediaStroke" to "mediaNightStroke",
            // 六格文字没有自己的夜间键：主数值跟主文字、标题/标签跟标签色
            "p2TextMain" to "textMainNight",
            "p2TextTitle" to "textLabelNight",
            "p2TextLabel" to "textLabelNight"
        )

        /**
         * 这几项的夜间值在配置里就是纯白（和主文字同色），需要压一档；
         * musicArtist / musicTime / statusIconOff 本来就有各自的夜间灰，不要动。
         */
        private val LABEL_KEYS = setOf("textLabel", "p2TextLabel", "p2TextTitle")
    }
}

/**
 * 当前是否夜间。
 *
 * ## 判据（`theme.mode`）
 * | 取值 | 含义 |
 * |---|---|
 * | `auto`（默认） | **跟随车灯**：读 ICarInfo `0x01 HEAD_LIGHT_STATUS`（1 夜间 / 0 白天）；没回值再按时间兜底 |
 * | `time` | 只按时间：18:40 之后、06:20 之前算夜间（与原版曲线区间一致） |
 * | `day` / `night` | 手动锁定 |
 *
 * 车灯 item 在 [com.h6n.launcher.core.CarBinderSource] 里 3 秒一轮轮询，
 * 开灯/关灯（含进出隧道自动大灯）都能在几秒内把主题切过去，这就是原版
 * 「昼夜跟随灯光」的实际做法；高德那边由 [com.h6n.launcher.core.AmapNightSync]
 * 同步广播，两边同一判据，不会出现桌面夜间、地图白天的割裂。
 */
object NightMode {

    /** 夜间起始（分钟）：18:40 → 06:20 */
    private const val NIGHT_FROM = 18 * 60 + 40
    private const val NIGHT_TO = 6 * 60 + 20

    fun isNight(
        snap: ConfigSnapshot,
        sig: CarSignal? = null,
        now: Calendar = Calendar.getInstance()
    ): Boolean {
        when (snap.str("theme.mode", "auto")) {
            "night" -> return true
            "day" -> return false
            "time" -> return byClock(now)
        }
        // auto：跟随车灯；读不到（-1）说明车身没连上或该 item 没回值，退回时间
        return when ((sig ?: SignalHub.current()).night) {
            1 -> true
            0 -> false
            else -> byClock(now)
        }
    }

    /** 按时间判夜间（`theme.mode=time` 与车灯读不到时的兜底共用）。 */
    fun byClock(now: Calendar = Calendar.getInstance()): Boolean {
        val minutes = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)
        return minutes >= NIGHT_FROM || minutes < NIGHT_TO
    }
}

/**
 * 超椭圆（squircle）路径，指数 n 越大越接近方形，n=2 即普通圆角矩形。
 *
 * 原版 `sizes.cornerStyle=squircle` + `squircleN=5`，卡片圆角用的是这种形状，
 * 用普通圆角矩形代替会明显"看着不一样"（尤其是 96dp 大圆角）。
 */
fun squirclePath(w: Float, h: Float, r: Float, n: Float, path: Path) {
    path.rewind()
    if (w <= 0f || h <= 0f) return
    val rad = r.coerceAtMost(Math.min(w, h) / 2f)
    if (rad <= 0.5f) {
        path.addRect(0f, 0f, w, h, Path.Direction.CW)
        return
    }
    val e = 2f / n.coerceAtLeast(1.2f)
    val steps = 10

    path.moveTo(w - rad, 0f)
    // 右上角：从 (w-rad,0) 走到 (w,rad)
    corner(path, w - rad, rad, rad, e, steps, 1f, -1f, false)
    path.lineTo(w, h - rad)
    // 右下角：从 (w,h-rad) 走到 (w-rad,h)
    corner(path, w - rad, h - rad, rad, e, steps, 1f, 1f, false)
    path.lineTo(rad, h)
    // 左下角：从 (rad,h) 走到 (0,h-rad)
    corner(path, rad, h - rad, rad, e, steps, -1f, 1f, true)
    path.lineTo(0f, rad)
    // 左上角：从 (0,rad) 走到 (rad,0)
    corner(path, rad, rad, rad, e, steps, -1f, -1f, true)
    path.close()
}

/**
 * 画一个角：以 (cx,cy) 为圆心，sx/sy 决定象限方向。
 * 采样点满足 |dx/r|^n + |dy/r|^n = 1，也就是超椭圆方程。
 */
private fun corner(
    path: Path, cx: Float, cy: Float, r: Float, e: Float,
    steps: Int, sx: Float, sy: Float, reverse: Boolean
) {
    for (i in 1..steps) {
        val k = if (reverse) steps - i else i
        val t = k.toFloat() / steps * (Math.PI / 2).toFloat()
        val c = Math.pow(Math.cos(t.toDouble()), e.toDouble()).toFloat()
        val s = Math.pow(Math.sin(t.toDouble()), e.toDouble()).toFloat()
        path.lineTo(cx + sx * r * c, cy - sy * r * s)
    }
}

/**
 * 整屏背景：斜向三段渐变 + 两团柔和光晕。
 *
 * 原来只用 [android.graphics.drawable.GradientDrawable] 铺三段纯色，屏幕大面积
 * 色块过渡很"平"，卡片浮在上面像贴纸。加两团低透明度径向光（左上冷白、右下淡蓝）
 * 之后背景有了光源方向，玻璃卡片的边缘也更明显 —— 车机屏亮度高，这点层次很关键。
 */
class BackdropBg(
    private val top: Int,
    private val mid: Int,
    private val bot: Int,
    private val glowWarm: Int,
    private val glowCool: Int
) : Drawable() {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    /** 三个渐变按屏幕尺寸缓存（v6.0.43）：尺寸不变时不重建 Shader。 */
    private var shaderW = -1f
    private var shaderH = -1f
    private var baseShader: LinearGradient? = null
    private var warmShader: RadialGradient? = null
    private var coolShader: RadialGradient? = null

    override fun draw(canvas: Canvas) {
        val b = bounds
        if (b.isEmpty) return
        val w = b.width().toFloat()
        val h = b.height().toFloat()

        if (w != shaderW || h != shaderH) {
            baseShader = LinearGradient(
                0f, 0f, w * 0.55f, h,
                intArrayOf(top, mid, bot), floatArrayOf(0f, 0.58f, 1f),
                Shader.TileMode.CLAMP
            )
            warmShader = RadialGradient(
                w * 0.30f, h * 0.04f, h * 0.85f,
                intArrayOf(glowWarm, glowWarm and 0x00FFFFFF),
                floatArrayOf(0f, 1f), Shader.TileMode.CLAMP
            )
            coolShader = RadialGradient(
                w * 0.86f, h * 0.96f, h * 0.90f,
                intArrayOf(glowCool, glowCool and 0x00FFFFFF),
                floatArrayOf(0f, 1f), Shader.TileMode.CLAMP
            )
            shaderW = w
            shaderH = h
        }

        paint.shader = baseShader
        canvas.drawRect(b, paint)

        if (Color.alpha(glowWarm) > 0) {
            paint.shader = warmShader
            canvas.drawRect(b, paint)
        }
        if (Color.alpha(glowCool) > 0) {
            paint.shader = coolShader
            canvas.drawRect(b, paint)
        }
        paint.shader = null
    }

    override fun setAlpha(alpha: Int) {}

    override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) {}

    @Suppress("DEPRECATION")
    override fun getOpacity(): Int = android.graphics.PixelFormat.OPAQUE
}

/**
 * 玻璃卡片背景：外投影 + 可选填充 + 描边，形状为超椭圆。
 *
 * 原版 `cardFillPct=0` 时走 `GlassBgDrawable.shadowOnly`：卡片本身是透明的，
 * 只有一层柔和外阴影，靠大圆角把"卡片"的边界暗示出来。
 *
 * ## v6.0.26：静态阴影，回归硬件加速层
 * 原先外阴影靠 `Paint.setShadowLayer` 做 CPU 模糊，而它在硬件加速画布上不生效，
 * 逼得六格卡片和停靠栏都得 `setLayerType(LAYER_TYPE_SOFTWARE, null)` ——
 * 整棵 View 树每帧离屏重绘，是 1920×720 车机上最重的一笔绘制开销。
 * 现在改成 **4 层静态超椭圆填充**由外向内叠加（外淡内浓）近似模糊衰减，
 * 再用 CLEAR 挖掉卡片内部只留外圈。全是普通 Path 绘制，硬件层直接可画，
 * 视觉保持 squircle 大圆角不变，使用本 Drawable 的 View 不再需要软件层。
 */
private const val SHADOW_STEPS = 4

class GlassBg(
    private val radiusPx: Float,
    private val blurPx: Float,
    private val spreadPx: Float,
    private val shadowColor: Int,
    private val fillTop: Int,
    private val fillBottom: Int,
    private val strokePx: Float,
    private val strokeColor: Int,
    private val exponent: Float
) : Drawable() {

    private val path = Path()
    private val rect = RectF()
    /** 4 层阴影路径（v6.0.43 缓存，见 [draw]）。 */
    private val shadowPaths = Array(SHADOW_STEPS) { Path() }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val clear = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
    }

    /** 已缓存的卡片尺寸（不含 spread）：尺寸不变就复用 [path] / [shadowPaths]。 */
    private var cacheW = -1f
    private var cacheH = -1f
    /** 已缓存的填充渐变：高度不变就不重建 Shader。 */
    private var fillShader: LinearGradient? = null
    private var fillShaderH = -1f

    override fun draw(canvas: Canvas) {
        val b = bounds
        if (b.isEmpty) return
        val inset = spreadPx
        rect.set(
            b.left + inset, b.top + inset,
            b.right - inset, b.bottom - inset
        )
        val w = rect.width()
        val h = rect.height()
        if (w <= 0f || h <= 0f) return

        // ★ v6.0.43：路径只在尺寸变化时重算。
        //   squirclePath 每个圆角 10 段采样、每段两次 Math.pow()，一条卡片的
        //   「主体 + 4 层阴影」要算 5 条路径；而卡片尺寸在两次布局之间根本不变，
        //   原先每帧都重算是纯浪费（1920×720 上每帧几十次 pow）。
        if (w != cacheW || h != cacheH) {
            squirclePath(w, h, radiusPx, exponent, path)
            if (blurPx > 0f && Color.alpha(shadowColor) > 0) {
                for (i in SHADOW_STEPS downTo 1) {
                    val grow = blurPx * i / SHADOW_STEPS
                    squirclePath(
                        w + grow * 2f, h + grow * 2f,
                        radiusPx + grow, exponent, shadowPaths[i - 1]
                    )
                }
            }
            cacheW = w
            cacheH = h
        }

        val save = canvas.save()
        canvas.translate(rect.left, rect.top)

        // 1) 外阴影（静态）：由外向内叠 SHADOW_STEPS 层逐渐内收的超椭圆。
        //    最外圈只被 1 层覆盖（最淡），越靠内叠得越多（越浓），近似模糊衰减；
        //    全部是普通 Path 填充，不再用 Paint.setShadowLayer 做 CPU 模糊。
        //    saveLayer 只是为了让 CLEAR 挖空内部时不擦到父容器，硬件加速下可用。
        if (blurPx > 0f && Color.alpha(shadowColor) > 0) {
            val pad = blurPx + inset
            val layer = canvas.saveLayer(
                -pad, -pad, w + pad, h + pad, null
            )
            val rgb = shadowColor and 0x00FFFFFF
            val stepAlpha = (Color.alpha(shadowColor) / SHADOW_STEPS).coerceIn(1, 255)
            paint.reset()
            paint.isAntiAlias = true
            paint.style = Paint.Style.FILL
            for (i in SHADOW_STEPS downTo 1) {
                val grow = blurPx * i / SHADOW_STEPS
                val s = canvas.save()
                canvas.translate(-grow, -grow)
                paint.color = rgb + (stepAlpha shl 24)
                canvas.drawPath(shadowPaths[i - 1], paint)
                canvas.restoreToCount(s)
            }
            // 挖掉卡片内部，只留外圈渐变
            canvas.drawPath(path, clear)
            canvas.restoreToCount(layer)
        }

        // 2) 填充
        if (Color.alpha(fillTop) > 0 || Color.alpha(fillBottom) > 0) {
            val shader = fillShader?.takeIf { fillShaderH == h } ?: LinearGradient(
                0f, 0f, 0f, h,
                fillTop, fillBottom, Shader.TileMode.CLAMP
            ).also {
                fillShader = it
                fillShaderH = h
            }
            paint.reset()
            paint.isAntiAlias = true
            paint.shader = shader
            canvas.drawPath(path, paint)
            paint.shader = null
        }

        // 3) 描边
        if (strokePx > 0f && Color.alpha(strokeColor) > 0) {
            paint.reset()
            paint.isAntiAlias = true
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = strokePx
            paint.color = strokeColor
            canvas.drawPath(path, paint)
        }

        canvas.restoreToCount(save)
    }

    override fun setAlpha(alpha: Int) {}

    override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) {}

    @Suppress("DEPRECATION")
    override fun getOpacity(): Int = android.graphics.PixelFormat.TRANSLUCENT
}
