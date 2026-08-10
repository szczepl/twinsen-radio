package net.mspanc.twinsenradio.playback

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import net.mspanc.twinsenradio.data.ClockFace
import java.io.ByteArrayOutputStream
import java.time.LocalTime
import kotlin.math.cos
import kotlin.math.sin

/**
 * Rysuje zegar, ktory zastepuje okladke.
 *
 * Bez zewnetrznej biblioteki - to kilkanascie linii na Canvasie, a doklejanie
 * zaleznosci po to, zeby narysowac kolo i dwie kreski, byloby przesada.
 *
 * Wynik idzie do metadanych jako bajty PNG (`artworkData`), bo grafika
 * generowana w locie nie ma adresu, ktory glowica moglaby pobrac.
 */
object ClockArt {

    private const val SIZE = 512

    fun pngBytes(
        face: ClockFace,
        backgroundColor: Int,
        foregroundColor: Int,
        time: LocalTime = LocalTime.now()
    ): ByteArray? {
        val bitmap = when (face) {
            ClockFace.DIGITAL -> digital(time, backgroundColor, foregroundColor)
            ClockFace.ANALOG -> analog(time, backgroundColor, foregroundColor)
            ClockFace.NONE -> return null
        }
        return ByteArrayOutputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            bitmap.recycle()
            out.toByteArray()
        }
    }

    private fun newCanvas(backgroundColor: Int): Pair<Bitmap, Canvas> {
        val bmp = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(backgroundColor)
        return bmp to canvas
    }

    private fun digital(time: LocalTime, bg: Int, fg: Int): Bitmap {
        val (bmp, canvas) = newCanvas(bg)
        val text = "%02d:%02d".format(time.hour, time.minute)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = fg
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            textSize = SIZE * 0.30f
        }
        // wysrodkowanie w pionie po metrykach fontu, nie "na oko"
        val metrics = paint.fontMetrics
        val baseline = SIZE / 2f - (metrics.ascent + metrics.descent) / 2f
        canvas.drawText(text, SIZE / 2f, baseline, paint)
        return bmp
    }

    private fun analog(time: LocalTime, bg: Int, fg: Int): Bitmap {
        val (bmp, canvas) = newCanvas(bg)
        val cx = SIZE / 2f
        val cy = SIZE / 2f
        val radius = SIZE * 0.42f

        val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = fg
            style = Paint.Style.STROKE
            strokeWidth = SIZE * 0.02f
        }
        canvas.drawCircle(cx, cy, radius, rim)

        // kreski godzinowe
        val tick = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = fg
            strokeWidth = SIZE * 0.012f
            strokeCap = Paint.Cap.ROUND
        }
        for (i in 0 until 12) {
            val angle = Math.toRadians(i * 30.0 - 90.0)
            val outer = radius * 0.94f
            val inner = radius * 0.82f
            canvas.drawLine(
                cx + (cos(angle) * inner).toFloat(), cy + (sin(angle) * inner).toFloat(),
                cx + (cos(angle) * outer).toFloat(), cy + (sin(angle) * outer).toFloat(),
                tick
            )
        }

        fun hand(angleDeg: Double, length: Float, width: Float) {
            val angle = Math.toRadians(angleDeg - 90.0)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = fg
                strokeWidth = width
                strokeCap = Paint.Cap.ROUND
            }
            canvas.drawLine(
                cx, cy,
                cx + (cos(angle) * length).toFloat(),
                cy + (sin(angle) * length).toFloat(),
                paint
            )
        }

        // wskazowka godzinowa uwzglednia minuty, zeby nie skakala co godzine
        hand((time.hour % 12) * 30.0 + time.minute * 0.5, radius * 0.52f, SIZE * 0.028f)
        hand(time.minute * 6.0, radius * 0.76f, SIZE * 0.018f)

        canvas.drawCircle(cx, cy, SIZE * 0.022f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(242, 169, 0)
        })
        return bmp
    }
}
