package com.clockin.hackathon.ui

import com.clockin.hackathon.i18n.Message
import com.clockin.hackathon.i18n.tr
import com.clockin.hackathon.i18n.AppLanguage

import android.Manifest
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.AccountBalanceWallet
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.NotificationsNone
import androidx.compose.material.icons.outlined.PersonOutline
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationManagerCompat
import com.clockin.hackathon.ChainState

/**
 * Le parcours d'entrée, dans l'ordre de la maquette.
 *
 * `Splash` et `Langue` précèdent le compteur ; les huit suivantes l'alimentent,
 * ce que dit [OnboardingStep.number]. Les quatre premières expliquent le jeu
 * sans rien engager ; les quatre dernières engagent — conditions acceptées,
 * wallet connecté, mise en jeu, autorisations accordées — et chacune attend
 * son effet réel avant de laisser passer.
 */
internal enum class OnboardingStep {
    Splash, Langue, Moment, Cercle, Mise, Pool, Conditions, Wallet, Stake, Permissions, Start;

    /**
     * Rang dans la barre de progression, ou `null` pour les écrans qui
     * l'encadrent : l'accueil et la langue la précèdent, « Ta mise est en
     * jeu » la suit — le parcours y est déjà fini.
     */
    val number: Int? get() =
        if (ordinal in Moment.ordinal..Permissions.ordinal) ordinal - Moment.ordinal + 1 else null

    companion object { const val COUNT = 8 }
}

@Composable
internal fun Onboarding(
    wallet: String?,
    state: ChainState,
    now: Long,
    busy: Boolean,
    error: String?,
    resumeAtStake: Boolean,
    onConnect: () -> Unit,
    onStake: (Long) -> Unit,
    onFaucet: () -> Unit,
    onDisconnect: () -> Unit,
    /** `openCapture` demande à ouvrir la caméra dans la foulée. */
    onFinish: (openCapture: Boolean) -> Unit,
) {
    val context = LocalContext.current
    val device = AppLanguage.deviceCode
    var step by rememberSaveable {
        mutableStateOf(if (resumeAtStake) OnboardingStep.Stake else OnboardingStep.Splash)
    }
    var accepted by rememberSaveable { mutableStateOf(false) }
    var reading by rememberSaveable { mutableStateOf<String?>(null) }
    var permissionsAsked by rememberSaveable { mutableStateOf(false) }
    var staking by rememberSaveable { mutableStateOf(false) }

    fun go(next: OnboardingStep) { step = next }

    /**
     * Une étape déjà obtenue ne se rejoue pas. La mise n'en fait pas partie :
     * l'écran se montre même quand elle existe déjà, pour la dire et laisser
     * l'augmenter — c'est le seul endroit du parcours qui l'expose.
     */
    fun settled(candidate: OnboardingStep) = candidate == OnboardingStep.Wallet && wallet != null

    // Sans cette enjambée, revenir sur une étape déjà satisfaite la verrait
    // ré-avancer aussitôt par l'effet ci-dessous : le retour semblerait mort.
    fun back() {
        var target = step.ordinal - 1
        while (target > 0 && settled(OnboardingStep.entries[target])) target--
        if (target >= 0) step = OnboardingStep.entries[target]
    }

    // Chaque étape qui engage attend son effet avant d'avancer : la connexion
    // rend une adresse, la mise rend un profil actif. Tant que la chaîne n'a
    // rien confirmé, l'écran reste — c'est lui qui porte l'erreur.
    //
    // `step` fait partie des clés : sans lui, arriver sur l'étape avec le
    // wallet déjà connecté (parcours repris) ne relancerait rien et bloquerait.
    // La chaîne peut répondre après la première composition : l'initialisateur
    // de `step` a déjà tourné, cet effet rattrape le cas.
    LaunchedEffect(resumeAtStake) {
        if (resumeAtStake && step == OnboardingStep.Splash) go(OnboardingStep.Stake)
    }
    LaunchedEffect(wallet, step) { if (wallet != null && step == OnboardingStep.Wallet) go(OnboardingStep.Stake) }
    // `staking` distingue « la mise que je viens de signer a été prise » de
    // « une mise existait déjà en arrivant ». Sans lui, le second cas sauterait
    // l'écran 7 au lieu de l'afficher.
    LaunchedEffect(state.active, staking) {
        if (state.active && staking) { staking = false; go(OnboardingStep.Permissions) }
    }

    val permissions = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { go(OnboardingStep.Start) }
    val settings = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()) { go(OnboardingStep.Start) }

    BackHandler(enabled = step != OnboardingStep.Splash || reading != null) {
        if (reading != null) reading = null else back()
    }

    reading?.let { document ->
        LegalText(document, onClose = { reading = null })
        return
    }

    AnimatedContent(
        targetState = step, label = "onboarding",
        transitionSpec = { fadeIn(tween(220, delayMillis = 60)) togetherWith fadeOut(tween(140)) },
    ) { current ->
        when (current) {
            OnboardingStep.Splash -> Splash(onStart = { go(OnboardingStep.Langue) })

            OnboardingStep.Langue -> StepScaffold(
                step = current, onBack = ::back,
                title = tr(Message.ChooseYourLanguage),
                body = tr(Message.YouCanChangeItLaterInSettings),
                titleFirst = true,
                illustration = {
                    LanguagePicker(AppLanguage.code, device, onSelect = AppLanguage::select)
                },
                actions = { PrimaryButton(tr(Message.Continue)) { go(OnboardingStep.Moment) } },
            )

            OnboardingStep.Moment -> StepScaffold(
                step = current, onBack = ::back,
                title = tr(Message.OneMomentADay),
                body = tr(Message.OnboardingCaptureExplanation),
                illustration = { MomentPreview() },
                actions = { PrimaryButton(tr(Message.Continue)) { go(OnboardingStep.Cercle) } },
            )

            OnboardingStep.Cercle -> StepScaffold(
                step = current, onBack = ::back,
                title = tr(Message.OneSharedCircle),
                body = tr(Message.OnboardingCircleExplanation),
                illustration = { CirclePreview() },
                actions = { PrimaryButton(tr(Message.Continue)) { go(OnboardingStep.Mise) } },
            )

            OnboardingStep.Mise -> StepScaffold(
                step = current, onBack = ::back,
                title = tr(Message.PlayWithAStake),
                body = tr(Message.OnboardingStakeExplanation, skr(state.minStake), decayPercent(state)),
                illustration = { StakePreview(state) },
                actions = { PrimaryButton(tr(Message.Continue)) { go(OnboardingStep.Pool) } },
            )

            OnboardingStep.Pool -> StepScaffold(
                step = current, onBack = ::back,
                title = tr(Message.PostAndGetYourShare),
                body = tr(Message.PenaltiesFormAPoolEveryDayThose),
                illustration = { PoolPreview() },
                actions = { PrimaryButton(tr(Message.Continue)) { go(OnboardingStep.Conditions) } },
            )

            OnboardingStep.Conditions -> StepScaffold(
                step = current, onBack = ::back,
                title = tr(Message.BeforeYouPlay),
                body = tr(Message.OnboardingTermsExplanation),
                titleFirst = true,
                illustration = {
                    Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
                        RuleRows(
                            Triple(Icons.Outlined.Lock, tr(Message.YourStake), tr(Message.ADayWithoutAMomentCosts, decayPercent(state))),
                            Triple(Icons.Outlined.Share, tr(Message.YourMoments), tr(Message.VisibleToEveryoneInTheCircle)),
                            Triple(Icons.Outlined.PersonOutline, tr(Message.YourData), tr(Message.YourWalletAddressIsVisibleOnYour)),
                        )
                        Row(Modifier.offset(x = (-12).dp)) {
                            TextButton(onClick = { reading = "conditions" }) { Text(tr(Message.ReadTheTerms), color = Accent) }
                            TextButton(onClick = { reading = "confidentialité" }) { Text(tr(Message.ReadThePrivacyPolicy), color = Accent) }
                        }
                    }
                },
                actions = {
                    AcceptCheckbox(accepted, onToggle = { accepted = it })
                    Spacer(Modifier.height(12.dp))
                    PrimaryButton(tr(Message.Continue), enabled = accepted) { go(OnboardingStep.Wallet) }
                },
            )

            OnboardingStep.Wallet -> StepScaffold(
                step = current, onBack = ::back,
                title = tr(Message.ConnectYourWallet),
                body = tr(Message.OnboardingWalletExplanation),
                error = error,
                illustration = { BadgeTile(Icons.Outlined.AccountBalanceWallet, tr(Message.Wallet)) },
                actions = {
                    PrimaryButton(
                        if (busy) "Connexion…" else tr(Message.ConnectMyWallet),
                        enabled = !busy, loading = busy, onClick = onConnect,
                    )
                    Text(tr(Message.NetworkFeesInSolAreShownBefore),
                        Modifier.fillMaxWidth().padding(top = 12.dp),
                        color = Muted, style = BodySm, textAlign = TextAlign.Center)
                },
            )

            OnboardingStep.Start -> FirstMoment(state, now, onFinish = onFinish)

            OnboardingStep.Stake -> StakeSheet(
                state = state, now = now, busy = busy, error = error,
                onBack = ::back,
                onStake = { staking = true; onStake(it) },
                onContinue = { go(OnboardingStep.Permissions) },
                onFaucet = onFaucet, onDisconnect = onDisconnect,
            )

            OnboardingStep.Permissions -> StepScaffold(
                step = current, onBack = ::back,
                title = tr(Message.TwoPermissions),
                body = tr(Message.OnboardingPermissionsExplanation),
                illustration = {
                    PermissionCards(
                        Triple(Icons.Outlined.PhotoCamera, tr(Message.Camera), tr(Message.ForYourPhotoAndSelfie)),
                        Triple(Icons.Outlined.NotificationsNone, tr(Message.Notifications), tr(Message.ToNotifyYouBeforeTheDayEnds)),
                    )
                },
                actions = {
                    PrimaryButton(if (permissionsAsked) tr(Message.OpenSettings) else tr(Message.Allow)) {
                        // Un refus définitif ne relance plus la demande : Android
                        // la rejette en silence. On envoie alors aux réglages.
                        if (permissionsAsked) {
                            settings.launch(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
                        } else {
                            permissionsAsked = true
                            permissions.launch(buildList {
                                add(Manifest.permission.CAMERA)
                                if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
                            }.toTypedArray())
                        }
                    }
                    if (permissionsAsked) TextButton(
                        onClick = { go(OnboardingStep.Start) },
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    ) { Text(tr(Message.ContinueWithoutPermissions), color = Muted) }
                },
            )
        }
    }
}

/** Le taux de pénalité, en pourcentage entier. */
private fun decayPercent(state: ChainState): Int = state.decayBps / 100

// ── Ossature commune ───────────────────────────────────────────────────────

/**
 * L'ossature que partagent les écrans du parcours : retour et progression en
 * haut, illustration au centre, titre et texte juste au-dessus des boutons.
 *
 * [titleFirst] inverse l'ordre pour les écrans qui listent — on lit alors la
 * consigne avant la liste, pas après.
 */
@Composable
private fun StepScaffold(
    step: OnboardingStep,
    onBack: () -> Unit,
    title: String,
    body: String,
    illustration: @Composable () -> Unit,
    actions: @Composable ColumnScope.() -> Unit,
    titleFirst: Boolean = false,
    error: String? = null,
) {
    Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding()) {
        Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 16.dp, top = 24.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = tr(Message.Back), tint = White)
            }
            step.number?.let { Progress(it, Modifier.weight(1f)) }
        }
        val heading = @Composable {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(title, style = DisplayMd)
                Text(body, color = Muted, style = BodyLg)
                if (error != null) Text(error, color = Danger, style = BodyMd)
            }
        }
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)) {
            if (titleFirst) {
                Spacer(Modifier.height(24.dp))
                heading()
                Spacer(Modifier.height(24.dp))
                illustration()
                Spacer(Modifier.height(24.dp))
            } else {
                Box(Modifier.weight(1f, fill = false).fillMaxWidth().padding(vertical = 32.dp),
                    contentAlignment = Alignment.Center) { illustration() }
                heading()
                Spacer(Modifier.height(32.dp))
            }
        }
        Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 24.dp),
            content = actions)
    }
}

/** Huit segments : pleins derrière soi, sourds devant. */
@Composable
private fun Progress(current: Int, modifier: Modifier = Modifier) {
    Row(modifier.semantics { contentDescription = tr(Message.StepOf, current, OnboardingStep.COUNT) },
        horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        repeat(OnboardingStep.COUNT) { index ->
            val done = index < current
            val color by animateColorAsState(if (done) Accent else SurfaceHigh, tween(300), label = "segment")
            Box(Modifier.weight(1f).height(4.dp).clip(CircleShape).background(color))
        }
    }
}

// ── Écrans ─────────────────────────────────────────────────────────────────

/** Premier écran vu : la marque, puis la promesse. Un seul geste avant d'entrer. */
@Composable
private fun Splash(onStart: () -> Unit) {
    Column(Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 16.dp)) {
        Spacer(Modifier.height(40.dp))
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            Box(Modifier.size(112.dp).clip(RoundedCornerShape(25.dp)).background(GradientBrand)
                .semantics { contentDescription = tr(Message.MomentLogo) }, contentAlignment = Alignment.Center) {
                Canvas(Modifier.size(56.dp)) {
                    drawCircle(OnBrand, size.minDimension * .34f, style = Stroke(size.minDimension * .11f))
                }
            }
        }
        Column(Modifier.padding(bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(tr(Message.OneMomentADay2), style = DisplayLg)
            Text(tr(Message.CaptureYourLifeShareItWithEveryone),
                color = Muted, style = BodyLg)
        }
        Column(Modifier.padding(bottom = 24.dp)) { PrimaryButton(tr(Message.GetStarted), onClick = onStart) }
    }
}

@Composable
internal fun LanguagePicker(selected: String, device: String, onSelect: (String) -> Unit) {
    Column(Modifier.fillMaxWidth().selectableGroup()
        .semantics { contentDescription = tr(Message.AppLanguage) },
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        AppLanguage.supported.forEach { code ->
            val name = if (code == "fr") "Français" else "English"
            val translated = if (code == "fr") tr(Message.French) else tr(Message.English)
            // L'étiquette suit l'appareil, elle ne se pose pas toujours au même endroit.
            val note = if (code == device) tr(Message.DeviceLanguage) else translated
            val on = code == selected
            Row(Modifier.fillMaxWidth().clip(RadiusLg)
                .background(if (on) AccentContainer else Panel)
                .selectable(on, role = Role.RadioButton) { onSelect(code) }
                .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f)) {
                    Text(name, style = TitleMd, color = if (on) OnAccentContainer else White)
                    Text(note, style = BodyMd, color = if (on) OnAccentContainer.copy(alpha = .7f) else Muted)
                }
                if (on) Icon(Icons.Outlined.Check, contentDescription = null, tint = OnAccentContainer)
            }
        }
    }
}

/** L'aperçu d'un moment : la scène, le selfie en médaillon, l'heure. */
@Composable
private fun MomentPreview() {
    Box(Modifier.width(240.dp).aspectRatio(4f / 5f).clip(RadiusXl)
        .background(Brush.linearGradient(listOf(Coral.copy(alpha = .24f), OnBrand)))) {
        Icon(Icons.Outlined.PhotoCamera, contentDescription = null, tint = White.copy(alpha = .24f),
            modifier = Modifier.align(Alignment.Center).size(72.dp))
        Box(Modifier.align(Alignment.TopStart).padding(12.dp).fillMaxWidth(.28f).aspectRatio(3f / 4f)
            .clip(RadiusLg).background(OnBrand).border(2.dp, Color.White, RadiusLg),
            contentAlignment = Alignment.Center) {
            Icon(Icons.Outlined.PersonOutline, contentDescription = null, tint = Color.White, modifier = Modifier.size(24.dp))
        }
        Text(tr(Message.Today2), Modifier.align(Alignment.BottomStart).padding(12.dp)
            .clip(CircleShape).background(Color.Black.copy(alpha = .6f))
            .padding(horizontal = 8.dp, vertical = 4.dp),
            color = Color.White, style = BodySm, fontWeight = FontWeight.SemiBold)
    }
}

/** Quatre profils : ce que le cercle donne à voir une fois ouvert. */
@Composable
private fun CirclePreview() {
    data class Member(val name: String, val when_: String, val tag: String?, val boosted: Boolean)
    val people = listOf(
        Member("Léa Martin", tr(Message.DemoTwelveMinutesAgo), tr(Message.Favorite), false),
        Member("Tom Perrin", tr(Message.DemoThirtyEightMinutesAgo), tr(Message.Boosted), true),
        Member("Inès Caron", tr(Message.DemoFortyOneMinutesAgo), tr(Message.Favorite), false),
        Member("Nolan Roux", tr(Message.HAgo), null, false),
    )
    Column(Modifier.fillMaxWidth().clip(Radius2xl).background(Panel).padding(horizontal = 16.dp)) {
        people.forEachIndexed { index, person ->
            if (index > 0) HorizontalDivider(color = Line)
            Row(Modifier.fillMaxWidth().padding(vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.size(40.dp).clip(CircleShape).background(GradientBrand).padding(2.dp),
                    contentAlignment = Alignment.Center) {
                    Box(Modifier.fillMaxSize().clip(CircleShape).background(SurfaceHigh),
                        contentAlignment = Alignment.Center) {
                        Text(person.name.take(1), style = TitleSm, color = White)
                    }
                }
                Column(Modifier.weight(1f)) {
                    Text(person.name, style = TitleMd)
                    Text(person.when_, style = BodyMd, color = Muted)
                }
                person.tag?.let {
                    Tag(it, if (person.boosted) Icons.Outlined.Bolt else Icons.Outlined.Star,
                        if (person.boosted) SuccessContainer else SurfaceHigh,
                        if (person.boosted) OnSuccessContainer else White)
                }
            }
        }
    }
}

@Composable
private fun Tag(label: String, icon: ImageVector, background: Color, content: Color) {
    Row(Modifier.clip(CircleShape).background(background).padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Icon(icon, contentDescription = null, tint = content, modifier = Modifier.size(14.dp))
        Text(label, color = content, style = BodySm, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun StakePreview(state: ChainState) {
    Column(Modifier.fillMaxWidth().clip(Radius2xl).background(Panel).padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(tr(Message.YourActiveStake), style = TitleSm, color = Muted)
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(skr(state.minStake), fontSize = 32.sp, lineHeight = 36.sp,
                fontWeight = FontWeight.Bold, letterSpacing = (-.32).sp)
            Text("SKR", Modifier.padding(bottom = 4.dp), style = TitleSm, color = Muted)
        }
        Text(tr(Message.ADayWithoutAMomentCosts, decayPercent(state)), style = BodyMd, color = Muted)
    }
}

@Composable
private fun PoolPreview() {
    Column(Modifier.fillMaxWidth().clip(Radius2xl).background(Panel).padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.size(32.dp).clip(CircleShape).background(SurfaceHigh), contentAlignment = Alignment.Center) {
                Icon(Icons.Outlined.Bolt, contentDescription = null, tint = White, modifier = Modifier.size(18.dp))
            }
            Text(tr(Message.YesterdaySPool), style = TitleSm, color = Muted)
        }
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("1 240", fontSize = 32.sp, lineHeight = 36.sp,
                fontWeight = FontWeight.Bold, letterSpacing = (-.32).sp)
            Text("SKR", Modifier.padding(bottom = 4.dp), style = TitleSm, color = Muted)
        }
        Text(tr(Message.MomentsPostedMissed), style = BodyMd, color = Muted)
        HorizontalDivider(Modifier.padding(top = 12.dp), color = Line)
        Row(Modifier.fillMaxWidth().padding(top = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween) {
            Text(tr(Message.YourShare), style = TitleSm)
            Text("58 SKR", style = TitleSm, color = Success)
        }
    }
}

/**
 * Les trois points des conditions : une seule carte, des filets entre les
 * lignes. Icônes de 40, gouttière de 12 — la liste se lit d'un bloc.
 */
@Composable
private fun RuleRows(vararg rows: Triple<ImageVector, String, String>) {
    Column(Modifier.fillMaxWidth().clip(Radius2xl).background(Panel).padding(horizontal = 16.dp)) {
        rows.forEachIndexed { index, row ->
            if (index > 0) HorizontalDivider(color = Line)
            Row(Modifier.fillMaxWidth().padding(vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.size(40.dp).clip(RadiusMd).background(SurfaceHigh),
                    contentAlignment = Alignment.Center) {
                    Icon(row.first, contentDescription = null, tint = Accent, modifier = Modifier.size(22.dp))
                }
                Column(Modifier.weight(1f)) {
                    Text(row.second, style = TitleMd)
                    Text(row.third, style = BodyMd, color = Muted)
                }
            }
        }
    }
}

/**
 * Les autorisations : une carte par demande, plus aérée que les conditions —
 * icône de 48, gouttière de 16. Chacune se pèse séparément, elle ne se lit pas
 * dans une liste.
 */
@Composable
private fun PermissionCards(vararg rows: Triple<ImageVector, String, String>) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        rows.forEach { row ->
            Row(Modifier.fillMaxWidth().clip(RadiusLg).background(Panel).padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Box(Modifier.size(48.dp).clip(RadiusMd).background(SurfaceHigh),
                    contentAlignment = Alignment.Center) {
                    Icon(row.first, contentDescription = null, tint = Accent, modifier = Modifier.size(24.dp))
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(row.second, style = TitleMd)
                    Text(row.third, style = BodyMd, color = Muted)
                }
            }
        }
    }
}

@Composable
private fun AcceptCheckbox(checked: Boolean, onToggle: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp)
        .toggleable(checked, role = Role.Checkbox, onValueChange = onToggle)
        .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.size(24.dp).clip(RoundedCornerShape(4.dp))
            .background(if (checked) Accent else Color.Transparent)
            .border(2.dp, if (checked) Accent else Muted, RoundedCornerShape(4.dp)),
            contentAlignment = Alignment.Center) {
            if (checked) Icon(Icons.Outlined.Check, contentDescription = null, tint = OnBrand,
                modifier = Modifier.size(16.dp))
        }
        Text(tr(Message.IHaveReadAndAcceptTheTerms),
            Modifier.weight(1f), style = BodyMd)
    }
}

@Composable
private fun BadgeTile(icon: ImageVector, label: String) {
    Box(Modifier.size(96.dp).clip(RadiusXl).background(PanelRaised)
        .semantics { contentDescription = label }, contentAlignment = Alignment.Center) {
        Icon(icon, contentDescription = null, tint = Accent, modifier = Modifier.size(40.dp))
    }
}

/**
 * La mise : le seul écran du parcours qui envoie une transaction.
 *
 * La maquette ne la met pas en page pleine mais en feuille, posée sur le fil
 * verrouillé — ce qu'on achète est visible derrière ce qu'on paie. Trois
 * états s'y succèdent : choix du montant, signature en cours, solde trop court.
 */
@Composable
private fun StakeSheet(
    state: ChainState, now: Long, busy: Boolean, error: String?,
    onBack: () -> Unit, onStake: (Long) -> Unit, onContinue: () -> Unit,
    onFaucet: () -> Unit, onDisconnect: () -> Unit,
) {
    val min = state.minStake
    val balance = state.tokenBalance
    var amount by rememberSaveable(min) { mutableLongStateOf(min) }
    val enough = balance >= min
    val staked = state.active
    // Un profil actif ne suffit pas : c'est la mise, decay déduit, qui doit
    // couvrir le minimum — sinon le fil resterait fermé après l'écran.
    val covered = staked && state.balance >= min
    // Mise déjà suffisante : le sélecteur ne s'ouvre que si on demande à ajouter.
    var adding by rememberSaveable { mutableStateOf(false) }
    // Ajouter demande d'avoir de quoi : le solde en compte, pas celui déjà misé.
    val canAdd = amount in min..balance
    // Les préréglages ne montrent que ce qui est atteignable — sauf le minimum,
    // qui reste visible même hors de portée : c'est le seuil à connaître.
    val presets = listOf(min, min * 2, min * 5).filter { it <= balance || it == min }

    Box(Modifier.fillMaxSize()) {
        // Le décor : ce que la mise ouvre. Inerte, et muet pour le lecteur d'écran.
        Column(Modifier.fillMaxSize().clearAndSetSemantics {}) {
            Spacer(Modifier.windowInsetsTopHeight(WindowInsets.safeDrawing))
            FeedTopBar("", posted = false, onSearch = null, onProfile = {})
            Box(Modifier.weight(1f).padding(horizontal = 16.dp)) {
                FeedGate(countdown(secondsUntilNextMoment(now)), atRisk = null, reward = null, onCapture = {}) {
                    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        demoMoments(Math.floorDiv(now, DAY_SECONDS)).take(2).forEach {
                            MomentCard(it.name, it.subtitle, it.caption, self = false, variant = it.variant)
                        }
                    }
                }
            }
            BottomBar(onHome = {}, onCapture = {})
        }
        MomentSheet(tr(Message.PutYourStakeInPlay), onClose = if (busy) null else onBack) {
            if (staked) Text(tr(Message.AlreadyStakedSkr, skr(state.balance)),
                Modifier.fillMaxWidth(), style = TitleMd, color = Success,
                textAlign = TextAlign.Center)
            if (covered) {
                // Le minimum est déjà couvert : la feuille ne redemande rien et
                // « Continuer » passe devant, sans sélecteur à traverser. En
                // ajouter reste possible, replié derrière un second geste.
                Text(tr(Message.YourStakeMeetsTheSkrMinimum, skr(min)),
                    Modifier.fillMaxWidth(), style = BodyMd, color = Muted,
                    textAlign = TextAlign.Center)
                PrimaryButton(if (busy) tr(Message.Signing) else tr(Message.Continue),
                    enabled = !busy, onClick = onContinue)
                if (!adding) {
                    TextButton(
                        onClick = { adding = true }, enabled = !busy,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(tr(Message.AddToMyStake), color = Accent) }
                } else {
                    AmountPicker(
                        value = amount, min = min, step = min, max = balance, presets = presets,
                        supporting = if (enough) tr(Message.ADayWithoutAMomentCosts2, decayPercent(state))
                            else tr(Message.YourBalanceIsSkr, skr(balance)),
                        error = !enough,
                        onChange = { amount = it },
                    )
                    error?.let { Text(it, style = BodyMd, color = Danger) }
                    TextButton(
                        onClick = { onStake(amount) }, enabled = !busy && canAdd,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(tr(Message.AddSkrToMyStake, skr(amount)), color = if (canAdd) Accent else Muted) }
                }
            } else {
                AmountPicker(
                    value = amount, min = min, step = min, max = balance, presets = presets,
                    supporting = when {
                        // Actif mais sous le seuil : le decay a mangé la mise, et la
                        // compléter est la seule sortie.
                        staked -> tr(Message.YourStakeFellBelowSkrTopIt, skr(min))
                        enough -> tr(Message.ADayWithoutAMomentCosts2, decayPercent(state))
                        else -> tr(Message.YouNeedSkrYourBalanceIsSkr, skr(min), skr(balance))
                    },
                    error = !enough,
                    onChange = { amount = it },
                )
                error?.let { Text(it, style = BodyMd, color = Danger) }
                PrimaryButton(
                    if (busy) tr(Message.Signing) else tr(Message.StakeSkr, skr(amount)),
                    enabled = enough && canAdd, loading = busy,
                ) { onStake(amount) }
                // Sans mise suffisante, le fil ne s'ouvrira jamais : un solde trop
                // court doit garder une issue, sinon la feuille devient une impasse.
                if (!enough) {
                    if (!state.faucetClaimed) TextButton(
                        onClick = onFaucet, enabled = !busy,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(tr(Message.GetTestSkr), color = Accent) }
                    TextButton(
                        onClick = onDisconnect, enabled = !busy,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(tr(Message.ChangeWallet), color = Muted) }
                }
            }
            SheetNote(tr(Message.NetworkFeesLessThanSol))
        }
    }
}

/**
 * Le dernier écran : la mise est prise, la journée court déjà.
 *
 * L'obturateur y est réel — il emmène capturer, il ne mime pas un bouton.
 */
@Composable
private fun FirstMoment(state: ChainState, now: Long, onFinish: (openCapture: Boolean) -> Unit) {
    val left = secondsUntilNextMoment(now)
    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
        Spacer(Modifier.height(40.dp))
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            Shutter(
                fraction = left / DAY_SECONDS.toFloat(),
                timeLabel = countdown(left),
                hint = tr(Message.YouHaveHLeftToCaptureToday, left / 3600),
                onCapture = { onFinish(true) },
            )
        }
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(tr(Message.YourStakeIsInPlay), style = DisplayMd)
            Text(tr(Message.CaptureYourFirstMomentToSeeThe), style = BodyLg, color = Muted)
        }
        Box(Modifier.padding(horizontal = 16.dp, vertical = 16.dp)) {
            Snackbar(tr(Message.SkrStaked, skr(state.balance)))
        }
        // L'obturateur seul laissait deviner la sortie. Les deux issues sont
        // maintenant nommées, et mènent chacune là où elles disent.
        Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 24.dp)) {
            PrimaryButton(tr(Message.CaptureMyMoment)) { onFinish(true) }
            TextButton(onClick = { onFinish(false) }, modifier = Modifier.fillMaxWidth()) {
                Text(tr(Message.SeeTheFeedFirst), color = Muted)
            }
        }
    }
}

/**
 * Le texte intégral, hors parcours.
 *
 * Il n'est pas embarqué : l'afficher en dur ici le figerait à la version du
 * binaire, alors qu'il doit pouvoir changer sans mise à jour de l'app.
 */
@Composable
private fun LegalText(document: String, onClose: () -> Unit) {
    val documentLabel = if (document == "conditions") tr(Message.TermsOfUse) else tr(Message.PrivacyPolicy)
    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
        Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 16.dp, top = 24.dp),
            verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = tr(Message.Back), tint = White)
            }
            Text(documentLabel.replaceFirstChar { it.uppercase() }, style = HeadlineSm)
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(tr(Message.TheFullTextHasnTBeenPublished, documentLabel),
                style = BodyLg, color = Muted)
            Text(tr(Message.LegalSummary),
                style = BodyMd, color = Muted)
        }
    }
}
