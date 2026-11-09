package com.xpanmode.app

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.view.TextureView
import java.util.Locale
import kotlin.math.min
import kotlin.math.roundToInt

const val ORANGE = 0xFFF47B20.toInt()

abstract class XView(ctx: Context) : View(ctx) {
    var classic: Boolean = true
        set(v) { field = v; invalidate() }
    protected val p = Paint(Paint.ANTI_ALIAS_FLAG)
    protected fun d(v: Float): Float = v * resources.displayMetrics.density
}

/** Nền trang trí: khung viền, đường gờ kim loại, chữ HASSELBLAD, biểu tượng máy ảnh (chỉ giao diện Hasselblad). */
class DecorView(ctx: Context) : XView(ctx) {
    val vf = RectF()
    var safeL = 0f
    private val logoFace = Typeface.create(Typeface.SERIF, Typeface.BOLD_ITALIC)

    override fun onDraw(c: Canvas) {
        if (!classic) return
        val w = width.toFloat()
        val h = height.toFloat()
        val cx = vf.centerX()
        p.style = Paint.Style.FILL
        p.color = 0xFF070708.toInt()
        c.drawRoundRect(vf.left - d(5f), vf.top - d(5f), vf.right + d(5f), vf.bottom + d(5f), d(8f), d(8f), p)

        p.style = Paint.Style.STROKE
        p.strokeWidth = d(1.2f)
        p.color = 0xFF38383C.toInt()
        val y1 = h * 0.085f
        val y2 = h * 0.145f
        val k = w * 0.03f
        val half = w * 0.16f
        val top = Path()
        top.moveTo(w * 0.12f, y1); top.lineTo(cx - half - k, y1); top.lineTo(cx - half, y2)
        top.lineTo(cx + half, y2); top.lineTo(cx + half + k, y1); top.lineTo(w, y1)
        c.drawPath(top, p)
        val b1 = h * 0.915f
        val b2 = h * 0.855f
        val bot = Path()
        bot.moveTo(w * 0.12f, b1); bot.lineTo(cx - half - k, b1); bot.lineTo(cx - half, b2)
        bot.lineTo(cx + half, b2); bot.lineTo(cx + half + k, b1); bot.lineTo(w, b1)
        c.drawPath(bot, p)

        val txt = "HASSELBLAD"
        p.style = Paint.Style.FILL
        p.typeface = logoFace
        p.textSize = h * 0.052f
        p.letterSpacing = 0.28f
        p.textAlign = Paint.Align.CENTER
        val tw = p.measureText(txt)
        p.color = 0xFF2A2A2D.toInt()
        c.drawRoundRect(cx - tw / 2 - d(10f), y1 - h * 0.036f, cx + tw / 2 + d(10f), y1 + h * 0.036f, d(3f), d(3f), p)
        p.color = 0xFFD2D2D4.toInt()
        c.drawText(txt, cx, y1 + h * 0.017f, p)
        p.typeface = Typeface.DEFAULT
        p.letterSpacing = 0f
        p.textAlign = Paint.Align.LEFT

        // biểu tượng máy ảnh góc dưới trái
        val ix = safeL + w * 0.033f
        val iy = h * 0.91f
        val s = h * 0.03f
        p.style = Paint.Style.STROKE
        p.strokeWidth = d(1.6f)
        p.color = Color.WHITE
        c.drawRoundRect(ix - s * 1.4f, iy - s * 0.9f, ix + s * 1.4f, iy + s * 1.1f, d(3f), d(3f), p)
        c.drawCircle(ix, iy + s * 0.1f, s * 0.6f, p)
        c.drawLine(ix - s * 0.6f, iy - s * 0.9f, ix - s * 0.4f, iy - s * 1.25f, p)
        c.drawLine(ix - s * 0.4f, iy - s * 1.25f, ix + s * 0.4f, iy - s * 1.25f, p)
        c.drawLine(ix + s * 0.4f, iy - s * 1.25f, ix + s * 0.6f, iy - s * 0.9f, p)
    }
}

/** Các vạch góc / cạnh của khung ngắm + viền mờ (giao diện tối giản) hoặc tối dần phía dưới (Hasselblad). */
class FrameOverlayView(ctx: Context) : XView(ctx) {
    private val path = Path()

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        p.style = Paint.Style.FILL
        if (classic) {
            p.shader = LinearGradient(0f, h * 0.72f, 0f, h, 0x00000000, 0xB0000000.toInt(), Shader.TileMode.CLAMP)
            c.drawRect(0f, h * 0.72f, w, h, p)
            p.shader = null
        } else {
            val e = 0xFF000000.toInt()
            val z = 0x00000000
            val fx = w * 0.022f
            val fy = h * 0.07f
            p.shader = LinearGradient(0f, 0f, fx, 0f, e, z, Shader.TileMode.CLAMP); c.drawRect(0f, 0f, fx, h, p)
            p.shader = LinearGradient(w, 0f, w - fx, 0f, e, z, Shader.TileMode.CLAMP); c.drawRect(w - fx, 0f, w, h, p)
            p.shader = LinearGradient(0f, 0f, 0f, fy, e, z, Shader.TileMode.CLAMP); c.drawRect(0f, 0f, w, fy, p)
            p.shader = LinearGradient(0f, h, 0f, h - fy, e, z, Shader.TileMode.CLAMP); c.drawRect(0f, h - fy, w, h, p)
            p.shader = null
        }
        p.style = Paint.Style.STROKE
        p.strokeCap = Paint.Cap.ROUND
        p.strokeWidth = d(if (classic) 1.6f else 2.4f)
        p.color = if (classic) 0xE6FFFFFF.toInt() else 0xFFD8D8D8.toInt()
        val ins = if (classic) d(10f) else d(12f)
        val len = w * 0.045f
        val r = if (classic) d(4f) else d(10f)
        path.reset()
        path.moveTo(ins, ins + len); path.lineTo(ins, ins + r)
        path.quadTo(ins, ins, ins + r, ins); path.lineTo(ins + len, ins)
        for (sx in floatArrayOf(1f, -1f)) for (sy in floatArrayOf(1f, -1f)) {
            c.save(); c.scale(sx, sy, w / 2f, h / 2f); c.drawPath(path, p); c.restore()
        }
        val top = if (classic) arrayOf(0.17f to 0.28f, 0.44f to 0.64f) else arrayOf(0.10f to 0.13f, 0.45f to 0.65f)
        val bot = if (classic) arrayOf(0.085f to 0.13f, 0.29f to 0.40f, 0.69f to 0.76f)
        else arrayOf(0.14f to 0.17f, 0.30f to 0.32f, 0.82f to 0.90f)
        for ((a, b) in top) c.drawLine(w * a, ins, w * b, ins, p)
        for ((a, b) in bot) c.drawLine(w * a, h - ins, w * b, h - ins, p)
        val side = arrayOf(0.22f to 0.32f, 0.62f to 0.74f)
        for ((a, b) in side) {
            c.drawLine(ins, h * a, ins, h * b, p)
            c.drawLine(w - ins, h * a, w - ins, h * b, p)
        }
    }
}

class FocusBoxView(ctx: Context) : XView(ctx) {
    private var fx = -1f
    private var fy = -1f
    private var scale = 1f
    private var anim: ValueAnimator? = null

    fun showAt(x: Float, y: Float) {
        fx = x; fy = y
        anim?.cancel()
        anim = ValueAnimator.ofFloat(1.3f, 1f).apply {
            duration = 220
            addUpdateListener { scale = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    override fun onDraw(c: Canvas) {
        if (fx < 0f) { fx = width / 2f; fy = height / 2f }
        val half = min(width, height) * 0.17f * scale
        val len = half * 0.45f
        p.style = Paint.Style.STROKE
        p.strokeWidth = d(1.4f)
        p.color = Color.WHITE
        val l = fx - half; val r = fx + half; val t = fy - half; val b = fy + half
        c.drawLine(l, t, l + len, t, p); c.drawLine(l, t, l, t + len, p)
        c.drawLine(r, t, r - len, t, p); c.drawLine(r, t, r, t + len, p)
        c.drawLine(l, b, l + len, b, p); c.drawLine(l, b, l, b - len, p)
        c.drawLine(r, b, r - len, b, p); c.drawLine(r, b, r, b - len, p)
    }
}

/** Thanh bù sáng bên trái: vuốt dọc để đổi EV. */
class EvView(ctx: Context) : XView(ctx) {
    var min = 0
        set(v) { field = v; invalidate() }
    var max = 0
        set(v) { field = v; invalidate() }
    var step = 0f
    var value = 0
        set(v) { field = v; invalidate() }
    var onChange: ((Int) -> Unit)? = null
    private var downY = 0f
    private var downVal = 0

    override fun onDraw(c: Canvas) {
        if (max <= min) return
        val h = height.toFloat()
        val pad = d(8f)
        val n = (max - min).toFloat()
        val perEv = if (step > 0f) (1f / step).roundToInt().coerceAtLeast(1) else 1
        p.style = Paint.Style.STROKE
        p.strokeWidth = d(1.4f)
        p.color = Color.WHITE
        for (i in min..max) {
            val y = pad + (max - i) / n * (h - 2 * pad)
            val len = if (i % perEv == 0) d(14f) else d(5f)
            c.drawLine(d(4f), y, d(4f) + len, y, p)
        }
        val y = pad + (max - value) / n * (h - 2 * pad)
        p.style = Paint.Style.FILL
        val tri = Path()
        tri.moveTo(d(22f), y); tri.lineTo(d(32f), y - d(6f)); tri.lineTo(d(32f), y + d(6f)); tri.close()
        c.drawPath(tri, p)
        val ev = value * step
        val label = if (value == 0) "0.0" else String.format(Locale.US, "%+.1f", ev)
        p.textSize = d(14f)
        p.textAlign = Paint.Align.LEFT
        c.drawText(label, d(38f), y + d(5f), p)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downY = e.y; downVal = value
                parent?.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_MOVE -> {
                if (max > min) {
                    val per = (height - 2 * d(8f)) / (max - min)
                    val nv = (downVal - ((e.y - downY) / per).roundToInt()).coerceIn(min, max)
                    if (nv != value) { value = nv; onChange?.invoke(nv) }
                }
            }
        }
        return true
    }
}

/** Thang zoom bên phải (giao diện Hasselblad): 0,6 · 1 · 2 · 3× */
class ZoomLadderView(ctx: Context) : XView(ctx) {
    var labels: List<String> = emptyList()
        set(v) { field = v; invalidate(); requestLayout() }
    var selected = 0
        set(v) { field = v; invalidate() }
    var onSelect: ((Int) -> Unit)? = null

    override fun onDraw(c: Canvas) {
        val n = labels.size
        if (n == 0) return
        val cellH = height.toFloat() / n
        val cx = width / 2f
        p.textAlign = Paint.Align.CENTER
        for (k in 0 until n) {
            val idx = n - 1 - k
            val cy = (k + 0.5f) * cellH
            val sel = idx == selected
            if (sel) {
                p.style = Paint.Style.FILL
                p.color = 0x66000000
                c.drawCircle(cx, cy, d(20f), p)
            }
            p.style = Paint.Style.FILL
            p.typeface = if (sel) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            p.textSize = d(if (sel) 16f else 15f)
            p.color = if (sel) ORANGE else Color.WHITE
            val txt = if (sel) labels[idx] + "×" else labels[idx]
            c.drawText(txt, cx, cy + d(5.5f), p)
        }
        p.typeface = Typeface.DEFAULT
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.actionMasked == MotionEvent.ACTION_UP && labels.isNotEmpty()) {
            val n = labels.size
            val k = (e.y / height * n).toInt().coerceIn(0, n - 1)
            onSelect?.invoke(n - 1 - k)
            performClick()
        }
        return true
    }
}

/** Các nút tiêu cự ở giữa dưới (giao diện tối giản): 14 · 23mm · 65 · 135 */
class LensPillsView(ctx: Context) : XView(ctx) {
    var labels: List<String> = emptyList()
        set(v) { field = v; invalidate(); requestLayout() }
    var selected = 0
        set(v) { field = v; invalidate() }
    var onSelect: ((Int) -> Unit)? = null

    fun pillSize() = d(38f)
    fun gap() = d(10f)

    override fun onDraw(c: Canvas) {
        val n = labels.size
        if (n == 0) return
        val s = pillSize()
        val r = s / 2f
        val cy = height / 2f
        p.textAlign = Paint.Align.CENTER
        for (i in 0 until n) {
            val cx = r + i * (s + gap())
            p.style = Paint.Style.FILL
            p.color = 0xCC0B0B0B.toInt()
            c.drawCircle(cx, cy, r, p)
            val sel = i == selected
            p.color = if (sel) ORANGE else Color.WHITE
            p.typeface = if (sel) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            if (sel) {
                p.textSize = d(12f)
                c.drawText(labels[i], cx, cy - d(1f), p)
                p.textSize = d(9f)
                c.drawText("mm", cx, cy + d(10f), p)
            } else {
                p.textSize = d(12f)
                c.drawText(labels[i], cx, cy + d(4.5f), p)
            }
        }
        p.typeface = Typeface.DEFAULT
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.actionMasked == MotionEvent.ACTION_UP && labels.isNotEmpty()) {
            val i = (e.x / (pillSize() + gap())).toInt().coerceIn(0, labels.size - 1)
            onSelect?.invoke(i)
            performClick()
        }
        return true
    }
}

class ShutterButton(ctx: Context) : XView(ctx) {
    init { isClickable = true }

    override fun onDraw(c: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val r = min(width, height) / 2f * 0.96f
        p.style = Paint.Style.FILL
        if (classic) {
            p.shader = LinearGradient(0f, cy - r, 0f, cy + r, 0xFFF4F4F4.toInt(), 0xFF68686B.toInt(), Shader.TileMode.CLAMP)
            c.drawCircle(cx, cy, r, p)
            p.shader = RadialGradient(cx, cy - r * 0.25f, r, 0xFFFFFFFF.toInt(), 0xFFCFCFD2.toInt(), Shader.TileMode.CLAMP)
            c.drawCircle(cx, cy, r * 0.84f, p)
            p.shader = null
        } else {
            p.style = Paint.Style.STROKE
            p.strokeWidth = d(4f)
            p.color = Color.WHITE
            c.drawCircle(cx, cy, r - d(2f), p)
            p.style = Paint.Style.FILL
            p.shader = LinearGradient(0f, cy - r, 0f, cy + r, 0xFFF68A2E.toInt(), 0xFFE96A12.toInt(), Shader.TileMode.CLAMP)
            c.drawCircle(cx, cy, r - d(7f), p)
            p.shader = null
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> animate().scaleX(0.92f).scaleY(0.92f).setDuration(70).start()
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> animate().scaleX(1f).scaleY(1f).setDuration(110).start()
        }
        return super.onTouchEvent(e)
    }
}

/** Nút bộ lọc: huy hiệu "Original" (Hasselblad) hoặc biểu tượng cuộn phim (tối giản). */
class FilterButtonView(ctx: Context) : XView(ctx) {
    var label: String = "Original"
        set(v) { field = v; invalidate() }

    init { isClickable = true }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (classic) {
            p.style = Paint.Style.FILL
            p.color = 0xFF0E0E10.toInt()
            c.drawRoundRect(0f, 0f, w, h, d(6f), d(6f), p)
            p.style = Paint.Style.STROKE
            p.strokeWidth = d(1f)
            p.color = 0xFF3A3A3D.toInt()
            c.drawRoundRect(0f, 0f, w, h, d(6f), d(6f), p)
            p.strokeWidth = d(2f)
            p.shader = LinearGradient(
                w * 0.1f, 0f, w * 0.9f, 0f,
                intArrayOf(0x00F47B20, ORANGE, 0xFFFFC040.toInt(), ORANGE, 0x00F47B20),
                null, Shader.TileMode.CLAMP
            )
            c.drawLine(w * 0.1f, h * 0.2f, w * 0.9f, h * 0.2f, p)
            c.drawLine(w * 0.1f, h * 0.8f, w * 0.9f, h * 0.8f, p)
            p.shader = null
            p.style = Paint.Style.FILL
            p.color = Color.WHITE
            p.typeface = Typeface.DEFAULT_BOLD
            p.textAlign = Paint.Align.CENTER
            p.textSize = h * 0.23f
            val fm = p.fontMetrics
            c.drawText(label, w / 2f, h / 2f - (fm.ascent + fm.descent) / 2f, p)
            p.typeface = Typeface.DEFAULT
        } else {
            p.style = Paint.Style.STROKE
            p.strokeWidth = d(1.8f)
            p.color = Color.WHITE
            c.drawRoundRect(w * 0.1f, h * 0.27f, w * 0.9f, h * 0.73f, d(3f), d(3f), p)
            p.style = Paint.Style.FILL
            val hole = w * 0.06f
            for (i in 0 until 5) {
                val x = w * (0.2f + i * 0.15f)
                c.drawRect(x, h * 0.31f, x + hole, h * 0.31f + hole, p)
                c.drawRect(x, h * 0.69f - hole, x + hole, h * 0.69f, p)
            }
        }
    }
}

/** Nút chuyển giao diện (⇄) và nút cài đặt (bánh răng). */
class IconButtonView(ctx: Context, private val kind: Int) : XView(ctx) {
    companion object {
        const val GEAR = 0
        const val TOGGLE = 1
    }

    init { isClickable = true }

    override fun onDraw(c: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val r = min(width, height) / 2f
        p.style = Paint.Style.FILL
        p.color = 0x33FFFFFF
        c.drawCircle(cx, cy, r * 0.95f, p)
        p.style = Paint.Style.STROKE
        p.color = Color.WHITE
        p.strokeCap = Paint.Cap.ROUND
        if (kind == GEAR) {
            p.strokeWidth = r * 0.14f
            c.drawCircle(cx, cy, r * 0.36f, p)
            val rr = r * 0.52f
            val circ = (2.0 * Math.PI * rr).toFloat()
            p.strokeWidth = r * 0.16f
            p.pathEffect = DashPathEffect(floatArrayOf(circ / 20f, circ / 20f), 0f)
            c.drawCircle(cx, cy, rr, p)
            p.pathEffect = null
            p.strokeWidth = r * 0.08f
            c.drawCircle(cx, cy, r * 0.14f, p)
        } else {
            p.strokeWidth = r * 0.1f
            val y1 = cy - r * 0.2f
            val y2 = cy + r * 0.2f
            val x0 = cx - r * 0.45f
            val x1 = cx + r * 0.45f
            val a = r * 0.16f
            c.drawLine(x0, y1, x1, y1, p)
            c.drawLine(x1, y1, x1 - a, y1 - a, p)
            c.drawLine(x1, y1, x1 - a, y1 + a, p)
            c.drawLine(x1, y2, x0, y2, p)
            c.drawLine(x0, y2, x0 + a, y2 - a, p)
            c.drawLine(x0, y2, x0 + a, y2 + a, p)
        }
    }
}

/** Hiệu ứng màn trập: đảo âm bản → xám phẳng → mờ dần về ảnh. */
class ShutterFxView(ctx: Context) : View(ctx) {
    private var greyAlpha = 0f

    override fun onDraw(c: Canvas) {
        if (greyAlpha > 0f) c.drawColor(Color.argb((greyAlpha * 255).toInt(), 124, 124, 126))
    }

    fun play(setInvert: (Boolean) -> Unit) {
        greyAlpha = 0f
        invalidate()
        setInvert(true)
        postDelayed({
            setInvert(false)
            greyAlpha = 0.94f
            invalidate()
        }, 140)
        postDelayed({
            ValueAnimator.ofFloat(0.94f, 0f).apply {
                duration = 320
                addUpdateListener { greyAlpha = it.animatedValue as Float; invalidate() }
                start()
            }
        }, 520)
    }
}

/** Màn hình xem lại: nền trắng, chữ HASSELBLAD, ảnh và thông số máy. */
class ReviewView(ctx: Context) : FrameLayout(ctx) {
    private val img = ImageView(ctx)
    var onClose: (() -> Unit)? = null
    var onSaveFrame: (() -> Unit)? = null
    var onOpen: (() -> Unit)? = null

    init {
        val dn = resources.displayMetrics.density
        setBackgroundColor(Color.WHITE)
        isClickable = true
        img.scaleType = ImageView.ScaleType.FIT_CENTER
        addView(img, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        val row = LinearLayout(ctx)
        row.orientation = LinearLayout.HORIZONTAL
        fun btn(t: String, a: () -> Unit) {
            val tv = TextView(ctx)
            tv.text = t
            tv.textSize = 13f
            tv.setTextColor(0xFF333333.toInt())
            tv.setPadding((12 * dn).toInt(), (8 * dn).toInt(), (12 * dn).toInt(), (8 * dn).toInt())
            tv.setOnClickListener { a() }
            row.addView(tv)
        }
        btn("Lưu ảnh khung") { onSaveFrame?.invoke() }
        btn("Thư viện") { onOpen?.invoke() }
        btn("✕") { onClose?.invoke() }
        val lp = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.END)
        lp.setMargins(0, (6 * dn).toInt(), (10 * dn).toInt(), 0)
        addView(row, lp)
        visibility = GONE
    }

    fun show(b: android.graphics.Bitmap) {
        img.setImageBitmap(b)
        visibility = VISIBLE
    }

    fun hide() {
        visibility = GONE
        img.setImageDrawable(null)
    }
}

/** Bố cục chính: sắp xếp lại toàn bộ control theo hai giao diện. */
class CameraLayout(ctx: Context) : ViewGroup(ctx) {
    val decor = DecorView(ctx)
    val texture = TextureView(ctx)
    val frame = FrameOverlayView(ctx)
    val focusBox = FocusBoxView(ctx)
    val fx = ShutterFxView(ctx)
    val ev = EvView(ctx)
    val ladder = ZoomLadderView(ctx)
    val pills = LensPillsView(ctx)
    val tray = HorizontalScrollView(ctx)
    val trayRow = LinearLayout(ctx)
    val shutter = ShutterButton(ctx)
    val thumb = ImageView(ctx)
    val filterBtn = FilterButtonView(ctx)
    val toggle = IconButtonView(ctx, IconButtonView.TOGGLE)
    val gear = IconButtonView(ctx, IconButtonView.GEAR)

    private val vf = RectF()
    private var safeL = 0
    private var safeR = 0
    private val dn = resources.displayMetrics.density

    var classic: Boolean = true
        set(v) { field = v; applyMode() }

    init {
        tray.isHorizontalScrollBarEnabled = false
        trayRow.orientation = LinearLayout.HORIZONTAL
        tray.addView(trayRow)
        tray.visibility = GONE
        thumb.scaleType = ImageView.ScaleType.CENTER_CROP
        thumb.clipToOutline = true
        thumb.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, 6f * dn)
            }
        }
        thumb.foreground = GradientDrawable().apply {
            setStroke((2 * dn).toInt(), 0x99FFFFFF.toInt())
            cornerRadius = 6f * dn
        }
        thumb.isClickable = true
        for (v in listOf<View>(decor, texture, frame, focusBox, fx, ev, ladder, pills, tray, shutter, thumb, filterBtn, toggle, gear)) {
            addView(v)
        }
        applyMode()
    }

    fun setSafe(l: Int, r: Int) {
        safeL = l; safeR = r
        decor.safeL = l.toFloat()
        requestLayout()
    }

    private fun applyMode() {
        setBackgroundColor(if (classic) 0xFF1B1B1D.toInt() else Color.BLACK)
        for (v in listOf<XView>(decor, frame, focusBox, ev, ladder, pills, shutter, filterBtn, toggle, gear)) v.classic = classic
        ladder.visibility = if (classic) VISIBLE else GONE
        pills.visibility = if (classic) GONE else VISIBLE
        requestLayout()
        invalidate()
    }

    private fun place(v: View, l: Float, t: Float, r: Float, b: Float) {
        val li = l.toInt(); val ti = t.toInt(); val ri = r.toInt(); val bi = b.toInt()
        v.measure(MeasureSpec.makeMeasureSpec(ri - li, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(bi - ti, MeasureSpec.EXACTLY))
        v.layout(li, ti, ri, bi)
    }

    private fun placeC(v: View, cx: Float, cy: Float, w: Float, h: Float) =
        place(v, cx - w / 2f, cy - h / 2f, cx + w / 2f, cy + h / 2f)

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), MeasureSpec.getSize(heightMeasureSpec))
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val w = (r - l).toFloat()
        val h = (b - t).toFloat()
        if (w <= 0f || h <= 0f) return
        val ax = safeL.toFloat()
        val aw = w - safeL - safeR
        val vfW = if (classic) aw * 0.835f else aw * 0.89f
        val vfH = vfW * 24f / 65f
        val vfL = if (classic) ax + aw * 0.008f else ax + (aw - vfW) / 2f
        val vfT = (h - vfH) / 2f
        vf.set(vfL, vfT, vfL + vfW, vfT + vfH)
        decor.vf.set(vf)

        place(decor, 0f, 0f, w, h)
        place(texture, vf.left, vf.top, vf.right, vf.bottom)
        place(frame, vf.left, vf.top, vf.right, vf.bottom)
        place(focusBox, vf.left, vf.top, vf.right, vf.bottom)
        place(fx, vf.left, vf.top, vf.right, vf.bottom)

        val btn = h * 0.1f
        placeC(toggle, ax + aw * 0.045f, h * 0.085f, btn, btn)
        placeC(gear, ax + aw * 0.045f + btn * 1.35f, h * 0.085f, btn, btn)

        placeC(tray, vf.centerX(), vf.top + dn * 26f, vfW * 0.8f, dn * 40f)

        val evH = vfH * 0.55f
        if (classic) {
            val cx = ax + aw * 0.943f
            placeC(shutter, cx, h * 0.5f, h * 0.19f, h * 0.19f)
            placeC(filterBtn, cx, h * 0.215f, aw * 0.075f, h * 0.095f)
            placeC(thumb, cx, h * 0.8f, h * 0.115f, h * 0.115f)
            val n = ladder.labels.size
            val lh = min(vfH * 0.9f, n * dn * 52f).coerceAtLeast(dn * 52f)
            placeC(ladder, vf.right - dn * 34f, vf.centerY(), dn * 56f, lh)
            place(ev, vf.left + vfW * 0.02f, vf.centerY() - evH * 0.35f, vf.left + vfW * 0.02f + dn * 90f, vf.centerY() - evH * 0.35f + evH)
        } else {
            val cx = vf.right - vfW * 0.087f
            placeC(shutter, cx, vf.centerY(), h * 0.19f, h * 0.19f)
            placeC(filterBtn, cx, vf.top + vfH * 0.18f, h * 0.085f, h * 0.085f)
            placeC(thumb, cx, vf.bottom - vfH * 0.17f, h * 0.13f, h * 0.13f)
            val n = pills.labels.size
            val pw = if (n == 0) dn * 40f else n * pills.pillSize() + (n - 1) * pills.gap()
            placeC(pills, vf.centerX(), vf.bottom - vfH * 0.12f, pw, pills.pillSize() + dn * 4f)
            place(ev, vf.left + vfW * 0.04f, vf.centerY() - evH * 0.5f, vf.left + vfW * 0.04f + dn * 90f, vf.centerY() - evH * 0.5f + evH)
        }
    }
}
