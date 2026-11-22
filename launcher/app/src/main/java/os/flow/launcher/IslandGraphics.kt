package os.flow.launcher

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.sin

fun formatDurationShort(ms: Long): String {
    val totalSec = (ms.coerceAtLeast(0L) / 1000L)
    val min = totalSec / 60L
    val sec = totalSec % 60L
    return "$min:${sec.toString().padStart(2, '0')}"
}

fun formatRemainingShort(posMs: Long, durationMs: Long): String {
    val remaining = (durationMs - posMs).coerceAtLeast(0L)
    return "-${formatDurationShort(remaining)}"
}

fun progressFraction(posMs: Long, durationMs: Long): Float {
    if (durationMs <= 0L) return 0f
    return (posMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
}

/** Égaliseur animé à 5 barres (style Dynamic Island) pour la musique et les appels. */
class IslandWaveformView(
    context: Context,
    private val barColors: IntArray,
    private val active: Boolean,
) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private var phase = 0f
    private var animator: ValueAnimator? = null

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (active) {
            animator = ValueAnimator.ofFloat(0f, (Math.PI * 2.0).toFloat()).apply {
                duration = 1100L
                repeatCount = ValueAnimator.INFINITE
                interpolator = LinearInterpolator()
                addUpdateListener {
                    phase = it.animatedValue as Float
                    invalidate()
                }
                start()
            }
        }
    }

    override fun onDetachedFromWindow() {
        animator?.cancel()
        animator = null
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        val count = barColors.size.coerceAtLeast(1)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val gap = w * 0.08f
        val barW = (w - gap * (count - 1)) / count
        val r = barW / 2f
        for (i in 0 until count) {
            val wave = if (active) {
                0.28f + 0.72f * ((sin(phase + i * 1.15f) + 1f) * 0.5f)
            } else {
                0.25f
            }
            val barH = (h * wave).coerceAtLeast(barW)
            val left = i * (barW + gap)
            val top = (h - barH) / 2f
            paint.color = barColors[i]
            rect.set(left, top, left + barW, top + barH)
            canvas.drawRoundRect(rect, r, r, paint)
        }
    }
}

/** Icône batterie verte style iPhone (capsule remplie + borne droite). */
class IslandBatteryIconView(
    context: Context,
    private val percent: Int,
) : View(context) {
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.parseColor("#5534C759")
    }
    private val rect = RectF()

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val tipW = w * 0.09f
        val bodyW = w - tipW - w * 0.06f
        val stroke = (h * 0.09f).coerceAtLeast(1.5f)
        borderPaint.strokeWidth = stroke
        val r = h * 0.28f

        // Contour
        rect.set(stroke, stroke, bodyW - stroke, h - stroke)
        canvas.drawRoundRect(rect, r, r, borderPaint)

        // Remplissage vert proportionnel
        val frac = (percent.coerceIn(0, 100) / 100f)
        val pad = stroke * 1.6f
        val fillW = ((bodyW - pad * 2f) * frac).coerceAtLeast(h * 0.2f)
        fillPaint.color = if (percent <= 20) Color.parseColor("#FF453A") else Color.parseColor("#34C759")
        rect.set(pad, pad, pad + fillW, h - pad)
        canvas.drawRoundRect(rect, r * 0.65f, r * 0.65f, fillPaint)

        // Borne droite
        fillPaint.color = Color.parseColor("#8834C759")
        val tipH = h * 0.36f
        rect.set(bodyW + w * 0.04f, (h - tipH) / 2f, w, (h + tipH) / 2f)
        canvas.drawRoundRect(rect, tipW / 2f, tipW / 2f, fillPaint)
    }
}

/** Barre de progression média fine et arrondie (blanc sur gris translucide). */
class IslandProgressBarView(context: Context) : View(context) {
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#3DFFFFFF") }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val rect = RectF()
    var progress: Float = 0f
        set(value) {
            field = value.coerceIn(0f, 1f)
            invalidate()
        }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val r = h / 2f
        rect.set(0f, 0f, w, h)
        canvas.drawRoundRect(rect, r, r, trackPaint)
        val fw = (w * progress).coerceAtLeast(if (progress > 0f) h else 0f)
        if (fw > 0f) {
            rect.set(0f, 0f, fw, h)
            canvas.drawRoundRect(rect, r, r, fillPaint)
        }
    }
}

/** Bouton vectoriel net pour les contrôles média et appels (style iOS, sans emojis). */
enum class IslandIconKind { PREV, NEXT, PLAY, PAUSE, PHONE_ANSWER, PHONE_DECLINE }

class IslandVectorIconView(
    context: Context,
    private val kind: IslandIconKind,
    private val tint: Int = Color.WHITE,
) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = tint
        style = Paint.Style.FILL
    }
    private val path = Path()
    private val rect = RectF()

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        path.reset()
        when (kind) {
            IslandIconKind.PLAY -> {
                path.moveTo(w * 0.26f, h * 0.16f)
                path.lineTo(w * 0.84f, h * 0.50f)
                path.lineTo(w * 0.26f, h * 0.84f)
                path.close()
                canvas.drawPath(path, paint)
            }
            IslandIconKind.PAUSE -> {
                val barW = w * 0.24f
                val r = barW * 0.35f
                rect.set(w * 0.20f, h * 0.16f, w * 0.20f + barW, h * 0.84f)
                canvas.drawRoundRect(rect, r, r, paint)
                rect.set(w * 0.80f - barW, h * 0.16f, w * 0.80f, h * 0.84f)
                canvas.drawRoundRect(rect, r, r, paint)
            }
            IslandIconKind.PREV -> {
                // Double triangle gauche ◀◀ (comme sur iOS)
                path.moveTo(w * 0.50f, h * 0.22f)
                path.lineTo(w * 0.10f, h * 0.50f)
                path.lineTo(w * 0.50f, h * 0.78f)
                path.close()
                path.moveTo(w * 0.90f, h * 0.22f)
                path.lineTo(w * 0.50f, h * 0.50f)
                path.lineTo(w * 0.90f, h * 0.78f)
                path.close()
                canvas.drawPath(path, paint)
            }
            IslandIconKind.NEXT -> {
                // Double triangle droit ▶▶ (comme sur iOS)
                path.moveTo(w * 0.10f, h * 0.22f)
                path.lineTo(w * 0.50f, h * 0.50f)
                path.lineTo(w * 0.10f, h * 0.78f)
                path.close()
                path.moveTo(w * 0.50f, h * 0.22f)
                path.lineTo(w * 0.90f, h * 0.50f)
                path.lineTo(w * 0.50f, h * 0.78f)
                path.close()
                canvas.drawPath(path, paint)
            }
            IslandIconKind.PHONE_ANSWER -> {
                canvas.save()
                canvas.rotate(-135f, w / 2f, h / 2f)
                drawHandset(canvas, w, h)
                canvas.restore()
            }
            IslandIconKind.PHONE_DECLINE -> {
                drawHandset(canvas, w, h)
            }
        }
    }

    /** Combiné téléphonique horizontal (position raccroché), pivoté pour décrocher. */
    private fun drawHandset(canvas: Canvas, w: Float, h: Float) {
        val cy = h * 0.50f
        // Arc central du combiné
        val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = tint
            style = Paint.Style.STROKE
            strokeWidth = h * 0.19f
            strokeCap = Paint.Cap.ROUND
        }
        rect.set(w * 0.16f, cy - h * 0.16f, w * 0.84f, cy + h * 0.44f)
        canvas.drawArc(rect, 200f, 140f, false, strokePaint)
        // Deux embouts arrondis
        val capR = h * 0.14f
        canvas.drawCircle(w * 0.21f, cy + h * 0.10f, capR, paint)
        canvas.drawCircle(w * 0.79f, cy + h * 0.10f, capR, paint)
    }
}

/** Convertit un Drawable en Bitmap carré compact pour l'avatar ou la pochette. */
internal fun drawableToSquareBitmap(drawable: Drawable?, sizePx: Int = 128): Bitmap? {
    if (drawable == null) return null
    if (drawable is BitmapDrawable && drawable.bitmap != null) {
        return Bitmap.createScaledBitmap(drawable.bitmap, sizePx, sizePx, true)
    }
    val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
    val c = Canvas(bmp)
    drawable.setBounds(0, 0, sizePx, sizePx)
    drawable.draw(c)
    return bmp
}
