package dev.backbeat.app

import android.app.Notification
import android.content.ComponentName
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/**
 * Holding notification-listener access unlocks MediaSessionManager.getActiveSessions(). YouTube
 * Music's own MediaStyle notification is snoozed while BackBeat supplies the replacement player;
 * this prevents HyperOS from replacing and collapsing the expanded Dynamic Island on a skip.
 */
class MediaNotificationListener : NotificationListenerService() {

    private var observing = false
    private val mediaListener = MediaHub.Listener { state ->
        RearController.render(this, state)
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        isConnected = true
        val attached = MediaHub.attach(this)
        if (!observing) {
            MediaHub.addListener(mediaListener)
            observing = true
        }
        snoozeActiveYouTubeMediaNotification()
        Probe.log("MEDIA LISTENER: connected; session access=$attached")
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        super.onNotificationPosted(sbn)
        if (sbn?.packageName != MediaHub.YT_MUSIC) return
        if (!sbn.notification.extras.containsKey(Notification.EXTRA_MEDIA_SESSION)) return

        // Reassert once for the initial competing update, then keep that notification out of
        // SystemUI while the allowlisted proxy supplies the replacement media player.
        RearController.onYouTubeMediaNotification(this)
        snoozeYouTubeMediaNotification(sbn)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        super.onNotificationRemoved(sbn)
        if (sbn?.packageName == packageName && sbn.id == NativeMediaProxy.NOTIF_ID) {
            NativeMediaProxy.onNotificationRemoved()
        }
    }

    override fun onListenerDisconnected() {
        stopObserving()
        super.onListenerDisconnected()
        NotificationListenerService.requestRebind(
            ComponentName(this, MediaNotificationListener::class.java)
        )
    }

    override fun onDestroy() {
        stopObserving()
        super.onDestroy()
    }

    private fun stopObserving() {
        isConnected = false
        if (observing) {
            MediaHub.removeListener(mediaListener)
            observing = false
        }
        if (!RearControlService.isRunning) {
            NativeMediaProxy.clear(this)
            MediaHub.detach()
        }
    }

    private fun snoozeActiveYouTubeMediaNotification() {
        val active = try {
            activeNotifications.orEmpty()
        } catch (e: SecurityException) {
            Probe.log("MEDIA LISTENER: cannot inspect active notifications (${e.javaClass.simpleName})")
            return
        }
        active.firstOrNull {
            it.packageName == MediaHub.YT_MUSIC &&
                it.notification.extras.containsKey(Notification.EXTRA_MEDIA_SESSION)
        }?.let(::snoozeYouTubeMediaNotification)
    }

    private fun snoozeYouTubeMediaNotification(sbn: StatusBarNotification) {
        try {
            snoozeNotification(sbn.key, YT_MEDIA_SNOOZE_MS)
            Probe.log("MEDIA LISTENER: snoozed YouTube Music media notification for 30 days")
        } catch (e: SecurityException) {
            Probe.log("MEDIA LISTENER: notification snooze failed (${e.javaClass.simpleName})")
        }
    }

    companion object {
        private const val YT_MEDIA_SNOOZE_MS = 30L * 24L * 60L * 60L * 1000L

        @Volatile
        var isConnected = false
            private set

        fun requestReconnect(context: android.content.Context) {
            NotificationListenerService.requestRebind(
                ComponentName(context, MediaNotificationListener::class.java)
            )
        }
    }
}
