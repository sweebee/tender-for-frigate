package nl.wiebenieuwenhuis.tender.ui

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.navigation.NavHostController
import kotlinx.serialization.Serializable
import nl.wiebenieuwenhuis.tender.api.FrigateEvent
import nl.wiebenieuwenhuis.tender.api.FrigateExport
import nl.wiebenieuwenhuis.tender.api.ReviewItem
import java.util.concurrent.ConcurrentHashMap

/*
 * Every screen another feature can open. A feature adds its own private routes in its own
 * `*Nav.kt` file; the routes here are the ones shared between features.
 */

// Top-level sections (tabs on a phone, the rail on a tablet).
@Serializable object LiveTab
@Serializable object ReviewTab
@Serializable object ExploreTab
@Serializable object MoreTab
@Serializable object ExportsTab
@Serializable object FacesTab
@Serializable object ClassificationTab
@Serializable object ChatTab
@Serializable object SettingsTab

/** A camera's live view. */
@Serializable data class CameraRoute(val serverId: String, val camera: String)

/** A camera's debug view: Frigate's annotated stream and the objects it tracks now. */
@Serializable data class CameraDebugRoute(val serverId: String, val camera: String)

/**
 * A review item (alert or detection). Put the item in [ItemCache] before navigating so it
 * shows at once; the screen loads it by id otherwise. `focusEventId`: the tracked object it was
 * opened on from Explore, shown first.
 */
@Serializable data class EventRoute(val serverId: String, val reviewId: String, val focusEventId: String? = null)

/** A tracked object from Explore; opens the review item it belongs to, or the object screen when it has none. */
@Serializable data class ObjectRoute(val serverId: String, val eventId: String)

/**
 * Recordings of a camera on a day. `day`: local midnight in epoch milliseconds.
 * `reviewId`: review item to start with. `showTimeline`: open on the timeline.
 * `startTime`: wall-clock moment (epoch seconds) to start at.
 */
@Serializable data class PlaybackRoute(
    val serverId: String,
    val camera: String,
    val day: Long,
    val reviewId: String? = null,
    val showTimeline: Boolean = false,
    val startTime: Double? = null,
)

/** Frigate's Exports, or one case of a server ("folder" of exports, Frigate 0.18). */
@Serializable data class ExportsRoute(val serverId: String? = null, val caseId: String? = null)

/** One export, played. Put the export in [ItemCache] before navigating. */
@Serializable data class ExportPlayerRoute(val serverId: String, val exportId: String)

/** One custom classification model. */
@Serializable data class ClassificationModelRoute(val serverId: String, val model: String)

/** Adding a server (`serverId` null) or editing one. */
@Serializable data class ConnectRoute(val serverId: String? = null)

/** The app's Live settings (tiles, live video, recent alerts). */
@Serializable object LiveSettingsRoute

/** A server's Frigate settings (admin): cameras, recording, detection, users, profiles, … */
@Serializable data class ServerSettingsRoute(val serverId: String)

/** Frigate's config file as YAML, to edit (admin). */
@Serializable data class ConfigEditorRoute(val serverId: String)

/** A camera's streams and inputs (admin). `camera` null: the server's go2rtc streams. */
@Serializable data class CameraStreamsRoute(val serverId: String, val camera: String? = null)

/** Adding a camera (admin), as Frigate 0.17's camera wizard. */
@Serializable data class CameraWizardRoute(val serverId: String)

/** Zones and masks of a camera, drawn on its picture (admin). */
@Serializable data class ZoneEditorRoute(val serverId: String, val camera: String, val zone: String? = null)

/** Frigate's own web interface in a web view. */
@Serializable data class WebInterfaceRoute(val serverId: String, val path: String = "")

/** Objects handed from one screen to the next, so it can show them before loading. */
object ItemCache {
    val reviews = ConcurrentHashMap<String, ReviewItem>()
    val events = ConcurrentHashMap<String, FrigateEvent>()
    val exports = ConcurrentHashMap<String, FrigateExport>()
    fun put(r: ReviewItem) = r.also { reviews[r.id] = it }
    fun put(e: FrigateEvent) = e.also { events[e.id] = it }
    fun put(e: FrigateExport) = e.also { exports[e.id] = it }
}

/** The app's navigation, for any composable: `LocalNav.current.navigate(CameraRoute(...))`. */
val LocalNav = staticCompositionLocalOf<NavHostController> { error("No navigation") }
