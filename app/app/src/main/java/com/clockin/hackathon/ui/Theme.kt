package com.clockin.hackathon.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Les tokens de la maquette « Moment », repris tels quels.
 *
 * Un fond presque noir mais chaud, une encre crème, et un seul dégradé de
 * marque — ambre, abricot, corail, rose — qui signe les actions principales.
 * Les noms disent la fonction, pas la teinte : un accent qui deviendrait vert
 * resterait `Accent`.
 */

// ── Surfaces ───────────────────────────────────────────────────────────────
internal val Ink = Color(0xFF0D0A08)          // --bg
internal val Panel = Color(0xFF16120F)        // --surface
internal val PanelRaised = Color(0xFF201A16)  // --surface-raised
internal val SurfaceHigh = Color(0xFF2B231D)  // --surface-high
internal val Line = Color(0xFF2F2721)         // --line
internal val Outline = Color(0xFF80726A)      // --outline

// ── Encre ──────────────────────────────────────────────────────────────────
internal val White = Color(0xFFF7F0E9)        // --ink
internal val Muted = Color(0xFFBCAE9F)        // --ink-muted
internal val OnBrand = Color(0xFF170D08)      // --on-brand

// ── Marque ─────────────────────────────────────────────────────────────────
internal val Amber = Color(0xFFF4C56F)
internal val Apricot = Color(0xFFEE9959)
internal val Coral = Color(0xFFEA7666)
internal val Rose = Color(0xFFE94F75)
internal val Accent = Color(0xFFF0A15E)               // --accent
internal val AccentContainer = Color(0xFF3D1D17)      // --accent-container
internal val OnAccentContainer = Color(0xFFFFD8CB)    // --on-accent-container

// ── Surface inverse ────────────────────────────────────────────────────────
// La snackbar renverse le contraste : crème sur sombre devient sombre sur crème.
internal val InverseSurface = Color(0xFFF4ECE4)
internal val InkInverse = Color(0xFF1D1510)
internal val AccentInverse = Color(0xFFB12048)

// ── États ──────────────────────────────────────────────────────────────────
internal val Success = Color(0xFF3ECFB0)
internal val SuccessContainer = Color(0xFF0F3A32)
internal val OnSuccessContainer = Color(0xFF8FE8D2)
internal val Danger = Color(0xFFFF7A7A)
internal val DangerContainer = Color(0xFF481616)
internal val OnDangerContainer = Color(0xFFFFC9C9)
internal val Focus = Color(0xFFF8CF7A)

/**
 * `linear-gradient(135deg, …)` en CSS part du coin haut-gauche vers le bas-droit.
 * Compose veut des points : on les donne en fraction de la boîte, `TileMode`
 * par défaut suffit puisque le dégradé couvre toute la surface. Les arrêts
 * (0 / 35 / 68 / 100 %) sont ceux de la maquette — une répartition régulière
 * décalerait le corail.
 */
internal val GradientBrand: Brush = Brush.linearGradient(
    0f to Amber, .35f to Apricot, .68f to Coral, 1f to Rose,
    start = Offset.Zero, end = Offset.Infinite,
)

/** Le voile qui assombrit le bas d'une photo pour que le texte tienne dessus. */
internal val GradientScrim: Brush = Brush.verticalGradient(
    listOf(Color.Transparent, Color(0xB3000000)),
)

// ── Formes ─────────────────────────────────────────────────────────────────
internal val RadiusSm = RoundedCornerShape(8.dp)
internal val RadiusMd = RoundedCornerShape(12.dp)
internal val RadiusLg = RoundedCornerShape(16.dp)
internal val RadiusXl = RoundedCornerShape(24.dp)
internal val Radius2xl = RoundedCornerShape(32.dp)
/** Historique : le rayon des grandes cartes du feed. */
internal val Shape = RoundedCornerShape(28.dp)

// ── Échelle typographique ──────────────────────────────────────────────────
// `Outfit` pour les chiffres et les titres, `Figtree` pour le texte : faute de
// fichiers de police embarqués, on garde la graisse et l'interlettrage, qui
// portent l'essentiel du caractère.
internal val DisplayLg = TextStyle(fontSize = 44.sp, lineHeight = 48.sp, fontWeight = FontWeight.Bold, letterSpacing = (-.88).sp)
internal val DisplayMd = TextStyle(fontSize = 36.sp, lineHeight = 40.sp, fontWeight = FontWeight.Bold, letterSpacing = (-.72).sp)
internal val HeadlineLg = TextStyle(fontSize = 28.sp, lineHeight = 34.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-.28).sp)
internal val HeadlineMd = TextStyle(fontSize = 24.sp, lineHeight = 30.sp, fontWeight = FontWeight.SemiBold)
internal val HeadlineSm = TextStyle(fontSize = 20.sp, lineHeight = 26.sp, fontWeight = FontWeight.SemiBold)
internal val TitleLg = TextStyle(fontSize = 18.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold)
internal val TitleMd = TextStyle(fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold)
internal val TitleSm = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold)
internal val BodyLg = TextStyle(fontSize = 16.sp, lineHeight = 24.sp)
internal val BodyMd = TextStyle(fontSize = 14.sp, lineHeight = 20.sp)
internal val BodySm = TextStyle(fontSize = 12.sp, lineHeight = 16.sp)
internal val LabelLg = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold, letterSpacing = .14.sp)
internal val LabelMd = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.SemiBold, letterSpacing = .24.sp)
internal val LabelSm = TextStyle(fontSize = 11.sp, lineHeight = 16.sp, fontWeight = FontWeight.SemiBold, letterSpacing = .44.sp)

// Les montants en SKR ont leur propre échelle : `Outfit` en gras, chiffres
// tabulaires, pour qu'un solde qui change ne fasse pas danser la ligne.
internal val SkrXl = TextStyle(fontSize = 48.sp, lineHeight = 48.sp, fontWeight = FontWeight.Bold, letterSpacing = (-.96).sp, fontFeatureSettings = "tnum")
internal val SkrLg = TextStyle(fontSize = 32.sp, lineHeight = 36.sp, fontWeight = FontWeight.Bold, letterSpacing = (-.32).sp, fontFeatureSettings = "tnum")
