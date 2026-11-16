package nl.wiebenieuwenhuis.tender.ui.servers

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import nl.wiebenieuwenhuis.tender.R
import nl.wiebenieuwenhuis.tender.app.AppModel
import nl.wiebenieuwenhuis.tender.app.TileLive
import nl.wiebenieuwenhuis.tender.app.TileStream
import nl.wiebenieuwenhuis.tender.ui.components.ChoiceRow
import nl.wiebenieuwenhuis.tender.ui.components.RowDivider
import nl.wiebenieuwenhuis.tender.ui.components.ScreenScaffold
import nl.wiebenieuwenhuis.tender.ui.components.SectionCard
import nl.wiebenieuwenhuis.tender.ui.components.SectionLabel
import nl.wiebenieuwenhuis.tender.ui.components.SwitchRow
import nl.wiebenieuwenhuis.tender.ui.components.readableWidth

/** Settings for the Live tab: live video in tiles, which stream, recent alerts. */
@Composable
fun LiveSettingsScreen() {
    ScreenScaffold(stringResource(R.string.live_view)) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally) {
            Column(Modifier.readableWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionLabel(stringResource(R.string.camera_tiles))
                SectionCard {
                    val tileLive = AppModel.tileLive
                    ChoiceRow(
                        stringResource(R.string.live_video), TileLive.entries, tileLive,
                        label = { stringResource(when (it) { TileLive.Always -> R.string.always; TileLive.OnActivity -> R.string.on_activity; TileLive.Off -> R.string.off }) },
                        onSelect = { AppModel.tileLive = it },
                        subtitle = stringResource(when (tileLive) {
                            TileLive.Always -> R.string.tile_live_always_info
                            TileLive.OnActivity -> R.string.tile_live_activity_info
                            TileLive.Off -> R.string.tile_live_off_info
                        }),
                    )
                    if (tileLive != TileLive.Off) {
                        RowDivider()
                        ChoiceRow(
                            stringResource(R.string.stream), TileStream.entries, AppModel.tileStream,
                            label = { stringResource(when (it) { TileStream.Automatic -> R.string.automatic; TileStream.Main -> R.string.main_stream; TileStream.Sub -> R.string.sub_stream }) },
                            onSelect = { AppModel.updateTileStream(it) },
                            subtitle = stringResource(when (AppModel.tileStream) {
                                TileStream.Automatic -> R.string.tile_stream_auto_info
                                TileStream.Main -> R.string.tile_stream_main_info
                                TileStream.Sub -> R.string.tile_stream_sub_info
                            }),
                        )
                    }
                    if (tileLive == TileLive.Always) {
                        RowDivider()
                        SwitchRow(stringResource(R.string.also_on_mobile_data), AppModel.liveGridOnCellular, { AppModel.updateLiveGridOnCellular(it) },
                            subtitle = stringResource(R.string.also_on_mobile_data_info))
                    }
                }
                SectionLabel(stringResource(R.string.alerts), Modifier.padding(top = 14.dp))
                SectionCard {
                    SwitchRow(stringResource(R.string.show_recent_alerts), AppModel.showRecentAlerts, { AppModel.updateShowRecentAlerts(it) },
                        subtitle = stringResource(R.string.show_recent_alerts_info))
                    if (AppModel.showRecentAlerts) {
                        RowDivider()
                        SwitchRow(stringResource(R.string.play_alerts), AppModel.playAlertPreviews, { AppModel.updatePlayAlertPreviews(it) },
                            subtitle = stringResource(R.string.play_alerts_info))
                    }
                }
            }
        }
    }
}
