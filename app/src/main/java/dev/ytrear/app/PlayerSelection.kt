package dev.ytrear.app

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.edit
import java.util.Locale

/**
 * Persists which app's MediaSession YTRear should mirror.
 *
 * In [Mode.MANUAL] the target is the app chosen in the picker. In [Mode.AUTO] [MediaHub] follows
 * the eligible player that most recently started playing and records it here, so the last
 * auto target survives process death and reboot.
 */
object PlayerSelection {

    const val YOUTUBE_MUSIC = "com.google.android.apps.youtube.music"
    private const val SPOTIFY = "com.spotify.music"
    private val NATIVE_REAR_PLAYERS = setOf(SPOTIFY)

    enum class Mode { MANUAL, AUTO }

    data class AppEntry(
        val packageName: String,
        val label: String
    )

    private const val PREFS = "player_selection"
    private const val KEY_PACKAGE = "package"
    private const val KEY_LABEL = "label"
    private const val KEY_PENDING_RESTORE_PACKAGES = "pending_restore_packages"
    private const val KEY_MODE = "mode"
    private const val KEY_AUTO_PACKAGE = "auto_package"
    private const val KEY_AUTO_LABEL = "auto_label"
    private const val KEY_AUTO_EXCLUDED = "auto_excluded_packages"

    /** Launcher lookups are binder calls; candidates are re-checked on every session change. */
    private val launchableCache = HashMap<String, Boolean>()

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // --- mode ----------------------------------------------------------------

    fun mode(context: Context): Mode =
        when (prefs(context).getString(KEY_MODE, null)) {
            Mode.AUTO.name -> Mode.AUTO
            else -> Mode.MANUAL
        }

    fun isAuto(context: Context) = mode(context) == Mode.AUTO

    /** True when there is enough configuration to start the proxy. */
    fun isConfigured(context: Context) = isAuto(context) || selectedPackage(context) != null

    fun setMode(context: Context, mode: Mode) {
        if (mode(context) == mode) return
        val previousTarget = targetPackage(context)
        prefs(context).edit { putString(KEY_MODE, mode.name) }
        val nextTarget = targetPackage(context)
        if (previousTarget != null && previousTarget != nextTarget) {
            queueRestore(context, previousTarget)
        }
        Probe.log("PLAYER: mode=${mode.name}")
    }

    // --- effective target ---------------------------------------------------

    /** The package currently mirrored (or about to be), whatever the mode. */
    fun targetPackage(context: Context): String? =
        if (isAuto(context)) autoPackage(context) else selectedPackage(context)

    fun targetLabel(context: Context): String? =
        if (isAuto(context)) autoLabel(context) else selectedLabel(context)

    // --- manual selection ---------------------------------------------------

    fun selectedPackage(context: Context): String? =
        prefs(context)
            .getString(KEY_PACKAGE, null)
            ?.takeIf { it.isNotBlank() }
            ?.takeUnless { it in NATIVE_REAR_PLAYERS }

    fun selectedLabel(context: Context): String? {
        if (selectedPackage(context) == null) return null
        return prefs(context)
            .getString(KEY_LABEL, null)
            ?.takeIf { it.isNotBlank() }
    }

    /** An explicit pick always means MANUAL mode. */
    fun select(context: Context, app: AppEntry) {
        val previousTarget = targetPackage(context)
        prefs(context).edit {
            putString(KEY_PACKAGE, app.packageName)
            putString(KEY_LABEL, app.label)
            putString(KEY_MODE, Mode.MANUAL.name)
        }
        if (previousTarget != null && previousTarget != app.packageName) {
            queueRestore(context, previousTarget)
        }
        Probe.log("PLAYER: selected ${app.label} (${app.packageName})")
    }

    // --- auto target --------------------------------------------------------

    fun autoPackage(context: Context): String? =
        prefs(context)
            .getString(KEY_AUTO_PACKAGE, null)
            ?.takeIf { it.isNotBlank() }
            ?.takeUnless { it in NATIVE_REAR_PLAYERS || it in autoExcludedPackages(context) }

    fun autoLabel(context: Context): String? {
        val pkg = autoPackage(context) ?: return null
        return prefs(context).getString(KEY_AUTO_LABEL, null)?.takeIf { it.isNotBlank() } ?: pkg
    }

    /** Persists a new auto target and queues the previous one for notification restore. */
    fun recordAutoTarget(context: Context, packageName: String) {
        val previous = autoPackage(context)
        if (previous == packageName) return
        val label = resolveLabel(context, packageName)
        prefs(context).edit {
            putString(KEY_AUTO_PACKAGE, packageName)
            putString(KEY_AUTO_LABEL, label)
        }
        if (previous != null) queueRestore(context, previous)
        Probe.log("PLAYER: auto target $label ($packageName); previous=${previous ?: "none"}")
    }

    fun autoExcludedPackages(context: Context): Set<String> =
        prefs(context).getStringSet(KEY_AUTO_EXCLUDED, emptySet()).orEmpty().toSet()

    fun setAutoExcluded(context: Context, packageName: String, excluded: Boolean) {
        val current = autoExcludedPackages(context).toMutableSet()
        val changed = if (excluded) current.add(packageName) else current.remove(packageName)
        if (!changed) return
        val wasAutoTarget = excluded && prefs(context).getString(KEY_AUTO_PACKAGE, null) == packageName
        prefs(context).edit {
            putStringSet(KEY_AUTO_EXCLUDED, current)
            if (wasAutoTarget) {
                remove(KEY_AUTO_PACKAGE)
                remove(KEY_AUTO_LABEL)
            }
        }
        if (wasAutoTarget) queueRestore(context, packageName)
    }

    /**
     * Whether a session owner may become the auto target. Our own proxy session, players with a
     * native rear widget, headless packages (nothing to open on tap) and user exclusions are
     * skipped.
     */
    fun isAutoCandidate(context: Context, packageName: String): Boolean {
        if (packageName.isBlank()) return false
        if (packageName == context.packageName) return false
        if (packageName in NATIVE_REAR_PLAYERS) return false
        if (packageName in autoExcludedPackages(context)) return false
        return isLaunchable(context, packageName)
    }

    private fun isLaunchable(context: Context, packageName: String): Boolean =
        synchronized(launchableCache) {
            launchableCache.getOrPut(packageName) {
                context.packageManager.getLaunchIntentForPackage(packageName) != null
            }
        }

    fun resolveLabel(context: Context, packageName: String): String {
        val pm = context.packageManager
        return try {
            val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getApplicationInfo(packageName, PackageManager.ApplicationInfoFlags.of(0L))
            } else {
                @Suppress("DEPRECATION")
                pm.getApplicationInfo(packageName, 0)
            }
            pm.getApplicationLabel(info).toString().trim().ifBlank { packageName }
        } catch (_: PackageManager.NameNotFoundException) {
            packageName
        }
    }

    // --- notification restore bookkeeping ----------------------------------

    private fun queueRestore(context: Context, packageName: String) {
        val preferences = prefs(context)
        val pending = preferences
            .getStringSet(KEY_PENDING_RESTORE_PACKAGES, emptySet())
            .orEmpty()
            .toMutableSet()
        if (pending.add(packageName)) {
            preferences.edit { putStringSet(KEY_PENDING_RESTORE_PACKAGES, pending) }
        }
    }

    /** Packages whose YTRear-managed media notification should return after a player switch. */
    fun pendingRestorePackages(context: Context): Set<String> =
        prefs(context)
            .getStringSet(KEY_PENDING_RESTORE_PACKAGES, emptySet())
            .orEmpty()
            .toSet()

    fun markRestoresHandled(context: Context, packages: Set<String>) {
        if (packages.isEmpty()) return
        val preferences = prefs(context)
        val remaining = preferences
            .getStringSet(KEY_PENDING_RESTORE_PACKAGES, emptySet())
            .orEmpty()
            .toMutableSet()
        if (!remaining.removeAll(packages)) return
        preferences.edit { putStringSet(KEY_PENDING_RESTORE_PACKAGES, remaining) }
    }

    /**
     * Returns every enabled app with a launcher activity. Headless services are intentionally
     * omitted: there is nothing useful to open when the proxy notification is tapped.
     */
    fun launchableApps(context: Context): List<AppEntry> {
        val pm = context.packageManager
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val resolved = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.queryIntentActivities(launcher, PackageManager.ResolveInfoFlags.of(0L))
        } else {
            @Suppress("DEPRECATION")
            pm.queryIntentActivities(launcher, 0)
        }

        return resolved.mapNotNull { info ->
            val application = info.activityInfo?.applicationInfo ?: return@mapNotNull null
            val packageName = application.packageName
            if (packageName == context.packageName || packageName in NATIVE_REAR_PLAYERS) {
                return@mapNotNull null
            }
            val label = application.loadLabel(pm)?.toString()?.trim().orEmpty()
                .ifBlank { packageName }
            AppEntry(packageName, label)
        }
            .distinctBy { it.packageName }
            .sortedWith(
                compareBy<AppEntry>(
                    { it.label.lowercase(Locale.getDefault()) },
                    { it.packageName }
                )
            )
    }
}
