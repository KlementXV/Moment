package com.clockin.hackathon.capture

import androidx.core.graphics.createBitmap
import androidx.core.graphics.scale
import android.graphics.Canvas
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.Rect
import java.io.ByteArrayOutputStream

object PhotoSanitizer {
    const val MAX_EDGE = 1280
    /** Transform pixels, then create a NEW JPEG. Never copy source JPEG/EXIF segments. */
    fun clean(source: Bitmap, rotation: Int, mirror: Boolean, crop: Rect = Rect(0, 0, source.width, source.height)): ByteArray {
        require(rotation in listOf(0, 90, 180, 270))
        require(crop.left >= 0 && crop.top >= 0 && crop.right <= source.width && crop.bottom <= source.height && !crop.isEmpty)
        val pixels = Bitmap.createBitmap(source, crop.left, crop.top, crop.width(), crop.height(), Matrix().apply {
            postRotate(rotation.toFloat())
            if (mirror) postScale(-1f, 1f)
        }, true)
        val ratio = minOf(1f, MAX_EDGE.toFloat() / maxOf(pixels.width, pixels.height))
        val resized = pixels.scale( (pixels.width * ratio).toInt().coerceAtLeast(1),
            (pixels.height * ratio).toInt().coerceAtLeast(1), true)
        // Render into a new sRGB bitmap rather than preserving a source color profile.
        val normalized = createBitmap(resized.width, resized.height)
        Canvas(normalized).drawBitmap(resized, 0f, 0f, null)
        return try {
            ByteArrayOutputStream().use { output ->
                check(normalized.compress(Bitmap.CompressFormat.JPEG, 88, output))
                withoutMetadata(output.toByteArray()).also {
                    require(it.size <= SnapshotCodec.MAX_PHOTO_BYTES)
                    require(hasNoMetadata(it))
                }
            }
        } finally {
            normalized.recycle()
            if (resized !== source && resized !== pixels) resized.recycle()
            if (pixels !== source) pixels.recycle()
        }
    }
    /** Bitmap.compress may add an ICC APP2 segment even to a new bitmap.
     * Strip ALL APP/COM segments from our freshly encoded baseline JPEG, not from raw capture.
     */
    private fun withoutMetadata(jpeg: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(jpeg.size)
        require(jpeg.size >= 4 && jpeg[0] == 0xff.toByte() && jpeg[1] == 0xd8.toByte())
        out.write(jpeg, 0, 2)
        var offset = 2
        while (offset + 3 < jpeg.size) {
            require(jpeg[offset] == 0xff.toByte())
            val marker = jpeg[offset + 1].toInt() and 255
            if (marker == 0xda) {
                out.write(jpeg, offset, jpeg.size - offset)
                return out.toByteArray()
            }
            val length = ((jpeg[offset + 2].toInt() and 255) shl 8) or (jpeg[offset + 3].toInt() and 255)
            require(length >= 2 && offset + length + 2 <= jpeg.size)
            if (marker !in 0xe0..0xef && marker != 0xfe) out.write(jpeg, offset, length + 2)
            offset += length + 2
        }
        error("Invalid encoded JPEG")
    }
    /** Reject EXIF/XMP/IPTC/ICC/vendor APP segments and comments. JFIF APP0 is harmless. */
    fun hasNoMetadata(jpeg: ByteArray): Boolean {
        if (jpeg.size < 4 || jpeg[0] != 0xff.toByte() || jpeg[1] != 0xd8.toByte()) return false
        var offset = 2
        while (offset + 3 < jpeg.size) {
            if (jpeg[offset].toInt() and 255 != 255) return false
            val marker = jpeg[offset + 1].toInt() and 255
            if (marker in 0xe1..0xef || marker == 0xfe) return false
            if (marker == 0xda) return true // entropy-coded pixels begin
            val length = ((jpeg[offset + 2].toInt() and 255) shl 8) or (jpeg[offset + 3].toInt() and 255)
            if (length < 2 || offset + 2 + length > jpeg.size) return false
            if (marker == 0xe0 && (length < 7 || !jpeg.copyOfRange(offset + 4, offset + 9).contentEquals(byteArrayOf(74,70,73,70,0)))) return false
            offset += length + 2
        }
        return false
    }
}
