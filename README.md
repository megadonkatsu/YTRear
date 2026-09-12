# YTRear

Control YouTube Music from the Xiaomi 17 Pro rear "Magic Back Screen" while the main display is
off. Runs on a locked, non-rooted phone — no Shizuku, no privileged install.

## How it works

HyperOS gates its native rear music widget on an exact package-name allowlist. It does not check
the certificate, installer, UID lineage, or privileged status at that gate. YTRear builds under
an allowlisted package identity, mirrors YouTube Music's `MediaSession` through a notification-
listener-authorized `MediaController`, and republishes it to the native rear widget. Transport
callbacks from the rear screen are forwarded back to YouTube Music.

```text
YouTube Music MediaSession
        |  MediaController (notification listener)
        v
    MediaHub.kt
        |  metadata, artwork, playback state, position
        v
 NativeMediaProxy.kt
        |
        v
 HyperOS unified.music:music_media rear widget
        |  native MediaSession callbacks
        +---> YouTube Music transport controls
```

## Install

Install the APK by sideloading it — copy it to the phone and tap it, or `adb install -r
app-debug.apk`. HyperOS will ask you to allow installs from whichever app you opened it with.

Then grant these, all on YTRear:

| Setting | Where | Why |
|---|---|---|
| **Notification access** | Settings → Apps → Special app access → Notification access | Required. This is how YTRear reads what YouTube Music is playing. Nothing works without it. |
| **Notifications** | The prompt on first launch | YTRear's rear-screen card *is* a notification. Denying this breaks the widget. |
| **Autostart** | HyperOS app info → Autostart | Lets the listener rebind after a reboot or a restart of YouTube Music. |
| **Battery saver: No restrictions** | HyperOS app info → Battery saver | Stops HyperOS killing the proxy while the screen is off. |

Display over other apps is only used by the diagnostic probes under **Advanced**. Skip it.

Open YTRear once and tap **Start / Refresh rear controls**.

### Daily use

After that one-time setup, just open YouTube Music and press play. The rear card appears on its own
within a second or two — there is no need to open YTRear again, before or after.

Don't swipe YTRear away from Recents. HyperOS treats that as a force-stop and will refuse to rebind
the notification listener until you reopen the app.

## Does YouTube Music need anything?

No permission or setting changes on YouTube Music itself. One thing to leave alone:

**Keep YouTube Music's notifications enabled.** Don't turn them off app-wide. YTRear works *with*
that notification — it snoozes the active media one and posts its own in its place.

Expect YouTube Music's media notification to vanish from the shade while YTRear is running. That is
deliberate, not a fault: if both appear in SystemUI, HyperOS drops YTRear's Dynamic Island entry
during a skip. Playback is untouched, and YTRear's card becomes the visible player. To get the
native one back, turn off YTRear's notification access, then force-stop and reopen YouTube Music.

## Build

Requires JDK 17 and the Android SDK (compileSdk 36, build tools 36.0.0).

```bash
./gradlew :app:assembleDebug
```

The APK lands at `app/build/outputs/apk/debug/app-debug.apk`.

## Package identity

The build sets `applicationId` to a package name that is on the firmware's rear-music map, while
the Kotlin namespace stays `dev.ytrear.app`. Android cannot install the genuine owner of that
package alongside this build. Use it only on a device you own, for interoperability testing.

## Docs

- [Research](docs/RESEARCH.md) — reverse-engineering findings and rejected approaches
- [Testing](docs/TESTING.md) — validation status and diagnostic commands
- [Evidence](docs/evidence/README.md) — device traces and screenshots

Tested against Android 16 / SDK 36, HyperOS `OS3.0.318.0.WBLCNXM` on a Xiaomi 17 Pro. The rear
widget path is firmware-specific and will not survive arbitrary HyperOS versions.
