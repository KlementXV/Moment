package com.clockin.hackathon.ui

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
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.clickable
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
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

@Composable
internal fun CameraCapture(draft: PhotoPair?, busy: Boolean, onDraft: (PhotoPair?) -> Unit, onPublish: () -> Unit) {
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
    when {
        draft != null -> {
            PhotoPairView(draft, Modifier.fillMaxWidth().height(380.dp))
            InfoCard("Ton Moment, à toi de choisir.", "Vérifie les deux photos : adresse, badge ou document peuvent rester visibles. Elles seront enregistrées uniquement sur cet appareil, dans la démo.")
            PrimaryButton(if (busy) "Sauvegarde chiffrée…" else "Enregistrer mon Moment · local", enabled = !busy, onClick = onPublish)
            TextButton(onClick = { rear = null; onDraft(null) }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("Reprendre les deux photos", color = Muted) }
        }
        !granted -> {
            InfoCard("Les deux côtés de ton quotidien.", "Moment a besoin de la caméra pour prendre la scène, puis ton selfie. Aucun accès à ta galerie ou à ta position.")
            PrimaryButton("Autoriser la caméra", onClick = { permission.launch(Manifest.permission.CAMERA) })
            if (requested) {
                Text("Permission refusée ? Tu peux l’activer dans les réglages et revenir ici.", color = Muted, fontSize = 13.sp)
                TextButton(onClick = {
                    context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:${context.packageName}".toUri()))
                }, modifier = Modifier.fillMaxWidth()) { Text("Ouvrir les réglages") }
            }
        }
        else -> {
            val front = rear != null
            Text(if (front) "02 / 02 · Et maintenant, toi." else "01 / 02 · Ce qui t’entoure.", color = Mint, fontSize = 14.sp)
            Text("La scène puis le selfie : deux prises successives.", color = Muted, fontSize = 13.sp)
            key(front) {
                CameraViewport(front = front, onPhoto = { photo ->
                    if (!front) rear = photo
                    else rear?.let { onDraft(PhotoPair(it, photo)); rear = null }
                })
            }
            if (front) TextButton(onClick = { rear = null }, modifier = Modifier.fillMaxWidth()) { Text("Recommencer la scène", color = Muted) }
        }
    }
}

@Composable
private fun CameraViewport(front: Boolean, onPhoto: (ByteArray) -> Unit) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val onPhotoLatest by rememberUpdatedState(onPhoto)
    val previewView = remember { PreviewView(context).apply {
        implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        scaleType = PreviewView.ScaleType.FIT_CENTER
    } }
    var capture by remember { mutableStateOf<ImageCapture?>(null) }
    var streaming by remember { mutableStateOf(false) }
    var taking by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var retry by remember { mutableIntStateOf(0) }
    val live = remember(retry) { AtomicBoolean(true) }
    LaunchedEffect(retry, streaming) {
        if (!streaming) {
            delay(12_000)
            if (!streaming && error == null) error = "La caméra ne répond pas. Ferme les autres apps caméra, puis réessaie."
        }
    }
    DisposableEffect(owner, retry) {
        live.set(true)
        error = null; streaming = false; taking = false
        val disposed = AtomicBoolean(false)
        val executor = ContextCompat.getMainExecutor(context)
        val manager = context.getSystemService(CameraManager::class.java)
        val lenses = runCatching { manager.cameraIdList.map { manager.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) }.toSet() }.getOrDefault(emptySet())
        val bothCameras = CameraCharacteristics.LENS_FACING_FRONT in lenses && CameraCharacteristics.LENS_FACING_BACK in lenses
        val future = if (bothCameras) ProcessCameraProvider.getInstance(context) else null
        if (!bothCameras) error = "Moment nécessite une caméra arrière et une caméra avant. Les deux ne sont pas disponibles sur cet appareil."
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
                    preview.setSurfaceProvider(previewView.surfaceProvider)
                    cameraProvider.bindToLifecycle(owner, selector, preview, imageCapture)
                    capture = imageCapture
                } catch (_: Exception) {
                    error = if (front) "La caméra avant est indisponible. Vérifie qu’aucune autre app ne l’utilise."
                        else "La caméra est indisponible. Vérifie la permission et réessaie."
                }
            }
        }, executor)
        onDispose {
            disposed.set(true); live.set(false); capture = null
            previewView.previewStreamState.removeObserver(observer)
            provider?.unbind(preview, imageCapture)
        }
    }
    Box(Modifier.fillMaxWidth().height(360.dp).clip(Shape).background(Panel), contentAlignment = Alignment.Center) {
        AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
        if ((!streaming || taking) && error == null) CircularProgressIndicator(Modifier.size(30.dp), color = Mint, strokeWidth = 2.dp)
    }
    error?.let {
        InfoCard("On réessaie ?", it)
        TextButton(onClick = { retry++ }, modifier = Modifier.fillMaxWidth()) { Text("Réessayer la caméra") }
    }
    PrimaryButton(if (taking) "Préparation de la photo…" else if (front) "Prendre mon selfie" else "Capturer la scène",
        enabled = streaming && !taking && capture != null && error == null, onClick = {
            val camera = capture ?: return@PrimaryButton
            taking = true
            camera.targetRotation = previewView.display?.rotation ?: android.view.Surface.ROTATION_0
            camera.takePicture(Dispatchers.IO.asExecutor(), object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: ImageProxy) {
                    val result = runCatching {
                        val bitmap = image.toBitmap()
                        try { PhotoSanitizer.clean(bitmap, image.imageInfo.rotationDegrees, front, image.cropRect) }
                        finally { bitmap.recycle() }
                    }
                    image.close()
                    ContextCompat.getMainExecutor(context).execute {
                        if (live.get()) {
                            taking = false
                            result.onSuccess { onPhotoLatest(it) }.onFailure { error = "La photo n’a pas pu être préparée. Reprends-la pour continuer." }
                        }
                    }
                }
                override fun onError(exception: ImageCaptureException) {
                    ContextCompat.getMainExecutor(context).execute {
                        if (live.get()) { taking = false; error = "La capture a été interrompue. Tu peux réessayer." }
                    }
                }
            })
        })
}

@Composable
internal fun PhotoPairView(pair: PhotoPair, modifier: Modifier = Modifier) {
    var selfieLarge by remember(pair) { mutableStateOf(false) }
    val images by produceState<Pair<androidx.compose.ui.graphics.ImageBitmap, androidx.compose.ui.graphics.ImageBitmap>?>(null, pair) {
        value = withContext(Dispatchers.Default) {
            val rear = android.graphics.BitmapFactory.decodeByteArray(pair.rear, 0, pair.rear.size)
            val front = android.graphics.BitmapFactory.decodeByteArray(pair.front, 0, pair.front.size)
            if (rear == null || front == null) null else rear.asImageBitmap() to front.asImageBitmap()
        }
    }
    Box(modifier.clip(Shape).background(Panel), contentAlignment = Alignment.Center) {
        images?.let { (rear, front) ->
            Image(if (selfieLarge) front else rear, if (selfieLarge) "Ton selfie" else "Ta photo de la scène",
                Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
            Image(if (selfieLarge) rear else front, if (selfieLarge) "Agrandir la scène" else "Agrandir le selfie",
                Modifier.align(Alignment.TopStart).padding(14.dp).size(92.dp, 122.dp)
                .clip(androidx.compose.foundation.shape.RoundedCornerShape(18.dp))
                .background(Panel).clickable(role = Role.Button) { selfieLarge = !selfieLarge }
                .border(2.dp, White, androidx.compose.foundation.shape.RoundedCornerShape(18.dp)), contentScale = ContentScale.Fit)
            Box(Modifier.align(Alignment.BottomEnd).padding(14.dp)) {
                Text("Sur cet appareil", Modifier.background(Panel, Shape).padding(9.dp), color = White, fontSize = 10.sp, fontWeight = FontWeight.Medium)
            }
        } ?: CircularProgressIndicator(Modifier.size(24.dp), color = Mint, strokeWidth = 2.dp)
    }
}
