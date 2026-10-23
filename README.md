# Reel Vault (Android)

A TikTok-style player for every video and photo on your phone.

- Swipe up/down through everything in your gallery (asks once for Photos and videos access).
- Tap to pause. Hold the left or right side for 2x. Speed button locks 1x / 1.5x / 2x.
- Hold the middle of a video (or a tile in Library) for: Delete, Create new folder, Share, Add to folder.
  Folders are real folders on the phone (moves the file).
- Paste or share a TikTok / X link: plays as a real video and **Save** puts it in Movies/Reel Vault.
  YouTube Shorts and Instagram Reels play in their own player.
- Shows up in the Share sheet and in "Open with" for videos and photos.

## Build
Android Studio: File > Open this folder, then Build > Build App Bundle(s) / APK(s) > Build APK(s).
Command line (needs Android SDK + JDK 17): `./gradlew assembleDebug` (Windows: `gradlew.bat assembleDebug`).
APK: `app/build/outputs/apk/debug/app-debug.apk`.
GitHub: push this folder to a repo; the Actions tab builds the APK automatically.
