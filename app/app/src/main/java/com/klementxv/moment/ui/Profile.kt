package com.klementxv.moment.ui

import com.klementxv.moment.i18n.Message
import com.klementxv.moment.i18n.tr
import com.klementxv.moment.i18n.AppLanguage

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.window.Dialog
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.klementxv.moment.BuildConfig
import com.klementxv.moment.ChainState
import com.klementxv.moment.SkrUnit
import kotlinx.coroutines.delay

private val CardShape = RoundedCornerShape(18.dp)

private const val URGENT_SECONDS = 3 * 3600L

private const val NB = " "

private const val DEFAULT_WITHDRAWAL_SECONDS = 172_800L

@Composable
internal fun Profile(
    state: ChainState,
    now: Long,
    wallet: String?,
    busy: Boolean,
    error: String?,
    nickname: String,
    skrName: String?,
    skrLoading: Boolean,
    skrFailed: Boolean,
    favorites: Set<String>,
    onToggleFavorite: (String) -> Unit,
    onRetrySkr: () -> Unit,
    onNicknameChange: (String) -> Unit,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onFaucet: () -> Unit,
    onStake: (Long) -> Unit,
    onExit: () -> Unit,
    onCancel: () -> Unit,
    onWithdraw: () -> Unit,
    demoFeed: Boolean,
) {
    val exiting = state.exitUnlockAt > 0
    val withdrawable = exiting && now >= state.exitUnlockAt
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(32.dp)) {
        Identity(wallet, nickname, skrName, skrLoading, skrFailed, onRetrySkr, onNicknameChange)
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Stake(state, now, wallet, busy, exiting, withdrawable, onExit, onCancel, onFaucet, onStake)
            Pool(state, demoFeed)
            PendingGain(state)
        }
        if (error != null) ErrorCard(error)
        when {
            wallet == null -> ConnectSection(busy, onConnect)
            withdrawable -> WithdrawSection(state, busy, onWithdraw)
            exiting -> ExitPendingSection(state, now, busy, onCancel)
            !state.active -> AddSkr(state, busy, onFaucet, onStake)
        }
        Favorites(favorites, onToggleFavorite)
        Activity()
        SettingsSection(state, now)
        if (wallet != null) WalletActions(wallet, busy, onDisconnect)
    }
}


@Composable
private fun Identity(
    wallet: String?, nickname: String, skrName: String?, skrLoading: Boolean,
    skrFailed: Boolean, onRetrySkr: () -> Unit, onNicknameChange: (String) -> Unit,
) {
    val context = LocalContext.current
    val displayName = nickname.ifBlank { skrName.orEmpty() }
    var editing by rememberSaveable(wallet) { mutableStateOf(false) }
    var draft by rememberSaveable(wallet) { mutableStateOf("") }
    var copied by remember(wallet) { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) { delay(2000); copied = false }
    }
    val cleaned = draft.trim()
    val valid = cleaned.isNotEmpty() && validNickname(cleaned)
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Avatar(displayName, ring = wallet != null, size = 56.dp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(displayName.ifBlank { if (wallet == null) tr(Message.Welcome) else tr(Message.Unnamed) },
                    style = TitleLg)
                if (wallet != null) WalletAddress(wallet, copied) {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText(tr(Message.SolanaAddress), wallet))
                    copied = true
                } else Text(tr(Message.ConnectYourWalletToCreateYourProfile),
                    style = BodyMd, color = Muted)
            }
        }
        when {
            wallet == null -> Unit
            skrLoading -> Text(tr(Message.LookingUpYourSkrName), style = BodySm, color = Muted)
            skrFailed -> Column {
                Text(tr(Message.SkrLookupIsCurrentlyUnavailable), style = BodySm, color = Muted)
                TextButton(onClick = onRetrySkr) { Text(tr(Message.RetryLookup), color = Accent) }
            }
            skrName == null -> Text(tr(Message.NoSkrNameFoundForThisWallet), style = BodySm, color = Muted)
            nickname.isNotBlank() -> TextButton(onClick = { onNicknameChange("") }) {
                Text(tr(Message.Use, skrName), color = Accent)
            }
        }
        if (wallet != null) TonalButton(
            if (nickname.isBlank()) tr(Message.ChooseANickname) else tr(Message.EditMyNickname),
        ) { draft = nickname; editing = true }
    }
    if (editing) AlertDialog(
        onDismissRequest = { editing = false }, title = { Text(tr(Message.YourNickname)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(tr(Message.ChooseANameToPersonalizeYourProfile), color = Muted)
                OutlinedTextField(value = draft, onValueChange = { draft = it },
                    label = { Text(tr(Message.Nickname)) }, singleLine = true,
                    isError = draft.isNotEmpty() && !valid,
                    supportingText = { Text(tr(Message.CharactersLettersNumbersSpaces)) })
                Text(tr(Message.YourNicknameIsSavedOnThisDevice),
                    color = Muted, fontSize = 12.sp)
            }
        },
        confirmButton = { TextButton(enabled = valid, onClick = { onNicknameChange(cleaned); editing = false }) { Text(tr(Message.Save)) } },
        dismissButton = { TextButton(onClick = { editing = false }) { Text(tr(Message.Cancel)) } },
    )
}

internal fun validNickname(value: String): Boolean = value.trim().let { text ->
    text.isEmpty() || (text.length in 2..24 && text.all { it.isLetterOrDigit() || it in " _-." })
}


@Composable
private fun Stake(
    state: ChainState, now: Long, wallet: String?, busy: Boolean,
    exiting: Boolean, withdrawable: Boolean, onExit: () -> Unit, onCancel: () -> Unit,
    onFaucet: () -> Unit, onStake: (Long) -> Unit,
) {
    var adding by rememberSaveable { mutableStateOf(false) }
    val left = secondsUntilNextMoment(now)
    val urgent = !state.posted && left <= URGENT_SECONDS
    val below = state.active && state.balance < state.minStake
    val tag = when {
        !state.active -> tr(Message.NoStake)
        withdrawable -> tr(Message.Unlocked)
        exiting -> tr(Message.WithdrawalPending)
        below -> tr(Message.BelowMinimum)
        else -> tr(Message.AllSet)
    }
    val tone = when {
        !state.active -> TagTone.Neutral
        withdrawable -> TagTone.Success
        exiting || below -> TagTone.Danger
        urgent -> TagTone.Alert
        else -> TagTone.Success
    }
    val line = when {
        wallet == null -> tr(Message.ConnectYourWalletToStakeAndStart)
        !state.active -> tr(Message.ChooseAnAmountInSkrToStart)
        withdrawable -> tr(Message.AvailableSince, localDateTime(state.exitUnlockAt))
        below -> tr(Message.YouNeedSkrStakedToPublishTop, skr(state.minStake), NB)
        state.posted -> tr(Message.TodaySMomentIsPublishedNextMoment, nextMomentAt(now))
        else -> tr(Message.YouHaveLeftToPostAndKeep, countdown(left))
    }
    StakeCard(
        amount = state.balance,
        tag = tag, tone = tone, line = line,
        action = when {
            exiting && !withdrawable -> tr(Message.CancelWithdrawal)
            state.active && !exiting -> tr(Message.RequestWithdrawal2)
            else -> null
        },
        actionEnabled = !busy,
        onAction = if (exiting) onCancel else onExit,
        secondAction = if (wallet != null && state.active && !exiting) tr(Message.AddSkr) else null,
        onSecondAction = { adding = true },
    )
    if (adding) ProfileDialog(tr(Message.AddSkr), { adding = false }) {
        AddSkr(state, busy, onFaucet = { onFaucet(); adding = false },
            onStake = { onStake(it); adding = false })
    }
}

@Composable
private fun Pool(state: ChainState, demo: Boolean) {
    PoolCard(
        total = state.todayPoolTotal,
        counts = if (demo) tr(Message.WinnersMissedMoments) else null,
        mineLabel = if (state.posted) tr(Message.YourShareToday) else tr(Message.YourShareIfYouPost),
        mine = state.myShareToday,
    )
}

@Composable
private fun PendingGain(state: ChainState) {
    val at = state.payoutAt ?: return
    if (state.pendingGain <= 0) return
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(tr(Message.PendingGain, skr(state.pendingGain), NB), style = LabelLg, color = Success)
        Text(tr(Message.PaidAt, clockTime(at)), style = LabelLg, color = Muted)
    }
}


@Composable
private fun Favorites(favorites: Set<String>, onToggle: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(tr(Message.Favorites), style = HeadlineSm)
            Text(tr(Message.YourFavoritesAppearFirstInTheFeed), style = BodyMd, color = Muted)
        }
        if (favorites.isEmpty()) EmptyNote(
            tr(Message.NoOneYetFavoriteAMomentIn)
        ) else favorites.sorted().forEach { name ->
            Row(Modifier.fillMaxWidth().heightIn(min = 56.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Avatar(name, ring = false)
                Text(name, Modifier.weight(1f), style = TitleMd)
                IconButton(onClick = { onToggle(name) },
                    modifier = Modifier.size(48.dp)
                        .semantics { contentDescription = tr(Message.RemoveFromFavorites2, name) }) {
                    Icon(Icons.Outlined.Star, contentDescription = null, tint = Accent)
                }
            }
        }
    }
}

@Composable
private fun Activity() {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(tr(Message.Activity), style = HeadlineSm)
        EmptyNote(
            tr(Message.ActivityUnavailableExplanation)
        )
    }
}

@Composable
private fun EmptyNote(text: String) {
    Text(text, Modifier.fillMaxWidth().clip(RadiusLg).background(Panel).padding(16.dp),
        style = BodyMd, color = Muted)
}


@Composable
private fun ConnectSection(busy: Boolean, onConnect: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        SectionTitle(tr(Message.YourWallet))
        InfoCard(
            tr(Message.NotConnectedYet),
            tr(Message.ConnectWalletExplanation, BuildConfig.NETWORK),
        )
        PrimaryButton(
            if (busy) "Connexion…" else tr(Message.ConnectMyWallet),
            enabled = !busy, loading = busy, onClick = onConnect,
        )
    }
}

@Composable
private fun FaucetSection(state: ChainState, busy: Boolean, onFaucet: () -> Unit) {
    val amount = state.config?.faucetAmount ?: (1000 * SkrUnit.unit)
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        SectionTitle(tr(Message.GettingStarted))
        if (state.config?.faucetEnabled == false) {
            InfoCard(
                tr(Message.TestDistributionUnavailable),
                tr(Message.FaucetUnavailableExplanation),
            )
        } else {
            InfoCard(
                tr(Message.GetYourTestSkr),
                tr(Message.FaucetExplanation, skr(amount), NB),
            )
            PrimaryButton(
                if (busy) tr(Message.TransactionInProgress) else tr(Message.ReceiveSkr, skr(amount), NB),
                enabled = !busy, loading = busy, onClick = onFaucet,
            )
        }
    }
}

@Composable
private fun StakeSection(state: ChainState, busy: Boolean, onStake: (Long) -> Unit) {
    val minimum = state.minStake
    val floor = when {
        !state.active -> minimum
        state.balance < minimum -> minimum - state.balance
        else -> SkrUnit.unit
    }
    val presets = remember(floor) { listOf(floor, floor * 5, floor * 10).distinct() }
    var amount by rememberSaveable(floor) { mutableLongStateOf(floor) }
    val affordable = floor <= state.tokenBalance
    val enough = amount >= floor && amount <= state.tokenBalance
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        SectionTitle(if (state.active) tr(Message.AddToMyStake) else tr(Message.ChooseMyStake))
        AmountPicker(
            value = amount,
            min = floor,
            step = floor,
            max = state.tokenBalance,
            presets = presets,
            supporting = when {
                !affordable -> tr(Message.YouNeedAtLeastSkrAndYou, skr(floor), NB, skr(state.tokenBalance))
                amount < floor -> tr(Message.MinimumSkr, skr(floor), NB)
                else -> tr(Message.SkrAvailableInYourWallet, skr(state.tokenBalance), NB)
            },
            error = !enough,
            onChange = { amount = it },
        )
        PrimaryButton(
            if (busy) tr(Message.TransactionInProgress) else tr(Message.StakeSkr2, skr(amount), NB),
            enabled = !busy && enough, loading = busy, onClick = { onStake(amount) },
        )
    }
}

@Composable
private fun ExitPendingSection(state: ChainState, now: Long, busy: Boolean, onCancel: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        SectionTitle(tr(Message.WithdrawalPending))
        Column(
            Modifier.fillMaxWidth().clip(CardShape).background(Danger.copy(alpha = .07f))
                .border(1.dp, Danger.copy(alpha = .25f), CardShape).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(countdown(state.exitUnlockAt - now), color = Danger, style = SkrLg)
            Text(tr(Message.UnlocksOn, localDateTime(state.exitUnlockAt)), style = TitleSm)
            Text(tr(Message.KeepPostingEveryMissedUtcDayStill),
                color = Muted, style = BodyMd)
        }
        SecondaryButton(
            if (busy) tr(Message.TransactionInProgress) else tr(Message.CancelWithdrawal),
            enabled = !busy, onClick = onCancel,
        )
    }
}

@Composable
private fun WithdrawSection(state: ChainState, busy: Boolean, onWithdraw: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        SectionTitle(tr(Message.YourStakeIsUnlocked))
        InfoCard(
            tr(Message.AvailableSince2, localDateTime(state.exitUnlockAt)),
            tr(Message.PenaltiesStopAtUnlockTimeYouCan),
        )
        PrimaryButton(
            if (busy) tr(Message.TransactionInProgress) else tr(Message.WithdrawSkr, skr(state.balance), NB),
            enabled = !busy, loading = busy, onClick = onWithdraw,
        )
    }
}

@Composable
private fun WalletActions(wallet: String, busy: Boolean, onDisconnect: () -> Unit) {
    val uriHandler = LocalUriHandler.current
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(
            onClick = { uriHandler.openUri(explorerUrl(wallet)) },
            modifier = Modifier.weight(1f),
        ) { Text(tr(Message.ViewOnSolanaExplorer), color = Muted, fontSize = 13.sp) }
        TextButton(
            onClick = onDisconnect, enabled = !busy,
            modifier = Modifier.weight(1f),
        ) { Text(tr(Message.Disconnect), color = Muted, fontSize = 13.sp) }
    }
}

@Composable
private fun RulesContent(state: ChainState, now: Long) {
    val config = state.config
    val decay = percent(state.decayBps)
    val hours = (config?.withdrawalDelaySeconds ?: DEFAULT_WITHDRAWAL_SECONDS) / 3600
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        RulesDiagram(decay)
        Rule(tr(Message.EachPublishedMomentSharesTheDayPool,
            clockTime((state.day + 1) * DAY_SECONDS + (config?.poolCloseDelaySeconds ?: 21_600))))
        Rule(tr(Message.EachDayWithoutAMomentCostsOf, decay, NB, nextMomentAt(now)))
        Rule(tr(Message.AfterAWithdrawalRequestYourStakeBecomes, hours, NB))
        Text(
            if (config == null) tr(Message.ProvisionalSettingsWaitingToReadTheBlockchain)
            else tr(Message.SettingsReadFromTheBlockchainProvisionalDuring, BuildConfig.NETWORK),
            color = Muted, style = LabelSm,
        )
    }
}

@Composable
private fun Rule(text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(11.dp)) {
        Box(Modifier.padding(top = 7.dp).size(5.dp).clip(CircleShape).background(Accent))
        Text(text, color = Muted, style = BodyMd)
    }
}

@Composable
private fun ErrorCard(message: String) {
    Column(
        Modifier.fillMaxWidth().clip(CardShape).background(Danger.copy(alpha = .08f))
            .border(1.dp, Danger.copy(alpha = .28f), CardShape).padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(tr(Message.TheActionDidnTComplete), color = Danger, style = TitleSm)
        Text(message, color = White, style = BodyMd)
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = HeadlineSm)
}

private fun explorerUrl(wallet: String): String {
    val base = "https://explorer.solana.com/address/$wallet"
    val cluster = BuildConfig.NETWORK
    return if (cluster.isBlank() || cluster.startsWith("mainnet")) base else "$base?cluster=$cluster"
}

private fun percent(bps: Int): String =
    if (bps % 100 == 0) "${bps / 100}" else String.format(AppLanguage.locale, "%.2f", bps / 100.0)

@Composable
private fun LanguageContent() {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        LanguagePicker(AppLanguage.code, AppLanguage.deviceCode, AppLanguage::select)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(tr(Message.UseDeviceLanguage), Modifier.weight(1f), style = BodyMd)
            Switch(checked = AppLanguage.selection == null,
                onCheckedChange = { follow -> AppLanguage.select(if (follow) null else AppLanguage.code) })
        }
    }
}


private enum class ProfileSheet { Rules, Language }

@Composable
private fun SettingsSection(state: ChainState, now: Long) {
    var open by rememberSaveable { mutableStateOf<ProfileSheet?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SectionTitle(tr(Message.Settings))
        TonalButton(tr(Message.GameRules)) { open = ProfileSheet.Rules }
        TonalButton(tr(Message.ChangeLanguage)) { open = ProfileSheet.Language }
    }
    val close = { open = null }
    when (open) {
        ProfileSheet.Rules -> ProfileDialog(tr(Message.HowItWorks), close) { RulesContent(state, now) }
        ProfileSheet.Language -> ProfileDialog(tr(Message.Language), close) { LanguageContent() }
        null -> Unit
    }
}

@Composable
private fun AddSkr(state: ChainState, busy: Boolean, onFaucet: () -> Unit, onStake: (Long) -> Unit) {
    if (!state.faucetClaimed) FaucetSection(state, busy, onFaucet)
    else StakeSection(state, busy, onStake)
}

@Composable
private fun ProfileDialog(title: String, onDismiss: () -> Unit, content: @Composable () -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().clip(CardShape).background(PanelRaised)
                .verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Text(title, style = HeadlineSm)
            content()
            TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) {
                Text(tr(Message.Close), color = Accent)
            }
        }
    }
}

@Composable
private fun RulesDiagram(decay: String) {
    val transition = rememberInfiniteTransition(label = "rules")
    val phase by transition.animateFloat(0f, 2f,
        infiniteRepeatable(tween(6000, easing = LinearEasing)), label = "phase")
    val posted = phase < 1f
    val progress = ((phase % 1f) / .6f).coerceAtMost(1f)
    val tone = if (posted) Success else Danger
    val arrived by animateFloatAsState(if (progress >= 1f) 1f else .35f, label = "arrived")
    Row(
        Modifier.fillMaxWidth().clip(RadiusLg).background(Panel).padding(16.dp)
            .semantics { contentDescription = tr(Message.HowItWorks) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        DiagramNode(tr(Message.RulesStepStake), "SKR", Accent, 1f)
        Canvas(Modifier.weight(1f).height(24.dp).padding(horizontal = 6.dp)) {
            val y = size.height / 2
            val stroke = 2.dp.toPx()
            drawLine(Muted.copy(alpha = .3f), Offset(0f, y), Offset(size.width, y), stroke,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f)))
            drawLine(tone, Offset(0f, y), Offset(size.width * progress, y), stroke)
            drawCircle(tone, 5.dp.toPx(), Offset(size.width * progress, y))
        }
        DiagramNode(
            if (posted) tr(Message.RulesStepPosted) else tr(Message.RulesStepMissed),
            if (posted) "+SKR" else "−$decay$NB%", tone, arrived,
        )
    }
}

@Composable
private fun DiagramNode(label: String, value: String, tone: Color, alpha: Float) {
    Column(Modifier.width(92.dp).alpha(alpha), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.size(56.dp).clip(CircleShape).background(tone.copy(alpha = .12f))
            .border(1.5.dp, tone, CircleShape), contentAlignment = Alignment.Center) {
            Text(value, color = tone, style = TitleSm)
        }
        Text(label, color = Muted, style = LabelSm, maxLines = 1)
    }
}
