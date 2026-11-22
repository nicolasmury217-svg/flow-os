package os.flow.launcher

import android.app.Notification
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.SystemClock
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
    val albumArt: Bitmap? = null,
    val durationMs: Long = 0L,
    val positionMs: Long = 0L,
    val positionUpdateTimeMs: Long = 0L,
    val playbackSpeed: Float = 1f,
) {
    /** Position courante extrapolée en millisecondes. */
    fun currentPositionMs(): Long {
        if (!isPlaying || positionUpdateTimeMs <= 0L) return positionMs.coerceAtLeast(0L)
        val elapsed = (SystemClock.elapsedRealtime() - positionUpdateTimeMs).coerceAtLeast(0L)
        val raw = positionMs + (elapsed * playbackSpeed).toLong()
        return if (durationMs > 0L) raw.coerceIn(0L, durationMs) else raw.coerceAtLeast(0L)
    }
}

/** Notification récente à afficher brièvement dans Flow Island. */
data class IslandAlert(
    val id: Long,
    val packageName: String,
    val appName: String,
    val title: String,
    val text: String,
    val contentIntent: PendingIntent?,
)

/** Appel entrant ou en cours détecté via les notifications d'appel (téléphone, WhatsApp, Messenger, Meet…). */
data class IslandCall(
    val key: String,
    val packageName: String,
    val callerName: String,
    val subtitle: String,
    val answerIntent: PendingIntent?,
    val declineIntent: PendingIntent?,
    val contentIntent: PendingIntent?,
    val avatar: Bitmap? = null,
    val startedAtMs: Long = System.currentTimeMillis(),
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
        publishActiveCall()
        registerMediaSessions()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        publishActive()
        publishActiveCall()
        if (sbn != null && !isCallNotification(sbn.notification)) emitAlert(sbn)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        publishActive()
        publishActiveCall()
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        unregisterMediaSessions()
        _badgedPackages.value = emptySet()
        _nowPlaying.value = null
        _activeCall.value = null
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
        val pb = ctrl?.playbackState
        val state = pb?.state
        val active = state == PlaybackState.STATE_PLAYING || state == PlaybackState.STATE_PAUSED
        val title = meta?.getString(MediaMetadata.METADATA_KEY_TITLE)?.trim().orEmpty()
        if (ctrl == null || !active || title.isEmpty()) {
            _nowPlaying.value = null
            return
        }
        val artist = meta?.getString(MediaMetadata.METADATA_KEY_ARTIST)?.trim().orEmpty()
        val rawArt = meta?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: meta?.getBitmap(MediaMetadata.METADATA_KEY_ART)
            ?: meta?.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
        val art = rawArt?.let { runCatching { Bitmap.createScaledBitmap(it, 128, 128, true) }.getOrNull() }
        val duration = meta?.getLong(MediaMetadata.METADATA_KEY_DURATION)?.coerceAtLeast(0L) ?: 0L
        _nowPlaying.value = NowPlaying(
            packageName = ctrl.packageName,
            title = title,
            artist = artist,
            isPlaying = state == PlaybackState.STATE_PLAYING,
            albumArt = art,
            durationMs = duration,
            positionMs = pb?.position?.coerceAtLeast(0L) ?: 0L,
            positionUpdateTimeMs = pb?.lastPositionUpdateTime ?: 0L,
            playbackSpeed = pb?.playbackSpeed ?: 1f,
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

    private fun isCallNotification(n: Notification?): Boolean {
        if (n == null) return false
        if (n.category == Notification.CATEGORY_CALL) return true
        val extras = n.extras ?: return false
        return extras.containsKey(Notification.EXTRA_CALL_PERSON) ||
            extras.containsKey(Notification.EXTRA_ANSWER_INTENT) ||
            extras.containsKey(Notification.EXTRA_HANG_UP_INTENT)
    }

    @Suppress("DEPRECATION")
    private fun publishActiveCall() {
        val found = runCatching {
            activeNotifications.orEmpty().firstNotNullOfOrNull { sbn ->
                val n = sbn.notification ?: return@firstNotNullOfOrNull null
                if (!isCallNotification(n)) return@firstNotNullOfOrNull null
                val extras = n.extras
                val person = extras?.getParcelable<android.app.Person>(Notification.EXTRA_CALL_PERSON)
                val caller = person?.name?.toString()?.trim()?.takeIf { it.isNotEmpty() }
                    ?: extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim()?.takeIf { it.isNotEmpty() }
                    ?: "Appel entrant"

                var answerIntent = extras?.getParcelable<PendingIntent>(Notification.EXTRA_ANSWER_INTENT)
                var declineIntent = extras?.getParcelable<PendingIntent>(Notification.EXTRA_DECLINE_INTENT)
                    ?: extras?.getParcelable(Notification.EXTRA_HANG_UP_INTENT)

                n.actions.orEmpty().forEach { act ->
                    val label = act.title?.toString().orEmpty()
                    when (classifyCallAction(label)) {
                        CallActionKind.ANSWER -> if (answerIntent == null) answerIntent = act.actionIntent
                        CallActionKind.DECLINE -> if (declineIntent == null) declineIntent = act.actionIntent
                        null -> Unit
                    }
                }

                val subtitle = extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim().orEmpty()
                    .ifEmpty { if (answerIntent != null) "mobile" else "Appel en cours" }
                val avatarDrawable = runCatching { person?.icon?.loadDrawable(this) }.getOrNull()
                    ?: runCatching { n.getLargeIcon()?.loadDrawable(this) }.getOrNull()
                val avatarBmp = drawableToSquareBitmap(avatarDrawable, 128)

                IslandCall(
                    key = sbn.key,
                    packageName = sbn.packageName,
                    callerName = caller,
                    subtitle = subtitle,
                    answerIntent = answerIntent,
                    declineIntent = declineIntent,
                    contentIntent = n.contentIntent,
                    avatar = avatarBmp,
                    startedAtMs = if (sbn.postTime > 0L) sbn.postTime else System.currentTimeMillis(),
                )
            }
        }.getOrNull()
        _activeCall.value = found
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

        private val _activeCall = MutableStateFlow<IslandCall?>(null)
        val activeCall: StateFlow<IslandCall?> = _activeCall.asStateFlow()

        fun clearCall(key: String) {
            if (_activeCall.value?.key == key) _activeCall.value = null
        }

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
