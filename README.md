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

## Build

Requires JDK 17 and the Android SDK (compileSdk 36, build tools 36.0.0).

```bash
./gradlew :app:assembleAllowlistedDebug
```

Install the APK, open the app, grant notification access, then tap **Start / Refresh rear
controls**. In HyperOS app settings enable **Autostart** and set Battery saver to **No
restrictions**. Don't swipe the app from Recents — HyperOS treats that as a force-stop and blocks
listener rebinding.

## Variants

| Variant | Purpose |
|---|---|
| `allowlistedDebug` | Working rear-screen media proxy |
| `normalDebug` | Probe harness; not eligible for the native rear widget |

The `allowlisted` flavor overrides `applicationId` to a package name the firmware accepts. Android
cannot install the genuine owner of that package alongside this build. Use it only on a device you
own, for interoperability testing.

## Notification behavior

While active, YTRear snoozes YouTube Music's own `MediaStyle` notification. If both enter
SystemUI, HyperOS replaces YTRear's Dynamic Island entry during a skip and destroys its expanded
state. Playback is unaffected; YTRear's proxy notification becomes the visible player. To
restore the native notification, disable YTRear's notification access, then force-stop and
reopen YouTube Music.

## Docs

- [Research](docs/RESEARCH.md) — reverse-engineering findings and rejected approaches
- [Testing](docs/TESTING.md) — validation status and diagnostic commands
- [Evidence](docs/evidence/README.md) — device traces and screenshots

Tested against Android 16 / SDK 36, HyperOS `OS3.0.318.0.WBLCNXM` on a Xiaomi 17 Pro. The rear
widget path is firmware-specific and will not survive arbitrary HyperOS versions.
