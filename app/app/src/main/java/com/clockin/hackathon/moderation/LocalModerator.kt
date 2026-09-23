package com.clockin.hackathon.moderation

import android.content.Context
import android.os.SystemClock
import ai.onnxruntime.OnnxJavaType
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import com.clockin.hackathon.capture.PhotoPair
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.concurrent.Executors

/** App-owned, serial native worker. No photos or scores are sent to a server or logged. */
class LocalModerator(context: Context, private val backend: Backend = Backend.Cpu4) : AutoCloseable {
    enum class Backend(val threads: Int, val xnnpack: Boolean) {
        Cpu2(2, false), Cpu4(4, false), Xnnpack2(2, true), Xnnpack4(4, true)
    }

    private val assets = context.applicationContext.assets
    private val worker = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "moment-moderation") }
    private val dispatcher = worker.asCoroutineDispatcher()
    private var session: OrtSession? = null
    private var options: OrtSession.SessionOptions? = null
    private var policy: ModerationPolicy? = null
    private val environment by lazy { OrtEnvironment.getEnvironment() }

    suspend fun warmUp() = withContext(dispatcher) { ensureSession(); Unit }

    suspend fun analyze(photos: PhotoPair): ModerationAnalysis = withContext(dispatcher) {
        ensureSession()
        val start = SystemClock.elapsedRealtime()
        val rear = infer(ModerationImage.fromJpeg(photos.rear))
        val front = infer(ModerationImage.fromJpeg(photos.front))
        ModerationAnalysis(rear, front, SystemClock.elapsedRealtime() - start, requireNotNull(policy))
    }

    /** Raw RGB entry for device parity/benchmark tests, with the same session and tensor contract. */
    internal suspend fun scoreRgb(input: ByteBuffer): Float = withContext(dispatcher) {
        ensureSession()
        infer(input)
    }

    private fun ensureSession() {
        if (session != null) return
        val metadata = JSONObject(assets.open("moderation/model.json").bufferedReader().use { it.readText() })
        require(metadata.getString("preprocessing") == ModerationImage.CONTRACT)
        val bytes = assets.open("moderation/marqo-nsfw.onnx").use { it.readBytes() }
        val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
        require(hash == metadata.getString("sha256")) { "Model integrity mismatch" }
        val settings = JSONObject(assets.open("moderation/policy.json").bufferedReader().use { it.readText() })
        require(settings.getString("preprocessing") == ModerationImage.CONTRACT)
        val calibrated = settings.getBoolean("calibrated")
        require(settings.getString("modelSha256") == hash)
        val loadedPolicy = ModerationPolicy(settings.getString("id"), settings.getDouble("reviewThreshold").toFloat(),
            settings.getDouble("blockThreshold").toFloat(), calibrated)
        val opts = OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(if (backend.xnnpack) 1 else backend.threads)
            setInterOpNumThreads(1)
            setExecutionMode(OrtSession.SessionOptions.ExecutionMode.SEQUENTIAL)
            addConfigEntry("session.intra_op.allow_spinning", "0")
            if (backend.xnnpack) addXnnpack(mapOf("intra_op_num_threads" to backend.threads.toString()))
        }
        var created: OrtSession? = null
        try {
            created = environment.createSession(bytes, opts)
            val input = created.inputInfo["image"]?.info as? TensorInfo
            require(input?.type == OnnxJavaType.UINT8 && input.shape.contentEquals(longArrayOf(1, 384, 384, 3)))
            val output = created.outputInfo["nsfw"]?.info as? TensorInfo
            require(output?.type == OnnxJavaType.FLOAT && output.shape.contentEquals(longArrayOf(1)))
            session = created
            val gray = ByteBuffer.allocateDirect(384 * 384 * 3)
            repeat(gray.capacity()) { gray.put(128.toByte()) }
            gray.rewind()
            infer(gray)
            options = opts
            policy = loadedPolicy
        } catch (failure: Throwable) {
            session = null
            created?.close()
            opts.close()
            throw failure
        }
    }

    private fun infer(rgb: ByteBuffer): Float {
        require(rgb.remaining() == 384 * 384 * 3 && rgb.isDirect)
        return OnnxTensor.createTensor(environment, rgb, longArrayOf(1, 384, 384, 3), OnnxJavaType.UINT8).use { tensor ->
            requireNotNull(session).run(mapOf("image" to tensor)).use { result ->
                val value = (result.get("nsfw").get().value as FloatArray).single()
                require(value.isFinite() && value in 0f..1f)
                value
            }
        }
    }

    override fun close() {
        // Queue after native work; SessionOptions must outlive its session.
        worker.execute { session?.close(); options?.close(); session = null }
        worker.shutdown()
    }
}
