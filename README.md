# Courier - Minimal Video Downloader

A sleek, modern video and audio downloader for **Windows Desktop** (x64) and **Android** (arm64-v8a, x86_64), powered by **Kotlin Multiplatform** and **Compose Multiplatform**.

Paste a link from **YouTube**, **TikTok**, **Instagram**, or **Facebook** to download high-definition video, image carousels, or extract audio tracks directly.

---

## Features

- **Cross-Platform**: Native look and feel on Windows Desktop and Android from a single shared Compose codebase.
- **Multiple Platform Support**:
  - **YouTube** (Videos, Shorts, Playlists, Audio)
  - **TikTok** (HD videos, Audio)
  - **Instagram** (Reels, Posts, Stories, Image/Video Carousels)
  - **Facebook** (Watch, Reels, Posts)
  - **General Web Video** (Twitter/X, Vimeo, Reddit, etc.)
- **Per-Download Quality Picker**: Select video resolution (1080p, 720p, 480p, Best) with format details and custom destination folder before downloading.
- **Editing-Optimized Output**: Default H.264/AAC profile ensures downloaded media imports immediately into Premiere, Resolve, and Final Cut without transcode errors.
- **Auto-Resume Interrupted Downloads**: Uncompleted video downloads automatically resume upon restarting the application.
- **Real-Time Speed & Metrics**: Instant, responsive speed meter (`MB/s`) and accurate ETA calculations.
- **Android Foreground Service & Notification**: Active downloads run reliably in the background with persistent status bar progress notifications.
- **Android Location Picker & Write Probes**: Choose from curated media roots (`Downloads`, `Movies`, `Pictures`, `DCIM`, `Music`) with subfolder validation and verified disk write probes.
- **Desktop Frosted Glass Backdrop**: Windows 11 rounded window framing, native DWM acrylic backdrop effects, and layered depth styling.
- **Clipboard Detection & Share Target**: Tap *Share → Courier* on mobile or paste detected links via the in-app banner.
- **Cookie Import**: Select your desktop browser (Chrome, Edge, Firefox, Brave) in settings to access age-restricted or private media.
- **Weekly Engine Auto-Updates**: Automatically checks for `yt-dlp` updates on launch every 7 days and tracks the last-checked timestamp in Settings.

---

## Quick Launch (Binaries Ready in `release/`)

### Windows Desktop (Windows 10/11 x64):
1. Double-click `Launch-Courier-Desktop.bat` in the project root folder.
2. Alternatively, run:
   ```cmd
   java -jar release/Courier-Desktop-latest.jar
   ```

### Android (API 24+):
1. Sideload `release/Courier-Android-latest.apk` to your phone or tablet and install it.
2. Or with a device connected via USB with USB Debugging enabled:
   - Double-click `Install-Courier-Android.bat`
   - Or run: `adb install -r release/Courier-Android-latest.apk`

---

## Building from Source

### Prerequisites:
- JDK 17+
- Android SDK (for Android builds)

### Gradle Tasks:
```bash
# Run desktop app directly in development
./gradlew :desktopApp:run

# Package desktop standalone jar & publish to release/
./gradlew :desktopApp:publishDesktopRelease

# Build & publish both Desktop and Android release artifacts
./gradlew publishRelease

# Run unit tests
./gradlew :shared:desktopTest
```