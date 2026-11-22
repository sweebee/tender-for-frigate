package nl.wiebenieuwenhuis.tender

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.launch
import nl.wiebenieuwenhuis.tender.api.Format
import nl.wiebenieuwenhuis.tender.app.AppModel
import nl.wiebenieuwenhuis.tender.ui.AppRoot
import nl.wiebenieuwenhuis.tender.ui.theme.TenderTheme

class MainActivity : ComponentActivity() {
    /** A frigate:// link to open, picked up by the navigation once it is ready. */
    var pendingUri by mutableStateOf<Uri?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Dark bars with light icons, whatever the system's theme.
        enableEdgeToEdge(SystemBarStyle.dark(android.graphics.Color.TRANSPARENT), SystemBarStyle.dark(android.graphics.Color.TRANSPARENT))
        current = this
        pendingUri = intent?.data
        setContent { TenderTheme { AppRoot(this) } }
        ProcessLifecycleOwner.get().lifecycle.addObserver(foreground)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.data?.let { pendingUri = it }
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: android.content.res.Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        nl.wiebenieuwenhuis.tender.ui.AppChrome.pip = isInPictureInPictureMode
    }

    /** Leaving the app while a camera plays: on into picture in picture (Android 12 and later do this themselves). */
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        val params = nl.wiebenieuwenhuis.tender.ui.AppChrome.pipParams ?: return
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.S) runCatching { enterPictureInPictureMode(params) }
    }

    override fun onDestroy() {
        ProcessLifecycleOwner.get().lifecycle.removeObserver(foreground)
        if (current === this) current = null
        super.onDestroy()
    }

    /** Back in the foreground: refresh every server (and reopen their feeds); in the background: close the feeds. */
    private val foreground = object : DefaultLifecycleObserver {
        private var started = false
        override fun onStart(owner: LifecycleOwner) {
            Format.refreshClock()
            if (!started) { started = true; return }
            AppModel.scope.launch { AppModel.refreshAll(reloadConfig = true) }
        }
        override fun onStop(owner: LifecycleOwner) { AppModel.stopFeeds() }
    }

    companion object {
        /** The running activity, for things that need one (full screen, picture in picture). */
        var current: MainActivity? = null
            private set
    }
}
