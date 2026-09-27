package com.klementxv.moment.ui

import com.klementxv.moment.i18n.Message
import com.klementxv.moment.i18n.tr
import com.klementxv.moment.i18n.AppLanguage

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
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
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.pullToRefresh
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.material.icons.Icons
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.blur
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.PersonOutline
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import com.klementxv.moment.ChainState
import com.klementxv.moment.MomentModel
import com.klementxv.moment.SkrUnit
import com.klementxv.moment.capture.PhotoPair
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

internal fun money(value: Long, decimals: Int = SkrUnit.decimals) =
    String.format(AppLanguage.locale, "%.2f", value / SkrUnit.pow10(decimals).toDouble())
internal fun skr(value: Long, decimals: Int = SkrUnit.decimals): String {
    val unit = SkrUnit.pow10(decimals)
    return if (value % unit == 0L) "${value / unit}" else money(value, decimals)
}
internal fun parseSkrAmount(text: String, decimals: Int = SkrUnit.decimals): Long? {
    val unit = SkrUnit.pow10(decimals)
    val cleaned = text.replace(',', '.').trim()
    val parts = cleaned.split('.')
    if (parts.size > 2) return null
    val whole = parts[0]
    val fraction = parts.getOrNull(1).orEmpty()
    if (whole.isEmpty() && fraction.isEmpty()) return null
    if (whole.any { !it.isDigit() } || fraction.any { !it.isDigit() }) return null
    val units = whole.ifEmpty { "0" }.toLongOrNull() ?: return null
    if (units > Long.MAX_VALUE / unit) return null
    return units * unit + fraction.take(decimals).padEnd(decimals, '0').ifEmpty { "0" }.toLong()
}

internal fun formatSkrInput(value: Long, decimals: Int = SkrUnit.decimals): String {
    val unit = SkrUnit.pow10(decimals)
    val units = value / unit
    val fraction = if (decimals == 0) "" else (value % unit).toString().padStart(decimals, '0').trimEnd('0')
    return if (fraction.isEmpty()) "$units" else "$units${java.text.DecimalFormatSymbols.getInstance(AppLanguage.locale).decimalSeparator}$fraction"
}

internal const val DAY_SECONDS = 86_400L
internal fun countdown(seconds: Long): String {
    val s = seconds.coerceAtLeast(0)
    return "%02d:%02d:%02d".format(s / 3600, s % 3600 / 60, s % 60)
}

internal enum class MomentPage { Home, Profile }

@Composable
fun MomentApp(model: MomentModel) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val original = androidx.compose.ui.platform.LocalConfiguration.current
    val configuration = remember(original, AppLanguage.code) {
        android.content.res.Configuration(original).apply { setLocale(AppLanguage.locale) }
    }
    val resources = remember(context, configuration) {
        context.createConfigurationContext(configuration).resources
    }
    CompositionLocalProvider(
        androidx.compose.ui.platform.LocalConfiguration provides configuration,
        androidx.compose.ui.platform.LocalResources provides resources,
    ) { MomentContent(model) }
}

@Composable
private fun MomentContent(model: MomentModel) {
    var page by rememberSaveable { mutableStateOf(MomentPage.Home) }
    var captureOpen by rememberSaveable { mutableStateOf(false) }
    var now by remember { mutableLongStateOf(Instant.now().epochSecond) }
    val state = model.state
    val wallet = model.walletAddress
    val context = androidx.compose.ui.platform.LocalContext.current
    val identities = remember(context) { context.getSharedPreferences("profile_identity", android.content.Context.MODE_PRIVATE) }
    var nickname by remember(wallet) { mutableStateOf(wallet?.let { identities.getString(it, "") }.orEmpty()) }
    var skrName by remember(wallet) { mutableStateOf<String?>(null) }
    var skrLoading by remember(wallet) { mutableStateOf(false) }
    var skrFailed by remember(wallet) { mutableStateOf(false) }
    var skrRetry by remember(wallet) { mutableIntStateOf(0) }
    val resolver = remember { com.klementxv.moment.chain.SkrResolver(com.klementxv.moment.BuildConfig.IDENTITY_RPC_URL) }
    val authorNames = remember { androidx.compose.runtime.mutableStateMapOf<String, String?>() }
    LaunchedEffect(model.remoteFeed) {
        for (author in model.remoteFeed.map { it.wallet }.distinct()) {
            if (author in authorNames) continue
            try {
                authorNames[author] = resolver.resolve(author)
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Exception) {
            }
        }
    }
    LaunchedEffect(wallet, skrRetry) {
        if (wallet != null) {
            skrLoading = true
            skrFailed = false
            try {
                skrName = resolver.resolve(wallet)
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                skrFailed = true
            } finally {
                skrLoading = false
            }
        }
    }
    val demoPrefs = remember(context) { context.getSharedPreferences("demo", android.content.Context.MODE_PRIVATE) }
    val demoFeed = remember { demoPrefs.getBoolean("fakeFeed", false) }
    val favoritePrefs = remember(context) { context.getSharedPreferences("favorites", android.content.Context.MODE_PRIVATE) }
    var favorites by remember { mutableStateOf(favoritePrefs.getStringSet("names", emptySet()).orEmpty()) }
    val toggleFavorite: (String) -> Unit = { name ->
        favorites = (if (name in favorites) favorites - name else favorites + name)
            .also { favoritePrefs.edit().putStringSet("names", it).apply() }
    }
    val onboarding = remember(context) { context.getSharedPreferences("onboarding", android.content.Context.MODE_PRIVATE) }
    val restoredWallet = remember { wallet }
    var completed by remember(wallet) {
        mutableStateOf(wallet != null && (wallet == restoredWallet || onboarding.getBoolean(wallet, false)))
    }
    val staked = !state.loaded || state.active
    val entered = wallet != null && completed && staked && model.signedInWallet == wallet
    val resumeAtStake = wallet != null && completed && state.loaded && !state.active
    val saveNickname: (String) -> Unit = { value ->
        wallet?.let { identities.edit().putString(it, value).apply(); nickname = value }
    }
    LaunchedEffect(entered) { if (!entered) { captureOpen = false; page = MomentPage.Home } }
    var confirmExit by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) { while (true) { now = Instant.now().epochSecond; delay(1000) } }
    LaunchedEffect(wallet, state.posted, state.day) { if (wallet != null && state.posted) model.loadFeed() }
    LaunchedEffect(wallet, now / DAY_SECONDS) { if (wallet != null) model.refresh() }
    val posted = state.posted && state.day == Math.floorDiv(now, DAY_SECONDS)
    val unlocked = posted
    LaunchedEffect(posted) { if (posted) captureOpen = false }
    BackHandler(entered && !captureOpen && page != MomentPage.Home) { page = MomentPage.Home }
    MaterialTheme(colorScheme = darkColorScheme(
        primary = Accent, onPrimary = OnBrand,
        primaryContainer = AccentContainer, onPrimaryContainer = OnAccentContainer,
        secondary = Rose, onSecondary = OnBrand,
        background = Ink, onBackground = White,
        surface = Panel, onSurface = White,
        surfaceVariant = SurfaceHigh, onSurfaceVariant = Muted,
        outline = Outline, outlineVariant = Line,
        error = Danger, onError = OnBrand,
        errorContainer = DangerContainer, onErrorContainer = OnDangerContainer),
        typography = Typography(
            bodyLarge = BodyLg, bodyMedium = BodyMd, bodySmall = BodySm,
            titleMedium = TitleMd, titleSmall = TitleSm,
            headlineLarge = HeadlineLg, headlineSmall = HeadlineSm,
            labelLarge = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Medium),
        )) {
        Column(Modifier.fillMaxSize().background(Ink)) {
        Surface(color = Ink, modifier = Modifier.weight(1f).fillMaxWidth()) {
            if (wallet != null && completed && model.signedInWallet != wallet) {
                SignIn(wallet, model.busy, model.error, onSignIn = model::signIn, onChangeWallet = model::disconnect)
            } else if (!entered) {
                Onboarding(wallet, state, now, model.busy, model.error,
                    resumeAtStake = resumeAtStake,
                    onConnect = model::connect, onStake = model::stake,
                    onFaucet = model::claimFaucet, onDisconnect = model::disconnect) { openCapture ->
                    wallet?.let { address ->
                        onboarding.edit().putBoolean(address, true).apply()
                        completed = true
                        captureOpen = openCapture
                    }
                }
            } else if (!state.loaded) {
                Column(Modifier.fillMaxSize().safeDrawingPadding().padding(28.dp), verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally) {
                    if (model.error == null) CircularProgressIndicator(color = Accent)
                    else {
                        InfoCard(tr(Message.ReadingTheBlockchain), model.error!!)
                        Spacer(Modifier.height(20.dp))
                        PrimaryButton(tr(Message.TryAgain), enabled = !model.busy, loading = model.busy, onClick = model::refresh)
                    }
                }
            } else {
                Scaffold(containerColor = Ink,
                    contentWindowInsets = WindowInsets.safeDrawing) { padding ->
                    val direction = LocalLayoutDirection.current
                    Column(Modifier.fillMaxSize().padding(
                        start = padding.calculateStartPadding(direction),
                        top = padding.calculateTopPadding(),
                        end = padding.calculateEndPadding(direction))) {
                        when (page) {
                            MomentPage.Home -> FeedTopBar(
                                nickname.ifBlank { skrName.orEmpty() }, posted,
                                onSearch = null,
                                onProfile = { page = MomentPage.Profile },
                            )
                            MomentPage.Profile -> Row(Modifier.fillMaxWidth().height(64.dp)
                                .padding(start = 8.dp, end = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                                IconButton(onClick = { page = MomentPage.Home }) {
                                    Icon(Icons.AutoMirrored.Outlined.ArrowBack,
                                        contentDescription = tr(Message.BackToFeed), tint = White)
                                }
                                Text(tr(Message.Profile), style = HeadlineSm, fontSize = 20.sp)
                            }
                        }
                        AnimatedContent(targetState = page, modifier = Modifier.weight(1f),
                            transitionSpec = {
                                (fadeIn(tween(260, delayMillis = 60)) + slideInVertically(tween(320)) { it / 35 }) togetherWith
                                    fadeOut(tween(120))
                            }, label = "navigation") { currentPage ->
                        val pull = rememberPullToRefreshState()
                        Box(Modifier.fillMaxSize().pullToRefresh(
                            isRefreshing = model.feedLoading, state = pull,
                            enabled = currentPage == MomentPage.Home && unlocked,
                            onRefresh = { model.refresh(); model.loadFeed(interactive = true) },
                        )) {
                            BoxWithConstraints(Modifier.fillMaxSize()) {
                                val locked = currentPage == MomentPage.Home && !unlocked
                                val minimumHeight = if (locked) maxHeight
                                    else (maxHeight - 42.dp - padding.calculateBottomPadding()).coerceAtLeast(0.dp)
                                Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState(),
                                    enabled = !locked)
                                    .padding(horizontal = if (currentPage == MomentPage.Home) 16.dp else 26.dp)
                                    .padding(top = if (locked) 0.dp else 8.dp,
                                        bottom = if (locked) 0.dp else 24.dp + padding.calculateBottomPadding())
                                    .heightIn(min = minimumHeight),
                                    verticalArrangement = if (currentPage == MomentPage.Home && !unlocked) Arrangement.Center
                                        else Arrangement.spacedBy(24.dp)) {
                                    when (currentPage) {
                                        MomentPage.Home -> Column {
                                            if (model.hasPendingPublication) {
                                                Text(tr(Message.ResumePendingExplanation), style = BodySm, color = Muted)
                                                TextButton(enabled = !model.busy && !model.feedLoading,
                                                    onClick = { model.publish { captureOpen = false } }) {
                                                    Text(tr(Message.ResumePending))
                                                }
                                            }
                                            Feed(state, now, unlocked, demo = demoFeed,
                                            remote = model.remoteFeed,
                                            loading = model.feedLoading,
                                            hasMore = model.feedCursor != null,
                                            onRefresh = { model.loadFeed(interactive = true) },
                                            onMore = { model.loadFeed(more = true, interactive = true) },
                                            needsSignature = model.feedNeedsSignature,
                                            feedError = model.feedError,
                                            onLike = model::toggleLike,
                                            authorName = { author ->
                                                if (author == wallet) nickname.ifBlank { skrName.orEmpty() }.ifBlank { null }
                                                else authorNames[author]
                                            },
                                            photos = model.draft,
                                            caption = model.caption,
                                            favorites = favorites,
                                            onToggleFavorite = toggleFavorite,
                                            minimumHeight = minimumHeight,
                                            onCapture = { captureOpen = true },
                                            onProfile = { page = MomentPage.Profile })
                                        }
                                        MomentPage.Profile -> Profile(state, now, wallet, model.busy, model.error,
                                            nickname = nickname,
                                            skrName = skrName,
                                            skrLoading = skrLoading,
                                            skrFailed = skrFailed,
                                            favorites = favorites,
                                            onToggleFavorite = toggleFavorite,
                                            onRetrySkr = { skrRetry++ },
                                            onNicknameChange = saveNickname,
                                            onConnect = model::connect,
                                            onDisconnect = model::disconnect,
                                            onFaucet = model::claimFaucet,
                                            onStake = model::stake,
                                            onExit = { confirmExit = true },
                                            onCancel = model::cancelExit,
                                            onWithdraw = model::finalizeExit,
                                            demoFeed = demoFeed)
                                    }
                                }
                            }
                            PullToRefreshDefaults.Indicator(pull, model.feedLoading,
                                Modifier.align(Alignment.TopCenter),
                                containerColor = PanelRaised, color = Accent)
                        }
                    }
                    }
                }
            }
            if (entered && captureOpen) CaptureSheet(
                state = state,
                now = now,
                draft = model.draft,
                busy = model.busy,
                onDraft = model::replaceDraft,
                onPublish = { acknowledged, caption ->
                    model.publish(acknowledged, caption) { captureOpen = false; page = MomentPage.Home }
                },
                moderation = model.moderation,
                onRetryModeration = model::retryModeration,
                onProfile = { captureOpen = false; page = MomentPage.Profile },
                onDismiss = { captureOpen = false },
            )
            if (entered && (state.loaded || captureOpen) && model.error != null) AlertDialog(onDismissRequest = model::dismissError,
                title = { Text(tr(Message.YourMomentIsSafe)) }, text = { Text(model.error!!) },
                confirmButton = { TextButton(onClick = model::dismissError) { Text(tr(Message.GotIt)) } })
            if (confirmExit) AlertDialog(onDismissRequest = { confirmExit = false },
                containerColor = Panel,
                title = { Text(tr(Message.StartWithdrawalWait, (state.config?.withdrawalDelaySeconds ?: 172_800L) / 3600)) },
                text = { Text(tr(Message.WithdrawalWarning, state.decayBps / 100)) },
                confirmButton = { TextButton(onClick = {
                    model.requestExit(); confirmExit = false
                }) { Text(tr(Message.RequestWithdrawal)) } },
                dismissButton = { TextButton(onClick = { confirmExit = false }) { Text(tr(Message.Stay)) } })
        }
    }
    }
}


@Composable internal fun Wordmark() {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
        Canvas(Modifier.size(24.dp)) {
            drawCircle(GradientBrand, size.width * .38f,
                style = Stroke(size.width * .12f))
            drawCircle(Ink, size.width * .14f, Offset(size.width * .78f, size.height * .23f))
            drawCircle(Coral, size.width * .10f, Offset(size.width * .78f, size.height * .23f))
        }
        Text("Moment", fontSize = 24.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-.8).sp)
    }
}
@Composable private fun Pill(text: String, color: Color) {
    Text(text, Modifier.clip(CircleShape).background(color.copy(alpha = .07f)).padding(horizontal = 11.dp, vertical = 7.dp),
        color = color, fontSize = 10.sp, fontWeight = FontWeight.Medium)
}
@Composable internal fun PrimaryButton(text: String, enabled: Boolean = true, loading: Boolean = false, onClick: () -> Unit) {
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
        enabled = enabled && !loading, interactionSource = interaction,
        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).graphicsLayer { scaleX = scale; scaleY = scale }
            .background(if (enabled) GradientBrand else SolidColor(SurfaceHigh), CircleShape),
        shape = CircleShape, colors = ButtonDefaults.buttonColors(
            containerColor = Color.Transparent, contentColor = OnBrand,
            disabledContainerColor = Color.Transparent, disabledContentColor = Muted)) {
        if (loading) {
            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = OnBrand)
            Spacer(Modifier.width(10.dp))
        }
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
@Composable internal fun Glyph(kind: String, color: Color) {
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
                "close" -> { line(6f,6f,18f,18f); line(18f,6f,6f,18f) }
                "back" -> { line(20f,12f,4f,12f); line(4f,12f,11f,5f); line(4f,12f,11f,19f) }
                "retry" -> {
                    drawArc(color, 45f, 285f, false, Offset(4f,4f), Size(16f,16f), style = stroke)
                    line(20f,3f,20f,9f); line(20f,9f,14f,9f)
                }
                "clock" -> { drawCircle(color,9f,Offset(12f,12f),style=stroke); line(12f,7f,12f,12f); line(12f,12f,16f,14f) }
                "check" -> { line(5f,12f,10f,17f); line(10f,17f,20f,6f) }
                "pool" -> {
                    drawOval(color, Offset(4f,4f), Size(16f,6f), style = stroke)
                    line(4f,7f,4f,15f); line(20f,7f,20f,15f)
                    drawArc(color, 0f, 180f, false, Offset(4f,8f), Size(16f,6f), style = stroke)
                    drawArc(color, 0f, 180f, false, Offset(4f,12f), Size(16f,6f), style = stroke)
                }
                "copy" -> {
                    drawRoundRect(color, Offset(9f,9f), Size(11f,11f), androidx.compose.ui.geometry.CornerRadius(2.5f), style = stroke)
                    drawPath(Path().apply { moveTo(15f,6f); lineTo(15f,4.5f); quadraticTo(15f,4f,14.5f,4f); lineTo(5.5f,4f)
                        quadraticTo(5f,4f,5f,4.5f); lineTo(5f,14.5f); quadraticTo(5f,15f,5.5f,15f); lineTo(7f,15f) }, color, style = stroke)
                }
                "lock" -> {
                    drawRoundRect(color,Offset(4f,10f),Size(16f,12f),androidx.compose.ui.geometry.CornerRadius(3f),style=stroke)
                    drawArc(color,180f,180f,false,Offset(7f,2f),Size(10f,16f),style=stroke)
                    line(12f,15f,12f,18f)
                }
            }
        }
    }
}
