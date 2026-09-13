# YTRear

Control a selected Android music player from the Xiaomi 17 Pro rear "Magic Back Screen" while the
main display is off. Runs on a locked, non-rooted phone — no Shizuku, no privileged install.

## How it works

HyperOS gates its native rear music widget on an exact package-name allowlist. It does not check
the certificate, installer, UID lineage, or privileged status at that gate. YTRear builds under
an allowlisted package identity, mirrors the selected app's `MediaSession` through a notification-
listener-authorized `MediaController`, and republishes it to the native rear widget. Transport
callbacks from the rear screen are forwarded back to that app.

```text
Selected player's MediaSession
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
        +---> selected player's transport controls
```

## Install

Install the APK by sideloading it — copy it to the phone and tap it, or `adb install -r
app-debug.apk`. HyperOS will ask you to allow installs from whichever app you opened it with.

Then grant these, all on YTRear:

| Setting | Where | Why |
|---|---|---|
| **Notification access** | Settings → Apps → Special app access → Notification access | Required. This is how YTRear reads the selected player's media session. Nothing works without it. |
| **Notifications** | The prompt on first launch | YTRear's rear-screen card *is* a notification. Denying this breaks the widget. |
| **Autostart** | HyperOS app info → Autostart | Lets the listener rebind after a reboot or a restart of the selected player. |
| **Battery saver: No restrictions** | HyperOS app info → Battery saver | Stops HyperOS killing the proxy while the screen is off. |

Display over other apps is only used by the diagnostic probes under **Advanced**. Skip it.

Open YTRear and choose the player to proxy. The searchable picker lists every installed app with a
launcher. Select one, grant the requested access, then open that player and start playback.

### Daily use

YTRear asks for the target whenever its main screen is opened and remembers the current selection.
After setup, open the selected player and press play. The rear card should appear within a second or
two. Use **Player** on the main screen to switch targets at any time.

Don't swipe YTRear away from Recents. HyperOS treats that as a force-stop and will refuse to rebind
the notification listener until you reopen the app.

## Player compatibility

The selected app must publish a standard Android `MediaSession`. Play/pause, next, previous,
metadata, and artwork depend on what that session exposes. Apps without a media session can still
be selected, but YTRear will remain at **Waiting for music**.

Spotify is intentionally omitted from the picker because this Xiaomi firmware already supports it
on the rear screen without a proxy.

YouTube Music remains the fully validated target. Its MediaStyle notification is snoozed while
YTRear supplies the replacement player; this preserves HyperOS's expanded Dynamic Island during a
skip. Other players keep their original notification because snoozing has not been proven safe for
them yet. Their core proxy controls should work, but each app needs physical regression testing.

### YouTube Music note

No permission or setting changes on YouTube Music itself. One thing to leave alone:

**Keep YouTube Music's notifications enabled.** Don't turn them off app-wide. When it is selected,
YTRear works *with*
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
- [Version and rollback](docs/ROLLBACK.md) — pinned 1.2 baseline and tested downgrade commands

Tested against Android 16 / SDK 36, HyperOS `OS3.0.318.0.WBLCNXM` on a Xiaomi 17 Pro. The rear
widget path is firmware-specific and will not survive arbitrary HyperOS versions.
