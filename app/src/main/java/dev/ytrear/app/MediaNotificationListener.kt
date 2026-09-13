package dev.ytrear.app

import android.app.Notification
import android.content.ComponentName
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/**
 * Holding notification-listener access unlocks MediaSessionManager.getActiveSessions(). Updates
 * from the selected player's MediaStyle notification also trigger the proxy's ranking defence.
 * The selected player's original media notification is snoozed while YTRear supplies its proxy,
 * preventing the original card from taking back HyperOS's top-media rank during track changes.
 */
class MediaNotificationListener : NotificationListenerService() {

    private var observing = false
    private val mediaListener = MediaHub.Listener { state ->
        RearController.render(this, state)
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        activeInstance = this
        isConnected = true
        val attached = MediaHub.attach(this)
        if (!observing) {
            MediaHub.addListener(mediaListener)
            observing = true
        }
        snoozeActiveTargetMediaNotification()
        Probe.log("MEDIA LISTENER: connected; session access=$attached")
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        super.onNotificationPosted(sbn)
        val target = MediaHub.targetPackage ?: PlayerSelection.selectedPackage(this)
        if (target == null || sbn?.packageName != target) return
        if (!sbn.notification.extras.containsKey(Notification.EXTRA_MEDIA_SESSION)) return

        RearController.onTargetMediaNotification(this)
        snoozeTargetMediaNotification(sbn)
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
        if (activeInstance === this) activeInstance = null
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

    private fun snoozeActiveTargetMediaNotification() {
        val target = MediaHub.targetPackage ?: PlayerSelection.selectedPackage(this) ?: return
        val active = try {
            activeNotifications.orEmpty()
        } catch (e: SecurityException) {
            Probe.log("MEDIA LISTENER: cannot inspect active notifications (${e.javaClass.simpleName})")
            return
        }
        active.firstOrNull {
            it.packageName == target &&
                it.notification.extras.containsKey(Notification.EXTRA_MEDIA_SESSION)
        }?.let(::snoozeTargetMediaNotification)
    }

    private fun snoozeTargetMediaNotification(sbn: StatusBarNotification) {
        try {
            snoozeNotification(sbn.key, TARGET_MEDIA_SNOOZE_MS)
            Probe.log(
                "MEDIA LISTENER: snoozed selected player media notification for 30 days " +
                    "(${sbn.packageName})"
            )
        } catch (e: SecurityException) {
            Probe.log("MEDIA LISTENER: notification snooze failed (${e.javaClass.simpleName})")
        }
    }

    companion object {
        private const val TARGET_MEDIA_SNOOZE_MS = 30L * 24L * 60L * 60L * 1000L

        @Volatile
        private var activeInstance: MediaNotificationListener? = null

        @Volatile
        var isConnected = false
            private set

        /** Applies the new selection immediately when the listener is already connected. */
        fun targetChanged(context: android.content.Context) {
            val listener = activeInstance
            if (listener != null) {
                listener.snoozeActiveTargetMediaNotification()
            } else {
                NotificationListenerService.requestRebind(
                    ComponentName(context, MediaNotificationListener::class.java)
                )
            }
        }
    }
}
