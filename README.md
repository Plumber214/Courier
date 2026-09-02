# Courier - Minimal Video Downloader & Device Link

A sleek, modern video, photo, and audio downloader with local LAN device sync for **Windows Desktop** (x64) and **Android** (arm64-v8a, x86_64), powered by **Kotlin Multiplatform** and **Compose Multiplatform**.

Paste a link from **YouTube**, **TikTok**, **Instagram**, or **Facebook** to download high-definition video, image carousels, or extract audio tracks directly — or send downloads across your local network directly to your desktop.

---

## What's New in v1.5.0

- **Device Link (Local LAN Sync)**:
  - **Zero-Config Discovery**: Automatic local network discovery over UDP broadcast (port 1816) with JmDNS (`_courier._tcp.local.`) fallback and manual IP connection.
  - **Cryptographic Trust & Pairing**: End-to-end TLS encryption with mutual certificate pinning and an 8-character verification code for instant, secure pairing.
  - **Remote Downloads**: Send video and audio download requests from your phone straight to your desktop PC, with real-time status, progress, speed, and ETA streaming back to your phone.
  - **Clipboard Sync**: Share copied links and text across paired devices with deduplication loops prevention and per-device toggle controls.
  - **Close-to-Tray on Desktop**: Desktop app runs quietly in the system tray so Device Link remains available in the background.
- **Palette Retint**:
  - Replaced the purple accent palette with a sophisticated steel blue-gray (`#7D9BB8`) and slate (`#8FA8B8`) over a clean neutral dark foundation (`#0E1116`), creating a refined studio workstation aesthetic.
- **Adaptive 3-Tab Navigation**:
  - Modern `NavigationBar` on Android (bottom) and `NavigationRail` on Desktop (left) with preserved scroll and composition states across tab switches.

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
- **Android Foreground Service & Notification**: Active downloads and connected device sessions run reliably with persistent notifications.
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

# Run all unit tests
./gradlew :shared:desktopTest

# Package both desktop & android release artifacts to release/
./gradlew publishRelease
```