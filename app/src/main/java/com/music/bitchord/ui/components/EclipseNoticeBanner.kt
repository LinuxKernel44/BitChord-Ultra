package com.music.bitchord.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.bitchord.data.sources.eclipse.EclipseNotice
import com.music.bitchord.data.sources.eclipse.EclipseNotices
import kotlinx.coroutines.delay

/**
 * The notice an Eclipse addon asked to have shown: a banner at the top of the
 * screen with the addon's name, its level (info / success / warning / error), an
 * optional title and message, and a progress bar when the notice carries one.
 *
 * Shown for the addon's own duration (2–10 s). A later notice with the same id
 * replaces the one on show — [EclipseNotices] re-emits it — so a "preparing"
 * banner updates in place rather than stacking.
 */
@Composable
fun EclipseNoticeBanner() {
    val shown by EclipseNotices.current.collectAsStateWithLifecycle()
    val current = shown ?: return

    LaunchedEffect(current.serial) {
        delay((current.notice.displaySeconds * 1000).toLong())
        EclipseNotices.dismiss(current)
    }

    val accent = when (current.notice.severity) {
        EclipseNotice.Level.SUCCESS -> Color(0xFF2E9E5B)
        EclipseNotice.Level.WARNING -> Color(0xFFE0A100)
        EclipseNotice.Level.ERROR -> MaterialTheme.colorScheme.error
        EclipseNotice.Level.INFO -> MaterialTheme.colorScheme.primary
    }

    Popup(
        alignment = Alignment.TopCenter,
        properties = PopupProperties(focusable = false, clippingEnabled = false),
    ) {
        Surface(
            modifier = Modifier
                .statusBarsPadding()
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .fillMaxWidth(),
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surfaceVariant,
            shadowElevation = 6.dp,
        ) {
            Column(
                Modifier
                    .background(accent.copy(alpha = 0.10f))
                    .padding(horizontal = 14.dp, vertical = 10.dp),
            ) {
                Text(
                    text = current.addon,
                    style = MaterialTheme.typography.labelSmall,
                    color = accent,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                current.notice.heading.takeIf { it.isNotEmpty() }?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    text = current.notice.text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
                current.notice.progress?.coerceIn(0.0, 1.0)?.let { progress ->
                    LinearProgressIndicator(
                        progress = { progress.toFloat() },
                        color = accent,
                        modifier = Modifier
                            .padding(top = 8.dp)
                            .fillMaxWidth(),
                    )
                }
            }
        }
    }
}
