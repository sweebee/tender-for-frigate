package nl.wiebenieuwenhuis.tender.api

import android.text.format.DateFormat
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import nl.wiebenieuwenhuis.tender.R
import nl.wiebenieuwenhuis.tender.TenderApplication
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.max
import kotlin.math.roundToInt

/** Text in the user's language, outside of a composable. */
fun L(id: Int, vararg args: Any): String = TenderApplication.context.getString(id, *args)
fun Lq(id: Int, count: Int, vararg args: Any): String =
    TenderApplication.context.resources.getQuantityString(id, count, *args)

/** Formatting shared by every screen, as `FrigateFormat` in the iOS app. */
object Format {
    fun prettyName(raw: String): String = raw.replace('_', ' ').replaceFirstChar { it.uppercase() }

    /** A Frigate object label in the user's language (person → persoon), with a capital first letter. */
    fun labelName(raw: String): String {
        val ctx = TenderApplication.context
        val id = ctx.resources.getIdentifier("label_$raw", "string", ctx.packageName)
        val name = if (id != 0) ctx.getString(id) else raw.replace('_', ' ')
        return name.replaceFirstChar { it.uppercase() }
    }

    /** Each server's ui.time_format. "12hour" or "24hour" wins, as in Frigate's web app; "browser" means the device's. */
    val serverTimeFormats = mutableMapOf<String, String?>()
    var uses12Hour by mutableStateOf(false)
        private set

    fun refreshClock() {
        val server = serverTimeFormats.values.filterNotNull().sorted().firstOrNull { it == "12hour" || it == "24hour" }
        uses12Hour = server?.let { it == "12hour" } ?: !DateFormat.is24HourFormat(TenderApplication.context)
    }

    fun time(date: Date, seconds: Boolean = false): String {
        val pattern = if (uses12Hour) (if (seconds) "h:mm:ss a" else "h:mm a") else (if (seconds) "HH:mm:ss" else "HH:mm")
        return SimpleDateFormat(pattern, Locale.getDefault()).format(date)
    }
    fun time(epoch: Double, seconds: Boolean = false): String = time(Date((epoch * 1000).toLong()), seconds)

    /** "1.9 s" under a minute, otherwise "m:ss". */
    fun shortDuration(t: Double): String {
        if (t < 60) {
            val v = max(t, 0.1)
            return if (t < 10) String.format(Locale.getDefault(), "%.1f s", v).replace(".0 s", " s").replace(",0 s", " s")
            else "${v.roundToInt()} s"
        }
        return duration(t)
    }

    /** Review items longer than an hour (closed only after a restart) show as "> 1 h". */
    fun reviewDuration(item: ReviewItem): String = if (item.duration > 3600) L(R.string.more_than_hour) else duration(item.duration)

    fun duration(t: Double): String {
        val total = t.roundToInt()
        val h = total / 3600; val m = (total % 3600) / 60; val s = total % 60
        return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s) else String.format(Locale.US, "%d:%02d", m, s)
    }

    private fun skeleton(template: String, date: Date): String =
        SimpleDateFormat(DateFormat.getBestDateTimePattern(Locale.getDefault(), template), Locale.getDefault()).format(date)

    /** "13 nov": a day on a timeline axis. */
    fun shortDay(date: Date): String = skeleton("dMMM", date)

    fun dayTitle(date: Date): String {
        val cal = Calendar.getInstance()
        val today = cal.get(Calendar.DAY_OF_YEAR) to cal.get(Calendar.YEAR)
        cal.add(Calendar.DAY_OF_YEAR, -1)
        val yesterday = cal.get(Calendar.DAY_OF_YEAR) to cal.get(Calendar.YEAR)
        cal.time = date
        val day = cal.get(Calendar.DAY_OF_YEAR) to cal.get(Calendar.YEAR)
        return when (day) {
            today -> L(R.string.today)
            yesterday -> L(R.string.yesterday)
            else -> skeleton("EEEdMMM", date)
        }
    }

    fun dateTime(date: Date): String = skeleton("EEEdMMM", date) + " " + time(date)

    fun relative(date: Date, now: Date = Date()): String {
        val d = (now.time - date.time) / 1000.0
        return when {
            d < 60 -> L(R.string.just_now)
            d < 3600 -> L(R.string.minutes_ago, (d / 60).toInt())
            d < 86400 -> L(R.string.hours_ago, (d / 3600).toInt())
            else -> (d / 86400).toInt().let { Lq(R.plurals.days_ago, it, it) }
        }
    }

    /** "0.18.2-cfa08c5" → "v0.18" */
    fun shortVersion(v: String): String = "v" + v.split("-").first().split(".").take(2).joinToString(".")

    /** Compares "0.18.2-x" with a minimum major.minor. */
    fun versionAtLeast(version: String?, major: Int, minor: Int): Boolean {
        val parts = (version ?: "").split("-").first().split(".").mapNotNull { it.toIntOrNull() }
        if (parts.size < 2) return false
        return parts[0] > major || (parts[0] == major && parts[1] >= minor)
    }

    fun percent(v: Double): String = "${(v * 100).roundToInt()}%"
}

fun Double.toDate(): Date = Date((this * 1000).toLong())
fun nowSeconds(): Double = System.currentTimeMillis() / 1000.0
