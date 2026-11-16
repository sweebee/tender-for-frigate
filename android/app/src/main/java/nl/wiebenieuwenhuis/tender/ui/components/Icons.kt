package nl.wiebenieuwenhuis.tender.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.ChildCare
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.DirectionsBike
import androidx.compose.material.icons.filled.DirectionsBoat
import androidx.compose.material.icons.filled.DirectionsBus
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.DirectionsWalk
import androidx.compose.material.icons.filled.FaceRetouchingNatural
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Grass
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.LocalShipping
import androidx.compose.material.icons.filled.NotificationImportant
import androidx.compose.material.icons.filled.Pets
import androidx.compose.material.icons.filled.Pin
import androidx.compose.material.icons.filled.TwoWheeler
import androidx.compose.material.icons.filled.Flare
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.JsonObject
import nl.wiebenieuwenhuis.tender.TenderApplication
import nl.wiebenieuwenhuis.tender.api.FrigateJson
import nl.wiebenieuwenhuis.tender.api.Format
import nl.wiebenieuwenhuis.tender.api.str
import nl.wiebenieuwenhuis.tender.ui.theme.Palette
import java.util.concurrent.ConcurrentHashMap

/**
 * Frigate's camera group icons: the Lucide set its web UI offers (names like "LuHouse"), drawn
 * from assets/LucideIcons.json (SVG paths on a 24×24 grid, from the iOS app's resources).
 */
object LucideIcons {
    val paths: Map<String, String> by lazy {
        runCatching {
            val text = TenderApplication.context.assets.open("LucideIcons.json").bufferedReader().readText()
            (FrigateJson.parseToJsonElement(text) as JsonObject).mapValues { it.value.str() ?: "" }
        }.getOrDefault(emptyMap())
    }
    val names: List<String> by lazy { paths.keys.sorted() }
    private val cache = ConcurrentHashMap<String, Path>()

    fun path(name: String): Path? {
        cache[name]?.let { return it }
        val d = paths[name] ?: return null
        val p = runCatching { PathParser().parsePathString(d).toPath() }.getOrNull() ?: return null
        cache[name] = p
        return p
    }

    /** "LuParkingSquare" → "parking square", for search and accessibility. */
    fun words(name: String): String {
        val sb = StringBuilder()
        for (ch in name.drop(2)) {
            if ((ch.isUpperCase() || ch.isDigit()) && sb.isNotEmpty() && !(sb.last().isDigit() && ch.isDigit())) sb.append(' ')
            sb.append(ch)
        }
        return sb.toString().lowercase()
    }
}

/** One Lucide icon, stroked like Frigate's (2 of 24, round ends). */
@Composable
fun LucideIcon(name: String, modifier: Modifier = Modifier, size: Dp = 16.dp, tint: Color = LocalContentColor.current) {
    val path = LucideIcons.path(name)
    if (path == null) {
        Icon(Icons.Filled.GridView, LucideIcons.words(name), modifier.size(size), tint = tint)
        return
    }
    Canvas(modifier.size(size).semantics { contentDescription = LucideIcons.words(name) }) {
        val s = this.size.width / 24f
        scale(s, pivot = androidx.compose.ui.geometry.Offset.Zero) {
            drawPath(path, tint, style = Stroke(width = 2f, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
    }
}

/** A Material icon for Frigate's common labels (person, car, …); null for others, which keep their text. */
fun labelIcon(label: String): ImageVector? = when (label) {
    "person" -> Icons.Filled.DirectionsWalk
    "face" -> Icons.Filled.FaceRetouchingNatural
    "car" -> Icons.Filled.DirectionsCar
    "truck", "garbage_truck" -> Icons.Filled.LocalShipping
    "bus", "school_bus" -> Icons.Filled.DirectionsBus
    "motorcycle" -> Icons.Filled.TwoWheeler
    "bicycle" -> Icons.Filled.DirectionsBike
    "on_demand" -> Icons.Filled.FiberManualRecord
    "boat" -> Icons.Filled.DirectionsBoat
    "license_plate" -> Icons.Filled.Pin
    "package" -> Icons.Filled.Inventory2
    "waste_bin" -> Icons.Filled.DeleteOutline
    "robot_lawnmower" -> Icons.Filled.Grass
    "amazon", "ups", "usps", "fedex", "dhl", "dpd", "gls", "postnl", "postnord", "an_post", "purolator", "nzpost", "canada_post", "royal_mail" ->
        Icons.Filled.LocalShipping
    "dog", "cat", "bird", "horse", "rabbit", "deer", "fox", "skunk", "squirrel", "raccoon", "bear", "kangaroo", "cow", "goat", "sheep", "bark" ->
        Icons.Filled.Pets
    "speech", "yell" -> Icons.Filled.GraphicEq
    "baby_crying" -> Icons.Filled.ChildCare
    "fire_alarm" -> Icons.Filled.NotificationImportant
    "glass_breaking" -> Icons.Filled.Flare
    else -> null
}

/** A label as an icon for common objects (person, car, …) on its colour, else as its name. */
@Composable
fun LabelIcon(label: String, modifier: Modifier = Modifier, size: Dp = 18.dp) {
    val icon = labelIcon(label)
    if (icon != null) {
        Box(
            modifier.size(size).background(Palette.labelColor(label), RoundedCornerShape(size * 0.28f)),
            contentAlignment = Alignment.Center,
        ) { Icon(icon, Format.labelName(label), Modifier.size(size * 0.62f), tint = Palette.status.onLabel) }
    } else {
        LabelTag(label, modifier.sizeIn(minHeight = size), fontSize = maxOf(10, (size.value * 0.58f).toInt()))
    }
}

@Composable
fun StatusDot(color: Color, modifier: Modifier = Modifier, size: Dp = 7.dp) {
    Box(modifier.size(size).background(color, CircleShape))
}
