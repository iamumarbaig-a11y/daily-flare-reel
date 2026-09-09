# Daily Flare Reel

Reconstruction baseline for the Daily Flare Reel APK (`app-debug-18.apk`).

Package: `com.thedailyflare.reel`
Reference canvas: 1080×1920
Core workflow: background image + music + headlines → 15-second reel.

This repository is intentionally incremental: make one change, build it, verify the APK, then move to the next change.

The APK is compiled output, so the original Kotlin source cannot be recovered byte-for-byte. The editable baseline reconstructs the observable structure and named classes from the APK.

<!-- GitHub write-access test: 2026-09-09 -->
