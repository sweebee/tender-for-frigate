package nl.wiebenieuwenhuis.tender.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

/** Tints that tell servers apart (badges, chips). */
val ServerTints = listOf(
    Color(0xFF5B9BFF), Color(0xFF3DD6B5), Color(0xFFFFB547),
    Color(0xFFFF6B9B), Color(0xFFB48CFF), Color(0xFF64D2FF),
)

fun serverTint(index: Int): Color = ServerTints[Math.floorMod(index, ServerTints.size)]

/** Colours with a meaning that stay the same in light and dark, as in Frigate's web UI. */
@Immutable
data class StatusColors(
    val online: Color = Color(0xFF34C759),
    val offline: Color = Color(0xFF8E8E93),
    val warning: Color = Color(0xFFFF9F43),
    val destructive: Color = Color(0xFFE5483D),
    val recording: Color = Color(0xFFFF453A),
    val motion: Color = Color(0xFFE5B33E),
    /** Text on a label colour. */
    val onLabel: Color = Color(0xFF1A1206),
)

val LocalStatusColors = staticCompositionLocalOf { StatusColors() }

object Palette {
    val status: StatusColors @Composable get() = LocalStatusColors.current

    fun labelColor(label: String): Color = when (label.lowercase()) {
        "person", "face" -> Color(0xFFFF9F43)
        "car", "truck", "motorcycle", "bus", "license_plate" -> Color(0xFFFFD60A)
        "dog", "cat", "bird", "horse", "squirrel", "skunk", "deer", "rabbit", "fox" -> Color(0xFF64D2FF)
        "package", "amazon", "ups", "fedex", "dhl", "dpd", "gls", "postnl", "usps", "postnord", "an_post", "canada_post", "nzpost" -> Color(0xFF5B9BFF)
        "bicycle" -> Color(0xFF3DD6B5)
        "on_demand" -> Color(0xFFFF5A5F)
        else -> if (label.startsWith("audio:") || label in setOf("speech", "bark", "yell", "fire_alarm")) Color(0xFFB48CFF) else Color(0xFFC9CFD6)
    }

    /** Faint tint behind timeline rows for an event's span. */
    fun labelTint(label: String): Color = labelColor(label).copy(alpha = 0.13f)
}

private val Accent = Color(0xFF5B9BFF)

private val DarkFallback = darkColorScheme(
    primary = Accent, onPrimary = Color(0xFF0A0B0D),
    background = Color(0xFF0A0B0D), surface = Color(0xFF0A0B0D),
    surfaceContainer = Color(0xFF15171B), surfaceContainerHigh = Color(0xFF1A1D22),
    surfaceContainerHighest = Color(0xFF23272E), surfaceContainerLow = Color(0xFF111316),
    onSurface = Color(0xFFF2F4F7), onSurfaceVariant = Color(0xFF9AA3AF),
    outline = Color(0xFF2A2E35), outlineVariant = Color(0xFF23272E),
)

private val LightFallback = lightColorScheme(primary = Color(0xFF2F6FDB))

/** Follows the system: light or dark, and the wallpaper's colours where Android offers them. */
@Composable
fun TenderTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val ctx = LocalContext.current
    val scheme = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        dark -> DarkFallback
        else -> LightFallback
    }
    androidx.compose.runtime.CompositionLocalProvider(LocalStatusColors provides StatusColors()) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}
