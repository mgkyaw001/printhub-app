package com.printhub

import android.graphics.*
import java.io.ByteArrayOutputStream

enum class ColorMode { COLOR, GRAYSCALE, BLACK_WHITE }
enum class ScalingMode { FIT_TO_PAGE, FILL_PAGE, ACTUAL_SIZE }
enum class PaperOrientation { PORTRAIT, LANDSCAPE }
enum class ImageAlignment { CENTER, TOP, BOTTOM }

data class RenderOptions(
    val targetWidthDots: Int,
    val targetHeightDots: Int,
    val colorMode: ColorMode = ColorMode.COLOR,
    val orientation: PaperOrientation = PaperOrientation.PORTRAIT,
    val scalingMode: ScalingMode = ScalingMode.FIT_TO_PAGE,
    val alignment: ImageAlignment = ImageAlignment.CENTER,
    val marginDots: Int = 36,
    val rotationDegrees: Float = 0f
)

object ImagePrintRenderer {
    fun renderToCanvasBitmap(source: Bitmap, options: RenderOptions): Bitmap {
        var pageW = options.targetWidthDots
        var pageH = options.targetHeightDots
        if (options.orientation == PaperOrientation.LANDSCAPE && pageW < pageH) {
            val t = pageW; pageW = pageH; pageH = t
        }

        val output = Bitmap.createBitmap(pageW, pageH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        canvas.drawColor(Color.WHITE)

        val preparedSrc = if (options.rotationDegrees % 360f != 0f) {
            val m = Matrix().apply { postRotate(options.rotationDegrees) }
            Bitmap.createBitmap(source, 0, 0, source.width, source.height, m, true)
        } else source

        val printableRect = RectF(
            options.marginDots.toFloat(),
            options.marginDots.toFloat(),
            (pageW - options.marginDots).toFloat(),
            (pageH - options.marginDots).toFloat()
        )

        val scale = when (options.scalingMode) {
            ScalingMode.FIT_TO_PAGE -> (printableRect.width() / preparedSrc.width.toFloat()).coerceAtMost(printableRect.height() / preparedSrc.height.toFloat())
            ScalingMode.FILL_PAGE -> (printableRect.width() / preparedSrc.width.toFloat()).coerceAtLeast(printableRect.height() / preparedSrc.height.toFloat())
            ScalingMode.ACTUAL_SIZE -> 1.0f
        }

        val dstW = preparedSrc.width * scale
        val dstH = preparedSrc.height * scale
        val dstX = printableRect.left + (printableRect.width() - dstW) / 2f
        val dstY = printableRect.top + (printableRect.height() - dstH) / 2f

        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        when (options.colorMode) {
            ColorMode.COLOR -> canvas.drawBitmap(preparedSrc, null, RectF(dstX, dstY, dstX + dstW, dstY + dstH), paint)
            ColorMode.GRAYSCALE -> {
                paint.colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0f) })
                canvas.drawBitmap(preparedSrc, null, RectF(dstX, dstY, dstX + dstW, dstY + dstH), paint)
            }
            ColorMode.BLACK_WHITE -> {
                val dithered = floydSteinbergDither(preparedSrc)
                canvas.drawBitmap(dithered, null, RectF(dstX, dstY, dstX + dstW, dstY + dstH), paint)
            }
        }
        return output
    }

    fun floydSteinbergDither(source: Bitmap): Bitmap {
        val w = source.width
        val h = source.height
        val pixels = IntArray(w * h)
        source.getPixels(pixels, 0, w, 0, 0, w, h)

        val gray = Array(h) { FloatArray(w) }
        for (y in 0 until h) {
            for (x in 0 until w) {
                val c = pixels[y * w + x]
                gray[y][x] = (0.299f * ((c shr 16) and 0xFF) + 0.587f * ((c shr 8) and 0xFF) + 0.114f * (c and 0xFF))
            }
        }

        val outPixels = IntArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val oldVal = gray[y][x].coerceIn(0f, 255f)
                val newVal = if (oldVal < 128f) 0f else 255f
                val err = oldVal - newVal
                outPixels[y * w + x] = if (newVal == 0f) Color.BLACK else Color.WHITE

                if (x + 1 < w) gray[y][x + 1] += err * 7f / 16f
                if (y + 1 < h) {
                    if (x - 1 >= 0) gray[y + 1][x - 1] += err * 3f / 16f
                    gray[y + 1][x] += err * 5f / 16f
                    if (x + 1 < w) gray[y + 1][x + 1] += err * 1f / 16f
                }
            }
        }
        val dithered = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        dithered.setPixels(outPixels, 0, w, 0, 0, w, h)
        return dithered
    }
}