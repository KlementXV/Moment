package com.clockin.hackathon.moderation

import android.graphics.BitmapFactory
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.floor

/** pad-rgb128-bilinear-v1. Keep in sync with scripts/nsfw/preprocess.py. */
object ModerationImage {
    const val SIZE = 384
    const val CONTRACT = "pad-rgb128-bilinear-v1"

    fun fromJpeg(jpeg: ByteArray): ByteBuffer {
        require(jpeg.isNotEmpty() && jpeg.size <= 4 * 1024 * 1024)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, bounds)
        require(bounds.outWidth > 0 && bounds.outHeight > 0)
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 1536) sample *= 2
        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = android.graphics.Bitmap.Config.ARGB_8888
            inScaled = false
        }
        val bitmap = requireNotNull(BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, options))
        return try {
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            fromPixels(pixels, bitmap.width, bitmap.height)
        } finally {
            bitmap.recycle()
        }
    }

    fun fromPixels(pixels: IntArray, width: Int, height: Int): ByteBuffer {
        require(width > 0 && height > 0 && pixels.size.toLong() == width.toLong() * height)
        val scale = SIZE.toDouble() / maxOf(width, height)
        val dw = maxOf(1, floor(width * scale + .5).toInt())
        val dh = maxOf(1, floor(height * scale + .5).toInt())
        val left = (SIZE - dw) / 2
        val top = (SIZE - dh) / 2
        val output = ByteBuffer.allocateDirect(SIZE * SIZE * 3).order(ByteOrder.nativeOrder())
        repeat(output.capacity()) { output.put(128.toByte()) }
        for (y in 0 until dh) {
            val sy = ((y + .5) * height / dh - .5).coerceIn(0.0, (height - 1).toDouble())
            val y0 = sy.toInt()
            val y1 = minOf(y0 + 1, height - 1)
            val fy = sy - y0
            for (x in 0 until dw) {
                val sx = ((x + .5) * width / dw - .5).coerceIn(0.0, (width - 1).toDouble())
                val x0 = sx.toInt()
                val x1 = minOf(x0 + 1, width - 1)
                val fx = sx - x0
                val offset = ((top + y) * SIZE + left + x) * 3
                for (channel in 0..2) {
                    val shift = (2 - channel) * 8
                    fun component(px: Int, py: Int) = (pixels[py * width + px] ushr shift) and 255
                    val upper = component(x0, y0) * (1 - fx) + component(x1, y0) * fx
                    val lower = component(x0, y1) * (1 - fx) + component(x1, y1) * fx
                    output.put(offset + channel, floor(upper * (1 - fy) + lower * fy + .5).toInt().toByte())
                }
            }
        }
        output.rewind()
        return output
    }
}
