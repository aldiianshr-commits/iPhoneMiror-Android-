package com.aldiianshr.iphonemiror.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aldiianshr.iphonemiror.domain.model.LinkQuality
import com.aldiianshr.iphonemiror.domain.model.ReceiverStatus
import com.aldiianshr.iphonemiror.ui.theme.Palette

@Composable
fun Panel(
    modifier: Modifier = Modifier,
    title: String? = null,
    subtitle: String? = null,
    trailing: @Composable (() -> Unit)? = null,
    contentPadding: PaddingValues = PaddingValues(20.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(18.dp)
    Column(
        modifier
            .clip(shape)
            .background(Palette.Surface)
            .border(1.dp, Palette.Outline, shape)
            .padding(contentPadding),
    ) {
        if (title != null) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleMedium, color = Palette.Text)
                    if (subtitle != null) {
                        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Palette.TextSecondary)
                    }
                }
                trailing?.invoke()
            }
            Spacer(Modifier.size(14.dp))
        }
        content()
    }
}

@Composable
fun StatusDot(color: Color, modifier: Modifier = Modifier) {
    Box(modifier.size(8.dp).clip(CircleShape).background(color))
}

@Composable
fun Chip(text: String, color: Color = Palette.TextSecondary, modifier: Modifier = Modifier) {
    Row(
        modifier
            .clip(RoundedCornerShape(50))
            .background(Palette.SurfaceHigh)
            .border(1.dp, Palette.Outline, RoundedCornerShape(50))
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StatusDot(color)
        Spacer(Modifier.width(6.dp))
        Text(text, style = MaterialTheme.typography.labelMedium, color = Palette.Text, maxLines = 1)
    }
}

@Composable
fun KeyValue(label: String, value: String?, modifier: Modifier = Modifier, mono: Boolean = false) {
    Row(modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.Top) {
        Text(
            label, style = MaterialTheme.typography.bodyMedium, color = Palette.TextSecondary,
            modifier = Modifier.width(150.dp),
        )
        Text(
            value ?: "—",
            style = if (mono) com.aldiianshr.iphonemiror.ui.theme.Mono else MaterialTheme.typography.bodyMedium,
            color = if (value == null) Palette.TextTertiary else Palette.Text,
            fontWeight = if (mono) FontWeight.Normal else FontWeight.Medium,
            modifier = Modifier.weight(1f),
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
fun PrimaryButton(text: String, icon: ImageVector? = null, enabled: Boolean = true, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        colors = ButtonDefaults.buttonColors(containerColor = Palette.Accent, contentColor = Palette.OnAccent),
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 12.dp),
    ) {
        if (icon != null) { Icon(icon, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)) }
        Text(text, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
fun SecondaryButton(text: String, icon: ImageVector? = null, enabled: Boolean = true, modifier: Modifier = Modifier, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, Palette.OutlineStrong),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = Palette.Text),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
    ) {
        if (icon != null) { Icon(icon, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)) }
        Text(text, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
fun InfoNote(text: String, modifier: Modifier = Modifier, color: Color = Palette.TextSecondary, icon: ImageVector? = null) {
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Palette.SurfaceHigh)
            .padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (icon != null) Icon(icon, null, tint = color, modifier = Modifier.size(18.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, color = color)
    }
}

@Composable
fun EmptyState(icon: ImageVector, title: String, body: String, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(vertical = 28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.size(52.dp).clip(CircleShape).background(Palette.SurfaceHigh),
            contentAlignment = Alignment.Center,
        ) { Icon(icon, null, tint = Palette.TextSecondary) }
        Spacer(Modifier.size(12.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, color = Palette.Text)
        Spacer(Modifier.size(4.dp))
        Text(body, style = MaterialTheme.typography.bodySmall, color = Palette.TextSecondary)
    }
}

fun ReceiverStatus.label(): String = when (this) {
    ReceiverStatus.STOPPED -> "Penerima mati"
    ReceiverStatus.WAITING -> "Menunggu iPhone"
    ReceiverStatus.CONNECTING -> "Menghubungkan…"
    ReceiverStatus.PAIRED -> "Terpasang (paired)"
    ReceiverStatus.MIRRORING -> "Mencerminkan"
    ReceiverStatus.NO_NETWORK -> "Tidak ada Wi-Fi"
    ReceiverStatus.ERROR -> "Galat"
}

fun ReceiverStatus.color(): Color = when (this) {
    ReceiverStatus.MIRRORING -> Palette.Ok
    ReceiverStatus.PAIRED, ReceiverStatus.CONNECTING -> Palette.Info
    ReceiverStatus.WAITING -> Palette.TextSecondary
    ReceiverStatus.NO_NETWORK -> Palette.Warn
    ReceiverStatus.ERROR -> Palette.Bad
    ReceiverStatus.STOPPED -> Palette.TextTertiary
}

fun LinkQuality.color(): Color = when (this) {
    LinkQuality.EXCELLENT, LinkQuality.GOOD -> Palette.Ok
    LinkQuality.FAIR -> Palette.Warn
    LinkQuality.POOR -> Palette.Bad
    LinkQuality.NONE -> Palette.TextTertiary
}

fun formatDuration(ms: Long): String {
    val s = ms / 1000
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, (s % 3600) / 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}
