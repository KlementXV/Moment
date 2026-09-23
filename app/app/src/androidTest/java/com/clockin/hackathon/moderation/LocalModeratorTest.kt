package com.clockin.hackathon.moderation

import android.os.Debug
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import com.clockin.hackathon.MomentApplication
import com.clockin.hackathon.capture.PhotoPair
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import kotlin.math.ceil

class LocalModeratorTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private fun fixture(name: String) = instrumentation.context.assets.open("moderation/$name").use { it.readBytes() }
    private fun buffer(bytes: ByteArray) = ByteBuffer.allocateDirect(bytes.size).apply { put(bytes); rewind() }

    @Test fun preprocessing_matches_python_golden_rgb() {
        val rgb = fixture("source-7x13.rgb")
        val pixels = IntArray(7 * 13) { i ->
            (255 shl 24) or ((rgb[i * 3].toInt() and 255) shl 16) or
                ((rgb[i * 3 + 1].toInt() and 255) shl 8) or (rgb[i * 3 + 2].toInt() and 255)
        }
        val actual = ModerationImage.fromPixels(pixels, 7, 13)
        val bytes = ByteArray(actual.remaining()).also { actual.get(it) }
        assertArrayEquals(fixture("padded-7x13.rgb"), bytes)
        assertThrows(IllegalArgumentException::class.java) { ModerationImage.fromJpeg(byteArrayOf(1, 2)) }
    }

    @Test fun jpeg_pair_matches_desktop_and_decoder_failure_is_not_a_score() = runBlocking {
        val engine = (context.applicationContext as MomentApplication).moderator
        val expected = JSONObject(String(fixture("jpeg-expected.json")))
        val photos = PhotoPair(fixture("rear.jpg"), fixture("front.jpg"))
        val actual = engine.analyze(photos)
        // JPEG decoders can differ slightly; RGB tensor parity is separately checked at 1e-5.
        assertEquals(expected.getDouble("rear").toFloat(), actual.rear, .005f)
        assertEquals(expected.getDouble("front").toFloat(), actual.front, .005f)
        try {
            engine.analyze(PhotoPair(byteArrayOf(1), photos.front))
            fail("Invalid JPEG must fail, not produce a safe score")
        } catch (_: IllegalArgumentException) { }
        assertTrue(engine.analyze(photos).score.isFinite())
    }

    @Test fun benchmark_backends_and_verify_android_desktop_parity() = runBlocking {
        // Let app startup finish first, so it doesn't contend with measurements.
        (context.applicationContext as MomentApplication).moderator.warmUp()
        val expected = JSONObject(String(fixture("expected.json"))).getDouble("nsfw").toFloat()
        val rgb = fixture("input.rgb")
        val photos = PhotoPair(fixture("rear.jpg"), fixture("front.jpg"))
        val rows = JSONArray()
        for (backend in LocalModerator.Backend.entries) {
            LocalModerator(context, backend).use { engine ->
                val started = SystemClock.elapsedRealtime()
                engine.warmUp()
                val coldMs = SystemClock.elapsedRealtime() - started
                val actual = engine.scoreRgb(buffer(rgb))
                assertEquals("Backend $backend", expected, actual, 1e-5f)
                engine.analyze(photos)
                val times = mutableListOf<Long>()
                var peakPssKb = 0
                repeat(30) {
                    val result = engine.analyze(photos)
                    assertTrue(result.score.isFinite())
                    times += result.elapsedMs
                    val memory = Debug.MemoryInfo().also { Debug.getMemoryInfo(it) }
                    peakPssKb = maxOf(peakPssKb, memory.totalPss)
                }
                val sorted = times.sorted()
                rows.put(JSONObject().put("backend", backend.name).put("coldIncludingWarmupMs", coldMs)
                    .put("pairP50Ms", sorted[sorted.size / 2]).put("pairP95Ms", sorted[ceil(sorted.size * .95).toInt() - 1])
                    .put("processPeakSampledPssKb", peakPssKb).put("samplesMs", JSONArray(times))
                    .put("desktopNsfw", expected).put("androidNsfw", actual))
            }
        }
        File(context.filesDir, "moderation-benchmark.json").writeText(JSONObject()
            .put("device", android.os.Build.MODEL).put("android", android.os.Build.VERSION.RELEASE)
            .put("ort", "1.30.0").put("runsPerBackend", 30).put("results", rows).toString(2))
    }
}
