package com.klementxv.moment.ui

import com.klementxv.moment.i18n.Message
import com.klementxv.moment.i18n.tr
import com.klementxv.moment.i18n.AppLanguage

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.OutlinedFlag
import androidx.compose.material.icons.outlined.PersonOutline
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.klementxv.moment.ChainState
import com.klementxv.moment.capture.PhotoPair
import androidx.compose.ui.unit.Dp
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter



@Composable
internal fun FeedTopBar(displayName: String, posted: Boolean, onSearch: (() -> Unit)?, onProfile: () -> Unit) {
    Row(Modifier.fillMaxWidth().height(64.dp).padding(start = 16.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text(tr(Message.Feed), Modifier.weight(1f), style = HeadlineSm, fontSize = 20.sp)
        if (onSearch != null) IconButton(onClick = onSearch) {
            Icon(Icons.Outlined.Search, contentDescription = tr(Message.SearchForSomeone), tint = White)
        }
        IconButton(onClick = onProfile, modifier = Modifier.semantics { contentDescription = tr(Message.ViewMyProfile) }) {
            Avatar(displayName, ring = posted)
        }
    }
}

@Composable
internal fun Avatar(displayName: String, ring: Boolean, size: Dp = 40.dp) {
    val face = @Composable {
        Box(Modifier.fillMaxSize().clip(CircleShape).background(SurfaceHigh), contentAlignment = Alignment.Center) {
            if (displayName.isBlank()) {
                Icon(Icons.Outlined.PersonOutline, contentDescription = null, tint = White,
                    modifier = Modifier.size(size * .55f))
            } else {
                Text(displayName.take(1).uppercase(),
                    style = if (size >= 56.dp) HeadlineSm else TitleSm, color = White)
            }
        }
    }
    if (ring) {
        Box(Modifier.size(size).clip(CircleShape).background(GradientBrand).padding(2.dp)) {
            Box(Modifier.fillMaxSize().clip(CircleShape).border(2.dp, Ink, CircleShape)) { face() }
        }
    } else {
        Box(Modifier.size(size)) { face() }
    }
}


@Composable
internal fun MomentCard(
    name: String,
    meta: String,
    caption: String?,
    self: Boolean,
    variant: Int = 0,
    place: String? = null,
    boosted: Boolean = false,
    favorite: Boolean = false,
    likes: Int = 0,
    liked: Boolean = false,
    onLike: (() -> Unit)? = null,
    photos: PhotoPair? = null,
    onOptions: (() -> Unit)? = null,
    verifiedRemote: Boolean = false,
) {
    Column(Modifier.fillMaxWidth().clip(Radius2xl).background(Panel).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Avatar(name, ring = true)
            Column(Modifier.weight(1f)) {
                Text(name, style = TitleMd)
                Text(meta, style = BodyMd, color = Muted)
            }
            if (favorite) Badge(tr(Message.Favorite), Icons.Outlined.Star, SurfaceHigh, White)
            if (onOptions != null) IconButton(onClick = onOptions, modifier = Modifier.offset(x = 8.dp)) {
                Icon(Icons.Outlined.MoreVert, contentDescription = tr(Message.MomentOptions), tint = Muted)
            }
        }
        MomentPhoto(
            image = {
                if (photos != null) PhotoPairImage(photos)
                else IllustratedPair(variant)
            },
            overlay = {
                place?.let {
                    Box(Modifier.align(Alignment.BottomStart).padding(12.dp)) { PhotoTag(it) }
                }
                if (boosted) Box(Modifier.align(Alignment.TopEnd).padding(12.dp)) {
                    Badge(tr(Message.Boosted), Icons.Outlined.Bolt, SuccessContainer, OnSuccessContainer)
                }
                Box(Modifier.align(Alignment.TopEnd).padding(12.dp)
                    .then(if (boosted) Modifier.offset(y = 32.dp) else Modifier)) {
                    PhotoTag(if (photos == null) "Illustration" else if (verifiedRemote) tr(Message.VerifiedSignature) else tr(Message.OnThisDevice))
                }
            },
        )
        caption?.let { Text(it, style = BodyLg) }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Reaction(if (liked) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder, likes, tr(Message.Like),
                active = liked, onClick = onLike)
            if (self) {
                Spacer(Modifier.weight(1f))
                Text(tr(Message.YourMoment), style = BodySm, color = Accent, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun Reaction(icon: ImageVector, count: Int, label: String, active: Boolean = false, onClick: (() -> Unit)? = null) {
    val tint = if (active) Accent else Muted
    Row(Modifier.heightIn(min = 40.dp).clip(CircleShape)
        .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
        .padding(horizontal = 8.dp)
        .clearAndSetSemantics { contentDescription = "$count $label" },
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
        Text("$count", style = BodyMd, color = tint)
    }
}

@Composable
private fun Badge(label: String, icon: ImageVector, background: Color, content: Color) {
    Row(Modifier.clip(CircleShape).background(background).padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Icon(icon, contentDescription = null, tint = content, modifier = Modifier.size(14.dp))
        Text(label, color = content, style = BodySm, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun PhotoTag(label: String) {
    Text(label, Modifier.clip(CircleShape).background(Color.Black.copy(alpha = .6f))
        .padding(horizontal = 8.dp, vertical = 4.dp),
        color = Color.White, style = BodySm, fontWeight = FontWeight.SemiBold)
}

@Composable
private fun IllustratedPair(variant: Int) {
    Box(Modifier.fillMaxSize()) {
        Landscape(Modifier.fillMaxSize(), variant)
        MomentSelfie { Portrait(Modifier.fillMaxSize(), variant) }
    }
}


@Composable
internal fun FeedGate(
    remaining: String,
    atRisk: String?,
    reward: String?,
    onCapture: () -> Unit,
    content: @Composable () -> Unit,
) {
    Box(Modifier.fillMaxSize().clipToBounds()) {
        Box(Modifier.fillMaxSize().blur(18.dp).clearAndSetSemantics {}) { content() }
        Box(Modifier.fillMaxSize().background(Ink.copy(alpha = .5f)))
        Column(Modifier.align(Alignment.Center).widthIn(max = 328.dp).fillMaxWidth()
            .padding(16.dp).clip(Radius2xl).background(PanelRaised)
            .padding(start = 20.dp, end = 20.dp, top = 24.dp, bottom = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.size(48.dp).clip(CircleShape).background(SurfaceHigh), contentAlignment = Alignment.Center) {
                Glyph("lock", White)
            }
            Text(tr(Message.YourMomentUnlocksTheFeed), style = HeadlineSm, textAlign = TextAlign.Center)
            Text(tr(Message.CaptureTodaySPhotoToSeeEveryone),
                style = BodyMd, color = Muted, textAlign = TextAlign.Center)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(tr(Message.TimeLeft), style = BodyMd, color = Muted)
                Text(remaining, fontSize = 20.sp, lineHeight = 24.sp, fontWeight = FontWeight.Bold)
            }
            atRisk?.let {
                Row(Modifier.fillMaxWidth().clip(RadiusMd).background(DangerContainer).padding(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Outlined.OutlinedFlag, contentDescription = null,
                        tint = OnDangerContainer, modifier = Modifier.size(20.dp))
                    Text(it, style = BodyMd, color = OnDangerContainer)
                }
            }
            Spacer(Modifier.height(4.dp))
            PrimaryButton(tr(Message.CaptureMyMoment), onClick = onCapture)
            reward?.let {
                Text(it, Modifier.fillMaxWidth().padding(top = 4.dp), style = BodySm, color = Muted)
            }
        }
    }
}


@Composable
internal fun MomentOptions(
    expanded: Boolean,
    favorite: Boolean,
    onFavorite: () -> Unit,
    onReport: () -> Unit,
    onDismiss: () -> Unit,
) {
    DropdownMenu(expanded, onDismissRequest = onDismiss,
        modifier = Modifier.background(SurfaceHigh).width(248.dp)) {
        DropdownMenuItem(
            text = { Text(if (favorite) tr(Message.RemoveFromFavorites) else tr(Message.AddToFavorites), style = BodyLg) },
            leadingIcon = {
                Icon(if (favorite) Icons.Outlined.Star else Icons.Outlined.StarOutline,
                    contentDescription = null, tint = White)
            },
            onClick = { onFavorite(); onDismiss() },
        )
        DropdownMenuItem(
            text = { Text(tr(Message.ReportThisMoment), style = BodyLg) },
            leadingIcon = { Icon(Icons.Outlined.OutlinedFlag, contentDescription = null, tint = White) },
            onClick = { onReport(); onDismiss() },
        )
    }
}

private class Sky(
    val air: List<Color>, val sun: Color, val sunX: Float, val sunY: Float, val sunRadius: Float,
    val far: Color, val mid: Color, val near: Color, val trail: Color, val trees: Color, val treeCount: Int,
)

private val SKIES = listOf(
    Sky(listOf(Color(0xFF617D91), Color(0xFFE1B998), Color(0xFF45665D)), Color(0xFFFFDAB0), .73f, .28f, .10f,
        Color(0xFF627977), Color(0xFF355B55), Color(0xFF193E37), Color(0xFFB1AA81), Color(0xFF102D29), 13),
    Sky(listOf(Color(0xFF8170B4), Color(0xFFEEBB9B), Color(0xFF334C4E)), Color(0xFFFFE2C4), .26f, .23f, .09f,
        Color(0xFF6E6E8E), Color(0xFF4A4A6B), Color(0xFF2A2B45), Color(0xFFC9B9A4), Color(0xFF1A1B2E), 9),
    Sky(listOf(Color(0xFFBCCBD6), Color(0xFFA6B9C6), Color(0xFF6B8794)), Color(0xFFF4F8FA), .55f, .17f, .07f,
        Color(0xFF8FA3B0), Color(0xFF6A8291), Color(0xFF465C6B), Color(0xFFCFD6DA), Color(0xFF32444F), 17),
    Sky(listOf(Color(0xFFF0B27A), Color(0xFFE08D5B), Color(0xFF9C5B3C)), Color(0xFFFFF0C9), .84f, .35f, .12f,
        Color(0xFFC98A63), Color(0xFF9E6144), Color(0xFF6E3F2C), Color(0xFFE8C79A), Color(0xFF4A2A1E), 6),
    Sky(listOf(Color(0xFF141A33), Color(0xFF243356), Color(0xFF16213A)), Color(0xFFDCE4F5), .19f, .18f, .06f,
        Color(0xFF2C3A5C), Color(0xFF1E2A45), Color(0xFF121A2C), Color(0xFF6C7796), Color(0xFF0A1020), 11),
)

@Composable internal fun Landscape(modifier: Modifier, variant: Int = 0) {
    val sky = SKIES[Math.floorMod(variant, SKIES.size)]
    Canvas(modifier.semantics { contentDescription = tr(Message.LandscapeIllustrationRearCameraExample) }) {
        val w = size.width; val h = size.height
        val lift = (Math.floorMod(variant, SKIES.size) - 2) * .03f
        drawRect(Brush.verticalGradient(sky.air))
        drawCircle(sky.sun, w * sky.sunRadius, Offset(w * sky.sunX, h * sky.sunY))
        fun ridge(color: Color, points: List<Pair<Float, Float>>) {
            drawPath(Path().apply { moveTo(0f, h); points.forEach { (x, y) -> lineTo(x * w, (y + lift) * h) }; lineTo(w, h); close() }, color)
        }
        ridge(sky.far, listOf(0f to .55f, .16f to .39f, .28f to .48f, .54f to .30f, .7f to .46f, 1f to .37f))
        ridge(sky.mid, listOf(0f to .62f, .25f to .48f, .5f to .69f, .75f to .53f, 1f to .66f))
        ridge(sky.near, listOf(0f to .73f, .2f to .8f, .46f to .67f, .78f to .78f, 1f to .63f))
        val trail = Path().apply { moveTo(w * .45f, h); cubicTo(w * .2f, h * .84f, w * .85f, h * .81f, w * .59f, h * .70f) }
        drawPath(trail, sky.trail, style = Stroke(w * .04f, cap = StrokeCap.Round))
        for (i in 0..sky.treeCount) {
            val x = w * (i / sky.treeCount.toFloat()); val y = h * (.83f + (i % 3) * .04f)
            val tree = Path().apply { moveTo(x, y - h * .12f); lineTo(x - w * .035f, y); lineTo(x + w * .035f, y); close() }
            drawPath(tree, sky.trees)
        }
    }
}

private class Face(val air: List<Color>, val ground: Color, val skin: Color, val neck: Color, val hair: Color)

private val FACES = listOf(
    Face(listOf(Color(0xFFABA4D4), Color(0xFF5A6582)), Color(0xFF253B39), Color(0xFFDAA383), Color(0xFFBE8668), Color(0xFF252524)),
    Face(listOf(Color(0xFFE7B9A0), Color(0xFF8A6A72)), Color(0xFF3A2A2E), Color(0xFF8D5A3C), Color(0xFF75492F), Color(0xFF1B1412)),
    Face(listOf(Color(0xFFC6D8E2), Color(0xFF6E8796)), Color(0xFF3F5560), Color(0xFFF0CBA8), Color(0xFFD4AC8B), Color(0xFF6B4A2A)),
    Face(listOf(Color(0xFFF3CE9E), Color(0xFFB07A55)), Color(0xFF5A3826), Color(0xFF5E3A24), Color(0xFF4B2E1C), Color(0xFF14100D)),
    Face(listOf(Color(0xFF2A3554), Color(0xFF151C2F)), Color(0xFF1A2236), Color(0xFFC2906B), Color(0xFFA37554), Color(0xFF241E2C)),
)

@Composable internal fun Portrait(modifier: Modifier, variant: Int = 0) {
    val face = FACES[Math.floorMod(variant, FACES.size)]
    Canvas(modifier.semantics { contentDescription = tr(Message.PortraitIllustrationFrontCameraExample) }) {
        val w = size.width; val h = size.height
        drawRect(Brush.verticalGradient(face.air))
        drawOval(face.ground, Offset(-w * .1f, h * .62f), Size(w * 1.2f, h * .6f))
        drawRoundRect(face.neck, Offset(w * .4f, h * .5f), Size(w * .2f, h * .22f), androidx.compose.ui.geometry.CornerRadius(8f))
        drawOval(face.skin, Offset(w * .24f, h * .20f), Size(w * .53f, h * .40f))
        drawArc(face.hair, 170f, 210f, true, Offset(w * .20f, h * .12f), Size(w * .59f, h * .35f))
        drawLine(Ink, Offset(w * .34f, h * .37f), Offset(w * .42f, h * .37f), 3f)
        drawLine(Ink, Offset(w * .58f, h * .37f), Offset(w * .66f, h * .37f), 3f)
        drawArc(Color(0xFF805846), 0f, 160f, false, Offset(w * .43f, h * .44f), Size(w * .14f, h * .07f), style = Stroke(2f))
    }
}

private const val GATE_URGENT_SECONDS = 3 * 3600L

@Composable
internal fun Feed(
    state: ChainState,
    now: Long,
    unlocked: Boolean,
    demo: Boolean,
    photos: PhotoPair?,
    caption: String,
    favorites: Set<String>,
    onToggleFavorite: (String) -> Unit,
    minimumHeight: Dp,
    onCapture: () -> Unit,
    onProfile: () -> Unit,
    remote: List<com.klementxv.moment.backend.RemoteMoment> = emptyList(),
    loading: Boolean = false,
    hasMore: Boolean = false,
    onRefresh: () -> Unit = {},
    onMore: () -> Unit = {},
    needsSignature: Boolean = false,
    feedError: String? = null,
    authorName: (String) -> String? = { null },
    onLike: (String) -> Unit = {},
) {
    var reporting by remember { mutableStateOf<String?>(null) }
    val moments = remember(now / DAY_SECONDS, AppLanguage.code) { demoMoments(Math.floorDiv(now, DAY_SECONDS)) }

    if (!unlocked) {
        val left = secondsUntilNextMoment(now)
        Box(Modifier.fillMaxWidth().height(minimumHeight)) {
            FeedGate(
                remaining = countdown(left),
                atRisk = if (left <= GATE_URGENT_SECONDS)
                    tr(Message.FinalHoursADayWithoutAMoment, state.decayBps / 100) else null,
                reward = if (state.totalCheckIns > 0)
                    tr(Message.YesterdayEveryPublishedMomentReceivedAShare) else null,
                onCapture = onCapture,
            ) {
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    moments.take(2).forEach { moment ->
                        MomentCard(moment.name, moment.subtitle, moment.caption, self = false,
                            variant = moment.variant, likes = 12)
                    }
                }
            }
        }
        return
    }

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(DateTimeFormatter.ofPattern("EEEE d MMMM", AppLanguage.locale).withZone(ZoneId.systemDefault())
            .format(Instant.ofEpochSecond(now)).uppercase(AppLanguage.locale),
            color = Muted, style = LabelSm, letterSpacing = 1.5.sp)
        if (state.posted && demo) {
            MomentCard(tr(Message.You), tr(Message.TodayDayStreak, state.streak),
                caption.ifBlank { null }, self = true, photos = photos)
        }
        if (demo) {
            moments.forEach { moment ->
                var open by remember(moment.name) { mutableStateOf(false) }
                Box {
                    MomentCard(moment.name, moment.subtitle, moment.caption, self = false,
                        variant = moment.variant, likes = 8 + moment.variant * 3,
                        favorite = moment.name in favorites,
                        onOptions = { open = true })
                    MomentOptions(open, moment.name in favorites,
                        onFavorite = { onToggleFavorite(moment.name) },
                        onReport = { reporting = moment.name },
                        onDismiss = { open = false })
                }
            }
        } else {
            if ((needsSignature || feedError != null) && !loading) {
                InfoCard(tr(Message.ShowTheFeed), feedError ?: tr(Message.FeedSignatureNeeded))
                PrimaryButton(tr(if (feedError != null) Message.TryAgain else Message.ShowTheFeed), onClick = onRefresh)
            }
            remote.forEach { moment ->
                MomentCard(authorName(moment.wallet) ?: (moment.wallet.take(6) + "…" + moment.wallet.takeLast(4)),
                    tr(Message.RemoteMomentToday), moment.caption.ifBlank { null }, self = false,
                    photos = moment.photos, verifiedRemote = true,
                    likes = moment.likes, liked = moment.liked, onLike = { onLike(moment.commitment) })
            }
            if (remote.isEmpty() && !loading && !needsSignature && feedError == null) {
                Text(tr(Message.NoOneElseHasPostedToday), style = BodyMd, color = Muted)
            }
            if (hasMore) TextButton(onClick = onMore, enabled = !loading) { Text(tr(Message.MoreMoments)) }
        }
    }

    reporting?.let { name ->
        AlertDialog(
            onDismissRequest = { reporting = null },
            containerColor = PanelRaised,
            title = { Text(tr(Message.ReportingUnavailable)) },
            text = {
                Text(tr(Message.ReportingUnavailableExplanation, name))
            },
            confirmButton = { TextButton(onClick = { reporting = null }) { Text(tr(Message.GotIt)) } },
        )
    }
}
