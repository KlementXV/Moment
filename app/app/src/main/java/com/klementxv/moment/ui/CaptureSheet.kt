package com.klementxv.moment.ui

import com.klementxv.moment.i18n.Message
import com.klementxv.moment.i18n.tr

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.klementxv.moment.BuildConfig
import com.klementxv.moment.moderation.*
import com.klementxv.moment.ChainState
import com.klementxv.moment.capture.DraftCodec
import com.klementxv.moment.capture.PhotoPair
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CaptureSheet(
    state: ChainState,
    now: Long,
    draft: PhotoPair?,
    busy: Boolean,
    onDraft: (PhotoPair?) -> Unit,
    onPublish: (acknowledged: Boolean, caption: String) -> Unit,
    moderation: ModerationState,
    onRetryModeration: () -> Unit,
    onProfile: () -> Unit,
    onDismiss: () -> Unit,
) {
    val currentBusy by rememberUpdatedState(busy)
    val sheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = true,
        confirmValueChange = { it != SheetValue.Hidden || !currentBusy },
    )
    val scope = rememberCoroutineScope()
    val dismiss: () -> Unit = {
        if (!busy) scope.launch { sheetState.hide(); if (!sheetState.isVisible) onDismiss() }
    }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        sheetMaxWidth = Dp.Unspecified,
        sheetGesturesEnabled = !busy,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        containerColor = Ink,
        contentColor = White,
        dragHandle = null,
        contentWindowInsets = { WindowInsets(0, 0, 0, 0) },
        properties = ModalBottomSheetProperties(shouldDismissOnBackPress = !busy),
    ) {
        Box(Modifier.fillMaxSize().background(Ink)) {
            when {
                state.posted -> CapturePublished(draft, now, dismiss)
                draft != null -> CaptureReview(
                    draft = draft, canPublish = state.canPublish(now), busy = busy,
                    onRetake = { onDraft(null) }, onPublish = onPublish,
                    moderation = moderation, onRetryModeration = onRetryModeration,
                    onProfile = { scope.launch { sheetState.hide(); if (!sheetState.isVisible) onProfile() } },
                )
                else -> CameraCapture(enabled = !busy, now = now, onClose = dismiss,
                    onDraft = { onDraft(it) })
            }
        }
    }
}

@Composable
internal fun CaptureReview(
    draft: PhotoPair,
    canPublish: Boolean,
    busy: Boolean,
    onRetake: () -> Unit,
    onPublish: (acknowledged: Boolean, caption: String) -> Unit,
    onProfile: () -> Unit,
    moderation: ModerationState,
    onRetryModeration: () -> Unit,
) {
    var caption by rememberSaveable(draft) { mutableStateOf("") }
    var confirmReview by remember(draft) { mutableStateOf(false) }
    val analysis = (moderation as? ModerationState.Ready)?.analysis
    val gateOpen = moderation.allowsPublication(BuildConfig.DEBUG, reviewAcknowledged = true)
    val publish: () -> Unit = {
        if (analysis?.decision == ModerationDecision.Review) confirmReview = true
        else onPublish(false, caption)
    }
    if (confirmReview) AlertDialog(
        onDismissRequest = { confirmReview = false },
        title = { Text(tr(Message.RetakeThePhotos)) },
        text = { Text(tr(Message.OneOfThePhotosMayShowIntimate)) },
        confirmButton = { TextButton(onClick = { confirmReview = false; onRetake() }) { Text(tr(Message.Retake)) } },
        dismissButton = { TextButton(onClick = { confirmReview = false; onPublish(true, caption) }) { Text(tr(Message.ICheckedPublish)) } },
    )
    CaptureScaffold(
        onClose = onRetake,
        closeIcon = "back",
        closeLabel = tr(Message.RetakeThePhotos2),
        title = tr(Message.YourMoment),
        enabled = !busy,
    ) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())
            .padding(start = 16.dp, end = 16.dp, top = 8.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)) {
            MomentPhoto(image = { PhotoPairImage(draft) })
            MomentTextField(
                value = caption,
                label = tr(Message.Caption),
                supporting = tr(Message.Optional),
                maxChars = DraftCodec.MAX_CAPTION_CHARS,
                enabled = !busy,
                onValueChange = { caption = it },
            )
        }
        Column(Modifier.imePadding().padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            ModerationNotice(moderation, onRetryModeration, !busy)
            if (!canPublish) Text(
                tr(Message.YourDraftIsReadyFinishSettingUp),
                style = BodyMd, color = Muted,
            )
            PrimaryButton(when {
                busy -> tr(Message.Validating)
                !canPublish -> tr(Message.ContinueToMyProfile)
                moderation is ModerationState.Pending -> tr(Message.AnalyzingPhotos)
                !gateOpen -> tr(Message.PublishingUnavailable)
                else -> tr(Message.Publish)
            }, enabled = !busy && (!canPublish || gateOpen), loading = busy,
                onClick = if (canPublish) publish else onProfile)
        }
    }
}

@Composable
internal fun CapturePublished(draft: PhotoPair?, now: Long, onDismiss: () -> Unit) {
    CaptureScaffold(onClose = onDismiss) {
        Box(Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp)) {
            MomentPhoto(image = { draft?.let { PhotoPairImage(it) } })
        }
        Box(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp, vertical = 24.dp),
            contentAlignment = Alignment.Center) {
            Shutter(
                fraction = 1f,
                timeLabel = tr(Message.MomentCaptured),
                hint = tr(Message.NothingIsDeductedTodayNextMoment, nextMomentAt(now)),
                icon = "check",
            )
        }
        Box(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 24.dp)) {
            Snackbar(tr(Message.MomentPublishedYourStakeIsSafe))
        }
    }
}

@Composable
internal fun ModerationNotice(state: ModerationState, onRetry: () -> Unit, enabled: Boolean) {
    val analysis = (state as? ModerationState.Ready)?.analysis
    val label = when (state) {
        ModerationState.Empty, ModerationState.Pending -> tr(Message.CheckingBothPhotosOnThisDevice)
        ModerationState.Unavailable -> tr(Message.AnalysisUnavailableTryAgainOrRetakeThe)
        is ModerationState.Ready -> when (analysis!!.decision) {
            ModerationDecision.Accepted -> tr(Message.CheckCompleteNoSensitiveContentDetected)
            ModerationDecision.Review -> tr(Message.PotentiallyIntimateContentCheckBothPhotos)
            ModerationDecision.Blocked -> tr(Message.SensitiveContentDetectedRetakeThePhotosTo)
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, color = if (analysis?.decision == ModerationDecision.Accepted) Success else Danger,
            style = BodySm)
        if (analysis != null && !analysis.policy.calibrated) Text(
            if (BuildConfig.DEBUG) tr(Message.ExperimentalDetectionUncalibratedThresholds)
            else tr(Message.PublishingPausedUntilDetectionIsCalibrated),
            color = Muted, style = LabelSm)
        if (state is ModerationState.Unavailable) TextButton(onClick = onRetry, enabled = enabled) {
            Text(tr(Message.RetryAnalysis))
        }
    }
}
