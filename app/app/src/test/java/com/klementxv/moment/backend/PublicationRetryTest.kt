package com.klementxv.moment.backend

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class PublicationRetryTest {
    @Test fun interruptedSubmissionThenModerationRefusalAllowsNewPhotos() = runBlocking {
        var pending = true
        suspend fun attempt(status: Int) {
            try {
                authorizePendingPost(false, { throw BackendException(status, "refused") }, { pending = false })
                fail("Expected refusal")
            } catch (failure: BackendException) { assertEquals(status, failure.status) }
        }
        attempt(503)
        assertTrue(pending)
        attempt(422)
        assertFalse(pending)
    }

    @Test fun uncertainOrPreviouslyAuthorizedRetriesKeepTheirPacket() = runBlocking {
        for (status in listOf(400, 401, 403, 409, 429, 503)) {
            var discarded = false
            try {
                authorizePendingPost(false, { throw BackendException(status, "retry") }, { discarded = true })
                fail("Expected refusal")
            } catch (_: BackendException) { }
            assertFalse("status=$status", discarded)
        }
    }
}
