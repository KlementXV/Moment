package com.klementxv.moment.backend

/** Keep uncertain submissions resumable; a definitive moderation refusal permits new photos. */
suspend fun authorizePendingPost(
    firstAttempt: Boolean,
    authorize: suspend () -> ByteArray,
    discard: suspend () -> Unit,
): ByteArray = try {
    authorize()
} catch (failure: BackendException) {
    // The server returns 409 instead of 422 when an earlier authorization exists.
    if (failure.status == 422 || (firstAttempt && failure.status in listOf(400, 403))) {
        discard()
    }
    throw failure
}
