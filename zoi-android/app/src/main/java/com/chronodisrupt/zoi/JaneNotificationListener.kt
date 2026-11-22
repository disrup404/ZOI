package com.chronodisrupt.zoi

import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.app.Notification

internal data class JaneNotification(
    val app: String,
    val packageName: String,
    val title: String,
    val text: String,
    val postedAt: Long
)

class JaneNotificationListener : NotificationListenerService() {

    override fun onListenerConnected() {
        refreshNotifications()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        refreshNotifications()
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        refreshNotifications()
    }

    private fun refreshNotifications() {
        val active = try {
            activeNotifications?.toList().orEmpty()
        } catch (_: SecurityException) {
            emptyList()
        } catch (_: Exception) {
            emptyList()
        }

        val packageManager = packageManager
        val snapshot = active.asSequence()
            .filter { it.packageName != packageName }
            .mapNotNull { item ->
                val extras = item.notification.extras ?: return@mapNotNull null
                val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.take(160).orEmpty()
                val text = (
                    extras.getCharSequence(Notification.EXTRA_BIG_TEXT)
                        ?: extras.getCharSequence(Notification.EXTRA_TEXT)
                    )?.toString()?.take(1200).orEmpty()
                if (title.isBlank() && text.isBlank()) return@mapNotNull null
                val app = try {
                    packageManager.getApplicationLabel(
                        packageManager.getApplicationInfo(item.packageName, 0)
                    ).toString()
                } catch (_: Exception) {
                    item.packageName
                }
                JaneNotification(app, item.packageName, title, text, item.postTime)
            }
            .sortedByDescending { it.postedAt }
            .take(25)
            .toList()

        synchronized(lock) {
            recent = snapshot
        }
    }

    companion object {
        private val lock = Any()
        @Volatile private var recent: List<JaneNotification> = emptyList()

        fun recentNotifications(): List<JaneNotification> =
            synchronized(lock) { recent.toList() }

        fun hasNotificationAccess(context: Context): Boolean {
            val enabled = Settings.Secure.getString(
                context.contentResolver,
                "enabled_notification_listeners"
            ).orEmpty()
            val component = ComponentName(context, JaneNotificationListener::class.java)
            return enabled.split(':').any { it.equals(component.flattenToString(), ignoreCase = true) }
        }
    }
}
