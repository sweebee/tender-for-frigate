package nl.wiebenieuwenhuis.tender.ui.live

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.DropdownMenu
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import nl.wiebenieuwenhuis.tender.api.ReviewItem
import nl.wiebenieuwenhuis.tender.ui.review.ReviewActions
import nl.wiebenieuwenhuis.tender.ui.review.ReviewEntry
import nl.wiebenieuwenhuis.tender.ui.review.ReviewItemMenuItems

/** A review item that opens on a tap and offers Review's own menu on a long press (save, share, delete). */
@Composable
fun ReviewMenuBox(serverId: String, item: ReviewItem, actions: ReviewActions, onClick: () -> Unit, modifier: Modifier = Modifier,
                  content: @Composable () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    val deleting = actions.deleting[item.id] == true
    Box(modifier.alpha(if (deleting) 0.4f else 1f).combinedClickable(enabled = !deleting, onClick = onClick, onLongClick = { menu = true })) {
        content()
        DropdownMenu(menu, { menu = false }) { ReviewItemMenuItems(ReviewEntry(serverId, item), actions) { menu = false } }
    }
}
