package com.clockin.hackathon.ui

import com.clockin.hackathon.i18n.Message
import com.clockin.hackathon.i18n.tr

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.cos
import kotlin.math.sin

/** Où en est la journée : l'anneau n'a que trois visages. */
internal enum class DayPhase { Open, Urgent, Done }

/** En dessous, la journée se termine : l'anneau passe en alerte. */
private const val URGENT_SECONDS = 3 * 3600L

/**
 * Ce que l'anneau du jour affiche, sans rien dessiner. Tout se déduit de
 * l'horloge UTC, du check-in du jour et de la série — aucun état à tenir.
 */
internal data class DayRingState(
    val phase: DayPhase,
    /** Part de la journée encore ouverte : 1 à minuit UTC, 0 à la seconde d'après. */
    val remaining: Float,
    val center: String,
    val label: String,
    val caption: String,
) {
    /** Ce qu'un lecteur d'écran doit entendre : le dessin ne lui dit rien. */
    val description: String get() = "$center $label. $caption."

    companion object {
        fun of(now: Long, posted: Boolean, streak: Long): DayRingState {
            val left = DAY_SECONDS - Math.floorMod(now, DAY_SECONDS)
            return DayRingState(
                phase = when {
                    posted -> DayPhase.Done
                    left <= URGENT_SECONDS -> DayPhase.Urgent
                    else -> DayPhase.Open
                },
                // Une fois le Moment publié, la journée ne se vide plus : l'anneau
                // se referme et reste plein jusqu'à minuit.
                remaining = if (posted) 1f else left.toFloat() / DAY_SECONDS,
                center = if (streak == 0L) tr(Message.FirstDay) else "$streak",
                label = when {
                    streak == 0L -> tr(Message.YourStreakStartsHere)
                    streak == 1L -> tr(Message.StreakDay)
                    else -> tr(Message.StreakDays)
                },
                caption = if (posted) tr(Message.MomentConfirmedWellDone) else tr(Message.YourTurn),
            )
        }
    }
}

/**
 * Le cercle qui donne son nom à la page : la journée UTC restante, tracée du
 * haut vers la droite. L'arc reprend le dégradé du logo, et sa tête est le
 * point du logo — il redescend vers midi à mesure que la journée s'épuise,
 * pour y revenir à minuit.
 */
@Composable
internal fun DayRing(state: DayRingState, modifier: Modifier = Modifier) {
    val accent by animateColorAsState(
        when (state.phase) {
            DayPhase.Done -> Success
            DayPhase.Urgent -> Danger
            DayPhase.Open -> Amber
        },
        tween(600), label = "dayRingAccent",
    )
    // La publication referme l'anneau d'un geste au lieu de le faire sauter.
    val sweep by animateFloatAsState(state.remaining, tween(600), label = "dayRingSweep")
    Column(
        modifier.fillMaxWidth().clearAndSetSemantics { contentDescription = state.description },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            Modifier.widthIn(max = 184.dp).fillMaxWidth().aspectRatio(1f),
            contentAlignment = Alignment.Center,
        ) {
            Canvas(Modifier.matchParentSize()) {
                val stroke = size.minDimension * .042f
                val radius = (size.minDimension - stroke) / 2f
                val halo = radius * 1.2f
                drawCircle(
                    Brush.radialGradient(
                        listOf(accent.copy(alpha = .14f), Color.Transparent),
                        center = center, radius = halo,
                    ),
                    halo,
                )
                // La piste n'est que le chemin parcouru : un trait fin, pour que le
                // temps qui reste garde tout le poids.
                drawCircle(Line, radius, style = Stroke(stroke * .40f))
                drawArc(
                    // En journée, le dégradé du logo ; en alerte et une fois publié,
                    // les deux bornes se rejoignent et l'anneau devient uni.
                    Brush.linearGradient(listOf(accent, if (state.phase == DayPhase.Open) Rose else accent)),
                    startAngle = -90f, sweepAngle = 360f * sweep, useCenter = false,
                    topLeft = Offset(center.x - radius, center.y - radius),
                    size = Size(radius * 2, radius * 2),
                    style = Stroke(stroke, cap = StrokeCap.Round),
                )
                val head = Math.toRadians((-90f + 360f * sweep).toDouble())
                val headCenter = Offset(
                    center.x + radius * cos(head).toFloat(),
                    center.y + radius * sin(head).toFloat(),
                )
                drawCircle(Ink, stroke * .95f, headCenter)
                drawCircle(accent, stroke * .56f, headCenter)
            }
            Column(
                Modifier.padding(horizontal = 20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Text(
                    state.center,
                    fontSize = if (state.center.all { it.isDigit() }) 56.sp else 32.sp,
                    lineHeight = 58.sp, fontWeight = FontWeight.Bold, letterSpacing = (-2).sp,
                    textAlign = TextAlign.Center,
                    style = LocalTextStyle.current.copy(fontFeatureSettings = "tnum"),
                )
                Text(
                    state.label, color = Muted, fontSize = 13.sp, lineHeight = 18.sp,
                    textAlign = TextAlign.Center,
                )
            }
        }
        if (state.phase == DayPhase.Done) Text(
            state.caption,
            color = if (state.phase == DayPhase.Open) Muted else accent,
            fontSize = 14.sp, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center,
            style = LocalTextStyle.current.copy(fontFeatureSettings = "tnum"),
        )
    }
}
