# Muzi Music — Android APK project

This repository wraps the supplied Muzi music-player HTML app in a lightweight Android WebView app. GitHub Actions compiles the APK for you.

## Build an APK with GitHub (no Android Studio required)

1. Create a new GitHub repository.
2. Upload **all files and folders in this ZIP** to the repository (keep the `.github/workflows/build-apk.yml` path).
3. Open the repository's **Actions** tab.
4. Select **Build Muzi APK**, then choose **Run workflow** (or wait for the automatic build after pushing).
5. Open the completed workflow run and download the **Muzi-debug-apk** artifact.
6. Extract the downloaded artifact ZIP. It contains `app-debug.apk`. Transfer it to your Android phone and install it. If Android asks, allow installation from that source.

## Build locally

Install Android Studio / Android SDK and JDK 17, then run:

```bash
gradle :app:assembleDebug
```

APK output: `app/build/outputs/apk/debug/app-debug.apk`.

## Included app features

- Player screen, play/pause, previous/next, seek bar and media-session controls
- Library, sorting, favorites and song metadata/cover reading
- Search by song title, artist or album
- Dark, light, normal and purple themes
- Five-band equalizer and presets
- Select multiple audio files from Android's file picker
- Imported songs are copied into Muzi's private app storage and restored automatically after app restarts
- Android reads available title, artist, album, duration and embedded cover-art metadata during import
- Removing a song from the library also deletes its saved app copy
- Android Back returns from Library/Settings to the player; Back again exits

## Notes / limitations

- This is an **unsigned debug APK** suitable for testing and personal installation. A Play Store release needs a separately configured signing key.
- The original HTML references Google Fonts and jsmediatags from CDNs, so those optional resources require internet access. Playback and the app shell are local.
- Android's standard document picker supports selecting multiple audio files. The browser-only folder picker is not available in every Android WebView; use the file picker and select multiple tracks.
- On Android, selected audio files are copied into the app's private storage, so they remain available after closing/reopening Muzi. Uninstalling Muzi or clearing its app data deletes those copies.
- The browser-only HTML version still uses temporary object URLs for imported files; durable music storage is implemented in the Android APK wrapper.

## Icon, loading screen and background playback

- **Icon / loading screen:** the GitHub build downloads both images automatically and generates every launcher-icon size (including adaptive and round icons). If a download ever fails, drop your own pictures into the repo as `branding/icon.jpg` and `branding/splash.jpg` and rebuild. Without them the built-in purple placeholders are used.
- **Background playback:** music keeps playing with the screen off or the app minimized. A notification with Previous / Play-Pause / Next appears while the app is running. On Android 13+ allow notifications when asked. On some phones (Xiaomi, Huawei, Samsung, Oppo) also set Muzi's battery mode to *Unrestricted* so the system doesn't kill it.
- **Compatibility:** Android 5.0 (API 21) and newer. Back on the player screen minimizes the app while music is playing.
