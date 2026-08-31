# Courier — Minimal Video Downloader

A sleek, modern video and audio downloader for **Windows Desktop** and **Android**, powered by **Kotlin Multiplatform** and **Compose Multiplatform**.

Paste a link from **YouTube**, **TikTok**, **Instagram**, or **Facebook** to download high-definition video or extract audio tracks directly.

---

## ? Features

- **Cross-Platform**: Native look and feel on Windows Desktop and Android from a single shared Compose codebase.
- **Multiple Platform Support**:
  - **YouTube** (Videos, Shorts, Playlists, Audio)
  - **TikTok** (HD videos, Audio)
  - **Instagram** (Reels, Posts, Stories)
  - **Facebook** (Watch, Reels, Posts)
  - **General Web Video** (Twitter/X, Vimeo, Reddit, etc.)
- **Per-Download Quality Picker**: Select video resolution (1080p, 720p, 480p, Best) with format details before downloading.
- **Audio Only Mode**: One-tap toggle to extract MP3 / M4A audio tracks.
- **Clipboard Detection**: Non-intrusive banner prompting to download when a video URL is copied.
- **Android Share Target**: Tap *Share ? Courier* in YouTube/Instagram/TikTok to send links directly to the app.
- **Download Queue & History**: Real-time progress bar, speed meter (`MB/s`), ETA, open downloaded file/folder actions.
- **Cookie Import**: Select your desktop browser (Chrome, Edge, Firefox, Brave) in settings to access age-restricted or private media.
- **Auto Engine Setup**: Automatically downloads and self-updates the `yt-dlp` engine component.

---

## ?? Quick Launch (Binaries Ready in `release/`)

### Windows Desktop:
1. Double-click `Launch-Courier-Desktop.bat` in the root folder (or `release/Launch-Courier.bat`).
2. Alternatively, run:
   ```cmd
   java -jar release/Courier-Desktop-v1.0.0.jar
   ```

### Android:
1. Sideload `release/Courier-Android-v1.0.0.apk` to your phone and install it.
2. Or with a device connected via USB with USB Debugging enabled:
   - Double-click `Install-Courier-Android.bat`
   - Or run: `adb install -r release/Courier-Android-v1.0.0.apk`

---

## ??? Building from Source

### Prerequisites:
- JDK 17+
- Android SDK (for Android builds)

### Gradle Tasks:
```bash
# Run desktop app directly in development
./gradlew :desktopApp:run

# Package desktop standalone jar
./gradlew :desktopApp:packageUberJarForCurrentOS

# Build Android APK
./gradlew :androidApp:assembleDebug

# Run unit tests
./gradlew :shared:desktopTest
```
