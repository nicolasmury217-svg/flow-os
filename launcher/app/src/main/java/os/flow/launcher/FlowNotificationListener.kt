package os.flow.launcher

import android.app.Notification
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Média en cours de lecture (Spotify, YouTube, etc.), alimenté par MediaSessionManager. */
data class NowPlaying(
    val packageName: String,
    val title: String,
    val artist: String,
    val isPlaying: Boolean,
)

/** Notification récente à afficher brièvement dans Flow Island. */
data class IslandAlert(
    val id: Long,
    val packageName: String,
    val appName: String,
    val title: String,
    val text: String,
    val contentIntent: PendingIntent?,
)

/**
 * Service d'écoute des notifications (opt-in dans les réglages Android) :
 * - alimente les pastilles sur les icônes ;
 * - suit la lecture média en cours (En un coup d'œil + Flow Island) ;
 * - transmet les nouvelles notifications à Flow Island.
 */
class FlowNotificationListener : NotificationListenerService() {

    private var activeController: MediaController? = null
    private val mediaCallback = object : MediaController.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackState?) = publishMedia()
        override fun onMetadataChanged(metadata: MediaMetadata?) = publishMedia()
    }
    private val sessionsListener = MediaSessionManager.OnActiveSessionsChangedListener { controllers ->
        attachController(controllers.orEmpty())
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        publishActive()
        registerMediaSessions()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        publishActive()
        if (sbn != null) emitAlert(sbn)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        publishActive()
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        unregisterMediaSessions()
        _badgedPackages.value = emptySet()
        _nowPlaying.value = null
    }

    private fun registerMediaSessions() {
        runCatching {
            val msm = getSystemService(MediaSessionManager::class.java) ?: return
            val cn = ComponentName(this, FlowNotificationListener::class.java)
            msm.addOnActiveSessionsChangedListener(sessionsListener, cn)
            attachController(msm.getActiveSessions(cn))
        }
    }

    private fun unregisterMediaSessions() {
        runCatching {
            activeController?.unregisterCallback(mediaCallback)
            activeController = null
            currentController = null
            getSystemService(MediaSessionManager::class.java)?.removeOnActiveSessionsChangedListener(sessionsListener)
        }
    }

    private fun attachController(controllers: List<MediaController>) {
        val chosen = controllers.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
            ?: controllers.firstOrNull { it.metadata != null }
        if (chosen?.sessionToken != activeController?.sessionToken) {
            activeController?.unregisterCallback(mediaCallback)
            activeController = chosen
            currentController = chosen
            chosen?.registerCallback(mediaCallback)
        }
        publishMedia()
    }

    private fun publishMedia() {
        val ctrl = activeController
        val meta = ctrl?.metadata
        val state = ctrl?.playbackState?.state
        val active = state == PlaybackState.STATE_PLAYING || state == PlaybackState.STATE_PAUSED
        val title = meta?.getString(MediaMetadata.METADATA_KEY_TITLE)?.trim().orEmpty()
        if (ctrl == null || !active || title.isEmpty()) {
            _nowPlaying.value = null
            return
        }
        val artist = meta?.getString(MediaMetadata.METADATA_KEY_ARTIST)?.trim().orEmpty()
        _nowPlaying.value = NowPlaying(
            packageName = ctrl.packageName,
            title = title,
            artist = artist,
            isPlaying = state == PlaybackState.STATE_PLAYING,
        )
    }

    private fun emitAlert(sbn: StatusBarNotification) {
        val n = sbn.notification ?: return
        val flags = n.flags
        val ongoing = (flags and Notification.FLAG_ONGOING_EVENT) != 0 ||
            (flags and Notification.FLAG_FOREGROUND_SERVICE) != 0
        val groupSummary = (flags and Notification.FLAG_GROUP_SUMMARY) != 0
        if (ongoing || groupSummary || sbn.packageName == packageName) return

        val extras = n.extras ?: return
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim().orEmpty()
        if (title.isEmpty() && text.isEmpty()) return

        val appName = runCatching {
            val ai = packageManager.getApplicationInfo(sbn.packageName, 0)
            packageManager.getApplicationLabel(ai).toString()
        }.getOrDefault(sbn.packageName)

        _latestAlert.value = IslandAlert(
            id = System.currentTimeMillis(),
            packageName = sbn.packageName,
            appName = appName,
            title = title.ifEmpty { appName },
            text = text,
            contentIntent = n.contentIntent,
        )
    }

    private fun publishActive() {
        val pkgs = runCatching {
            activeNotifications.orEmpty()
                .filter { sbn ->
                    val flags = sbn.notification.flags
                    val ongoing = (flags and Notification.FLAG_ONGOING_EVENT) != 0 ||
                        (flags and Notification.FLAG_FOREGROUND_SERVICE) != 0
                    val groupSummary = (flags and Notification.FLAG_GROUP_SUMMARY) != 0
                    !ongoing && !groupSummary
                }
                .map { it.packageName }
                .toSet()
        }.getOrDefault(emptySet())
        _badgedPackages.value = pkgs
    }

    companion object {
        @Volatile
        private var currentController: MediaController? = null

        private val _badgedPackages = MutableStateFlow<Set<String>>(emptySet())
        val badgedPackages: StateFlow<Set<String>> = _badgedPackages.asStateFlow()

        private val _nowPlaying = MutableStateFlow<NowPlaying?>(null)
        val nowPlaying: StateFlow<NowPlaying?> = _nowPlaying.asStateFlow()

        private val _latestAlert = MutableStateFlow<IslandAlert?>(null)
        val latestAlert: StateFlow<IslandAlert?> = _latestAlert.asStateFlow()

        fun clearAlert(id: Long) {
            if (_latestAlert.value?.id == id) _latestAlert.value = null
        }

        fun togglePlayPause(context: Context? = null) {
            val ctrl = currentController
            if (ctrl != null) {
                runCatching {
                    if (ctrl.playbackState?.state == PlaybackState.STATE_PLAYING) {
                        ctrl.transportControls.pause()
                    } else {
                        ctrl.transportControls.play()
                    }
                    return
                }
            }
            if (context != null) dispatchMediaKey(context, android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
        }

        fun skipNext(context: Context? = null) {
            val ctrl = currentController
            if (ctrl != null) {
                runCatching { ctrl.transportControls.skipToNext(); return }
            }
            if (context != null) dispatchMediaKey(context, android.view.KeyEvent.KEYCODE_MEDIA_NEXT)
        }

        fun skipPrevious(context: Context? = null) {
            val ctrl = currentController
            if (ctrl != null) {
                runCatching { ctrl.transportControls.skipToPrevious(); return }
            }
            if (context != null) dispatchMediaKey(context, android.view.KeyEvent.KEYCODE_MEDIA_PREVIOUS)
        }

        private fun dispatchMediaKey(context: Context, keyCode: Int) {
            runCatching {
                val am = context.getSystemService(android.media.AudioManager::class.java) ?: return
                am.dispatchMediaKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, keyCode))
                am.dispatchMediaKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, keyCode))
            }
        }

        fun isEnabled(context: Context): Boolean {
            val flat = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners").orEmpty()
            val expected = ComponentName(context, FlowNotificationListener::class.java).flattenToString()
            return flat.split(":").any { it.equals(expected, ignoreCase = true) }
        }
    }
}
