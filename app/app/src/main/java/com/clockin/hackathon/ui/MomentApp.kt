package com.clockin.hackathon.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.clockin.hackathon.demo.DemoSession
import com.clockin.hackathon.capture.MomentModel
import com.clockin.hackathon.capture.PhotoPair
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

internal val Ink = Color(0xFF080A0C)
internal val Panel = Color(0xFF15181C)
internal val Muted = Color(0xFFA1A6AF)
internal val Mint = Color(0xFF8AEAC5)
internal val Purple = Color(0xFFC2B5F5)
internal val Line = Color(0xFF292D33)
internal val White = Color(0xFFF4F3EF)
internal val Shape = RoundedCornerShape(28.dp)
private fun money(value: Long) = String.format(Locale.FRANCE, "%.2f", value / 100.0)
private fun countdown(seconds: Long): String {
    val s = seconds.coerceAtLeast(0)
    return "%02d:%02d:%02d".format(s / 3600, s % 3600 / 60, s % 60)
}

@Composable
fun MomentApp(wallet: String?, connecting: Boolean, walletError: String?, connect: () -> Unit, model: MomentModel) {
    var entered by rememberSaveable { mutableStateOf(false) }
    var page by rememberSaveable { mutableStateOf("feed") }
    val session = model.snapshot.session
    LaunchedEffect(model.ready) { if (model.ready && session.faucetClaimed) entered = true }
    var now by remember { mutableLongStateOf(Instant.now().epochSecond) }
    var confirmExit by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) { while (true) { now = Instant.now().epochSecond; delay(1000) } }
    val state = session.settle(now)
    val posted = state.lastCheckIn == now / DemoSession.DAY
    val unlocked = posted && state.active && state.staked >= DemoSession.MIN_STAKE &&
        (state.exitUnlock == 0L || now < state.exitUnlock)
    BackHandler(entered && page != "feed") { page = "feed" }
    MaterialTheme(colorScheme = darkColorScheme(primary = Mint, secondary = Purple,
        background = Ink, surface = Panel, onPrimary = Ink, onSurface = White, onBackground = White),
        typography = Typography(
            bodyLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 16.sp, lineHeight = 24.sp),
            bodyMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 14.sp, lineHeight = 21.sp),
            labelLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 15.sp, fontWeight = FontWeight.Medium)
        )) {
        Surface(color = Ink, modifier = Modifier.fillMaxSize()) {
            if (!model.ready) {
                Column(Modifier.fillMaxSize().safeDrawingPadding().padding(28.dp), verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally) {
                    if (model.error == null) CircularProgressIndicator(color = Mint)
                    else {
                        InfoCard("Tes Moments restent sur cet appareil.", model.error!!)
                        Spacer(Modifier.height(20.dp))
                        PrimaryButton("Réessayer", enabled = !model.busy, onClick = model::reload)
                    }
                }
            } else {
            AnimatedContent(targetState = entered, transitionSpec = {
                fadeIn(tween(300)) togetherWith fadeOut(tween(180))
            }, label = "welcome") { isEntered ->
            if (!isEntered) {
                Welcome(wallet, connecting, walletError, connect) { entered = true }
            } else {
                Scaffold(containerColor = Ink, contentWindowInsets = WindowInsets.safeDrawing,
                    bottomBar = { BottomBar(page) { page = it } }) { padding ->
                    Column(Modifier.fillMaxSize().padding(padding)) {
                        Box(Modifier.padding(horizontal = 26.dp, vertical = 14.dp)) { Header() }
                        AnimatedContent(targetState = page, modifier = Modifier.weight(1f),
                            transitionSpec = {
                                (fadeIn(tween(260, delayMillis = 60)) + slideInVertically(tween(320)) { it / 35 }) togetherWith
                                    fadeOut(tween(120))
                            }, label = "navigation") { currentPage ->
                        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                            .padding(horizontal = 26.dp).padding(top = 18.dp, bottom = 24.dp),
                            verticalArrangement = Arrangement.spacedBy(24.dp)) {
                        when (currentPage) {
                            "feed" -> Feed(state, now, unlocked, posted,
                                photos = model.snapshot.post?.takeIf { it.day == now / DemoSession.DAY }?.photos,
                                onCapture = { page = if (state.active && state.staked >= DemoSession.MIN_STAKE) "capture" else "profile" },
                                onProfile = { page = "profile" })
                            "capture" -> Capture(state, now, posted, model, onProfile = { page = "profile" },
                                onPublish = { model.publish { page = "feed" } })
                            "profile" -> Profile(state, now, wallet, connecting, walletError, connect,
                                onFaucet = { model.update { it.faucet() } },
                                onStake = { amount -> model.update { it.stake(amount, Instant.now().epochSecond) } },
                                onExit = { confirmExit = true },
                                onCancel = { model.update { it.cancelExit(Instant.now().epochSecond) } },
                                onWithdraw = { model.update { it.withdraw(Instant.now().epochSecond) } })
                        }
                        }
                    }
                    }
                }
            }
            }
            }
            if (model.ready && model.error != null) AlertDialog(onDismissRequest = model::dismissError,
                title = { Text("Le Moment n’est pas perdu.") }, text = { Text(model.error!!) },
                confirmButton = { TextButton(onClick = model::dismissError) { Text("Compris") } })
            if (confirmExit) AlertDialog(onDismissRequest = { confirmExit = false },
                containerColor = Panel, title = { Text("Lancer les 48 heures ?") },
                text = { Text("Dans cette démo, ta mise reste active pendant l’attente. Continue tes check-ins : chaque jour UTC manqué réduit le solde de 25 %. Ce taux est provisoire.") },
                confirmButton = { TextButton(onClick = {
                    model.update { it.requestExit(Instant.now().epochSecond) }; confirmExit = false
                }) { Text("Demander la sortie") } },
                dismissButton = { TextButton(onClick = { confirmExit = false }) { Text("Rester") } })
        }
    }
}

@Composable
private fun Welcome(wallet: String?, connecting: Boolean, error: String?, connect: () -> Unit, enter: () -> Unit) {
    Column(Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState())
        .padding(horizontal = 28.dp, vertical = 20.dp), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(24.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically) {
            Wordmark()
            Pill("Pour les Seekers", Purple)
        }
        Spacer(Modifier.height(12.dp))
        Text("La vie passe.\nGarde un Moment.", fontSize = 41.sp, lineHeight = 46.sp,
            fontWeight = FontWeight.SemiBold, letterSpacing = (-1.8).sp, textAlign = TextAlign.Center)
        Text("Toi. Ton quotidien. Tes proches.\nUn instant sincère, chaque jour.",
            color = Muted, fontSize = 16.sp, lineHeight = 25.sp, textAlign = TextAlign.Center)
        Box(Modifier.fillMaxWidth().height(300.dp), contentAlignment = Alignment.Center) {
            Canvas(Modifier.matchParentSize()) {
                drawCircle(Brush.radialGradient(listOf(Purple.copy(alpha = .16f), Color.Transparent),
                    center = center, radius = size.maxDimension * .55f), size.maxDimension * .55f)
            }
            DualFrame(Modifier.fillMaxWidth().padding(horizontal = 8.dp).height(280.dp)
                .graphicsLayer { rotationZ = -3f }, caption = "Les deux côtés d’un même instant")
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(5.dp).background(Mint, CircleShape))
            Text("Deux caméras. Une fois par jour.", color = Muted, fontSize = 13.sp)
        }
        PrimaryButton("Découvrir Moment", onClick = enter)
        TextButton(onClick = connect, enabled = !connecting && wallet == null,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
            if (connecting) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = Mint)
                Spacer(Modifier.width(10.dp))
            }
            Text(if (wallet != null) "Wallet connecté · ${wallet.take(4)}…${wallet.takeLast(4)}"
                else if (connecting) "Connexion…" else "Connecter mon wallet", color = Muted)
        }
        if (error != null) Text(error, color = Purple, fontSize = 13.sp)
        Text("Caméra réelle · Sauvegarde locale · SKR fictifs", color = Muted,
            fontSize = 11.sp, textAlign = TextAlign.Center)
    }
}

@Composable
private fun Feature(number: String, title: String, subtitle: String) {
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Text(number, color = Mint, fontSize = 10.sp)
        Text(title, fontWeight = FontWeight.Bold, fontSize = 15.sp)
        Text(subtitle, color = Muted, fontSize = 11.sp)
    }
}

@Composable
private fun Feed(state: DemoSession, now: Long, unlocked: Boolean, posted: Boolean, photos: PhotoPair?, onCapture: () -> Unit, onProfile: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Text(DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.FRANCE).withZone(ZoneOffset.UTC)
            .format(Instant.ofEpochSecond(now)).uppercase(Locale.FRANCE) + " · UTC", color = Muted, fontSize = 10.sp,
            letterSpacing = 1.5.sp, fontWeight = FontWeight.Medium)
        Text(if (unlocked) "Tu y es." else "Un jour.\nUn nouveau Moment.",
            fontSize = 34.sp, lineHeight = 37.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-1).sp)
    }
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Panel).padding(17.dp),
        horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(if (posted) "À demain" else "Prends ton temps", color = Mint, fontSize = 10.sp, letterSpacing = 1.sp)
            Text(if (posted) "Ton Moment est enregistré · local" else "Clôture dans ${countdown(DemoSession.DAY - now % DemoSession.DAY)}",
                fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
        }
        Glyph(if (posted) "check" else "clock", Mint)
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text("Les Seekers", fontSize = 19.sp, fontWeight = FontWeight.Bold)
        Text("Aujourd’hui", color = Muted, fontSize = 10.sp, letterSpacing = 1.sp)
    }
    if (unlocked) {
        PostCard("toi", "Aujourd’hui · sur cet appareil", "Un petit moment. Une bonne habitude.", true, photos)
        PostCard("maya.skr", "Membre fictif · illustration", "Prendre le temps de lever les yeux.", false)
        Text("Tu as fait le tour. À demain, dans la vraie vie.", color = Muted, fontSize = 12.sp)
    } else {
        Box(Modifier.fillMaxWidth().heightIn(min = 330.dp).clip(Shape).background(Panel)) {
            Landscape(Modifier.matchParentSize())
            Box(Modifier.matchParentSize().background(Ink.copy(alpha = .82f)))
            Column(Modifier.align(Alignment.Center).fillMaxWidth().padding(horizontal = 24.dp).padding(top = 32.dp, bottom = 56.dp), horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Box(Modifier.size(54.dp).clip(CircleShape).background(Mint.copy(alpha = .10f)), contentAlignment = Alignment.Center) {
                    Glyph("lock", Mint)
                }
                Text("Un Moment à partager.", fontWeight = FontWeight.Bold, fontSize = 19.sp, textAlign = TextAlign.Center)
                Text("Partage un instant de ta journée\npour découvrir celui des autres.", color = Muted,
                    fontSize = 14.sp, lineHeight = 21.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                PrimaryButton(if (state.active && state.staked >= DemoSession.MIN_STAKE) "Partager mon Moment" else "Préparer ma mise démo", onClick = onCapture)
            }
            Text("Illustration · aperçu de la démo", Modifier.align(Alignment.BottomCenter).padding(13.dp), color = Muted, fontSize = 9.sp, letterSpacing = 2.sp)
        }
    }
    Row(Modifier.fillMaxWidth().clickable(onClick = onProfile).padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween) {
        Text("${state.streak} jour${if (state.streak > 1) "s" else ""} de série", color = Purple, fontSize = 13.sp)
        Text("${money(state.staked)} SKR démo  ↗", color = Muted, fontSize = 13.sp)
    }
}

@Composable
private fun Capture(state: DemoSession, now: Long, posted: Boolean, model: MomentModel,
    onProfile: () -> Unit, onPublish: () -> Unit) {
    Text("Ton Moment.", fontSize = 34.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-1).sp)
    Text("Deux points de vue. Un seul souvenir.", color = Muted, fontSize = 15.sp)
    when {
        posted -> {
            model.snapshot.post?.photos?.let { PhotoPairView(it, Modifier.fillMaxWidth().height(360.dp)) }
            InfoCard("C’est fait pour aujourd’hui.", "Ton Moment est sauvegardé sur cet appareil. Prochain rendez-vous à 00:00 UTC.")
        }
        state.exitUnlock > 0 && now >= state.exitUnlock -> {
            InfoCard("Ta sortie est prête.", "Retire ta mise démo depuis ton profil avant de commencer une nouvelle position.")
            PrimaryButton("Voir mon profil", onClick = onProfile)
        }
        !state.active || state.staked < DemoSession.MIN_STAKE -> {
            InfoCard("Une petite mise pour commencer.", "Il faut au moins 10 SKR fictifs pour participer à la démo.")
            PrimaryButton("Préparer ma mise", onClick = onProfile)
        }
        else -> CameraCapture(model.draft, model.busy, model::replaceDraft, onPublish)
    }
}

@Composable
private fun Profile(state: DemoSession, now: Long, wallet: String?, connecting: Boolean, error: String?, connect: () -> Unit,
    onFaucet: () -> Unit, onStake: (Long) -> Unit, onExit: () -> Unit, onCancel: () -> Unit, onWithdraw: () -> Unit) {
    var amount by rememberSaveable { mutableLongStateOf(5_000) }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(15.dp)) {
        Box(Modifier.size(60.dp).clip(RoundedCornerShape(20.dp)).background(Brush.linearGradient(listOf(Purple, Mint))), contentAlignment = Alignment.Center) {
            Text("S", color = Ink, fontWeight = FontWeight.Black, fontSize = 28.sp)
        }
        Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text("À ton rythme.", fontSize = 26.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-.5).sp)
            Text("Seeker, un jour à la fois.", color = Muted, fontSize = 13.sp)
        }
    }
    Column(Modifier.fillMaxWidth().clip(Shape).background(Brush.linearGradient(listOf(Color(0xFF1B2B29), Panel)))
        .border(1.dp, Mint.copy(alpha = .12f), Shape).padding(23.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Solde démo", color = Mint, fontSize = 10.sp, letterSpacing = 2.sp)
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(money(state.staked), fontSize = 43.sp, fontWeight = FontWeight.Bold, letterSpacing = (-2).sp)
            Text("SKR", Modifier.padding(bottom = 8.dp), color = Mint, fontWeight = FontWeight.Medium)
        }
        HorizontalDivider(color = Mint.copy(alpha = .15f))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Feature("Ta série", "${state.streak} jour${if (state.streak > 1) "s" else ""}", "Série en cours")
            Feature("Tes instants", "${state.total} instant${if (state.total > 1) "s" else ""}", "Moments partagés")
        }
    }
    Text("Ton wallet", fontSize = 19.sp, fontWeight = FontWeight.Bold)
    InfoCard(if (wallet == null) "Pas encore connecté" else "${wallet.take(6)}…${wallet.takeLast(6)}",
        "Connecte ton wallet Solana. Les fonds de cette démo restent fictifs.")
    if (wallet == null) PrimaryButton(if (connecting) "Connexion…" else "Connecter mon wallet", enabled = !connecting, onClick = connect)
    if (error != null) Text(error, color = Purple, fontSize = 13.sp)
    if (!state.faucetClaimed) {
        InfoCard("Bienvenue dans le cercle.", "Récupère 100 SKR fictifs, puis choisis ta mise. Aucun frais et aucune signature.")
        PrimaryButton("Recevoir 100 SKR démo", onClick = onFaucet)
    } else if (state.exitUnlock == 0L) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(if (state.active) "Renforcer ma mise" else "Choisir ma mise", fontWeight = FontWeight.Bold, fontSize = 18.sp)
            Text("${money(state.available)} disponibles", color = Muted, fontSize = 11.sp)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            listOf(1_000L, 5_000L, 10_000L).forEach { choice ->
                val selected = amount == choice
                Box(Modifier.weight(1f).clip(RoundedCornerShape(14.dp))
                    .background(if (selected) Mint.copy(alpha = .12f) else Panel)
                    .border(1.dp, if (selected) Mint else Line, RoundedCornerShape(14.dp))
                    .clickable { amount = choice }.padding(vertical = 16.dp), contentAlignment = Alignment.Center) {
                    Text("${choice / 100} SKR", color = if (selected) Mint else White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
        PrimaryButton(if (amount > state.available) "Solde démo insuffisant" else "Miser ${amount / 100} SKR démo",
            enabled = amount <= state.available, onClick = { onStake(amount) })
    }
    InfoCard("La régularité compte.", "Règles provisoires de la démo : −25 % par jour UTC manqué. Récompense de 1 % par check-in, plafonnée à 1 SKR et au pool disponible. Sortie après 48 h.")
    if (state.exitUnlock > 0) {
        val ready = now >= state.exitUnlock
        InfoCard(if (ready) "Ta mise est disponible." else "Sortie dans ${countdown(state.exitUnlock - now)}",
            "Déblocage le " + DateTimeFormatter.ofPattern("dd MMM à HH:mm 'UTC'", Locale.FRANCE).withZone(ZoneOffset.UTC)
                .format(Instant.ofEpochSecond(state.exitUnlock)) +
                if (ready) ". Aucun decay supplémentaire après le déblocage." else ". Continue à publier pour éviter les pertes pendant l’attente.")
        PrimaryButton(if (ready) "Retirer ${money(state.staked)} SKR démo" else "Annuler la sortie", onClick = if (ready) onWithdraw else onCancel)
    } else if (state.active) {
        OutlinedButton(onClick = onExit, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
            Text("Demander ma sortie · 48 h", Modifier.padding(7.dp), color = White)
        }
    }
}

@Composable
private fun Header() {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Wordmark()
        Pill("Démo · aucun token réel", Muted)
    }
}
@Composable private fun Wordmark() {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
        Canvas(Modifier.size(24.dp)) {
            drawCircle(Brush.linearGradient(listOf(Mint, Purple)), size.width * .38f,
                style = Stroke(size.width * .12f))
            drawCircle(Ink, size.width * .14f, Offset(size.width * .78f, size.height * .23f))
            drawCircle(Mint, size.width * .10f, Offset(size.width * .78f, size.height * .23f))
        }
        Text("Moment", fontSize = 24.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-.8).sp)
    }
}
@Composable private fun Pill(text: String, color: Color) {
    Text(text, Modifier.clip(CircleShape).background(color.copy(alpha = .07f)).padding(horizontal = 11.dp, vertical = 7.dp),
        color = color, fontSize = 10.sp, fontWeight = FontWeight.Medium)
}
@Composable internal fun PrimaryButton(text: String, enabled: Boolean = true, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) .975f else 1f,
        animationSpec = spring(dampingRatio = .8f, stiffness = 700f), label = "buttonPress")
    val haptic = LocalHapticFeedback.current
    var lastClick by remember { mutableLongStateOf(0L) }
    Button(onClick = {
        val time = android.os.SystemClock.elapsedRealtime()
        if (time - lastClick > 450) {
            lastClick = time
            haptic.performHapticFeedback(HapticFeedbackType.Confirm)
            onClick()
        }
    },
        enabled = enabled, interactionSource = interaction,
        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).graphicsLayer { scaleX = scale; scaleY = scale },
        shape = CircleShape, colors = ButtonDefaults.buttonColors(containerColor = White, contentColor = Ink)) {
        Text(text, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
    }
}
@Composable internal fun InfoCard(title: String, body: String) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Panel).padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Text(title, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
        Text(body, color = Muted, fontSize = 13.sp, lineHeight = 20.sp)
    }
}
@Composable private fun BottomBar(page: String, navigate: (String) -> Unit) {
    val haptic = LocalHapticFeedback.current
    Box(Modifier.fillMaxWidth().background(Ink).navigationBarsPadding().padding(horizontal = 24.dp, vertical = 10.dp)) {
        Row(Modifier.fillMaxWidth().height(70.dp).clip(CircleShape)
            .background(Brush.verticalGradient(listOf(Color(0xFF22262C), Panel)))
            .border(.5.dp, White.copy(alpha = .12f), CircleShape).padding(6.dp).selectableGroup(),
            horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
            listOf(Triple("feed", "grid", "Le cercle"), Triple("capture", "camera", "Capturer"), Triple("profile", "person", "Moi")).forEach { (key, icon, label) ->
                val selected = page == key
                val background by animateColorAsState(if (selected) White.copy(alpha = .10f) else Color.Transparent,
                    tween(220), label = "tabSurface")
                val foreground by animateColorAsState(if (selected) Mint else Muted, tween(220), label = "tabColor")
                Column(Modifier.weight(1f).fillMaxHeight().clip(CircleShape).background(background)
                    .selectable(selected = selected, role = Role.Tab, onClick = {
                        if (!selected) { haptic.performHapticFeedback(HapticFeedbackType.SegmentTick); navigate(key) }
                    }), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                    Glyph(icon, foreground)
                    Spacer(Modifier.height(4.dp))
                    Text(label, color = foreground, fontSize = 10.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
                }
            }
        }
    }
}
@Composable private fun PostCard(name: String, subtitle: String, caption: String, self: Boolean, photos: PhotoPair? = null) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.size(36.dp).clip(CircleShape).background(if (self) Mint else Purple), contentAlignment = Alignment.Center) {
                Text(name.take(1).uppercase(), color = Ink, fontWeight = FontWeight.Bold)
            }
            Column(Modifier.weight(1f)) {
                Text(name, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                Text(subtitle, color = Muted, fontSize = 10.sp)
            }
            Pill("Démo", if (self) Mint else Purple)
        }
        if (photos != null) PhotoPairView(photos, Modifier.fillMaxWidth().height(340.dp))
        else DualFrame(Modifier.fillMaxWidth().height(340.dp), caption = "Un instant, deux regards", alternate = !self)
        Text(caption, fontSize = 14.sp)
        Spacer(Modifier.height(6.dp))
    }
}

/** Deliberately illustrative assets: no network, gallery import or camera access in the demo. */
@Composable private fun DualFrame(modifier: Modifier, caption: String, alternate: Boolean = false) {
    Box(modifier.clip(Shape).background(Panel)) {
        Landscape(Modifier.fillMaxSize(), alternate)
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Ink.copy(alpha = .55f)))))
        Box(Modifier.padding(15.dp).size(86.dp, 112.dp).clip(RoundedCornerShape(16.dp)).border(2.dp, White, RoundedCornerShape(16.dp))) {
            Portrait(Modifier.fillMaxSize())
            Text("AVANT", Modifier.align(Alignment.BottomCenter).padding(8.dp), color = White, fontSize = 7.sp, letterSpacing = 1.sp)
        }
        Text(caption, Modifier.align(Alignment.BottomStart).padding(19.dp), color = White, fontSize = 9.sp,
            fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
        Box(Modifier.align(Alignment.TopEnd).padding(15.dp)) { Pill("Illustration", White) }
    }
}
@Composable private fun Landscape(modifier: Modifier, alternate: Boolean = false) {
    Canvas(modifier.semantics { contentDescription = "Illustration de montagnes au coucher du soleil, exemple de caméra arrière" }) {
        val w = size.width; val h = size.height
        drawRect(Brush.verticalGradient(if (alternate) listOf(Color(0xFF8170B4), Color(0xFFEEBB9B), Color(0xFF334C4E))
            else listOf(Color(0xFF617D91), Color(0xFFE1B998), Color(0xFF45665D))))
        drawCircle(Color(0xFFFFDAB0), w * .10f, Offset(w * .73f, h * .28f))
        fun ridge(color: Color, points: List<Pair<Float, Float>>) {
            drawPath(Path().apply { moveTo(0f, h); points.forEach { (x, y) -> lineTo(x * w, y * h) }; lineTo(w, h); close() }, color)
        }
        ridge(Color(0xFF627977), listOf(0f to .55f, .16f to .39f, .28f to .48f, .54f to .30f, .7f to .46f, 1f to .37f))
        ridge(Color(0xFF355B55), listOf(0f to .62f, .25f to .48f, .5f to .69f, .75f to .53f, 1f to .66f))
        ridge(Color(0xFF193E37), listOf(0f to .73f, .2f to .8f, .46f to .67f, .78f to .78f, 1f to .63f))
        val trail = Path().apply { moveTo(w * .45f, h); cubicTo(w * .2f, h * .84f, w * .85f, h * .81f, w * .59f, h * .70f) }
        drawPath(trail, Color(0xFFB1AA81), style = Stroke(w * .04f, cap = StrokeCap.Round))
        for (i in 0..13) {
            val x = w * (i / 13f); val y = h * (.83f + (i % 3) * .04f)
            val tree = Path().apply { moveTo(x, y - h * .12f); lineTo(x - w * .035f, y); lineTo(x + w * .035f, y); close() }
            drawPath(tree, Color(0xFF102D29))
        }
    }
}
@Composable private fun Portrait(modifier: Modifier) {
    Canvas(modifier.semantics { contentDescription = "Portrait illustré, exemple de caméra avant" }) {
        val w = size.width; val h = size.height
        drawRect(Brush.verticalGradient(listOf(Color(0xFFABA4D4), Color(0xFF5A6582))))
        drawOval(Color(0xFF253B39), Offset(-w * .1f, h * .62f), Size(w * 1.2f, h * .6f))
        drawRoundRect(Color(0xFFBE8668), Offset(w * .4f, h * .5f), Size(w * .2f, h * .22f), androidx.compose.ui.geometry.CornerRadius(8f))
        drawOval(Color(0xFFDAA383), Offset(w * .24f, h * .20f), Size(w * .53f, h * .40f))
        drawArc(Color(0xFF252524), 170f, 210f, true, Offset(w * .20f, h * .12f), Size(w * .59f, h * .35f))
        drawLine(Ink, Offset(w * .34f, h * .37f), Offset(w * .42f, h * .37f), 3f)
        drawLine(Ink, Offset(w * .58f, h * .37f), Offset(w * .66f, h * .37f), 3f)
        drawArc(Color(0xFF805846), 0f, 160f, false, Offset(w * .43f, h * .44f), Size(w * .14f, h * .07f), style = Stroke(2f))
    }
}
@Composable private fun Glyph(kind: String, color: Color) {
    Canvas(Modifier.size(24.dp)) {
        scale(size.width / 24f, size.height / 24f, pivot = Offset.Zero) {
            val stroke = Stroke(1.7f, cap = StrokeCap.Round)
            fun line(x: Float, y: Float, x2: Float, y2: Float) = drawLine(color, Offset(x,y), Offset(x2,y2), 1.7f, StrokeCap.Round)
            when (kind) {
                "grid" -> for (x in listOf(3f, 14f)) for (y in listOf(3f, 14f)) drawRoundRect(color, Offset(x,y), Size(7f,7f), androidx.compose.ui.geometry.CornerRadius(2f), style = stroke)
                "camera" -> {
                    drawRoundRect(color, Offset(2f,6f), Size(20f,15f), androidx.compose.ui.geometry.CornerRadius(3f), style = stroke)
                    drawCircle(color, 4f, Offset(12f,13f), style = stroke)
                    line(8f,6f,9f,3f); line(9f,3f,15f,3f); line(15f,3f,16f,6f)
                }
                "person" -> {
                    drawCircle(color,4f,Offset(12f,7f),style=stroke)
                    drawArc(color,180f,180f,false,Offset(4f,14f),Size(16f,14f),style=stroke)
                }
                "clock" -> { drawCircle(color,9f,Offset(12f,12f),style=stroke); line(12f,7f,12f,12f); line(12f,12f,16f,14f) }
                "check" -> { line(5f,12f,10f,17f); line(10f,17f,20f,6f) }
                "lock" -> {
                    drawRoundRect(color,Offset(4f,10f),Size(16f,12f),androidx.compose.ui.geometry.CornerRadius(3f),style=stroke)
                    drawArc(color,180f,180f,false,Offset(7f,2f),Size(10f,16f),style=stroke)
                    line(12f,15f,12f,18f)
                }
            }
        }
    }
}
