# YTRear research notes

This document preserves the durable reverse-engineering findings behind YTRear. It is historical
reference, not a current test handoff. For current setup and artifact details, use the
[project README](../README.md); for open validation work, use [Testing](TESTING.md).

Findings were produced on a physical Xiaomi 17 Pro unless stated otherwise.

## Goal and constraints

The goal is to control YouTube Music—play/pause, next, and previous—from the rear display without
waking the main display. The selected solution must work with a locked bootloader and without root,
Shizuku, or privileged-system installation.

The working route is an ordinary application installed as an allowlisted music package. It mirrors
YouTube Music into a local `MediaSession`, lets HyperOS build its native rear MAML music widget, and
forwards native widget callbacks to YouTube Music.

## Test device

```text
Model            25098PN5AC (Xiaomi 17 Pro, codename pandora)
Android          16 / SDK 36
HyperOS          OS3.0.318.0.WBLCNXM (China ROM)
```

| Property | Rear panel | Main panel |
|---|---|---|
| Logical display id | 1 | 0 |
| Physical display id | vendor-assigned (see below) | vendor-assigned (see below) |
| Resolution | 904 × 572 | 1220 × 2656 |
| Density | 450 dpi | 520 dpi |
| Display group | 1, independent power | 0 |
| `canHostTasks` | false | true |

The physical display ids are vendor-assigned 64-bit values that `screencap -d` requires and that
`adb shell dumpsys display` reports per unit. Read them off the device rather than copying them
from documentation.

The rear camera cutout occupies the left 296 pixels. The practical content area is approximately
608 × 572 pixels. A sleeping panel is often `OFF` with committed state `DOZE_SUSPEND`; an all-black
screenshot around 3.5 KB usually means the panel was asleep, not that rendering failed.

Although display 1 advertises `FLAG_PRESENTATION`, Xiaomi applies a separate ownership check. A
normal app still cannot place a `Presentation` or overlay window there.

## Decisive firmware findings

### Package identity is the rear-media gate

YouTube Music has a valid playing `MediaSession` and MediaStyle notification, but its package
`com.google.android.apps.youtube.music` is absent from HyperOS's compiled rear-music map. An
otherwise equivalent session owned by an eligible package is accepted.

The relevant music packages on this firmware are:

```text
com.xiaomi.music
com.miui.player
com.tencent.qqmusic
com.netease.cloudmusic
com.luna.music
com.kugou.android
cn.kuwo.player
com.spotify.music
```

At this gate HyperOS compares only the package name. It does not validate the app certificate,
installer, UID lineage, system status, or privileged permissions. Building YTRear with
`applicationId = "com.luna.music"` passed the gate as an ordinary user-installed app.

This creates a namespace collision: YTRear and the genuine Luna Music app cannot coexist.

### Native media path

SystemUI selects its top media item, packages the media token, metadata, state, artwork, and click
intent, then calls transaction 6 on Xiaomi's `ISubScreen` service:

```text
topMediaDataChanged(Bundle mediaData)
```

`com.xiaomi.subscreencenter` checks that the active package is in its music map and that the global
music switch is enabled. It then creates `unified.music:music_media`. The MAML controller also
filters active sessions by the same package list, so an allowlisted proxy session is required;
keeping an unrelated eligible dummy session alive does not unlock YouTube Music directly.

The successful screen-off sequence was, in relative time:

```text
T+0        main display group 0 went to sleep
T+71 s     only rear display group 1 woke
T+72 s     PROXY CONTROL: sent previous to YouTube Music
T+73 s     PROXY CONTROL: sent previous to YouTube Music
```

Throughout that run the display dump reported display 0 as `OFF`, confirming the main panel never
woke.

### Focus-notification path

The earlier custom `RemoteViews` experiment established a separate five-hop path:

```text
app notification
  -> NotificationManagerService
  -> SystemUI FocusCoordinator
  -> SubScreenNotificationController
  -> ISubScreen binder
  -> com.xiaomi.subscreencenter
```

A focus notification qualifies when either `miui.focus.isFocus=true` or `miui.focus.rv` contains a
`RemoteViews`. SystemUI forwards `miui.*` extras. The rear host then checks package eligibility
before resolving the declared business. Adding `"business":"music"` cannot make an unknown package
eligible.

The allowlisted YTRear build successfully rendered a custom rear card, proving that package-name
spoofing also works on this path. Its `PendingIntent` buttons work while the main display is awake,
but HyperOS suppresses them when the main display sleeps. This is why the product uses the native
MAML media path instead.

### Track-transition ranking

During next/previous, YouTube Music can briefly become SystemUI's top media item. Xiaomi's rear host
delays removal of an unsupported player by 150 ms only when top-media callbacks remain less than
200 ms apart. A fast proxy recovery alone is insufficient if the previous callback is too old.

YTRear therefore alternates an unused, non-rendered `MediaSession` action bit:

- play/pause uses a short 45 ms pulse sequence;
- next/previous and real metadata changes start an 80 ms, 3.2-second lease; and
- the proxy stays logically playing through transient paused callbacks during a track transition.

Version 0.9 device traces contained no widget removal, recreation, forced popup, or slide event,
and a physical check confirmed that the rear card remains stationary.

### Main-display artwork

SystemUI binds title and artist live from the session, but rebuilds card artwork only when the
MediaStyle notification is posted. Ranking pulses can replay SystemUI's previous card snapshot, so
a single artwork repost left the front card exactly one track behind.

Version 1.0 posts the updated card and one confirmation post 600 ms later. It keeps notification
78's original `when` value to prevent shade re-sorting. In the verified run, new artwork reaches the
front card in about 1.5 seconds and subsequent ranking pulses replay the current track.

### Dynamic Island top-media handoff

A fresh physical Next trace established why the expanded Dynamic Island collapsed. YouTube Music
published its new playing state, HyperOS immediately selected notification key
`0|com.google.android.apps.youtube.music|2|null|<uid>` as top media, and
`MiuiIslandMediaControllerImpl` synchronously removed YTRear key
`0|com.luna.music|78|null|<uid>`. YTRear reclaimed top rank about 40 ms later, but the island's
expanded state had already been destroyed.

Firmware inspection showed that equivalent playing/local/active sessions are finally ordered by
their latest SystemUI update time. A faster proxy rank pulse therefore cannot close this race: the
island removal occurs synchronously during the competing YouTube Music update.

The successful route is to keep YouTube Music's MediaSession active while snoozing only its
MediaStyle notification. Contrary to the earlier cancellation experiment, a notification-manager
snooze did not tear down playback. YouTube Music and YTRear both remained `PLAYING`, subsequent
track changes did not repost the snoozed notification into SystemUI, and a physical check
confirmed that the expanded island stayed open.

Version 1.2 performs that targeted snooze from `MediaNotificationListener` for 30 days whenever
the notification becomes active. Its installed-build run recorded a successful proxy Next with
zero YouTube Music SystemUI media loads, zero YTRear island removals, and zero
`EXPANDED_TO_DELETED` transitions. Package-level app-op changes, UID-level app-op changes, runtime
permission revocation, and adb input injection were not viable on this HyperOS build.

## Rejected and fallback approaches

| Approach | Result | Durable conclusion |
|---|---|---|
| `Presentation` on display 1 | Blocked | `TYPE_PRESENTATION` is denied despite `FLAG_PRESENTATION` |
| `TYPE_APPLICATION_OVERLAY` on display 1 | Blocked | Same code works on display 0; rear display requires `INTERNAL_SYSTEM_WINDOW` |
| Custom focus `RemoteViews` | Awake-only | It renders, but rear-doze policy suppresses embedded `PendingIntent` delivery |
| App-owned rear screen wake lock | Failed after ~7 s | HyperOS disables screen locks from non-visible, non-whitelisted app UIDs |
| Host-owned `keepScreenOn` in `RemoteViews` | Failed | Lock remains enabled, but the rear still enters doze and suppresses input |
| `force_non_aod_state` extra | Unreachable | Host reads an unprefixed private key; SystemUI forwards only `miui.*` keys |
| Direct `ISubScreen` binder | Unreachable | Service binding requires Xiaomi's signature permission |
| Eligible dummy media session | Failed | Subscreen MAML controller still filters the selected session by package |
| `PinReceiveActivity` | Display-only fallback | Exported ACTION_SEND supports text/image but has no callbacks or actions |
| Cancel YouTube Music notification | Harmful | Direct cancellation can tear down or destabilize the player's foreground-service path |
| Snooze only YouTube Music MediaStyle notification | Working | Playback survives; the competing SystemUI media entry stays absent while YTRear replaces it |
| Change notification app-op over adb | Ineffective | HyperOS retained/reported the package notification op as allowed, and UID mode did not block the media post |

The direct-window, wake-lock, and custom `RemoteViews` input routes are conclusive for this
firmware and should not be resumed unless the firmware or product constraints change.

## Version history

| Version | Outcome |
|---|---|
| 0.2 | Rear-scoped app wake lock woke the correct panel but HyperOS disabled it after ~7 seconds |
| 0.3 | Host `keepScreenOn` lock stayed enabled but did not restore rear-doze input |
| 0.4 | Allowlisted proxy `MediaSession` and native MAML widget passed screen-off control |
| 0.5 | Notification flooding fixed; a transient paused callback could still cancel takeover retry |
| 0.6 | Retry race fixed; first no-touch automatic takeover passed |
| 0.7 | Coalesced promotions and guarded skip state; rear card still visibly slid |
| 0.8 | Session-only rank defense fixed play/pause slide but not next/previous |
| 0.9 | Full transition lease passed rear controls and stationary-card physical test |
| 1.0 | Front-card artwork refresh added; device trace passed, Dynamic Island limitation documented |
| 1.1 | Dynamic Island and proxy-notification taps now open YouTube Music instead of YTRear |
| 1.2 | Targeted YouTube Music media-notification snooze keeps the expanded Dynamic Island open on skip |

Old APK sizes, hashes, transient installation state, and superseded handoff instructions were
removed from the active documentation. The raw device traces behind these findings were captured
on a personal device and are not published; the durable conclusions are recorded above.

## Active implementation map

| File | Role |
|---|---|
| `MediaHub.kt` | Selects YouTube Music, mirrors state, and sends transport commands |
| `MediaNotificationListener.kt` | Maintains session access and snoozes YouTube Music's competing MediaStyle notification |
| `NativeMediaProxy.kt` | Owns the allowlisted session, media notification, artwork refresh, and rank pulses |
| `RearController.kt` | Debounces state and guards ranking/track transitions |
| `RearControlService.kt` | Keeps the proxy controller alive in a foreground service |
| `MainActivity.kt` | Setup/status UI plus collapsed historical diagnostics |
| `RearNotification.kt` | Historical custom `RemoteViews` fallback; awake-screen only |
| `Displays.kt`, `RearWindow.kt`, `OverlayService.kt` | Historical display/window probes |

The installed package is `com.luna.music`, but the source namespace remains
`dev.ytrear.app`. The explicit activity component is therefore:

```text
com.luna.music/dev.ytrear.app.MainActivity
```

## Practical gotchas

- Use PowerShell for adb on Windows. Git Bash may rewrite `/data/...` and `package:...` arguments.
- A non-interactive shell may find Java 8 first; point `JAVA_HOME` at Android Studio's JBR.
- `local.properties` must keep the escaped drive colon: `sdk.dir=C\:/Users/...`.
- Do not use unqualified AppCompat-inflatable widgets in a rear `RemoteViews`. Xiaomi's host
  replaced `<ImageButton>` with AppCompat and crashed on `setImageResource`; fully qualified
  `android.widget.*` tags avoid that substitution.
- A visible rear card is not proof that it is current. Verify the installed version, perform a
  real playback change, and inspect the callback log.
- Rear-panel wake and button delivery are distinct. A touch can wake `DOZE_SUSPEND` without
  dispatching a custom `PendingIntent`.
- `WakeLock.isHeld` is not authoritative on HyperOS; `dumpsys power` can show the held lock marked
  `DISABLED`.
- Do not swipe YTRear from Recents during normal use. HyperOS treats it as a force-stop and may
  reject notification-listener rebinding.
- Physical input cannot be automated on this phone because adb input injection lacks
  `INJECT_EVENTS`; final rear-panel checks require a person.
- Version 1.2 intentionally replaces YouTube Music's visible media notification with YTRear's.
  To restore the native notification, disable YTRear notification access, then force-stop and
  reopen YouTube Music.

