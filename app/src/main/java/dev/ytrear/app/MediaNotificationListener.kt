package dev.ytrear.app

import android.app.Notification
import android.content.ComponentName
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/**
 * Holding notification-listener access unlocks MediaSessionManager.getActiveSessions(). Updates
 * from the selected player's MediaStyle notification also trigger the proxy's ranking defence.
 * The original YouTube Music notification retains its proven snooze workaround; other players
 * remain visible until their notification behaviour has been validated individually.
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
        snoozeActiveYouTubeMediaNotificationIfSelected()
        Probe.log("MEDIA LISTENER: connected; session access=$attached")
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        super.onNotificationPosted(sbn)
        val target = MediaHub.targetPackage ?: PlayerSelection.selectedPackage(this)
        if (target == null || sbn?.packageName != target) return
        if (!sbn.notification.extras.containsKey(Notification.EXTRA_MEDIA_SESSION)) return

        RearController.onTargetMediaNotification(this)
        if (target == PlayerSelection.YOUTUBE_MUSIC) snoozeYouTubeMediaNotification(sbn)
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

    private fun snoozeActiveYouTubeMediaNotificationIfSelected() {
        if (MediaHub.targetPackage != PlayerSelection.YOUTUBE_MUSIC) return
        val active = try {
            activeNotifications.orEmpty()
        } catch (e: SecurityException) {
            Probe.log("MEDIA LISTENER: cannot inspect active notifications (${e.javaClass.simpleName})")
            return
        }
        active.firstOrNull {
            it.packageName == PlayerSelection.YOUTUBE_MUSIC &&
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
