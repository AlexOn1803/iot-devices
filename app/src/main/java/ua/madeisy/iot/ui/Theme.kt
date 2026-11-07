package ua.madeisy.iot.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import java.util.Locale
import kotlin.math.abs

private val LightColors = lightColorScheme(
    primary = Color(0xFF8A5A00),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFDDB0),
    onPrimaryContainer = Color(0xFF2C1800),
    secondary = Color(0xFF6F5B40),
    tertiary = Color(0xFF51643F),
    surface = Color(0xFFFFF8F4),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFFFB952),
    onPrimary = Color(0xFF482A00),
    primaryContainer = Color(0xFF693F00),
    onPrimaryContainer = Color(0xFFFFDDB0),
    secondary = Color(0xFFDDC2A1),
    tertiary = Color(0xFFB7CEA0),
    surface = Color(0xFF18120C),
)

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val ctx = LocalContext.current
    val colors = when {
        Build.VERSION.SDK_INT >= 31 -> if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        dark -> DarkColors
        else -> LightColors
    }
    MaterialTheme(colorScheme = colors, content = content)
}

/** Колір «увімкнено» — однаковий незалежно від теми, щоб стан читався з першого погляду. */
val OnGreen = Color(0xFF2E9E5B)

fun formatPower(w: Double?): String = when {
    w == null -> "—"
    abs(w) >= 1000 -> String.format(Locale.US, "%.2f кВт", w / 1000)
    abs(w) >= 100 -> String.format(Locale.US, "%.0f Вт", w)
    else -> String.format(Locale.US, "%.1f Вт", w)
}

fun formatEnergy(kwh: Double): String = when {
    kwh < 1 -> String.format(Locale.US, "%.0f Вт·год", kwh * 1000)
    else -> String.format(Locale.US, "%.2f кВт·год", kwh)
}

fun formatDuration(sec: Int): String {
    val h = sec / 3600
    val m = (sec % 3600) / 60
    val s = sec % 60
    return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s) else String.format(Locale.US, "%02d:%02d", m, s)
}

val DAY_NAMES = listOf("Пн", "Вт", "Ср", "Чт", "Пт", "Сб", "Нд")

fun formatDays(mask: Int): String = when (mask) {
    0 -> "Одноразово"
    0b1111111 -> "Щодня"
    0b0011111 -> "Будні"
    0b1100000 -> "Вихідні"
    else -> DAY_NAMES.filterIndexed { i, _ -> mask and (1 shl i) != 0 }.joinToString(", ")
}
