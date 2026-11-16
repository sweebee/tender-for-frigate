package nl.wiebenieuwenhuis.tender.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import nl.wiebenieuwenhuis.tender.api.Format
import java.util.Date

/** The current time, updated every minute: a "4 min ago" that reads it does not freeze. */
@Composable
fun rememberMinuteClock(): Long {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (isActive) {
            delay(60_000 - System.currentTimeMillis() % 60_000)
            now = System.currentTimeMillis()
        }
    }
    return now
}

/** "Just now", "4 min ago", "3 h ago" for an epoch-seconds moment, kept current. */
@Composable
fun relativeTime(epochSeconds: Double): String {
    val now = rememberMinuteClock()
    return Format.relative(Date((epochSeconds * 1000).toLong()), Date(now))
}
