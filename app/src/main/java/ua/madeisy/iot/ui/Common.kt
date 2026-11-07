package ua.madeisy.iot.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import ua.madeisy.iot.IotApp
import ua.madeisy.iot.data.DeviceConfig
import ua.madeisy.iot.data.PlugState
import ua.madeisy.iot.data.Source

/** Підтримує з'єднання з пристроєм, поки composable на екрані. */
@Composable
fun KeepConnected(key: String) {
    DisposableEffect(key) {
        val c = IotApp.instance.controller
        c.acquire(key)
        onDispose { c.release(key) }
    }
}

@Composable
fun SectionCard(title: String? = null, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(Modifier.padding(16.dp)) {
            if (title != null) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.size(12.dp))
            }
            content()
        }
    }
}

fun statusText(cfg: DeviceConfig, s: PlugState): String = when {
    s.source == Source.LOCAL -> "Локально · ${cfg.version}"
    s.source == Source.CLOUD -> "Через хмару"
    s.connecting -> "Підключення…"
    s.error != null -> s.error
    else -> "Офлайн"
}

@Composable
fun StatusDot(s: PlugState) {
    val color = when (s.source) {
        Source.LOCAL -> OnGreen
        Source.CLOUD -> Color(0xFF3D8BD9)
        Source.NONE -> if (s.connecting) Color(0xFFE0A030) else MaterialTheme.colorScheme.outline
    }
    Surface(shape = CircleShape, color = color, modifier = Modifier.size(8.dp)) {}
}

@Composable
fun StatusLine(cfg: DeviceConfig, s: PlugState, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Start) {
        StatusDot(s)
        Spacer(Modifier.width(6.dp))
        Text(
            statusText(cfg, s),
            style = MaterialTheme.typography.bodySmall,
            color = if (s.online || s.connecting) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
            maxLines = 2,
        )
    }
}

@Composable
fun StatTile(label: String, value: String, modifier: Modifier = Modifier, sub: String? = null) {
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.titleMedium, maxLines = 1)
            if (sub != null) Text(sub, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
