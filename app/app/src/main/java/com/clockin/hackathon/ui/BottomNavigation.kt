package com.clockin.hackathon.ui

import com.clockin.hackathon.i18n.Message
import com.clockin.hackathon.i18n.tr

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp

@Composable
internal fun DailyNavigation(page: MomentPage, posted: Boolean, onHome: () -> Unit, onCapture: () -> Unit) {
    if (!posted && page == MomentPage.Home) BottomBar(onHome, onCapture)
}

/**
 * Le verre de la barre : un fond sombre translucide que le fil traverse, posé
 * sous un voile clair qui s'éteint vers le bas. Sans flou d'arrière-plan
 * disponible partout, c'est la translucidité et le liseré qui font le verre.
 */
private val GlassFill: Brush = Brush.verticalGradient(
    listOf(Color(0x1FFFFFFF), Color(0x0AFFFFFF)),
)

/** Le liseré : vif en haut comme une arête éclairée, presque éteint en bas. */
private val GlassEdge: Brush = Brush.verticalGradient(
    listOf(Color(0x3DFFFFFF), Color(0x14FFFFFF)),
)

/**
 * La barre basse : une capsule flottante, pas un bandeau. Elle ne touche aucun
 * bord, laisse voir le fil au travers, et tient en 56 dp de haut.
 *
 * Deux destinations seulement. « Capturer » porte le dégradé de marque — c'est
 * le seul geste qui ouvre le fil, et il doit se voir comme tel.
 */
@Composable
internal fun BottomBar(onHome: () -> Unit, onCapture: () -> Unit) {
    val haptic = LocalHapticFeedback.current
    Box(Modifier.fillMaxWidth()
        .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom))
        .padding(horizontal = 24.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center) {
        Row(Modifier
            .shadow(20.dp, CircleShape, spotColor = Color.Black, ambientColor = Color.Black)
            .clip(CircleShape)
            .background(Ink.copy(alpha = .62f))
            .background(GlassFill)
            .border(1.dp, GlassEdge, CircleShape)
            .padding(5.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically) {
            NavigationItem(tr(Message.Home), Icons.Outlined.Home, selected = true, primary = false) {
                haptic.performHapticFeedback(HapticFeedbackType.SegmentTick)
                onHome()
            }
            NavigationItem(tr(Message.Capture), Icons.Outlined.PhotoCamera, selected = false, primary = true) {
                haptic.performHapticFeedback(HapticFeedbackType.Confirm)
                onCapture()
            }
        }
    }
}

/**
 * Un élément de la barre : une pastille ovale de 44 dp qui tient l'icône et son
 * libellé côte à côte. La pastille est ce qui se colore — jamais le verre.
 */
@Composable
private fun NavigationItem(
    label: String,
    icon: ImageVector,
    selected: Boolean,
    primary: Boolean,
    locked: Boolean = false,
    onClick: () -> Unit,
) {
    val indicator by animateColorAsState(
        if (selected) AccentContainer else Color.Transparent, tween(180), label = "navIndicator")
    val tint = when {
        primary -> OnBrand
        selected -> Accent
        else -> Muted
    }
    Row(Modifier.height(44.dp).clip(CircleShape)
        .background(if (primary) GradientBrand else SolidColor(indicator))
        .then(if (primary) Modifier.clickable(role = Role.Button, onClickLabel = tr(Message.OpenCamera), onClick = onClick)
            else Modifier.selectable(selected, role = Role.Tab, onClick = onClick))
        .semantics { if (locked) stateDescription = tr(Message.FeedLocked) }
        .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(7.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(19.dp))
        Text(label, color = if (primary) OnBrand else if (selected) White else Muted, style = LabelSm)
    }
}
