package dev.backbeat.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.annotation.ColorRes
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import dev.backbeat.app.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding
    private val rear by lazy { RearWindow(this) }

    /** Keeps the now-playing card live while the activity is in front. */
    private val mediaListener = MediaHub.Listener { runOnUiThread { refresh() } }
    private var observingMedia = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)

        // targetSdk 36 always lays out edge to edge; keep the content clear of the
        // status bar and the gesture bar.
        ViewCompat.setOnApplyWindowInsetsListener(b.root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(0, bars.top, 0, bars.bottom)
            insets
        }

        Probe.sink = Probe.Sink { full -> runOnUiThread { b.log.text = full } }
        b.log.text = Probe.text()

        b.btnNotificationAccess.setOnClickListener { openNotificationAccess() }
        b.btnController.setOnClickListener { startController() }
        b.btnAdvanced.setOnClickListener { toggleAdvanced() }
        b.btnRefresh.setOnClickListener { refresh() }
        b.btnActivity.setOnClickListener { showFromActivity() }
        b.btnService.setOnClickListener { showFromService() }
        b.btnHide.setOnClickListener { hideAll() }
        b.btnOverlayPerm.setOnClickListener { requestOverlay() }

        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }

        refresh()
        handleProbeExtra(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleProbeExtra(intent)
    }

    /** Lets the probe be driven over adb: -e probe activity|service|hide */
    private fun handleProbeExtra(intent: Intent?) {
        when (intent?.getStringExtra("probe")) {
            "activity" -> showFromActivity()
            "service" -> showFromService()
            "hide" -> hideAll()
            "main" -> showOnDisplay(android.view.Display.DEFAULT_DISPLAY)
            "notif" -> RearNotification.post(
                this,
                intent.getStringExtra("title") ?: "BackBeat",
                intent.getStringExtra("artist") ?: "Rear widget probe",
                playing = intent.getBooleanExtra("playing", true),
                business = intent.getStringExtra("business") ?: "music"
            )
            "notifoff" -> RearNotification.cancel(this)
        }
    }

    /** One plain-language sentence for the status card, plus the dot colour behind it. */
    private data class Stage(
        @ColorRes val dot: Int,
        @StringRes val title: Int,
        @StringRes val detail: Int
    )

    private fun stage(access: Boolean, running: Boolean, playing: MediaHub.NowPlaying?): Stage = when {
        !access -> Stage(R.color.state_bad, R.string.state_setup_title, R.string.state_setup_detail)
        !running -> Stage(R.color.state_wait, R.string.state_start_title, R.string.state_start_detail)
        playing == null -> Stage(R.color.state_wait, R.string.state_waiting_title, R.string.state_waiting_detail)
        !NativeMediaProxy.active -> Stage(R.color.state_wait, R.string.state_linking_title, R.string.state_linking_detail)
        else -> Stage(R.color.state_ok, R.string.state_ready_title, R.string.state_ready_detail)
    }

    private fun refresh() {
        val access = hasNotificationAccess()
        val running = RearControlService.isRunning
        val nowPlaying = MediaHub.state

        val stage = stage(access, running, nowPlaying)
        b.statusDot.backgroundTintList = ColorStateList.valueOf(getColor(stage.dot))
        b.statusHeadline.setText(stage.title)
        b.statusDetail.setText(stage.detail)

        b.nowPlaying.isVisible = nowPlaying != null
        if (nowPlaying != null) {
            b.trackTitle.text = nowPlaying.title.ifBlank { getString(R.string.unknown_track) }
            b.trackArtist.text = nowPlaying.artist.ifBlank { getString(R.string.unknown_artist) }
            val art = nowPlaying.art
            if (art != null) b.art.setImageBitmap(art) else b.art.setImageDrawable(null)
        }

        // Setup step one disappears for good once it is done; step two stays as a manual retry.
        b.btnNotificationAccess.isVisible = !access
        b.btnController.setText(
            if (running) R.string.refresh_rear_controls else R.string.start_rear_controls
        )

        // Media callbacks arrive in bursts; only pay for the display enumeration when the
        // panel that shows it is actually open.
        if (b.advancedPanel.isVisible) refreshDiagnostics(access, nowPlaying)
    }

    /** The old probe read-outs, unchanged, now living behind the Advanced toggle. */
    private fun refreshDiagnostics(access: Boolean, nowPlaying: MediaHub.NowPlaying?) {
        val displays = Displays.all(this)
        b.displays.text = displays.joinToString("\n\n") { it.toString() }
        val rearDisplay = Displays.pickRear(this)
        b.status.text = buildString {
            append("Installed identity: $packageName\n")
            append("Notification access: ${if (access) "GRANTED" else "MISSING"}\n")
            append("Media session access: ${if (MediaHub.connected) "CONNECTED" else "DISCONNECTED"}\n")
            append("Rear control service: ${if (RearControlService.isRunning) "RUNNING" else "STOPPED"}\n")
            append("YouTube Music: ${nowPlaying?.title?.ifBlank { "active" } ?: "no active session"}\n")
            append("Allowlisted proxy: ${if (NativeMediaProxy.active) "ACTIVE" else "INACTIVE"}\n")
            append("Displays: ${displays.size}\n")
            append("Rear pick: ${rearDisplay?.displayId ?: "none"}\n")
            append("Overlay permission: ${if (Settings.canDrawOverlays(this@MainActivity)) "GRANTED" else "MISSING"}")
        }
    }

    private fun toggleAdvanced() {
        val show = !b.advancedPanel.isVisible
        b.advancedPanel.isVisible = show
        b.btnAdvanced.setText(if (show) R.string.hide_advanced else R.string.show_advanced)
        if (show) refresh()
    }

    private fun hasNotificationAccess(): Boolean =
        NotificationManagerCompat.getEnabledListenerPackages(this).contains(packageName)

    private fun openNotificationAccess() {
        startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
    }

    private fun startController() {
        if (!hasNotificationAccess()) {
            Probe.log("CONTROLLER: notification access is required")
            openNotificationAccess()
            return
        }

        RearControlService.start(this)
        if (MediaHub.attach(this)) {
            RearController.render(this, MediaHub.state, force = true)
            Probe.log("CONTROLLER: attached; rear widget refreshed")
        } else {
            Probe.log("CONTROLLER: could not read active media sessions")
        }
        refresh()
    }

    private fun showFromActivity() {
        val display = Displays.pickRear(this)
        if (display == null) {
            Probe.log("ACTIVITY: no secondary display found")
            return
        }
        Probe.log("ACTIVITY: targeting display ${Displays.describe(display)}")
        rear.show(display)
    }

    /** Control test: same overlay window, aimed at an explicit display id. */
    private fun showOnDisplay(id: Int) {
        val display = Displays.manager(this).getDisplay(id)
        if (display == null) {
            Probe.log("CONTROL: display $id not found")
            return
        }
        Probe.log("CONTROL: targeting display ${Displays.describe(display)}")
        rear.show(display)
    }

    private fun showFromService() {
        if (!Settings.canDrawOverlays(this)) {
            Probe.log("SERVICE: overlay permission missing -- grant it first")
            return
        }
        OverlayService.send(this, OverlayService.ACTION_SHOW)
    }

    private fun hideAll() {
        hideActivityPresentation()
        OverlayService.send(this, OverlayService.ACTION_HIDE)
    }

    private fun hideActivityPresentation() {
        rear.hide()
    }

    private fun requestOverlay() {
        startActivity(
            Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
        )
    }

    override fun onResume() {
        super.onResume()
        if (hasNotificationAccess()) {
            MediaNotificationListener.requestReconnect(this)
            RearControlService.start(this)
            if (MediaHub.attach(this)) {
                RearController.render(this, MediaHub.state)
            }
        }
        if (!observingMedia) {
            MediaHub.addListener(mediaListener)
            observingMedia = true
        }
        refresh()
    }

    override fun onPause() {
        if (observingMedia) {
            MediaHub.removeListener(mediaListener)
            observingMedia = false
        }
        super.onPause()
    }

    override fun onDestroy() {
        Probe.sink = null
        hideActivityPresentation()
        super.onDestroy()
    }
}
