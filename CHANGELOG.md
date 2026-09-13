# Changelog

## 1.3.2 - 2026-09-13

- Restores the previous player's media notification automatically when the user selects a
  different player.
- Android does not expose direct unsnoozing to ordinary notification listeners, so YTRear shortens
  the previous notification's existing snooze to 250 ms. Android then reposts it normally after
  the new target has already been selected.
- Persists pending restorations until the notification listener is connected, so switching still
  cleans up correctly across listener disconnects or process restarts.
- Verified on-device in both directions. Switching away from actively playing YouTube Music
  removed it from both Android snooze lists and immediately restored its native notification;
  switching back snoozed only YouTube Music again. The pending restoration marker cleared after
  each handoff. If the previous player is not running, its notification returns the next time that
  player posts one.
- Bumped the candidate to version 1.3.2 (`versionCode 15`).

## 1.3.1 - 2026-09-13

- Generalized the stable YouTube Music notification-ranking workaround to the player selected in
  the launch picker.
- YTRear now snoozes that player's original MediaStyle notification for 30 days and supplies its
  proxy notification in its place. This prevents the original card from retaking HyperOS's
  top-media position and collapsing the expanded Dynamic Island during Next or Previous.
- Applies the workaround to an already-active notification immediately when the user switches
  players, when the notification listener connects, and whenever the selected player posts a new
  media notification.
- Verified on-device with Poweramp: its MediaSession remains active, controls continue forwarding,
  its original notification is snoozed, and YTRear's proxy remains active. Spotify remains excluded
  because HyperOS supports it natively.
- Bumped the candidate to version 1.3.1 (`versionCode 14`). The tagged 1.2 stable baseline is
  unchanged.

### Uninstall and notification recovery

YTRear never modifies the selected player's application settings or data. Uninstalling YTRear
stops all future proxying and notification snoozing. Android may retain the currently snoozed media
notification after YTRear is removed; if the player's native notification does not return
immediately, force-stop that player, reopen it, and start playback. This clears the old notification
instance and restores its normal notification and Dynamic Island behavior.
