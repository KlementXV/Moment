package com.klementxv.moment.ui

import com.klementxv.moment.i18n.Message
import com.klementxv.moment.i18n.tr

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

@Composable
internal fun CaptureScaffold(
    onClose: () -> Unit,
    closeIcon: String = "close",
    closeLabel: String = tr(Message.CloseCapture),
    title: String? = null,
    enabled: Boolean = true,
    action: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(Modifier.fillMaxSize().background(Ink).statusBarsPadding().navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            CaptureIconButton(closeIcon, closeLabel, enabled, onClose)
            if (title != null) Text(title, Modifier.weight(1f), style = HeadlineSm,
                textAlign = TextAlign.Center)
            else Spacer(Modifier.weight(1f))
            Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) { action?.invoke() }
        }
        content()
    }
}

@Composable
internal fun CaptureIconButton(
    icon: String,
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    IconButton(onClick = onClick, enabled = enabled,
        modifier = Modifier.size(48.dp).clip(CircleShape)
            .semantics { contentDescription = label }) {
        Glyph(icon, if (enabled) White else Muted)
    }
}

@Composable
internal fun PermissionRow(icon: String, title: String, reason: String) {
    Row(Modifier.fillMaxWidth().clip(RadiusLg).background(Panel).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Box(Modifier.size(48.dp).clip(RadiusMd).background(SurfaceHigh),
            contentAlignment = Alignment.Center) { Glyph(icon, Accent) }
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = TitleMd)
            Text(reason, style = BodyMd, color = Muted)
        }
    }
}
