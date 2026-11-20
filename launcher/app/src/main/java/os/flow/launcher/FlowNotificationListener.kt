package os.flow.launcher

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Écoute les notifications actives (uniquement si l'utilisateur l'active dans les réglages Android)
 * pour afficher une pastille discrète sur les icônes d'applis ayant une notification non lue.
 */
class FlowNotificationListener : NotificationListenerService() {

    override fun onListenerConnected() {
        super.onListenerConnected()
        publishActive()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        publishActive()
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        publishActive()
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        _badgedPackages.value = emptySet()
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
        private val _badgedPackages = MutableStateFlow<Set<String>>(emptySet())
        val badgedPackages: StateFlow<Set<String>> = _badgedPackages.asStateFlow()

        fun isEnabled(context: Context): Boolean {
            val flat = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners").orEmpty()
            val expected = ComponentName(context, FlowNotificationListener::class.java).flattenToString()
            return flat.split(":").any { it.equals(expected, ignoreCase = true) }
        }
    }
}
