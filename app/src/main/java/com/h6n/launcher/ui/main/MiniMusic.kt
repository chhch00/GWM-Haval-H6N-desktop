package com.h6n.launcher.ui.main

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.h6n.launcher.core.LocalPlayer
import com.h6n.launcher.core.MediaWatcher
import com.h6n.launcher.core.NowPlaying
import com.h6n.launcher.ui.common.Theme
import com.h6n.launcher.ui.common.dp
import com.h6n.launcher.ui.common.dpf

/**
 * 机油卡中间那条「正在播放」。
 *
 * 位置由 `OilCard` 的 row 决定：机油块 → **本 View** → 剩余储存块。
 * 6.0.74 起**三态都常驻**：歌曲态 / 空态 / 未授权提示态，不再 GONE、也不再把宽度
 * 还给储存块 —— 用户明确要求「没放歌时音乐卡片不要隐藏」。空态用音符块 + 两行淡色
 * 文字占住原位，点一下就开播。
 *
 * 设计约束（沿用本工程手写 UI 的三条）：
 * - 封面用 `BitmapShader` + `drawRoundRect` 画圆角，**不用 clipPath**（硬件层上会出锯齿），
 *   更不能用 `setShadowLayer`（红线）。
 * - 歌名/副行一律 `Paint` 实测后缩字号，**绝不 ellipsize**（歌名截一半比小一号更难看）。
 *   6.0.77 起副行只显示来源（U 盘音乐 / 播放器名），**不显示歌手**。
 * - 尺寸只由父容器决定，自身不写死宽高。
 *
 * 6.0.76：三个控制按钮从「封面底部 32dp」挪到**文字列下方、横跨整列**。
 * 原来挤在 90dp 宽的封面上，每个按钮只有 26dp、间距 4dp，图标小得难点；
 * 挪出来后每个按钮约 56×34dp、间距 16dp，封面也不再被遮掉下三分之一。
 * 代价是文字列少了 38dp 高度，所以 [fitText] 除宽度外还要按可用高度缩字号。
 *
 * 6.0.77：用户要求「音乐卡片可以占用更高的高度、歌名排不下时多行继续排、按钮加大」。
 * 机油卡的标题行「机油寿命」和引擎行「发动机累计」都只占左半边，右侧是空白 ——
 * 于是音乐条比正文行多借 [boostHDp]（上下各溢出一半），封面随之加大到 [coverMaxDp]，
 * 歌名最多 [songMaxLines] 行且不再狠缩字号，控制条也加高加宽。
 * 各级父容器（OilCard 的 row / body / P2Card / col1 / SixGrid）都放行了 clipChildren。
 */
@SuppressLint("ViewConstructor")
class MiniMusic(ctx: Context) : LinearLayout(ctx) {

    private val cover = MusicCover(ctx)
    private val coverBox = FrameLayout(ctx)
    private val ctlRow = LinearLayout(ctx)
    private val btnPrev = MusicBtn(ctx, MusicBtn.KIND_PREV)
    private val btnPlay = MusicBtn(ctx, MusicBtn.KIND_PLAY)
    private val btnNext = MusicBtn(ctx, MusicBtn.KIND_NEXT)
    private val song = TextView(ctx)
    private val sub = TextView(ctx)
    private val textCol = LinearLayout(ctx)

    private var theme: Theme? = null
    private var now: NowPlaying? = null

    /**
     * 当前形态：0 = 空态（没放歌）／1 = 歌曲态／2 = 未授权提示态。
     *
     * v6.0.74：三种形态全部 VISIBLE。之前只有 1、2 可见，0 是整条 GONE ——
     * 用户看到的就是机油卡中间莫名其妙空一块，现在改成常驻空态。
     */
    private var mode = 0

    /** 提示态文字，仅 [mode] == 2 时非空。 */
    private var hint: String? = null

    /** 提示态被点击（去设置页开授权）。由 OilCard 注入。 */
    var onHintClick: (() -> Unit)? = null

    /**
     * 封面边长上限（dp）。6.0.77 只小幅加到 100dp —— 借来的高度主要给「多行歌名 +
     * 更大的按钮」，封面再大就会挤窄文字列、反而把按钮压小；仍受宽度一半约束。
     */
    private val coverMaxDp = 100
    /**
     * 音乐条比正文行多借的高度（dp），上下各溢出一半到卡片右侧空白。
     * 标题行 34dp + 上内边距 10dp、引擎行约 25dp + 下内边距 10dp，各溢出 25dp 仍在卡内。
     */
    private val boostHDp = 50
    /** 控制条实际占高（dp）：按钮 42 + 上间距 4。 */
    private val ctlHDp = 46

    /** 歌名/副行字号（sp）。v6.0.72 起整体放大 1.5 倍（原 17 / 12）。 */
    private val songSpBase = 25.5f
    private val subSpBase = 18f
    /** 6.0.77：宁可多排几行，也不把长歌名缩到看不清 —— 下限 14 → 17sp。 */
    private val songSpMin = 17f
    private val subSpMin = 14f
    /** 歌名最多排几行（排不下时继续往下排，而不是一路缩字号）。 */
    private val songMaxLines = 4

    private var lastFitW = -1

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        clipChildren = false
        clipToPadding = false

        // 6.0.76：封面只管画封面，不再被控制按钮压住下面一截。
        coverBox.addView(
            cover,
            FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        )
        addView(coverBox)

        // 控制按钮放文字列最下面、横跨整列。6.0.77 再加大：按钮高 34 → 42dp，
        // 左右各 10dp（相邻间隔 20dp），图标随色块一起放大。
        ctlRow.orientation = HORIZONTAL
        ctlRow.gravity = Gravity.CENTER_VERTICAL
        for (b in listOf(btnPrev, btnPlay, btnNext)) {
            ctlRow.addView(b, LinearLayout.LayoutParams(0, LayoutParams.MATCH_PARENT, 1f).apply {
                leftMargin = ctx.dp(10)
                rightMargin = ctx.dp(10)
            })
        }

        btnPrev.setOnClickListener { control(KeyEvent.KEYCODE_MEDIA_PREVIOUS) }
        btnNext.setOnClickListener { control(KeyEvent.KEYCODE_MEDIA_NEXT) }
        btnPlay.setOnClickListener { control(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) }

        textCol.orientation = VERTICAL
        textCol.gravity = Gravity.CENTER_VERTICAL
        song.includeFontPadding = false
        // 6.0.77：歌名排不下时继续往下排（最多 4 行），而不是把字号一路压到十几 sp。
        // 音乐条已经借到更多高度，长歌名能在 3 行左右保持约 20sp 的可读字号。
        // 仍然绝不 ellipsize —— 车机上「歌名显示一截 + …」比多占一行难看得多。
        song.isSingleLine = false
        song.ellipsize = null
        song.maxLines = songMaxLines
        // 默认的 BREAK_STRATEGY_HIGH_QUALITY 会为了"断得漂亮"提前折行，
        // 加上英文单词（Remix）不能从中间断，两行经常装不下十几个字。
        // 贪心断行把每行填满才对得上我们实测出来的字号。
        song.breakStrategy = android.text.Layout.BREAK_STRATEGY_SIMPLE
        song.hyphenationFrequency = android.text.Layout.HYPHENATION_FREQUENCY_NONE
        song.setTextSize(TypedValue.COMPLEX_UNIT_SP, songSpBase)
        sub.includeFontPadding = false
        sub.isSingleLine = true
        sub.ellipsize = null
        sub.maxLines = 1
        sub.setTextSize(TypedValue.COMPLEX_UNIT_SP, subSpBase)
        textCol.addView(song, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        textCol.addView(
            sub,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                topMargin = ctx.dp(3)
            }
        )
        // 6.0.76：控制条是文字列的第三个子项。放在最后而不是叠在封面上，
        // 三个按钮才能横向铺开；GONE（外部播放器 / 未授权）时自动不占高度。
        textCol.addView(
            ctlRow,
            LayoutParams(LayoutParams.MATCH_PARENT, ctx.dp(42)).apply {
                topMargin = ctx.dp(4)
            }
        )
        addView(textCol, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply {
            leftMargin = ctx.dp(10)
        })

        isClickable = true
        isFocusable = false
        setOnClickListener {
            val np = now
            when {
                mode == 2 -> onHintClick?.invoke()
                // v6.0.73：U 盘那首是本应用自己放的，点它不该"打开桌面"（就是自己），
                // 改成播放/暂停 —— 桌面上唯一一个真正可控的音乐动作。
                mode == 1 && np?.pkg == context.packageName -> LocalPlayer.toggle()
                mode == 1 && np != null -> MediaWatcher.openApp(context, np.pkg)
                // v6.0.74 空态：点一下就播。有上次记录接着那首，没有就随机来一首。
                else -> {
                    if (LocalPlayer.lastTitle().isNullOrEmpty()) LocalPlayer.shuffle()
                    else LocalPlayer.resumeLast()
                }
            }
        }
        refresh(null)
    }

    fun applyTheme(t: Theme) {
        theme = t
        song.setTypeface(t.bold)
        song.setTextColor(
            if (mode == 1) t.color("p2TextMain", Theme.INK)
            else t.color("p2TextLabel", Theme.LABEL)
        )
        sub.setTypeface(t.regular)
        sub.setTextColor(t.color("p2TextLabel", Theme.LABEL))
        tintTile(t)
        lastFitW = -1
        fitText()
    }

    /** U 盘音乐没有专辑图，封面位置换成音符块，颜色跟随主题（底色只给它 14% 的淡色）。 */
    private fun tintTile(t: Theme) {
        val fg = t.color("p2TextLabel", Theme.LABEL)
        cover.setTileColors((fg and 0x00FFFFFF) or (0x24 shl 24), fg)
    }

    /** 数据到达。null = 没在放歌 —— 改画常驻空态，不再整条隐藏（6.0.74）。 */
    fun refresh(np: NowPlaying?) {
        if (np == null || np.title.isEmpty()) {
            showIdle()
            return
        }
        val old = now
        now = np
        // 从空态 / 提示态回到歌曲态：主文字换回深色，封面与副行本来就在（不再需要放回来）
        if (mode != 1) {
            mode = 1
            hint = null
            cover.visibility = VISIBLE
            sub.visibility = VISIBLE
            theme?.let { song.setTextColor(it.color("p2TextMain", Theme.INK)) }
        }
        // 同一首歌不重复 setText（setText 不做内容比较，同值也会触发重排）
        if (old == null || old.pkg != np.pkg || old.title != np.title) {
            song.text = np.title
        }
        // 6.0.77：用户要求卡片**不显示歌手**。副行只留来源（U 盘音乐 / 外部播放器名）。
        // 歌手仍在 MediaSession 元数据、断点记录里保留 —— 车机仪表/蓝牙要读，只是不上卡片。
        // 去重条件也随之只看 appLabel：同一首歌切歌手不该触发一次无谓 setText 重排。
        val line2 = np.appLabel
        if (old == null || old.appLabel != np.appLabel) sub.text = line2
        // 6.0.75：U 盘曲目现在也可能带封面（内嵌 / 同目录同名 jpg）；都没有才画音符块
        cover.set(np.art, if (np.pkg == context.packageName) "" else np.pkg, context)
        applyControls(np)
        visibility = VISIBLE
        lastFitW = -1
        requestLayout()
        post { fitText() }
    }

    /**
     * 常驻空态（6.0.74）：音符块 + 两行淡色文字占住原位，卡片不隐藏。
     *
     * 有上次播放记录时显示歌名与「已暂停 · 点此继续」，否则显示「未在播放」和
     * 「点此随机播放 U 盘音乐」。点击由 init 里的监听器接管，直接开播。
     */
    private fun showIdle() {
        val last = LocalPlayer.lastTitle()
        val t1 = last?.takeIf { it.isNotEmpty() } ?: "未在播放"
        val t2 = if (last.isNullOrEmpty()) "点此随机播放 U 盘音乐" else "已暂停 · 点此继续"
        if (mode == 0 && song.text?.toString() == t1 &&
            sub.text?.toString() == t2 && visibility == VISIBLE
        ) return
        mode = 0
        hint = null
        now = null
        song.text = t1
        sub.text = t2
        cover.visibility = VISIBLE
        sub.visibility = VISIBLE
        cover.set(null, "", context)
        theme?.let { song.setTextColor(it.color("p2TextLabel", Theme.LABEL)) }
        applyControls(null)
        visibility = VISIBLE
        lastFitW = -1
        requestLayout()
        post { fitText() }
    }

    /**
     * 6.0.79：三个按钮在**歌曲态与空态都常驻**，外部播放器（汽水音乐 / 酷狗 / QQ 音乐…）
     * 也照样显示。以前只借通知使用权读元数据、`getTransportControls()` 抛 SecurityException，
     * 所以「只显示不控制」；现在 [MediaWatcher.sendMediaKey] 走媒体按键通道，按钮是真的能按。
     * 只有未授权提示态（连会话都读不到）不摆按钮。
     */
    private fun applyControls(np: NowPlaying?) {
        val want = if (mode != 2) VISIBLE else GONE
        if (ctlRow.visibility != want) ctlRow.visibility = want
        btnPlay.playing = np?.playing == true
    }

    /**
     * 按钮统一入口：按当前卡片是谁在放歌分流。
     *
     * - U 盘音乐 / 空态：本应用自己放的，直接调 [LocalPlayer]（路径最短，还带 150ms 去重，
     *   避免方向盘媒体键 down/up 双发导致连跳）；
     * - 外部播放器：走 [MediaWatcher.sendMediaKey] 的媒体按键。
     */
    private fun control(keyCode: Int) {
        val np = now
        val external = np != null && np.pkg.isNotEmpty() && np.pkg != context.packageName
        if (external) {
            MediaWatcher.sendMediaKey(context, keyCode)
            return
        }
        when (keyCode) {
            KeyEvent.KEYCODE_MEDIA_PREVIOUS -> LocalPlayer.userPrev()
            KeyEvent.KEYCODE_MEDIA_NEXT -> LocalPlayer.userNext()
            else -> {
                // 空态（从没播过）点播放要能直接开播，toggle() 在没有任何曲目时不会做事
                if (now == null && LocalPlayer.lastTitle().isNullOrEmpty()) LocalPlayer.shuffle()
                else LocalPlayer.toggle()
            }
        }
    }

    /**
     * 未授权提示态：可点，点了去开通知使用权。
     *
     * 与 [refresh] / [showIdle] 互斥，谁后调用谁生效。同样常驻、同样用音符块，
     * 只是文字更淡，不抢机油寿命的注意力。
     */
    fun showHint(text: String) {
        if (mode == 2 && hint == text && visibility == VISIBLE) return
        mode = 2
        hint = text
        now = null
        song.text = text
        sub.text = "开启后显示正在播放的歌"
        cover.visibility = VISIBLE
        sub.visibility = VISIBLE
        cover.set(null, "", context)
        theme?.let { song.setTextColor(it.color("p2TextLabel", Theme.LABEL)) }
        applyControls(null)
        visibility = VISIBLE
        lastFitW = -1
        requestLayout()
        post { fitText() }
    }

    override fun onMeasure(w: Int, h: Int) {
        val hAvail = MeasureSpec.getSize(h)
        // 6.0.77：父给的正文行只有 94dp 左右，装不下「大封面 + 多行歌名 + 大按钮」。
        // 卡片标题行与引擎行的右侧都是空白，所以把自己的测量高度加 [boostHDp]，
        // 上层 LinearLayout 按 CENTER_VERTICAL 摆放，上下各溢出一半；各级父容器已放行
        // clipChildren。只在父给 EXACTLY 时借高度，AT_MOST / UNSPECIFIED 保持原样。
        val boost = if (MeasureSpec.getMode(h) == MeasureSpec.EXACTLY && hAvail > 0) {
            context.dp(boostHDp)
        } else 0
        val boosted = hAvail + boost
        val hSpec = if (boost > 0) {
            MeasureSpec.makeMeasureSpec(boosted, MeasureSpec.EXACTLY)
        } else h

        // 封面取「可用高度」与「上限」的较小值；宽度不足一半时再压一次，保证文字还有地方
        val wAvail = MeasureSpec.getSize(w)
        var side = boosted.coerceAtMost(context.dp(coverMaxDp))
        if (wAvail > 0) side = side.coerceAtMost(wAvail / 2)
        side = side.coerceAtLeast(context.dp(20))
        if ((coverBox.layoutParams?.width ?: -1) != side) {
            coverBox.layoutParams = LayoutParams(side, side)
        }
        // 圆角随尺寸走（22% 边长），与卡片圆角风格一致
        cover.setRadius(context.dpf(side * 0.22f))
        super.onMeasure(w, hSpec)
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        // 尺寸变了要重新分配字号。子 View 此刻还没 layout，只做文本宽度实测（Paint 可用），
        // 不在这里估高度 —— 见 v6.0.50 的教训。
        if (w != lastFitW) {
            lastFitW = w
            post { fitText() }
        }
    }

    /**
     * 按实测宽度缩字号。
     *
     * 6.0.77 起顺序反过来：**先允许多排几行**（最多 4 行），只有宽度与高度都装不下才缩字号，
     * 下限也从 14 / 13sp 提到 17 / 14sp —— 长歌名宁可占三行，也不要小到看不清。
     * **绝不用 ellipsis**：车机上"歌曲名显示一截 + …"比多占一行难看得多。
     *
     * 量的时候用两个临时 Paint，**不碰 TextView 本身** —— 循环里反复 `setTextSize`
     * 每次都会 requestLayout，光缩字号就能把整行重排几十遍。
     */
    private fun fitText() {
        if (visibility != VISIBLE) return
        val tt = theme ?: return
        val avail = width - coverBox.width - context.dp(10)
        if (avail <= 0) return

        val sd = context.resources.displayMetrics.scaledDensity
        // p1 用 TextPaint：StaticLayout 只吃 TextPaint（它是 Paint 的子类，量宽一样用）
        val p1 = android.text.TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = tt.bold }
        val p2 = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = tt.regular }
        val s1 = song.text?.toString().orEmpty()
        val s2 = sub.text?.toString().orEmpty()

        fun widthOf(p: Paint, s: String, sp: Float): Float {
            p.textSize = sp * sd
            return p.measureText(s)
        }

        /**
         * 按当前字号真排一遍，返回歌名占几行（同时供宽度与高度约束使用）。
         *
         * 不能拿「总宽 ≤ 2×单行宽」估 —— 中英混排 + 英文单词不可断，
         * 实际折行浪费的空间可能到 20%，估出来的行数会让第三行被 maxLines 裁掉。
         * 这里用和 TextView 相同的断行策略真排一遍。
         */
        fun songLines(s: String, sp: Float): Int {
            p1.textSize = sp * sd
            if (p1.measureText(s) <= avail) return 1
            val sl = android.text.StaticLayout.Builder
                .obtain(s, 0, s.length, p1, avail)
                .setBreakStrategy(android.text.Layout.BREAK_STRATEGY_SIMPLE)
                .setHyphenationFrequency(android.text.Layout.HYPHENATION_FREQUENCY_NONE)
                .setIncludePad(false)
                .build()
            return sl.lineCount
        }

        fun songFits(s: String, sp: Float): Boolean {
            // 未授权提示态强制单行：那是一句说明，不该折行占掉按钮的位置
            if (mode == 2) {
                p1.textSize = sp * sd
                return p1.measureText(s) <= avail
            }
            // 6.0.77：排不下就继续往下排（最多 songMaxLines 行），不再逼着缩字号塞进 2 行
            return songLines(s, sp) <= songMaxLines
        }

        // 控制条占掉文字列底部；歌名 + 副行必须留在它上面。
        // 6.0.77 借高后文字区可用约 96dp，但仍按真实高度约束，避免 4 行长歌名把
        // 副行和按钮一起顶出音乐条。高度不够时循环才继续缩字号（下限 17sp）。
        val ctlH = if (ctlRow.visibility == VISIBLE) context.dp(ctlHDp) else 0
        val boxH = if (height > 0) height else coverBox.height
        val availH = if (boxH > 0) (boxH - ctlH - context.dp(2)).toFloat() else Float.MAX_VALUE

        fun blockHeight(sp1: Float, sp2: Float): Float {
            p1.textSize = sp1 * sd
            val lines = songLines(s1, sp1).coerceAtMost(songMaxLines)
            val h1 = (p1.descent() - p1.ascent()) * lines
            p2.textSize = sp2 * sd
            return h1 + (p2.descent() - p2.ascent()) + context.dp(3).toFloat()
        }

        var songSp = if (mode == 1) songSpBase else 22f
        var subSp = subSpBase
        var guard = 0
        while (guard++ < 60) {
            val ok1 = songFits(s1, songSp)
            val w2 = widthOf(p2, s2, subSp)
            val okH = blockHeight(songSp, subSp) <= availH
            if (ok1 && w2 <= avail && okH) break
            var moved = false
            if (!ok1 && songSp > songSpMin) { songSp -= 0.5f; moved = true }
            if (w2 > avail && subSp > subSpMin) { subSp -= 0.5f; moved = true }
            if (!okH) {
                if (songSp > songSpMin) { songSp -= 0.5f; moved = true }
                else if (subSp > subSpMin) { subSp -= 0.5f; moved = true }
            }
            if (!moved) break
        }

        song.setTextSize(TypedValue.COMPLEX_UNIT_SP, songSp)
        sub.setTextSize(TypedValue.COMPLEX_UNIT_SP, subSp)
        // 字号换了 Paint 行高会变，副行间距跟着走
        (sub.layoutParams as? LayoutParams)?.topMargin = context.dp(if (songSp < 20f) 2 else 3)
    }
}

/**
 * 音乐卡片的三个控制按钮（6.0.75 引入，6.0.76 放大并挪到文字列下方）。
 *
 * 纯自绘、无 XML / drawable：半透明圆角底 + 白色几何图形，浅色卡片背景上也看得清。
 * 按钮自身 clickable，事件会被它消费，不会冒泡到整条卡片那个「点一下暂停」的监听器。
 * 6.0.77：点击区约 47×42dp、色块约 39×37dp，图标放大到约 30dp（系数 0.82）。
 */
@SuppressLint("ViewConstructor")
private class MusicBtn(ctx: Context, private val kind: Int) : View(ctx) {

    companion object {
        const val KIND_PREV = 0
        const val KIND_PLAY = 1
        const val KIND_NEXT = 2
    }

    // 6.0.76 起按钮画在浅色卡片上（以前是叠在深色封面图上），底色加深一点保证白图标对比
    private val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x80000000.toInt() }
    private val fg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt()
        style = Paint.Style.FILL
    }
    private val path = Path()
    private val rect = RectF()

    /** true = 正在播放，中间按钮画暂停双竖条；false = 画播放三角。 */
    var playing = false
        set(v) {
            if (field != v) {
                field = v
                invalidate()
            }
        }

    init {
        setWillNotDraw(false)
        isClickable = true
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        // 点击区比可见色块大：6.0.77 约 47×42dp 好点、约 39×37dp 的圆角块之间自然留白，
        // 三块不再像 6.0.75 那样连成一条（那时按钮铺满整个 26dp 宽、只隔 4dp）。
        val bw = (h * 0.94f).coerceAtMost(w)
        val bh = h * 0.88f
        rect.set((w - bw) / 2f, (h - bh) / 2f, (w + bw) / 2f, (h + bh) / 2f)
        c.drawRoundRect(rect, bh * 0.3f, bh * 0.3f, bg)

        val s = bw.coerceAtMost(bh) * 0.82f
        val cx = w / 2f
        val cy = h / 2f
        when (kind) {
            KIND_PLAY -> if (playing) {
                c.drawRect(cx - s * 0.30f, cy - s * 0.48f, cx - s * 0.08f, cy + s * 0.48f, fg)
                c.drawRect(cx + s * 0.08f, cy - s * 0.48f, cx + s * 0.30f, cy + s * 0.48f, fg)
            } else {
                tri(c, cx - s * 0.30f, cy - s * 0.50f, cx - s * 0.30f, cy + s * 0.50f, cx + s * 0.42f, cy)
            }
            KIND_PREV -> {
                tri(c, cx + s * 0.28f, cy - s * 0.50f, cx + s * 0.28f, cy + s * 0.50f, cx - s * 0.34f, cy)
                c.drawRect(cx - s * 0.46f, cy - s * 0.50f, cx - s * 0.34f, cy + s * 0.50f, fg)
            }
            else -> {
                tri(c, cx - s * 0.28f, cy - s * 0.50f, cx - s * 0.28f, cy + s * 0.50f, cx + s * 0.34f, cy)
                c.drawRect(cx + s * 0.34f, cy - s * 0.50f, cx + s * 0.46f, cy + s * 0.50f, fg)
            }
        }
    }

    private fun tri(
        c: Canvas,
        x1: Float, y1: Float, x2: Float, y2: Float, x3: Float, y3: Float
    ) {
        path.reset()
        path.moveTo(x1, y1)
        path.lineTo(x2, y2)
        path.lineTo(x3, y3)
        path.close()
        c.drawPath(path, fg)
    }
}

/**
 * 圆角封面。
 *
 * 用 `BitmapShader` 打底 + `drawRoundRect` 裁剪 —— 硬件加速下完全正确，
 * 比 `clipPath` 省一次离屏合成，也没有边缘锯齿。
 */
@SuppressLint("ViewConstructor")
class MusicCover(ctx: Context) : View(ctx) {

    private val paint = Paint(Paint.FILTER_BITMAP_FLAG).apply { isAntiAlias = true }
    private val rect = RectF()
    private val matrix = Matrix()
    private var shader: BitmapShader? = null
    private var src: Bitmap? = null
    private var radius = 0f
    /** 上一次的输入：同一个 app、同样没有封面时不必再去查一遍图标（PackageManager 调用不便宜）。 */
    private var lastPkg = "\u0000"
    private var lastArtNull = false

    /** U 盘本地音乐没有封面：画一个音符方块，而不是把桌面自己的图标贴上（v6.0.73）。 */
    private var tile = false
    private var tileBg = 0
    private var tileFg = 0
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val note = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = android.graphics.Typeface.DEFAULT_BOLD
    }

    init {
        setWillNotDraw(false)
    }

    /** 音符块的底色/字色，跟随主题（由 MiniMusic 在 applyTheme 时喂进来）。 */
    fun setTileColors(bg: Int, fg: Int) {
        if (bg == tileBg && fg == tileFg) return
        tileBg = bg
        tileFg = fg
        if (tile) invalidate()
    }

    fun setRadius(r: Float) {
        if (r == radius) return
        radius = r
        invalidate()
    }

    /** art 为空时退到 app 图标；`pkg` 为空串表示「没有来源图标」，改画音符块。 */
    fun set(art: Bitmap?, pkg: String, ctx: Context) {
        if (art == null && pkg.isEmpty()) {
            if (tile) return
            tile = true
            src = null
            shader = null
            lastPkg = ""
            lastArtNull = true
            invalidate()
            return
        }
        if (art == null && lastArtNull && pkg == lastPkg) return
        if (tile) tile = false
        lastPkg = pkg
        lastArtNull = art == null
        val bmp = art ?: runCatching {
            MediaWatcher.appIcon(ctx, pkg)?.let { MediaWatcher.toBitmap(it, MediaWatcher.ART_PX) }
        }.getOrNull()
        if (bmp === src) return
        src = bmp
        shader = bmp?.let { BitmapShader(it, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP) }
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        rect.set(0f, 0f, w, h)
        val r = if (radius > 0f) radius else w * 0.22f

        if (tile) {
            fill.color = if (tileBg != 0) tileBg else 0x1F000000.toInt()
            canvas.drawRoundRect(rect, r, r, fill)
            note.color = if (tileFg != 0) tileFg else 0x99000000.toInt()
            note.textSize = h * 0.52f
            val base = h / 2f - (note.descent() + note.ascent()) / 2f
            canvas.drawText("\u266a", w / 2f, base, note)
            return
        }

        val sh = shader ?: return
        val b = src ?: return
        // centerCrop：等比放大到铺满，再居中
        val s = Math.max(w / b.width, h / b.height)
        matrix.reset()
        matrix.postScale(s, s)
        matrix.postTranslate((w - b.width * s) / 2f, (h - b.height * s) / 2f)
        sh.setLocalMatrix(matrix)
        paint.shader = sh
        canvas.drawRoundRect(rect, r, r, paint)
    }
}
