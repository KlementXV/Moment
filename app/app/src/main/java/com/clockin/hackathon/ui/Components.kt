package com.clockin.hackathon.ui

import com.clockin.hackathon.i18n.Message
import com.clockin.hackathon.i18n.tr

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Remove
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Les pièces que plusieurs écrans partagent : la feuille du bas, l'obturateur,
 * le bandeau, le sélecteur de montant. Toutes reprennent les mesures de la
 * maquette telles quelles — 1 dp pour 1 px.
 */

// ── Feuille du bas ─────────────────────────────────────────────────────────

/**
 * La feuille glissée sur l'écran, avec son voile.
 *
 * Le voile ferme au toucher, comme la poignée et la croix : trois sorties pour
 * une feuille qui ne piège jamais.
 */
@Composable
internal fun MomentSheet(
    title: String,
    onClose: (() -> Unit)?,
    content: @Composable ColumnScope.() -> Unit,
) {
    Box(Modifier.fillMaxSize()) {
        // `detectTapGestures` consomme le toucher quoi qu'il arrive, y compris
        // quand il n'y a pas de fermeture à offrir : sans lui, le décor
        // resterait cliquable pendant qu'une signature est en vol.
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .6f))
            .pointerInput(onClose) { detectTapGestures { onClose?.invoke() } })
        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth()
            .clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)).background(PanelRaised)
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom))
            .padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Box(Modifier.align(Alignment.CenterHorizontally).size(32.dp, 4.dp)
                .clip(CircleShape).background(Outline))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(title, Modifier.weight(1f), style = HeadlineSm)
                if (onClose != null) IconButton(onClick = onClose, modifier = Modifier.offset(x = 12.dp)) {
                    Icon(Icons.Outlined.Close, contentDescription = tr(Message.Close), tint = White)
                }
            }
            content()
        }
    }
}

/** La note sous le bouton d'une feuille : les frais, la précision qui rassure. */
@Composable
internal fun SheetNote(text: String) {
    Text(text, Modifier.fillMaxWidth(), style = BodySm, color = Muted, textAlign = TextAlign.Center)
}

// ── Bandeau ────────────────────────────────────────────────────────────────

/** Contraste renversé : ce bandeau doit se détacher de tout ce qu'il recouvre. */
@Composable
internal fun Snackbar(text: String, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().widthIn(max = 360.dp).heightIn(min = 48.dp)
        .clip(RadiusMd).background(InverseSurface).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(text, Modifier.weight(1f), style = BodyMd, color = InkInverse)
    }
}

// ── Sélecteur de montant ───────────────────────────────────────────────────

/**
 * Le montant de la mise : deux boutons, un chiffre, des préréglages.
 *
 * [max] borne le pas et les préréglages au solde réel. Dépasser n'est pas
 * interdit à l'écran puis refusé à la signature — c'est empêché avant.
 */
@Composable
internal fun AmountPicker(
    value: Long,
    min: Long,
    step: Long,
    max: Long,
    presets: List<Long>,
    supporting: String,
    error: Boolean,
    onChange: (Long) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween) {
            StepButton(Icons.Outlined.Remove, tr(Message.DecreaseStake),
                enabled = value - step >= min) { onChange(value - step) }
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AmountInput(value, max, error, onChange)
                Text("SKR", Modifier.padding(bottom = 8.dp), style = TitleSm, color = Muted)
            }
            StepButton(Icons.Outlined.Add, tr(Message.IncreaseStake),
                enabled = value + step <= max) { onChange(value + step) }
        }
        if (presets.isNotEmpty()) Row(Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)) {
            presets.forEach { preset ->
                Chip(skr(preset), selected = preset == value, enabled = preset <= max) { onChange(preset) }
            }
        }
        Text(supporting, Modifier.fillMaxWidth(), style = BodyMd,
            color = if (error) Danger else Muted, textAlign = TextAlign.Center)
    }
}

/**
 * Le montant lui-même, tapé à la main.
 *
 * Le texte affiché est un état à part : le rendre à partir de [value] à chaque
 * frappe effacerait une saisie en cours — « 12, » n'est pas encore un nombre,
 * et « 0012 » se réécrirait sous les doigts. On ne le resynchronise que quand
 * la valeur change ailleurs (les pas, les préréglages).
 *
 * Le plafond est appliqué à la frappe : dépasser son solde ne doit pas être
 * possible à l'écran puis refusé à la signature.
 */
@Composable
private fun AmountInput(value: Long, max: Long, error: Boolean, onChange: (Long) -> Unit) {
    var text by remember { mutableStateOf(formatSkrInput(value)) }
    // Ce que le champ a lui-même émis en dernier. Sans ce repère, vider le
    // champ ferait tomber la valeur à zéro, et la resynchronisation y
    // réécrirait « 0 » sous les doigts : on ne pourrait plus l'effacer.
    var emitted by remember { mutableLongStateOf(value) }
    LaunchedEffect(value) {
        if (value != emitted) { text = formatSkrInput(value); emitted = value }
    }
    val style = TextStyle(fontSize = 48.sp, lineHeight = 48.sp, fontWeight = FontWeight.Bold,
        letterSpacing = (-.96).sp, color = if (error) Muted else White,
        fontFeatureSettings = "tnum")
    Box(contentAlignment = Alignment.CenterStart) {
        // `BasicTextField` n'a pas de largeur naturelle : ce texte invisible,
        // de même style et de même contenu, la lui donne.
        Text(text.ifEmpty { "0" }, style = style, modifier = Modifier.alpha(0f))
        BasicTextField(
            value = text,
            onValueChange = { raw ->
                val kept = raw.filter { it.isDigit() || it == ',' || it == '.' }
                val amount = parseSkrAmount(kept)
                // Une saisie vide ou en cours reste à l'écran telle quelle : la
                // valeur, elle, tombe à zéro le temps qu'elle se termine.
                val next = (amount ?: 0).coerceAtMost(max)
                text = if (amount != null && amount > max) formatSkrInput(max) else kept
                emitted = next
                onChange(next)
            },
            textStyle = style,
            cursorBrush = SolidColor(Accent),
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            modifier = Modifier.matchParentSize()
                .semantics { contentDescription = tr(Message.AmountInSkr) },
        )
    }
}

@Composable
private fun StepButton(icon: ImageVector, label: String, enabled: Boolean, onClick: () -> Unit) {
    IconButton(onClick = onClick, enabled = enabled,
        modifier = Modifier.size(48.dp).semantics { contentDescription = label }) {
        Icon(icon, contentDescription = null, tint = if (enabled) White else Muted.copy(alpha = .38f))
    }
}

@Composable
private fun Chip(label: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Box(Modifier.heightIn(min = 48.dp), contentAlignment = Alignment.Center) {
        Box(Modifier.height(40.dp).widthIn(min = 64.dp).clip(CircleShape)
            .background(if (selected) AccentContainer else Color.Transparent)
            .then(if (selected) Modifier
                else Modifier.border(1.dp, Outline.copy(alpha = if (enabled) 1f else .38f), CircleShape))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp),
            contentAlignment = Alignment.Center) {
            Text(label, style = TitleSm,
                color = when {
                    selected -> OnAccentContainer
                    enabled -> White
                    else -> Muted.copy(alpha = .38f)
                })
        }
    }
}

// ── Obturateur ─────────────────────────────────────────────────────────────

/**
 * Le bouton du jour : un anneau qui se vide à mesure que la journée passe.
 *
 * [fraction] va de 1 au début de la journée UTC à 0 à sa fin ; c'est lui, et
 * non l'heure affichée, qui porte l'urgence au premier coup d'œil.
 */
@Composable
internal fun Shutter(
    fraction: Float,
    timeLabel: String,
    hint: String,
    late: Boolean = false,
    enabled: Boolean = true,
    loading: Boolean = false,
    icon: String? = null,
    label: String = tr(Message.CaptureMyMoment),
    onCapture: (() -> Unit)? = null,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val interaction = remember { MutableInteractionSource() }
        val pressed by interaction.collectIsPressedAsState()
        // La maquette enfonce le cœur, pas l'anneau : le repère de progression
        // doit rester fixe pendant qu'on appuie.
        val scale by animateFloatAsState(if (pressed) .94f else 1f,
            tween(120, easing = CubicBezierEasing(.2f, 0f, 0f, 1f)), label = "shutterPress")
        // Sans geste à offrir — le moment est déjà pris — l'anneau n'est plus un
        // bouton : il ne prend ni le focus ni les annonces d'un lecteur d'écran.
        val button = if (onCapture == null) Modifier else Modifier
            .clip(CircleShape)
            .clickable(enabled = enabled, interactionSource = interaction, indication = null,
                role = androidx.compose.ui.semantics.Role.Button, onClick = onCapture)
            .semantics { contentDescription = label }
        Box(contentAlignment = Alignment.Center) {
            // `--glow-brand: 0 8px 32px #ea766659` : un halo corail décalé vers
            // le bas. Il lui faut déborder du bouton pour exister, donc sa toile
            // est plus grande que lui — dessinée dessous, elle n'intercepte rien.
            if (!late && (enabled || loading)) Canvas(Modifier.size(168.dp)) {
                val glow = center + Offset(0f, 8.dp.toPx())
                drawCircle(
                    Brush.radialGradient(
                        0f to Coral.copy(alpha = .42f),
                        .42f to Coral.copy(alpha = .30f),
                        1f to Color.Transparent,
                        center = glow, radius = 84.dp.toPx(),
                    ),
                    radius = 84.dp.toPx(), center = glow,
                )
            }
            Box(Modifier.size(96.dp).then(button), contentAlignment = Alignment.Center) {
                Canvas(Modifier.fillMaxSize()) {
                    val stroke = 4.dp.toPx()
                    val inset = stroke / 2
                    drawArc(SurfaceHigh, 0f, 360f, false,
                        topLeft = Offset(inset, inset),
                        size = androidx.compose.ui.geometry.Size(size.width - stroke, size.height - stroke),
                        style = Stroke(stroke))
                    drawArc(if (late) Brush.linearGradient(listOf(Danger, Danger)) else GradientBrand,
                        -90f, 360f * fraction.coerceIn(0f, 1f), false,
                        topLeft = Offset(inset, inset),
                        size = androidx.compose.ui.geometry.Size(size.width - stroke, size.height - stroke),
                        style = Stroke(stroke, cap = StrokeCap.Round))
                }
                Box(Modifier.size(72.dp).graphicsLayer { scaleX = scale; scaleY = scale }
                    .clip(CircleShape)
                    .alpha(if (enabled || loading) 1f else .38f)
                    .background(if (late) SolidColor(SurfaceHigh) else GradientBrand),
                    contentAlignment = Alignment.Center) {
                    when {
                        loading -> CircularProgressIndicator(Modifier.size(28.dp), color = OnBrand, strokeWidth = 2.dp)
                        icon != null -> Glyph(icon, OnBrand)
                    }
                }
            }
        }
        Text(timeLabel, fontSize = 20.sp, lineHeight = 24.sp, fontWeight = FontWeight.Bold)
        Text(hint, Modifier.widthIn(max = 240.dp), style = BodyMd, color = Muted, textAlign = TextAlign.Center)
    }
}

// ── Carte photo ────────────────────────────────────────────────────────────

/**
 * Le cadre d'un Moment : 4:5, coins de 24 dp, voile bas pour que les étiquettes
 * restent lisibles. Le viseur, l'aperçu et le fil partagent ce cadre — sans
 * quoi la photo changerait de proportion entre le moment où on la prend et
 * celui où on la revoit.
 *
 * [image] remplit le cadre ; [overlay] se pose par-dessus le voile.
 */
@Composable
internal fun MomentPhoto(
    modifier: Modifier = Modifier,
    overlay: @Composable BoxScope.() -> Unit = {},
    image: @Composable BoxScope.() -> Unit,
) {
    Box(modifier.fillMaxWidth().aspectRatio(4f / 5f).clip(RadiusXl).background(PhotoField)) {
        image()
        Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().fillMaxHeight(.3f)
            .background(GradientScrim))
        overlay()
    }
}

/** Le fond d'un cadre encore vide — ou d'une photo qui ne remplit pas tout. */
internal val PhotoField = Brush.linearGradient(listOf(Coral.copy(alpha = .24f), OnBrand))

/**
 * La vignette dans le coin : la seconde vue d'un Moment.
 *
 * Elle occupe 28 % de la largeur du cadre, comme dans la maquette — une part,
 * pas une taille fixe, pour que la proportion tienne sur toutes les largeurs.
 */
@Composable
internal fun BoxScope.MomentSelfie(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(modifier.align(Alignment.TopStart).padding(12.dp).fillMaxWidth(.28f)
        .aspectRatio(3f / 4f).clip(RadiusLg).background(OnBrand)
        .border(2.dp, White, RadiusLg),
        contentAlignment = Alignment.Center, content = content)
}

// ── Champ de texte ─────────────────────────────────────────────────────────

/**
 * Le champ de la maquette : une boîte de 56 dp, un liseré qui s'épaissit et
 * passe à l'accent au focus, une étiquette qui monte, et un pied en deux
 * colonnes — l'aide à gauche, le compteur à droite.
 *
 * [maxChars] est compté en points de code, comme le codec du brouillon : un
 * emoji vaut un caractère à l'écran comme au stockage.
 */
@Composable
internal fun MomentTextField(
    value: String,
    label: String,
    supporting: String,
    maxChars: Int,
    enabled: Boolean = true,
    onValueChange: (String) -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val count = value.codePointCount(0, value.length)
    val border by animateDpAsState(if (focused) 2.dp else 1.dp, tween(150), label = "fieldBorder")
    val borderColor by animateColorAsState(if (focused) Accent else Outline, tween(150), label = "fieldBorderColor")
    val raised = focused || value.isNotEmpty()
    val labelScale by animateFloatAsState(if (raised) .75f else 1f,
        tween(150, easing = CubicBezierEasing(.2f, 0f, 0f, 1f)), label = "fieldLabel")
    val labelOffset by animateDpAsState(if (raised) 5.dp else 16.dp,
        tween(150, easing = CubicBezierEasing(.2f, 0f, 0f, 1f)), label = "fieldLabelOffset")
    Column(Modifier.fillMaxWidth().alpha(if (enabled) 1f else .38f)) {
        BasicTextField(
            value = value,
            onValueChange = { next ->
                // On tronque au lieu de refuser : un collage trop long donne les
                // 80 premiers caractères, pas un champ qui ne réagit plus.
                val trimmed = if (next.codePointCount(0, next.length) <= maxChars) next
                    else next.substring(0, next.offsetByCodePoints(0, maxChars))
                onValueChange(trimmed)
            },
            enabled = enabled,
            interactionSource = interaction,
            textStyle = BodyLg.copy(color = White),
            cursorBrush = SolidColor(Accent),
            singleLine = true,
            modifier = Modifier.fillMaxWidth().height(56.dp)
                .semantics { contentDescription = label },
            decorationBox = { field ->
                Box(Modifier.fillMaxSize().clip(RadiusLg).background(Panel)
                    .border(border, borderColor, RadiusLg).padding(horizontal = 16.dp)) {
                    Text(label, Modifier.align(Alignment.TopStart).offset(y = labelOffset)
                        .graphicsLayer {
                            scaleX = labelScale; scaleY = labelScale
                            transformOrigin = TransformOrigin(0f, 0f)
                        },
                        style = BodyLg, color = if (focused) Accent else Muted)
                    // Le texte saisi loge sous l'étiquette haute : 20 dp de marge
                    // en tête, comme la maquette, et non un centrage qui les
                    // ferait se chevaucher.
                    Box(Modifier.align(Alignment.TopStart).padding(top = 20.dp)) { field() }
                }
            },
        )
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween) {
            Text(supporting, style = BodySm, color = Muted)
            Text("$count/$maxChars", style = BodySm, color = Muted)
        }
    }
}

// ── Étiquette ──────────────────────────────────────────────────────────────

/** Le ton d'une étiquette : ce qu'elle dit, pas la couleur qu'elle prend. */
internal enum class TagTone { Neutral, Alert, Success, Danger }

/** `.mo-tag` : une pastille de 24 dp qui qualifie ce qu'elle accompagne. */
@Composable
internal fun MomentTag(label: String, tone: TagTone = TagTone.Neutral, modifier: Modifier = Modifier) {
    val (fill, ink) = when (tone) {
        TagTone.Neutral -> SurfaceHigh to White
        TagTone.Alert -> AccentContainer to OnAccentContainer
        TagTone.Success -> SuccessContainer to OnSuccessContainer
        TagTone.Danger -> DangerContainer to OnDangerContainer
    }
    Text(label, modifier.clip(CircleShape).background(fill).padding(horizontal = 8.dp),
        color = ink, style = LabelMd, maxLines = 1)
}

// ── Boutons secondaires ────────────────────────────────────────────────────

/** `.mo-btn-tonal` : l'action utile mais pas principale. */
@Composable
internal fun TonalButton(text: String, enabled: Boolean = true, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(modifier.fillMaxWidth().heightIn(min = 48.dp).clip(CircleShape)
        .background(if (enabled) SurfaceHigh else SurfaceHigh.copy(alpha = .38f))
        .clickable(enabled = enabled, role = androidx.compose.ui.semantics.Role.Button, onClick = onClick)
        .padding(horizontal = 24.dp, vertical = 14.dp),
        contentAlignment = Alignment.Center) {
        Text(text, style = LabelLg, color = if (enabled) White else Muted)
    }
}

/** `.mo-btn-outlined` : l'action qu'on n'encourage pas, mais qu'on n'enterre pas. */
@Composable
internal fun SecondaryButton(text: String, enabled: Boolean = true, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(modifier.fillMaxWidth().heightIn(min = 48.dp).clip(CircleShape)
        .border(1.dp, if (enabled) Outline else White.copy(alpha = .12f), CircleShape)
        .clickable(enabled = enabled, role = androidx.compose.ui.semantics.Role.Button, onClick = onClick)
        .padding(horizontal = 24.dp, vertical = 14.dp),
        contentAlignment = Alignment.Center) {
        Text(text, style = LabelLg, color = if (enabled) White else Muted)
    }
}

/** `.mo-btn-text`, aligné sur le bord gauche de la carte qui le porte. */
@Composable
internal fun CardTextButton(text: String, enabled: Boolean = true, onClick: () -> Unit) {
    Box(Modifier.heightIn(min = 48.dp).clip(CircleShape)
        .clickable(enabled = enabled, role = androidx.compose.ui.semantics.Role.Button, onClick = onClick)
        .padding(horizontal = 12.dp, vertical = 14.dp),
        contentAlignment = Alignment.Center) {
        Text(text, style = LabelLg, color = if (enabled) Accent else Muted)
    }
}

// ── Adresse de wallet ──────────────────────────────────────────────────────

/**
 * `.mo-wallet` : l'adresse raccourcie, et la copie au même endroit.
 *
 * La pastille entière est la cible — la maquette y loge un bouton de 40 dp
 * débordant ; une seule zone de 40 dp fait le même travail sans empiler deux
 * cibles concentriques qu'un lecteur d'écran annoncerait deux fois.
 */
@Composable
internal fun WalletAddress(address: String, copied: Boolean, onCopy: () -> Unit) {
    Row(Modifier.height(40.dp).clip(CircleShape).background(SurfaceHigh)
        .clickable(onClickLabel = tr(Message.CopyAddress), role = androidx.compose.ui.semantics.Role.Button,
            onClick = onCopy)
        .padding(start = 12.dp, end = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(if (copied) tr(Message.AddressCopied) else "${address.take(4)}…${address.takeLast(4)}",
            style = LabelLg, color = if (copied) Success else White,
            letterSpacing = .28.sp)
        Glyph(if (copied) "check" else "copy", if (copied) Success else Muted)
    }
}

// ── Cartes de la mise et de la pool ────────────────────────────────────────

/** Une carte du profil : `surface`, rayon 32, l'ombre de la maquette en moins. */
@Composable
private fun MomentCardSurface(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().clip(Radius2xl).background(Panel)
        .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp), content = content)
}

/**
 * `.mo-stake` : ce qui est en jeu, son état, et la seule action qui s'y rapporte.
 *
 * [action] est nullable : sans wallet connecté, la carte informe sans rien
 * proposer plutôt que d'afficher un bouton mort.
 */
@Composable
internal fun StakeCard(
    amount: Long,
    tag: String,
    tone: TagTone,
    line: String,
    action: String? = null,
    actionEnabled: Boolean = true,
    onAction: () -> Unit = {},
) {
    MomentCardSurface {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically) {
            Text(tr(Message.Stake), style = LabelLg, color = Muted)
            MomentTag(tag, tone)
        }
        Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(skr(amount), style = SkrLg)
            Text("SKR", Modifier.padding(bottom = 3.dp), style = LabelLg, color = Muted,
                letterSpacing = .56.sp)
        }
        Text(line, style = BodyMd)
        if (action != null) Box(Modifier.padding(top = 8.dp).offset(x = (-12).dp)) {
            CardTextButton(action, actionEnabled, onAction)
        }
    }
}

/**
 * `.mo-pool` : la réserve du jour.
 *
 * [total] et [mine] viennent de la chaîne — le solde de la pool et ce qu'un
 * Moment publié rapporte, plafond compris. [counts] n'a pas de source : il est
 * étiqueté comme tel, pour qu'on ne le lise pas comme un chiffre de la chaîne.
 */
@Composable
internal fun PoolCard(total: Long, counts: String?, mineLabel: String, mine: Long) {
    MomentCardSurface {
        Row(verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.size(32.dp).clip(CircleShape).background(SurfaceHigh),
                contentAlignment = Alignment.Center) { Glyph("pool", White) }
            Text(tr(Message.TodaySPool), style = LabelLg, color = Muted)
        }
        Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(skr(total), style = SkrLg)
            Text("SKR", Modifier.padding(bottom = 3.dp), style = LabelLg, color = Muted,
                letterSpacing = .56.sp)
        }
        if (counts != null) Row(verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(counts, style = BodyMd, color = Muted)
            MomentTag(tr(Message.Demo), TagTone.Alert)
        }
        Row(Modifier.fillMaxWidth().padding(top = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween) {
            Text(mineLabel, style = LabelLg, color = Muted)
            Text("${skr(mine)} SKR", style = LabelLg, color = Success)
        }
    }
}
