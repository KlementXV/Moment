package com.clockin.hackathon.ui

import com.clockin.hackathon.i18n.Message
import com.clockin.hackathon.i18n.tr

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.UseCaseGroup
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.core.view.doOnLayout
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.Observer
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.clockin.hackathon.capture.PhotoPair
import com.clockin.hackathon.capture.PhotoSanitizer
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Le viseur : la caméra dans le cadre du Moment, et l'obturateur du jour.
 *
 * Les deux vues se prennent l'une après l'autre — c'est la paire que la chaîne
 * publie. La maquette ne montre qu'un obturateur ; la progression ne passe donc
 * pas par des étapes numérotées mais par le cadre lui-même : la scène prise
 * glisse dans la vignette, et la caméra avant occupe la grande vue.
 */
@Composable
internal fun CameraCapture(
    enabled: Boolean,
    now: Long,
    onClose: () -> Unit,
    onDraft: (PhotoPair) -> Unit,
) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    var granted by remember { mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
    var requested by rememberSaveable { mutableStateOf(false) }
    var rear by remember { mutableStateOf<ByteArray?>(null) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it; requested = true }
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) granted = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    if (!granted) {
        CameraPermission(
            blocked = requested,
            onClose = onClose,
            onAllow = { permission.launch(Manifest.permission.CAMERA) },
            onSettings = {
                context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:${context.packageName}".toUri()))
            },
        )
    } else {
        val front = rear != null
        key(front) {
            CameraViewport(enabled = enabled, now = now, front = front, rear = rear,
                onClose = onClose, onRestart = { rear = null }, onPhoto = { photo ->
                    if (!front) rear = photo
                    else rear?.let { onDraft(PhotoPair(it, photo)); rear = null }
                })
        }
    }
}

/**
 * La demande d'autorisation, sur la forme de l'écran « Deux autorisations » du
 * parcours : ce qui est demandé, pourquoi, puis le bouton.
 *
 * Une seule ligne : l'app ne demande pas les notifications. Une carte pour un
 * réglage qu'on ne demande jamais serait une promesse à vide.
 */
@Composable
internal fun CameraPermission(
    blocked: Boolean,
    onClose: () -> Unit,
    onAllow: () -> Unit,
    onSettings: () -> Unit,
) {
    CaptureScaffold(onClose = onClose) {
        Column(Modifier.weight(1f).padding(horizontal = 16.dp)) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                PermissionRow("camera", tr(Message.Camera), tr(Message.ForYourPhotoAndSelfie))
            }
            Column(Modifier.padding(bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(tr(Message.MakeRoomForYourEveryday), style = DisplayMd)
                Text(tr(Message.CameraPermissionExplanation), style = BodyLg, color = Muted)
            }
        }
        Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            PrimaryButton(tr(Message.Allow), onClick = onAllow)
            // Un second refus ne rouvre plus la fenêtre système : sans ce
            // passage vers les réglages, l'écran deviendrait une impasse.
            if (blocked) {
                Text(tr(Message.IfPermissionIsBlockedEnableItIn),
                    Modifier.fillMaxWidth(), color = Muted, style = BodySm, textAlign = TextAlign.Center)
                TextButton(onClick = onSettings, modifier = Modifier.fillMaxWidth()) {
                    Text(tr(Message.OpenSettings), color = Accent)
                }
            }
        }
    }
}

@Composable
private fun CameraViewport(
    enabled: Boolean,
    now: Long,
    front: Boolean,
    rear: ByteArray?,
    onClose: () -> Unit,
    onRestart: () -> Unit,
    onPhoto: (ByteArray) -> Unit,
) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val onPhotoLatest by rememberUpdatedState(onPhoto)
    val previewView = remember { PreviewView(context).apply {
        implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        scaleType = PreviewView.ScaleType.FILL_CENTER
    } }
    var viewportSize by remember { mutableStateOf(IntSize.Zero) }
    var resumed by remember { mutableStateOf(owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
    var capture by remember { mutableStateOf<ImageCapture?>(null) }
    var streaming by remember { mutableStateOf(false) }
    var taking by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var retry by remember { mutableIntStateOf(0) }
    val live = remember(owner, retry, viewportSize) { AtomicBoolean(true) }
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, _ ->
            resumed = owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(retry, streaming, resumed) {
        if (!streaming && resumed) {
            delay(12_000)
            if (!streaming && error == null) error = tr(Message.TheCameraIsnTRespondingCloseOther)
        }
    }
    DisposableEffect(owner, retry, viewportSize) {
        if (viewportSize == IntSize.Zero) return@DisposableEffect onDispose { }
        live.set(true)
        error = null; streaming = false; taking = false
        val disposed = AtomicBoolean(false)
        val executor = ContextCompat.getMainExecutor(context)
        val manager = context.getSystemService(CameraManager::class.java)
        val lenses = runCatching { manager.cameraIdList.map { manager.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) }.toSet() }.getOrDefault(emptySet())
        val bothCameras = CameraCharacteristics.LENS_FACING_FRONT in lenses && CameraCharacteristics.LENS_FACING_BACK in lenses
        val future = if (bothCameras) ProcessCameraProvider.getInstance(context) else null
        if (!bothCameras) error = tr(Message.MomentNeedsARearAndAFront)
        var provider: ProcessCameraProvider? = null
        val selector = if (front) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
        val resolution = ResolutionSelector.Builder().setResolutionStrategy(ResolutionStrategy(
            android.util.Size(1280, 960), ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER)).build()
        val preview = Preview.Builder().setResolutionSelector(resolution).build()
        val imageCapture = ImageCapture.Builder().setResolutionSelector(resolution)
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY).build()
        val observer = Observer<PreviewView.StreamState> {
            streaming = it == PreviewView.StreamState.STREAMING
        }
        previewView.previewStreamState.observe(owner, observer)
        if (future != null) future.addListener({
            if (!disposed.get()) {
                try {
                    val cameraProvider = future.get()
                    provider = cameraProvider
                    check(cameraProvider.hasCamera(selector))
                    previewView.doOnLayout {
                        if (!disposed.get()) {
                            try {
                                // La photo reprend le cadrage du viseur, désormais en 4:5 :
                                // ce qu'on voit dans la carte est ce que le fil affichera.
                                val useCases = UseCaseGroup.Builder()
                                    .setViewPort(requireNotNull(previewView.viewPort))
                                    .addUseCase(preview).addUseCase(imageCapture).build()
                                preview.setSurfaceProvider(previewView.surfaceProvider)
                                cameraProvider.bindToLifecycle(owner, selector, useCases)
                                capture = imageCapture
                            } catch (_: Exception) {
                                error = tr(Message.TheCameraCouldnTStartTryAgain)
                            }
                        }
                    }
                } catch (_: Exception) {
                    error = if (front) tr(Message.TheFrontCameraIsUnavailableCheckThat)
                        else tr(Message.TheCameraIsUnavailableCheckPermissionAnd)
                }
            }
        }, executor)
        onDispose {
            disposed.set(true); live.set(false); capture = null
            previewView.previewStreamState.removeObserver(observer)
            provider?.unbind(preview, imageCapture)
        }
    }
    val takePhoto: () -> Unit = takePhoto@{
        val camera = capture ?: return@takePhoto
        if (!enabled || taking || !streaming || error != null) return@takePhoto
        taking = true
        camera.targetRotation = previewView.display?.rotation ?: android.view.Surface.ROTATION_0
        camera.takePicture(Dispatchers.IO.asExecutor(), object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(image: ImageProxy) {
                val result = try {
                    runCatching {
                        val bitmap = image.toBitmap()
                        try { PhotoSanitizer.clean(bitmap, image.imageInfo.rotationDegrees, front, image.cropRect) }
                        finally { bitmap.recycle() }
                    }
                } finally { image.close() }
                ContextCompat.getMainExecutor(context).execute {
                    if (live.get()) {
                        taking = false
                        result.onSuccess { onPhotoLatest(it) }.onFailure { error = tr(Message.ThePhotoCouldnTBePreparedRetake) }
                    }
                }
            }
            override fun onError(exception: ImageCaptureException) {
                ContextCompat.getMainExecutor(context).execute {
                    if (live.get()) { taking = false; error = tr(Message.CaptureWasInterruptedYouCanTryAgain) }
                }
            }
        })
    }
    val left = secondsUntilNextMoment(now)
    val ready = enabled && streaming && !taking && capture != null && error == null
    CaptureScaffold(
        onClose = onClose,
        enabled = enabled,
        // La maquette met ici un changement de caméra ; le viseur bascule seul
        // d'une vue à l'autre. Le seul geste qui reste est de refaire la scène,
        // et il ne s'offre qu'une fois qu'elle existe.
        action = if (front) ({
            CaptureIconButton("retry", tr(Message.RetakeTheScene), enabled && !taking, onRestart)
        }) else null,
    ) {
        Box(Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp)) {
            MomentPhoto(
                image = {
                    AndroidView(factory = { previewView },
                        modifier = Modifier.fillMaxSize().onSizeChanged { viewportSize = it })
                    if (!streaming && error == null) CameraNotice {
                        CircularProgressIndicator(Modifier.size(28.dp), color = Accent, strokeWidth = 2.dp)
                        Text(tr(Message.OpeningCamera), color = White, style = BodyMd)
                    }
                    error?.let { message ->
                        CameraNotice {
                            Text(tr(Message.TryAgain2), style = HeadlineSm)
                            Text(message, color = Muted, style = BodyMd, textAlign = TextAlign.Center)
                            TextButton(onClick = { retry++ }) { Text(tr(Message.RetryCamera), color = Accent) }
                        }
                    }
                },
                overlay = { rear?.let { SceneThumbnail(it) } },
            )
        }
        Box(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp, vertical = 24.dp),
            contentAlignment = Alignment.Center) {
            Shutter(
                fraction = left / DAY_SECONDS.toFloat(),
                timeLabel = countdown(left),
                hint = if (front) tr(Message.ASelfieToCompleteTheMoment)
                    else tr(Message.YouHaveHLeftToCaptureToday, left / 3600),
                late = left <= 3 * 3600,
                enabled = ready,
                loading = taking,
                label = if (front) tr(Message.TakeMySelfie) else tr(Message.CaptureTheScene),
                onCapture = takePhoto,
            )
        }
    }
}

/** Ce qui se dit par-dessus le viseur : l'attente, ou la panne. */
@Composable
private fun BoxScope.CameraNotice(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.align(Alignment.Center).padding(24.dp)
        .clip(RadiusXl).background(Ink.copy(alpha = .92f)).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
}

/** La scène déjà prise, en attendant le selfie : la vignette de la carte. */
@Composable
private fun BoxScope.SceneThumbnail(bytes: ByteArray) {
    val image by produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, bytes) {
        value = withContext(Dispatchers.IO) {
            android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
        }
    }
    MomentSelfie {
        image?.let {
            Image(it, tr(Message.CapturedScene), Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        }
    }
}

/**
 * Les deux vues d'un Moment dans un cadre : la grande derrière, l'autre en
 * vignette. Un appui sur la vignette échange les deux — c'est la seule façon
 * de regarder de près la vue qui n'est pas au premier plan.
 */
@Composable
internal fun BoxScope.PhotoPairImage(pair: PhotoPair) {
    var selfieLarge by remember(pair) { mutableStateOf(false) }
    val images by produceState<Pair<androidx.compose.ui.graphics.ImageBitmap, androidx.compose.ui.graphics.ImageBitmap>?>(null, pair) {
        value = withContext(Dispatchers.Default) {
            val rear = android.graphics.BitmapFactory.decodeByteArray(pair.rear, 0, pair.rear.size)
            val front = android.graphics.BitmapFactory.decodeByteArray(pair.front, 0, pair.front.size)
            if (rear == null || front == null) null else rear.asImageBitmap() to front.asImageBitmap()
        }
    }
    images?.let { (rear, front) ->
        Image(if (selfieLarge) front else rear, if (selfieLarge) tr(Message.YourSelfie) else tr(Message.YourScenePhoto),
            Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        MomentSelfie(Modifier.clickable(role = Role.Button) { selfieLarge = !selfieLarge }) {
            Image(if (selfieLarge) rear else front,
                if (selfieLarge) tr(Message.EnlargeTheScene) else tr(Message.EnlargeTheSelfie),
                Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        }
    } ?: CircularProgressIndicator(Modifier.align(Alignment.Center).size(24.dp),
        color = Accent, strokeWidth = 2.dp)
}
