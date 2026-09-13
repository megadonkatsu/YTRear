# Version and rollback

## Source

The untouched 1.2 source is pinned at tag `baseline-v1.2` and commit
`9044cf025908a08b81141c60fda871e14b20fec4`. The multi-player work lives on branch
`feature/player-picker-v1.3`.

```powershell
# Stable 1.2 source
git switch main

# Multi-player candidate source
git switch feature/player-picker-v1.3
```

## Device APK

The exact APK originally pulled from the test phone is:

```text
releases\YTRear-1.2-installed-baseline.apk
SHA-256 86AE87EFB6C9BDE18F144238AACA59B7D2701B5E5A48E2A873DAB01E6E669E03
```

The current 1.3.1 candidate is:

```text
releases\YTRear-1.3.1-player-picker-candidate.apk
SHA-256 145D1C1B1029C233F29B7AC995A4BDDADCB7786D7CCC426B5F2871092E6FD505
```

With `$adb` and `$DEVICE` set as in [Testing](TESTING.md):

```powershell
# Roll back while preserving app data and permissions.
& $adb -s $DEVICE install -r -d releases\YTRear-1.2-installed-baseline.apk

# Return to the candidate.
& $adb -s $DEVICE install -r releases\YTRear-1.3.1-player-picker-candidate.apk
```

The downgrade/upgrade route was successfully exercised on the test phone with the 1.3 candidate.
Version 1.2 ignores the player-selection preference; reinstalling the multi-player candidate
restores the saved selection.
