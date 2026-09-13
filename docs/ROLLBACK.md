# Version and rollback

## Source

The untouched 1.2 source is pinned at tag `baseline-v1.2` and commit
`9044cf025908a08b81141c60fda871e14b20fec4`. The 1.3 work lives on branch
`feature/player-picker-v1.3`.

```powershell
# Stable 1.2 source
git switch main

# Multi-player 1.3 source
git switch feature/player-picker-v1.3
```

## Device APK

The exact APK originally pulled from the test phone is:

```text
releases\YTRear-1.2-installed-baseline.apk
SHA-256 86AE87EFB6C9BDE18F144238AACA59B7D2701B5E5A48E2A873DAB01E6E669E03
```

The tested 1.3 candidate is:

```text
releases\YTRear-1.3-player-picker-candidate.apk
SHA-256 2A113A0A4D2EFAD65CECF251FE10ED185DF3B57932DCAB5224731C457D960BEA
```

With `$adb` and `$DEVICE` set as in [Testing](TESTING.md):

```powershell
# Roll back while preserving app data and permissions.
& $adb -s $DEVICE install -r -d releases\YTRear-1.2-installed-baseline.apk

# Return to the candidate.
& $adb -s $DEVICE install -r releases\YTRear-1.3-player-picker-candidate.apk
```

Both directions were successfully exercised on the test phone. Version 1.2 ignores the player
selection preference; reinstalling 1.3 restores the saved selection.
