package com.klementxv.moment.capture

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Rect
import android.media.ExifInterface
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.KeyStore
import javax.crypto.KeyGenerator

@RunWith(AndroidJUnit4::class)
class PhotoSanitizerTest {
    private fun bitmap() = Bitmap.createBitmap(100, 60, Bitmap.Config.ARGB_8888).apply {
        eraseColor(Color.BLUE)
        for (x in 0 until 50) for (y in 0 until 60) setPixel(x, y, Color.RED)
    }
    private fun decode(bytes: ByteArray) = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)!!

    @Test fun rotatesAndMirrorsPixels() {
        val source = bitmap()
        val rotated = decode(PhotoSanitizer.clean(source, 90, false))
        assertEquals(60, rotated.width); assertEquals(100, rotated.height)
        assertTrue(Color.red(rotated.getPixel(30, 10)) > 200)
        assertTrue(Color.blue(rotated.getPixel(30, 90)) > 200)
        val mirrored = decode(PhotoSanitizer.clean(source, 0, true))
        assertTrue(Color.blue(mirrored.getPixel(10, 30)) > 200)
        assertTrue(Color.red(mirrored.getPixel(90, 30)) > 200)
        source.recycle(); rotated.recycle(); mirrored.recycle()
    }
    @Test fun boundsDimensionsAndAppliesCrop() {
        val source = Bitmap.createBitmap(2000, 1000, Bitmap.Config.ARGB_8888)
        val resized = decode(PhotoSanitizer.clean(source, 0, false))
        assertEquals(1280, resized.width); assertEquals(640, resized.height)
        val cropped = decode(PhotoSanitizer.clean(source, 0, false, Rect(100, 100, 300, 400)))
        assertEquals(200, cropped.width); assertEquals(300, cropped.height)
        source.recycle(); resized.recycle(); cropped.recycle()
    }
    @Test fun stripsGpsCameraIdentityAndOriginalDate() {
        val file = File.createTempFile("metadata-fixture", ".jpg", InstrumentationRegistry.getInstrumentation().targetContext.cacheDir)
        try {
            val source = bitmap()
            file.outputStream().use { source.compress(Bitmap.CompressFormat.JPEG, 90, it) }
            ExifInterface(file.path).apply {
                setAttribute(ExifInterface.TAG_GPS_LATITUDE, "48/1,51/1,0/1")
                setAttribute(ExifInterface.TAG_GPS_LATITUDE_REF, "N")
                setAttribute(ExifInterface.TAG_MAKE, "Sensitive camera identity")
                setAttribute(ExifInterface.TAG_DATETIME, "2026:09:15 12:34:56")
                saveAttributes()
            }
            assertFalse(PhotoSanitizer.hasNoMetadata(file.readBytes()))
            val decoded = BitmapFactory.decodeFile(file.path)!!
            val clean = PhotoSanitizer.clean(decoded, 0, false)
            assertTrue(PhotoSanitizer.hasNoMetadata(clean))
            val exif = ExifInterface(clean.inputStream())
            assertNull(exif.getAttribute(ExifInterface.TAG_GPS_LATITUDE))
            assertNull(exif.getAttribute(ExifInterface.TAG_MAKE))
            assertNull(exif.getAttribute(ExifInterface.TAG_DATETIME))
            source.recycle(); decoded.recycle()
        } finally { file.delete() }
    }
    @Test fun rejectsXmpIptcCommentsAndVendorSegments() {
        val source = bitmap()
        val clean = PhotoSanitizer.clean(source, 0, false)
        for (marker in listOf(0xe1, 0xed, 0xef, 0xfe)) {
            val raw = clean.copyOfRange(0, 2) + byteArrayOf(0xff.toByte(), marker.toByte(), 0, 6, 65, 66, 67, 68) + clean.copyOfRange(2, clean.size)
            assertFalse(PhotoSanitizer.hasNoMetadata(raw))
            val decoded = decode(raw)
            assertTrue(PhotoSanitizer.hasNoMetadata(PhotoSanitizer.clean(decoded, 0, false)))
            decoded.recycle()
        }
        source.recycle()
    }
    @Test fun keystoreEncryptionUsesFreshIvAndAuthenticatesPayload() {
        val alias = "moment-test-${System.nanoTime()}"
        try {
            val key = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
                init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setKeySize(256).build())
            }.generateKey()
            val plain = DraftCodec.encode(
                LocalDraft(
                    day = 20_706L,
                    photos = PhotoPair(byteArrayOf(1, 2, 3), byteArrayOf(4, 5, 6)),
                    nonce = ByteArray(DraftCodec.NONCE_SIZE) { 7 },
                )
            )
            val first = LocalEncryption.encrypt(plain, key)
            val second = LocalEncryption.encrypt(plain, key)
            assertFalse(first.contentEquals(second))
            assertArrayEquals(plain, LocalEncryption.decrypt(first, key))
        } finally { KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(alias) } }
    }
}
