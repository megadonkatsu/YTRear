package dev.ytrear.app

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.util.concurrent.CopyOnWriteArraySet

/**
 * Watches the system's active media sessions and exposes the target app's session. Reading
 * sessions requires notification-listener access, which is why [MediaNotificationListener]
 * exists — its ComponentName is the key we hand to MediaSessionManager.
 *
 * In [PlayerSelection.Mode.MANUAL] the target is the picked package. In
 * [PlayerSelection.Mode.AUTO] every eligible session is observed and the one that most recently
 * started playing becomes the target, after a short debounce that prevents flapping.
 */
object MediaHub {

    /** Minimum gap between two auto switches when both sessions remain simultaneously playing. */
    private const val AUTO_SWITCH_COOLDOWN_MS = 500L

    data class NowPlaying(
        val pkg: String,
        val title: String,
        val artist: String,
        val art: Bitmap?,
        val playing: Boolean,
        val position: Long,
        val duration: Long,
        val positionAt: Long,
        val speed: Float,
        val actions: Long
    ) {
        /** PlaybackState only reports position at a point in time; extrapolate from there. */
        fun currentPosition(): Long {
            if (position < 0) return 0
            if (!playing) return position
            val drift = ((SystemClock.elapsedRealtime() - positionAt) * speed).toLong()
            val now = position + drift
            return if (duration > 0) now.coerceIn(0, duration) else now.coerceAtLeast(0)
        }

        fun can(action: Long) = actions and action != 0L
    }

    fun interface Listener {
        fun onChanged(state: NowPlaying?)
    }

    private val main = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArraySet<Listener>()

    private var appContext: Context? = null
    private var manager: MediaSessionManager? = null
    private var component: ComponentName? = null
    private var controller: MediaController? = null

    // --- auto-mode bookkeeping (main thread only) ---------------------------
    private var autoMode = false
    /** Lightweight observers on every eligible session, keyed by session token. */
    private val watchers = HashMap<MediaSession.Token, Watcher>()
    /** When each session was last seen entering PLAYING. */
    private val lastPlayingAt = HashMap<MediaSession.Token, Long>()
    private var lastAutoSwitchAt = 0L
    private var pendingSwitch: MediaSession.Token? = null
    private val autoSwitch = Runnable { refreshSessions() }

    @Volatile
    var targetPackage: String? = null
        private set

    @Volatile
    var state: NowPlaying? = null
        private set

    /** True once we've successfully read the session list at least once. */
    @Volatile
    var connected = false
        private set

    fun addListener(l: Listener) {
        listeners.add(l)
        l.onChanged(state)
    }

    fun removeListener(l: Listener) = listeners.remove(l)

    fun attach(context: Context): Boolean {
        val app = context.applicationContext
        appContext = app
        autoMode = PlayerSelection.isAuto(app)
        // In AUTO mode the bound controller, not the persisted value, is authoritative once set.
        if (!autoMode || controller == null) targetPackage = PlayerSelection.targetPackage(app)
        if (!autoMode) clearAutoState()
        val m = manager ?: app.getSystemService(MediaSessionManager::class.java) ?: return false
        manager = m
        val cn = ComponentName(app, MediaNotificationListener::class.java)
        component = cn
        return try {
            m.removeOnActiveSessionsChangedListener(sessionsChanged)
            m.addOnActiveSessionsChangedListener(sessionsChanged, cn, main)
            bind(pick(m.getActiveSessions(cn)))
            connected = true
            true
        } catch (e: SecurityException) {
            // Notification access not granted (yet).
            connected = false
            false
        }
    }

    fun detach() {
        manager?.removeOnActiveSessionsChangedListener(sessionsChanged)
        clearAutoState()
        bind(null)
        connected = false
    }

    /** Drops the previous controller and immediately resolves the newly persisted selection. */
    fun targetChanged(context: Context): Boolean {
        val app = context.applicationContext
        targetPackage = PlayerSelection.targetPackage(app)
        clearAutoState()
        RearController.targetSwitched()
        bind(null)
        return attach(app)
    }

    /**
     * Resolves active sessions as soon as an eligible player posts a MediaStyle notification in
     * AUTO mode, so its first notification can be snoozed before SystemUI switches top media.
     */
    fun onCandidateMediaNotification(context: Context) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post { onCandidateMediaNotification(context.applicationContext) }
            return
        }
        if (connected) refreshSessions() else attach(context.applicationContext)
    }

    private val sessionsChanged =
        MediaSessionManager.OnActiveSessionsChangedListener { list -> bind(pick(list.orEmpty())) }

    private fun pick(list: List<MediaController>): MediaController? =
        if (autoMode) pickAuto(list) else pickManual(list)

    private fun pickManual(list: List<MediaController>): MediaController? {
        val target = targetPackage ?: return null
        if (list.isEmpty()) return null
        return list.firstOrNull { it.packageName == target && isPlaying(it) }
            ?: list.firstOrNull { it.packageName == target }
    }

    /**
     * Chooses the most recently started eligible player.
     *
     * - The newest playing candidate wins, but only after [AUTO_SWITCH_DEBOUNCE_MS] if another
     *   target is already bound, so ads, previews and brief blips do not steal the card.
     * - With nothing playing the current target is sticky; otherwise fall back to the persisted
     *   target, then to the most recently playing session still alive.
     */
    private fun pickAuto(list: List<MediaController>): MediaController? {
        val context = appContext ?: return null
        val candidates = list.filter { PlayerSelection.isAutoCandidate(context, it.packageName) }
        syncWatchers(candidates)

        val now = SystemClock.elapsedRealtime()
        // getActiveSessions() is priority ordered; preserve that order for sessions we have not
        // yet seen start playing, so an initial scan still prefers the system's newest player.
        candidates.forEachIndexed { index, c ->
            if (isPlaying(c) && c.sessionToken !in lastPlayingAt) {
                lastPlayingAt[c.sessionToken] = now - index
            }
        }
        val liveTokens = candidates.mapTo(HashSet()) { it.sessionToken }
        lastPlayingAt.keys.retainAll(liveTokens)

        val current = controller?.let { bound ->
            candidates.firstOrNull { it.sessionToken == bound.sessionToken }
        }
        // Prime the proxy's <200 ms SubScreenCenter window as soon as Android reorders another
        // session to the top of getActiveSessions(), before its PLAYING state reaches SystemUI.
        if (current != null && candidates.firstOrNull()?.sessionToken != current.sessionToken) {
            NativeMediaProxy.reassertRanking()
        }
        val best = candidates
            .filter(::isPlaying)
            .maxByOrNull { lastPlayingAt[it.sessionToken] ?: 0L }

        if (best == null) {
            cancelPendingSwitch()
            return current
                ?: candidates.firstOrNull { it.packageName == targetPackage }
                ?: candidates.maxByOrNull { lastPlayingAt[it.sessionToken] ?: Long.MIN_VALUE }
                    ?.takeIf { it.sessionToken in lastPlayingAt }
                ?: candidates.firstOrNull()
        }
        if (current == null || best.sessionToken == current.sessionToken) {
            cancelPendingSwitch()
            return best
        }

        // Only overtake a bound target when the candidate started playing more recently than the
        // target, or the target has stopped playing outside a track-skip transition.
        val bestStartedAt = lastPlayingAt[best.sessionToken] ?: 0L
        val currentStartedAt = lastPlayingAt[current.sessionToken] ?: 0L
        val currentEffectivelyPlaying = isPlaying(current) || RearController.isInTrackTransition()
        if (bestStartedAt <= currentStartedAt && currentEffectivelyPlaying) {
            cancelPendingSwitch()
            return current
        }

        // Rate-limit only rapid back-and-forth switches between two simultaneously-playing
        // sessions; normal switches happen immediately so the new player's notification is snoozed
        // before HyperOS can tear down the rear card.
        val cooldownRemaining = AUTO_SWITCH_COOLDOWN_MS - (now - lastAutoSwitchAt)
        if (currentEffectivelyPlaying && lastAutoSwitchAt > 0L && cooldownRemaining > 0) {
            if (pendingSwitch != best.sessionToken) {
                Probe.log("AUTO: ${best.packageName} started; switching after cooldown")
            }
            pendingSwitch = best.sessionToken
            main.removeCallbacks(autoSwitch)
            main.postDelayed(autoSwitch, cooldownRemaining)
            return current
        }
        cancelPendingSwitch()
        return best
    }

    private fun cancelPendingSwitch() {
        if (pendingSwitch == null) return
        pendingSwitch = null
        main.removeCallbacks(autoSwitch)
    }

    private fun syncWatchers(candidates: List<MediaController>) {
        val wanted = candidates.associateBy { it.sessionToken }
        val iterator = watchers.entries.iterator()
        while (iterator.hasNext()) {
            val (token, watcher) = iterator.next()
            if (token !in wanted) {
                watcher.controller.unregisterCallback(watcher)
                iterator.remove()
            }
        }
        wanted.forEach { (token, c) ->
            if (token !in watchers) {
                val watcher = Watcher(c)
                c.registerCallback(watcher, main)
                watchers[token] = watcher
            }
        }
    }

    private fun clearAutoState() {
        cancelPendingSwitch()
        watchers.values.forEach { it.controller.unregisterCallback(it) }
        watchers.clear()
        lastPlayingAt.clear()
        lastAutoSwitchAt = 0L
    }

    /** Observes a non-target session only for the moment it starts playing. */
    private class Watcher(val controller: MediaController) : MediaController.Callback() {
        private var wasPlaying = isPlaying(controller)

        override fun onPlaybackStateChanged(state: PlaybackState?) {
            val playing = state?.state == PlaybackState.STATE_PLAYING
            if (playing && !wasPlaying) {
                lastPlayingAt[controller.sessionToken] = SystemClock.elapsedRealtime()
            }
            val changed = playing != wasPlaying
            wasPlaying = playing
            if (controller.sessionToken != MediaHub.controller?.sessionToken) {
                // Prime the proxy's <200 ms SubScreenCenter window while a competitor warms up.
                NativeMediaProxy.reassertRanking()
                if (changed) refreshSessions()
            }
        }

        override fun onSessionDestroyed() {
            main.post { refreshSessions() }
        }
    }

    private fun bind(next: MediaController?) {
        val current = controller
        if (current?.sessionToken == next?.sessionToken) {
            publish(next?.let(::snapshot))
            return
        }
        current?.unregisterCallback(callback)
        controller = next
        next?.registerCallback(callback, main)
        val switchedTarget =
            next != null && (current?.packageName != next.packageName || next.packageName != targetPackage)
        if (autoMode && next != null) onAutoTargetBound(current, next)
        publish(next?.let(::snapshot))
        if (autoMode && switchedTarget) {
            // Restore the previous player's original notification and snooze the new one.
            appContext?.let { MediaNotificationListener.targetChanged(it) }
        }
    }

    /**
     * Applies a committed auto switch. The proxy goes straight from the old snapshot to the new
     * one without an intermediate null, so the rear card is never torn down.
     */
    private fun onAutoTargetBound(previous: MediaController?, next: MediaController) {
        val context = appContext ?: return
        val packageChanged = next.packageName != targetPackage || previous?.packageName != next.packageName
        if (packageChanged) PlayerSelection.recordAutoTarget(context, next.packageName)
        if (previous != null) lastAutoSwitchAt = SystemClock.elapsedRealtime()
        targetPackage = next.packageName
        RearController.targetSwitched()
        Probe.log(
            "AUTO: target ${previous?.packageName ?: "none"} -> ${next.packageName}" +
                if (previous?.packageName == next.packageName) " (new session)" else ""
        )
    }

    private val callback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) {
            val switching = autoMode && SystemClock.elapsedRealtime() - lastAutoSwitchAt < 500L
            if (metadata != null && !switching) NativeMediaProxy.defendTrackTransition()
            republish()
        }

        override fun onPlaybackStateChanged(state: PlaybackState?) {
            if (autoMode && state?.state != PlaybackState.STATE_PLAYING) {
                // When the current target loses audio focus to a starting player, prime the
                // proxy's <200 ms SubScreenCenter window before the new player enters PLAYING.
                NativeMediaProxy.reassertRanking()
            }
            republish()
            // A paused target lets a playing competitor take over without waiting for a
            // session-list change.
            if (autoMode && state?.state != PlaybackState.STATE_PLAYING && watchers.size > 1) {
                refreshSessions()
            }
        }

        override fun onSessionDestroyed() {
            controller?.unregisterCallback(this)
            controller = null
            if (autoMode) {
                // Resolve a successor first; bind() publishes null only if there is none.
                main.post { refreshSessions() }
            } else {
                publish(null)
                main.post { refreshSessions() }
            }
        }
    }

    private fun refreshSessions() {
        val m = manager ?: return
        val cn = component ?: return
        try {
            bind(pick(m.getActiveSessions(cn)))
            connected = true
        } catch (_: SecurityException) {
            bind(null)
            connected = false
        }
    }

    private fun republish() = publish(controller?.let(::snapshot))

    private fun publish(next: NowPlaying?) {
        state = next
        main.post { listeners.forEach { it.onChanged(next) } }
    }

    private fun isPlaying(c: MediaController) =
        c.playbackState?.state == PlaybackState.STATE_PLAYING

    private fun snapshot(c: MediaController): NowPlaying {
        val md = c.metadata
        val ps = c.playbackState
        return NowPlaying(
            pkg = c.packageName,
            title = md?.getString(MediaMetadata.METADATA_KEY_TITLE)
                ?: md?.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE).orEmpty(),
            artist = md?.getString(MediaMetadata.METADATA_KEY_ARTIST)
                ?: md?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
                ?: md?.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE).orEmpty(),
            art = md?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
                ?: md?.getBitmap(MediaMetadata.METADATA_KEY_ART)
                ?: md?.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON),
            playing = ps?.state == PlaybackState.STATE_PLAYING,
            position = ps?.position ?: 0L,
            duration = md?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L,
            positionAt = ps?.lastPositionUpdateTime ?: SystemClock.elapsedRealtime(),
            speed = ps?.playbackSpeed?.takeIf { it > 0f } ?: 1f,
            actions = ps?.actions ?: 0L
        )
    }

    // --- transport ---------------------------------------------------------

    fun togglePlayPause(): Boolean {
        val c = controller ?: return false
        if (c.playbackState?.state == PlaybackState.STATE_PLAYING) {
            RearController.cancelTrackTransition()
            c.transportControls.pause()
        } else {
            c.transportControls.play()
        }
        return true
    }

    fun play(): Boolean = controller?.let {
        it.transportControls.play()
        true
    } ?: false

    fun pause(): Boolean = controller?.let {
        RearController.cancelTrackTransition()
        it.transportControls.pause()
        true
    } ?: false

    fun next(): Boolean = controller?.let {
        RearController.expectTrackTransition()
        it.transportControls.skipToNext()
        true
    } ?: false

    fun previous(): Boolean = controller?.let {
        RearController.expectTrackTransition()
        it.transportControls.skipToPrevious()
        true
    } ?: false

    fun seekTo(ms: Long): Boolean = controller?.let {
        it.transportControls.seekTo(ms)
        true
    } ?: false
}
