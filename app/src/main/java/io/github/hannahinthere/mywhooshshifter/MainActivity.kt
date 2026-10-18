package io.github.hannahinthere.mywhooshshifter

import android.animation.ValueAnimator
import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 换档：两颗占满半屏的大按钮，上加下减；音量键同样能换。打开即广播，MyWhoosh 那边点一下连接就能用。
 * 视觉：暖调深色底，按下只闪一下暖色 + 轻震——不位移不缩放（防晕）。
 * 连接状态是常驻的，所以放顶边一行小字，不挡在两颗按钮中间；按钮之间只留一道窄缝，和两边留白一样宽。
 */
class MainActivity : Activity() {

    private lateinit var server: ObcServer
    private lateinit var up: ShiftButton
    private lateinit var down: ShiftButton
    private lateinit var status: TextView
    private lateinit var dot: GradientDrawable

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)   // 骑着车不许黑屏

        val d = resources.displayMetrics.density
        fun dp(v: Float) = (v * d).toInt()

        up = ShiftButton(this, true, getString(R.string.shift_up))
        down = ShiftButton(this, false, getString(R.string.shift_down))
        up.onPress = { shift(ObcServer.UP) }
        down.onPress = { shift(ObcServer.DOWN) }

        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val lp = { LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f) }
            addView(up, lp().apply { bottomMargin = dp(7f) })
            addView(down, lp().apply { topMargin = dp(7f) })
        }

        dot = GradientDrawable().apply { shape = GradientDrawable.OVAL; setSize(dp(7f), dp(7f)); setColor(DOT_IDLE) }
        status = TextView(this).apply {
            textSize = 12f
            letterSpacing = 0.04f
            setTextColor(TEXT_IDLE)
            setCompoundDrawablesRelativeWithIntrinsicBounds(dot, null, null, null)
            compoundDrawablePadding = dp(8f)
            text = getString(R.string.advertising)
        }
        val hint = TextView(this).apply {
            textSize = 11f
            letterSpacing = 0.12f
            setTextColor(Color.parseColor("#57524B"))
            text = getString(R.string.volume_hint)
        }

        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.parseColor("#141311"))
            addView(column, FrameLayout.LayoutParams(-1, -1))
            addView(status, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL))
            addView(hint, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL))
            setOnApplyWindowInsetsListener { v, ins ->
                val bars = if (Build.VERSION.SDK_INT >= 30) ins.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout()) else null
                val top = bars?.top ?: 0
                val bottom = bars?.bottom ?: 0
                column.setPadding(dp(14f), top + dp(40f), dp(14f), bottom + dp(34f))
                // 状态行居中，竖向落在顶边留白的正中
                (status.layoutParams as FrameLayout.LayoutParams).topMargin = top + dp(12f)
                status.requestLayout()
                (hint.layoutParams as FrameLayout.LayoutParams).bottomMargin = bottom + dp(10f)
                hint.requestLayout()
                ins
            }
        }
        setContentView(root)
        hideBars()

        server = ObcServer(this, ::render, ::haptic)
        server.start()
    }

    private fun hideBars() {
        // 底色铺进摄像头挖孔那一条，不然藏掉状态栏后顶上会留一道黑带；内容仍按挖孔的 inset 让开
        if (Build.VERSION.SDK_INT >= 28) window.attributes = window.attributes.apply {
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        if (Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false)
            window.insetsController?.let {
                it.hide(WindowInsets.Type.statusBars())
                it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        }
    }

    private fun render(st: ObcServer.State) {
        when {
            st.appName != null || st.clients > 0 -> {
                status.text = st.appName ?: getString(R.string.connected); dot.setColor(DOT_ON); status.setTextColor(TEXT_ON)
            }
            st.listening -> { status.text = getString(R.string.waiting); dot.setColor(DOT_IDLE); status.setTextColor(TEXT_IDLE) }
            else -> { status.text = getString(R.string.advertising); dot.setColor(DOT_IDLE); status.setTextColor(TEXT_IDLE) }
        }
    }

    private fun shift(button: Int) {
        server.press(button)
        tick(18)
    }

    /** MyWhoosh 发来的振动（0x03）：1 单下、2 双下、3 三下，别的给一下长的 */
    private fun haptic(pattern: Int) {
        val v = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        val wave = when (pattern) {
            1 -> longArrayOf(0, 30)
            2 -> longArrayOf(0, 30, 80, 30)
            3 -> longArrayOf(0, 30, 80, 30, 80, 30)
            else -> longArrayOf(0, 160)
        }
        v.vibrate(VibrationEffect.createWaveform(wave, -1))
    }

    private fun tick(ms: Long) {
        val v = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        if (Build.VERSION.SDK_INT >= 29) v.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK))
        else v.vibrate(VibrationEffect.createOneShot(ms, 120))
    }

    /** 音量键 = 换档（只在这个页面开着时；不改音量）。蓝牙自拍快门按下去发的也是音量加，以后买一个绑车把上就是实体换档器。 */
    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        val btn = when (e.keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP -> up
            KeyEvent.KEYCODE_VOLUME_DOWN -> down
            else -> return super.dispatchKeyEvent(e)
        }
        if (e.action == KeyEvent.ACTION_DOWN && e.repeatCount == 0) btn.fire()
        return true
    }

    override fun onDestroy() {
        server.stop()
        super.onDestroy()
    }

    companion object {
        val DOT_ON = Color.parseColor("#6BC48E")
        val DOT_IDLE = Color.parseColor("#5E5952")
        val TEXT_IDLE = Color.parseColor("#BDB5A9")   // 没连上要看得清，好排查
        val TEXT_ON = Color.parseColor("#7D766C")     // 连上了就退到背景里，和按钮小字同色
    }
}

/** 一颗半屏大按钮：圆角面 + 发丝边 + 细线箭头 + 小字。按下只闪色。 */
class ShiftButton(ctx: Context, private val isUp: Boolean, private val label: String) : View(ctx) {

    var onPress: (() -> Unit)? = null
    private val d = ctx.resources.displayMetrics.density
    private var glow = 0f
    private var anim: ValueAnimator? = null

    private val face = Paint(Paint.ANTI_ALIAS_FLAG)
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1f * d }
    private val glyph = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 4.2f * d; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 14f * d; textAlign = Paint.Align.CENTER; letterSpacing = 0.32f
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
    }
    private val rect = RectF()
    private val path = Path()

    init { isHapticFeedbackEnabled = false }

    fun fire() {
        onPress?.invoke()
        anim?.cancel()
        glow = 1f
        anim = ValueAnimator.ofFloat(1f, 0f).apply {
            duration = 420; interpolator = DecelerateInterpolator(1.6f)
            addUpdateListener { glow = it.animatedValue as Float; invalidate() }
            start()
        }
        invalidate()
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.actionMasked == MotionEvent.ACTION_DOWN) fire()   // 按下就换，不等抬手：骑车时手指会滑
        return true
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat(); val r = 30f * d
        rect.set(0f, 0f, w, h)
        val top = mix(0x22201D, 0x3A3125, glow); val bot = mix(0x1A1916, 0x2C251B, glow)
        face.shader = LinearGradient(0f, 0f, 0f, h, top, bot, Shader.TileMode.CLAMP)
        c.drawRoundRect(rect, r, r, face)
        edge.color = mix(0x2C2925, 0x5A4A32, glow)
        rect.inset(edge.strokeWidth / 2, edge.strokeWidth / 2)
        c.drawRoundRect(rect, r, r, edge)

        // 箭头：宽 64dp、高 28dp 的人字，和图标同一个画法；加档暖白，减档压暗一档，按下都转琥珀
        val cx = w / 2; val cy = h / 2 - 14f * d; val hw = 32f * d; val hh = 14f * d
        path.reset()
        if (isUp) { path.moveTo(cx - hw, cy + hh); path.lineTo(cx, cy - hh); path.lineTo(cx + hw, cy + hh) }
        else { path.moveTo(cx - hw, cy - hh); path.lineTo(cx, cy + hh); path.lineTo(cx + hw, cy - hh) }
        glyph.color = mix(if (isUp) 0xEFE2C8 else 0xA8A096, 0xF6CF86, glow)
        c.drawPath(path, glyph)

        text.color = mix(0x7D766C, 0xD9B475, glow)
        c.drawText(label, cx, cy + hh + 44f * d, text)
    }

    private fun mix(a: Int, b: Int, t: Float): Int {
        fun ch(s: Int) = (((a shr s) and 0xFF) * (1 - t) + ((b shr s) and 0xFF) * t).toInt()
        return Color.rgb(ch(16), ch(8), ch(0))
    }
}
