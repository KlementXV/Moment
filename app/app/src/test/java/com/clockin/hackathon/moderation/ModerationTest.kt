package com.clockin.hackathon.moderation

import com.clockin.hackathon.capture.PhotoPair
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class ModerationTest {
    private val policy = ModerationPolicy("test", .5f, .9f, false)
    private fun result(rear: Float, front: Float) = ModerationAnalysis(rear, front, 12, policy)

    @Test fun either_camera_can_require_retake_and_boundaries_are_inclusive() {
        assertEquals(ModerationDecision.Review, result(.5f, .1f).decision)
        assertEquals(ModerationDecision.Blocked, result(.1f, .9f).decision)
        assertEquals(ModerationDecision.Accepted, result(.499f, .1f).decision)
    }

    @Test fun missing_failed_or_uncalibrated_analysis_never_allows_release_publication() {
        listOf(ModerationState.Empty, ModerationState.Pending, ModerationState.Unavailable).forEach {
            assertFalse(it.allowsPublication(true, true))
        }
        val ready = ModerationState.Ready(result(.1f, .1f))
        assertFalse(ready.allowsPublication(false, true))
        assertTrue(ready.allowsPublication(true))
        assertTrue(ModerationState.Ready(ready.analysis.copy(policy = policy.copy(calibrated = true))).allowsPublication(false))
    }

    @Test fun review_needs_acknowledgement_and_block_cannot_be_overridden() {
        val review = ModerationState.Ready(result(.6f, .1f))
        assertFalse(review.allowsPublication(true))
        assertTrue(review.allowsPublication(true, true))
        assertFalse(ModerationState.Ready(result(.99f, .1f)).allowsPublication(true, true))
    }

    @Test fun invalid_scores_and_policies_are_rejected() {
        listOf(Float.NaN, Float.POSITIVE_INFINITY, -.01f, 1.01f).forEach { score ->
            assertThrows(IllegalArgumentException::class.java) { result(score, .1f) }
        }
        assertThrows(IllegalArgumentException::class.java) { policy.copy(reviewThreshold = .95f) }
    }

    @Test fun late_native_result_cannot_replace_new_capture_or_reset() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        var state: ModerationState = ModerationState.Empty
        val delayed = CompletableDeferred<ModerationAnalysis>()
        val coordinator = ModerationCoordinator(scope, { photos ->
            if (photos.rear[0] == 1.toByte()) withContext(NonCancellable) { delayed.await() }
            else result(.95f, .1f)
        }) { state = it }
        coordinator.replace(PhotoPair(byteArrayOf(1), byteArrayOf(1)))
        assertEquals(ModerationState.Pending, state)
        coordinator.replace(PhotoPair(byteArrayOf(2), byteArrayOf(2)))
        assertEquals(ModerationDecision.Blocked, (state as ModerationState.Ready).analysis.decision)
        delayed.complete(result(.01f, .01f))
        yield()
        assertEquals(ModerationDecision.Blocked, (state as ModerationState.Ready).analysis.decision)
        coordinator.replace(null)
        assertEquals(ModerationState.Empty, state)
        scope.cancel()
    }

    @Test fun analyzer_failure_is_visible_and_retry_can_recover() = runBlocking {
        var fail = true
        var state: ModerationState = ModerationState.Empty
        val coordinator = ModerationCoordinator(this, {
            if (fail) error("decoder failure") else result(.1f, .1f)
        }) { state = it }
        val photos = PhotoPair(byteArrayOf(1), byteArrayOf(1))
        coordinator.replace(photos)
        yield()
        assertEquals(ModerationState.Unavailable, state)
        fail = false
        coordinator.replace(photos)
        yield()
        assertTrue(state is ModerationState.Ready)
    }
}
