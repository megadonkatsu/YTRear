# YTRear testing

## Version 1.3.1 multi-player candidate

Version 1.3.1 starts from the tagged 1.2 baseline and adds a persisted, searchable player picker.
The picker is shown whenever the main activity is launched and lists every enabled app with a
launcher activity. Only apps that publish a standard Android `MediaSession` can be proxied.

Before promoting 1.3.1:

- [ ] Confirm the picker opens on launch and can be searched by label or package name.
- [ ] Confirm Spotify is omitted because it is supported by the rear screen natively.
- [ ] Confirm cancelling keeps the previous selection and selecting an app starts or refreshes the
  proxy service.
- [ ] Confirm a selected player remains selected across process death and reboot.
- [ ] Confirm proxy notification taps open the selected player.
- [ ] Run play/pause, next, previous, metadata, artwork, screen-off, and lifecycle checks for each
  supported player.
- [ ] Confirm switching players clears the old session and binds only the newly selected package.
- [ ] Re-run the complete 1.2 YouTube Music regression below.

Every selected player receives the validated 30-day notification-snooze workaround. YTRear
replaces the selected app's original MediaStyle notification so it cannot retake HyperOS's
top-media rank and collapse the expanded Dynamic Island during a track change.

### Automated candidate check — 2026-09-13

- [x] Clean `assembleDebug` and `lintDebug` completed with zero lint errors.
- [x] Version 1.3.1 installed while retaining notification access and app data.
- [x] A normal launch displayed the searchable picker; repeated launch intents produced one dialog.
- [x] The picker explained that Spotify is omitted, and a previously stored Spotify target was
  rejected.
- [x] The selected Poweramp package persisted across force-stop/relaunch.
- [x] Poweramp and `YTRearProxy` sessions were simultaneously active while an unrelated YouTube
  Music session was also present, confirming exact-package selection.
- [x] Poweramp's MediaStyle notification was moved into both `Snoozed notifications` and
  `Pending snoozed notifications` for 30 days, while its underlying MediaSession remained active.
- [x] Targeted Play, Next, and Pause commands were forwarded through YTRear to
  `com.maxmpz.audioplayer`; the Poweramp and proxy states stayed synchronized.
- [x] The exact final APK kept notifications 76 and 78 active, kept Poweramp's original card out of
  the active list, and logged zero island-removal or `EXPANDED_TO_DELETED` events during Play/Next.
- [x] A 1.3 → exact archived 1.2 → 1.3 install round-trip succeeded while preserving the Poweramp
  selection and notification access.
- [ ] Expand the Dynamic Island and press Next with Poweramp selected; confirm it physically stays
  expanded. ADB input injection is denied by this firmware.
- [ ] Physically type into the search field and tap a result. Shell input injection is denied by
  this firmware, so this cannot be automated over adb.
- [ ] Repeat the rear-panel controls with display 0 off; physical rear input cannot be injected.

The installed candidate APK SHA-256 is
`145D1C1B1029C233F29B7AC995A4BDDADCB7786D7CCC426B5F2871092E6FD505`.

## Version 1.2 stable baseline

This is the validation record for the baseline build tagged `baseline-v1.2`.

## Baseline

The validated configuration is:

```text
Device       Xiaomi 17 Pro
OS           Android 16, HyperOS OS3.0.318.0.WBLCNXM
Package      com.luna.music
Version      1.2 (versionCode 12, targetSdk 36)
```

Confirm the APK installed on the phone matches your own build output by SHA-256 before running
these checks, and that notification access is granted with `RearControlService` in the
foreground.

## Confirmed behavior

- The native Xiaomi MAML card receives YTRear proxy controls while display 0 is off.
- Rear play/pause, next, and previous control YouTube Music.
- Version 0.9's 80 ms / 3.2-second rank lease keeps the rear card stationary during track changes.
- Version 1.0's saved trace shows the main-display artwork converging to the current track after
  the refresh and confirm posts.
- The version 1.0 trace contains no rear-widget removal, recreation, or notification-rate shedding.
- Version 1.1's installed proxy MediaSession activity and notification 78 content intent both
  resolve to `com.google.android.apps.youtube.music/.activities.MusicActivity`.
- The Dynamic Island player and proxy media notification were both physically confirmed to open
  YouTube Music instead of YTRear.
- Version 1.2 automatically snoozes only YouTube Music's MediaStyle notification for 30 days. A
  forced YouTube Music restart produced `MEDIA LISTENER: snoozed YouTube Music media notification
  for 30 days`; both the real and proxy MediaSessions remained `PLAYING`.
- A targeted command against the installed 1.2 `YTRearProxy` changed tracks and logged zero
  YouTube Music SystemUI media loads, zero YTRear island removals, and zero
  `EXPANDED_TO_DELETED` transitions.
- The same notification-snooze state was physically confirmed to keep the Dynamic Island expanded
  when Next was pressed.

These results were established across the 0.4, 0.9, 1.0, 1.1, and 1.2 device runs. The checks below
are the remaining regression and durability coverage for the exact currently installed 1.2 APK.

## Remaining checks

### 1. Version 1.2 physical regression

- [x] Tap the expanded Dynamic Island player and confirm YouTube Music opens instead of YTRear.
- [x] Tap the proxy media notification card and confirm YouTube Music opens instead of YTRear.
- [x] Expand the Dynamic Island, press Next, and confirm the island stays expanded.
- [ ] Open the main display's media card and press next.
- [ ] Confirm title and artist update immediately and artwork follows in about 1.5 seconds.
- [ ] Confirm the artwork remains on the new track rather than falling one track behind.
- [ ] During the same skips, confirm the rear card stays visible and does not slide away/reopen.
- [ ] Repeat with previous and with several consecutive track changes.

Expected log pair for each changed track:

```text
PROXY NOTIFICATION: artwork refreshed for '<new track>'
PROXY NOTIFICATION: artwork post confirmed for '<new track>'
```

During these checks, `cmd notification list` should contain YTRear notification 78 and no
YouTube Music notification 2. `dumpsys notification --noredact` should list notification 2 under
`Snoozed notifications` and `Pending snoozed notifications`.

### 2. All controls with display 0 off

- [ ] Start YouTube Music and ensure YTRear reports **Rear controls active**.
- [ ] Turn off display 0 and wait at least 30 seconds.
- [ ] Wake only the rear panel and test previous, play/pause, and next separately.
- [ ] Confirm each action changes playback and logs `PROXY CONTROL: sent ... to YouTube Music`.
- [ ] Confirm `dumpsys display` still reports display 0 as `OFF`.

Previous was proven after more than 70 seconds in the original screen-off run; play/pause and
next passed shorter runs. This repetition closes the symmetric matrix on version 1.2.

### 3. Automatic takeover after idle

Repeat this test at least twice:

1. Keep YTRear's foreground-service notification, but pause playback and dismiss the media card
   used in the original failure.
2. Wait at least five minutes without reopening YTRear.
3. Open only YouTube Music and start a track.
4. Confirm YTRear becomes top media within about one to two seconds without touching either
   notification.
5. Confirm the log ends with `proxy ranking lease complete; sessionOnly=true` and contains no
   notification enqueue-rate shedding.

### 4. Lifecycle and stress matrix

- [ ] Kill and restart YouTube Music while YTRear stays alive.
- [ ] Revoke and re-grant notification-listener access.
- [ ] Force-stop YTRear, relaunch it, and tap **Start / Refresh rear controls**.
- [ ] Reboot, start YouTube Music, and verify automatic listener reconnection.
- [ ] Pause for more than 60 minutes and verify the widget can be restored normally.
- [ ] Press next three times rapidly; confirm title, artwork, and playback converge on the final
  track with no enqueue-rate warning.
- [ ] Disable YTRear notification access, force-stop/reopen YouTube Music, and confirm its native
  media notification returns; then re-enable YTRear and confirm automatic snoozing resumes.

Do not disable YouTube Music notifications app-wide. Version 1.2 intentionally snoozes only its
active MediaStyle notification through the authorized notification listener. The verified device
run kept the underlying YouTube Music MediaSession and playback alive.

## Commands

Run in PowerShell on the Windows host:

```powershell
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
$DEVICE = (& $adb devices) [1].Split()[0]   # your adb serial

# Installed build
& $adb -s $DEVICE shell dumpsys package com.luna.music |
  Select-String -Pattern 'versionCode|versionName|targetSdk|lastUpdateTime'

# Proxy notification and MediaSession
& $adb -s $DEVICE shell dumpsys notification --noredact |
  Select-String -Pattern 'com.luna.music|android.mediaSession' -Context 3,12
& $adb -s $DEVICE shell dumpsys media_session |
  Select-String -Pattern 'YTRearProxy|com.luna.music' -Context 2,8

# Dynamic Island suppression state
& $adb -s $DEVICE shell cmd notification list
& $adb -s $DEVICE shell dumpsys notification --noredact |
  Select-String -Pattern 'Snoozed notifications|Pending snoozed|youtube.music' -Context 0,3

# Focused log
& $adb -s $DEVICE logcat -d -v threadtime |
  Select-String -Pattern 'PROXY SESSION|PROXY NOTIFICATION|PROXY CONTROL|proxy rank|topMediaDataChanged|unified.music|Removed current widget|Creating new music widget|Shedding'

# Rear capture: use the physical id, not logical display id 1.
# Read $REAR from your own `dumpsys display` output; it is vendor-assigned per unit.
& $adb -s $DEVICE shell screencap -p -d $REAR `
  /data/local/tmp/rear.png
& $adb -s $DEVICE pull /data/local/tmp/rear.png .

# Power/display state
& $adb -s $DEVICE shell dumpsys display
& $adb -s $DEVICE shell dumpsys power
```

An all-black rear PNG around 3.5 KB usually means the panel was asleep. A live capture is commonly
90–220 KB, so check display state and file size before treating a black capture as a render failure.

Device traces captured during these checks contain personal activity (running apps, wake/sleep
timing, and whatever media happens to be playing). Keep them local and redact them before sharing.
