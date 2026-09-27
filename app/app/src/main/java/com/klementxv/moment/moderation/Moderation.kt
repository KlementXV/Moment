package com.klementxv.moment.moderation

import com.klementxv.moment.capture.PhotoPair
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

enum class ModerationDecision { Accepted, Review, Blocked }

data class ModerationPolicy(
    val id: String,
    val reviewThreshold: Float,
    val blockThreshold: Float,
    val calibrated: Boolean,
) {
    init {
        require(id.isNotBlank())
        require(reviewThreshold.isFinite() && blockThreshold.isFinite())
        require(reviewThreshold > 0f && reviewThreshold < blockThreshold && blockThreshold <= 1f)
    }

    fun decision(score: Float): ModerationDecision {
        require(score.isFinite() && score in 0f..1f)
        return when {
            score >= blockThreshold -> ModerationDecision.Blocked
            score >= reviewThreshold -> ModerationDecision.Review
            else -> ModerationDecision.Accepted
        }
    }
}

data class ModerationAnalysis(
    val rear: Float,
    val front: Float,
    val elapsedMs: Long,
    val policy: ModerationPolicy,
) {
    init { require(rear.isFinite() && rear in 0f..1f && front.isFinite() && front in 0f..1f) }
    val score: Float get() = maxOf(rear, front)
    val decision: ModerationDecision get() = policy.decision(score)
}

sealed interface ModerationState {
    data object Empty : ModerationState
    data object Pending : ModerationState
    data object Unavailable : ModerationState
    data class Ready(val analysis: ModerationAnalysis) : ModerationState
}

fun ModerationState.allowsPublication(debugBuild: Boolean, reviewAcknowledged: Boolean = false): Boolean {
    val analysis = (this as? ModerationState.Ready)?.analysis ?: return false
    if (!analysis.policy.calibrated && !debugBuild) return false
    return when (analysis.decision) {
        ModerationDecision.Accepted -> true
        ModerationDecision.Review -> reviewAcknowledged
        ModerationDecision.Blocked -> false
    }
}

class ModerationCoordinator(
    private val scope: CoroutineScope,
    private val analyze: suspend (PhotoPair) -> ModerationAnalysis,
    private val onState: (ModerationState) -> Unit,
) {
    private var generation = 0L
    private var job: Job? = null

    fun replace(photos: PhotoPair?) {
        val current = ++generation
        job?.cancel()
        onState(if (photos == null) ModerationState.Empty else ModerationState.Pending)
        if (photos == null) return
        job = scope.launch {
            val result = try {
                ModerationState.Ready(analyze(photos))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: LinkageError) {
                ModerationState.Unavailable
            } catch (_: Exception) {
                ModerationState.Unavailable
            }
            if (generation == current) onState(result)
        }
    }
}
