# Plan 002 — Release Pipeline, Editor-Compatible Output, Recents UX

**Status:** Ready to build
**Written:** 2026-09-01
**Supersedes nothing.** [`PHOTO-SUPPORT-PLAN.md`](PHOTO-SUPPORT-PLAN.md) is **code-complete** — see §0.1 for why it appeared not to be.

**Three items, in priority order:**
1. **Phase 0** — the release pipeline is broken; the user has been testing a stale build. *This is why "nothing changed."*
2. **Phase 1** — downloaded videos are AV1/Opus and cannot be imported into Premiere. *This defeats the app's core purpose.*
3. **Phase 2** — Recents card: remove the play button, tap the thumbnail to preview, add a copy-link button.

Everything in Part 0 was empirically verified on 2026-09-01. Do not re-investigate it.

---

## Part 0 — Root cause findings

### 0.1 Photos ARE implemented. The shipped jar is stale.

All Plan-001 files exist on disk and compile:

```
PRESENT  shared/src/commonMain/kotlin/courier/engine/YtDlpJsonParser.kt      (7,781 bytes)
PRESENT  shared/src/commonMain/kotlin/courier/engine/ExtractionError.kt      (1,506 bytes)
PRESENT  shared/src/commonMain/kotlin/courier/engine/ShareLinkResolver.kt    (2,081 bytes)
PRESENT  shared/src/commonMain/kotlin/courier/ui/components/PhotoPickerDialog.kt (16,641 bytes)
```

The engine wiring is correct — `--ignore-no-formats-error` at `DownloadEngineDesktop.kt:39`, the image branch at `:142-148`, the gallery branch at `:149-158`, and the `Writing video thumbnail` parser at `:204`.

**But the jar being run does not contain any of it.** Class-level inspection of both jars:

| Jar | Modified | Size | New Plan-001 classes |
|---|---|---|---|
| `Courier-v1.3.0.jar` (repo root **and** `release/`) | **9:17 AM** | 89,137,151 | **0** |
| `desktopApp/build/compose/jars/Courier-windows-x64-1.3.0.jar` | **9:54 AM** | 89,194,721 | **13** |

The fresh build contains `courier/engine/YtDlpJsonParser.class`, `ExtractionError.class`, `ShareLinkResolver.class`, and the `PhotoPickerDialog` composable singletons. The shipped jar contains none of them — it predates the build by 37 minutes.

**Compounding this, the launcher points at a file that does not exist:**

```bat
:: Launch-Courier-Desktop.bat
start /b javaw -jar "%~dp0release\Courier-Desktop-v1.0.0.jar"
```

`release/` contains `Courier-Desktop-v1.1.0.jar`, `-v1.2.0.jar`, `-latest.jar`, and `Courier-v1.3.0.jar`. **There is no `Courier-Desktop-v1.0.0.jar`.** This launcher has been broken across several releases.

**Conclusion: no code fix is needed for photos.** The gap is that `gradlew build` produces an artifact in `desktopApp/build/compose/jars/` and *nothing copies it to `release/`*. Phase 0 fixes the pipeline; photo functionality must then be re-verified against a correctly packaged build before any further diagnosis.

### 0.2 Premiere: the app downloads AV1 video and Opus audio

The format strings at [`DownloadEngineDesktop.kt:161-169`](../shared/src/desktopMain/kotlin/courier/engine/DownloadEngineDesktop.kt#L161-L169) filter on **container**, not **codec**. `ext=mp4` does not mean H.264 — YouTube serves AV1 and VP9 inside MP4 containers.

What the app's own format strings actually select, verified with `yt-dlp -s --print`:

| App setting | Format string | **Actually selected** |
|---|---|---|
| `best` | `bestvideo[ext=mp4]+bestaudio[ext=m4a]/…` | `401+140` — **`av01.0.13M.08`** + `mp4a.40.2`, 3840×2160 |
| `1080p` | `bestvideo[height<=1080][ext=mp4]+…` | `399+140` — **`av01.0.09M.08`** + `mp4a.40.2` |

**The fallback branches are worse.** The second alternative in every chain is unconstrained:

```
$ yt-dlp -f "bestvideo+bestaudio" --print "%(format_id)s | vcodec=%(vcodec)s | acodec=%(acodec)s"
401+251 | vcodec=av01.0.13M.08 | acodec=opus
```

**AV1 video + Opus audio**, then muxed into MP4 by `--merge-output-format mp4`. That file is unreadable by Premiere on both streams.

Adobe's own community forums carry threads titled *"file uses unsupported video compression type av01"* and *"unsupported compression type VP09"*. Premiere Pro has no native AV1 or VP9 decode in the versions most users run, and cannot read Opus in an MP4 container at all.

**The fix works.** Forcing the codec:

```
$ yt-dlp -f "bestvideo[vcodec^=avc1][ext=mp4]+bestaudio[acodec^=mp4a][ext=m4a]/…" --print …
299+140 | vcodec=avc1.64002a | acodec=mp4a.40.2 | 1920x1080
```

H.264 High profile + AAC-LC. Premiere-native.

### 0.3 The unavoidable tradeoff: H.264 caps at 1080p

Full format inventory for the test video (`yt-dlp -F`), grouped by codec:

| Resolution | H.264 (`avc1`) | VP9 (`vp09`) | AV1 (`av01`) |
|---|---|---|---|
| 720×60 | ✅ 298 | ✅ 612 | ✅ 398 |
| **1080×60** | **✅ 299 (max avc1)** | ✅ 617 | ✅ 399 |
| 1440×60 | ❌ **none** | ✅ 623 | ✅ 400 |
| 2160×60 | ❌ **none** | ✅ 628 | ✅ 401 |

**YouTube publishes no H.264 above 1080p.** Any 1440p or 4K download is necessarily VP9 or AV1, and therefore necessarily needs transcoding before Premiere will touch it. This is a platform constraint, not something the app can engineer around — it can only choose how to handle it.

### 0.4 Transcode capability is already present

The bundled ffmpeg (`N-126374-g089a48eb36-20260831`, 145 MB, already downloaded by `BinaryManagerDesktop`) has every encoder needed:

```
V....D libx264      H.264 / AVC
V....D libx265      H.265 / HEVC
VF...D prores       Apple ProRes
VFS..D dnxhd        VC3/DNxHD
A....D aac          AAC
```

No new binaries required for Phase 1. Android already ships the `youtubedl-android-ffmpeg` aar.

---

## Phase 0 — Fix the release pipeline

**Goal:** The artifact the user launches is always the artifact that was just built.
**Do this first. Nothing else can be validated until it is done.**

### 0.1 Add a packaging Gradle task

In [`desktopApp/build.gradle.kts`](../desktopApp/build.gradle.kts), add a task that runs after `packageUberJarForCurrentOS` and copies the result into `release/` with a stable, version-stamped name.

```kotlin
val releaseVersion = /* read from courier.util.AppVersion or gradle.properties — single source of truth */

tasks.register<Copy>("publishDesktopRelease") {
    dependsOn("packageUberJarForCurrentOS")
    from(layout.buildDirectory.dir("compose/jars")) {
        include("*.jar")
        rename { "Courier-Desktop-v$releaseVersion.jar" }
    }
    into(rootProject.layout.projectDirectory.dir("release"))
    doLast {
        // Also refresh the stable "latest" alias the launcher uses.
        copy {
            from(rootProject.layout.projectDirectory.file("release/Courier-Desktop-v$releaseVersion.jar"))
            into(rootProject.layout.projectDirectory.dir("release"))
            rename { "Courier-Desktop-latest.jar" }
        }
    }
}
```

Do the equivalent for the Android APK → `release/Courier-Android-latest.apk`.

### 0.2 Fix the launcher

[`Launch-Courier-Desktop.bat`](../Launch-Courier-Desktop.bat) must point at the **stable alias**, never a version-pinned filename — that is exactly how it rotted:

```bat
@echo off
title Courier
if not exist "%~dp0release\Courier-Desktop-latest.jar" (
    echo ERROR: No build found. Run: gradlew publishDesktopRelease
    pause
    exit /b 1
)
echo Starting Courier...
start /b javaw -jar "%~dp0release\Courier-Desktop-latest.jar"
```

The existence check is the important part — a missing jar must fail loudly instead of silently doing nothing, which is what has been happening.

### 0.3 Make the build stamp verifiable at runtime

Add build metadata to [`AppVersion.kt`](../shared/src/commonMain/kotlin/courier/util/AppVersion.kt) — version, build number, and a build timestamp injected at compile time via `buildConfigField` or a generated source. Surface it in Settings:

```
Courier v1.3.0 (build 16) — built 2026-09-01 09:54
```

This makes "am I running the new code?" a two-second check instead of a class-level jar inspection. Given that this exact confusion cost a full debugging cycle, it earns its place.

### 0.4 Clean up stale artifacts

- Delete `Courier-v1.3.0.jar` from the **repo root** — build output does not belong there
- Add to `.gitignore`: `/release/*.jar`, `/release/*.apk`, `/*.jar`
- Keep `release/` as a directory (add a `.gitkeep`), but stop tracking binaries in git

### Acceptance criteria

- [ ] `gradlew publishDesktopRelease` produces `release/Courier-Desktop-latest.jar` containing `courier/engine/YtDlpJsonParser.class`.
- [ ] `Launch-Courier-Desktop.bat` starts that jar, and errors visibly if it is absent.
- [ ] Settings displays a build timestamp matching the last build.
- [ ] **Re-verify photos against this build** — Instagram single photo and carousel, per Plan-001's acceptance criteria. Only if they still fail does further photo diagnosis begin.

---

## Phase 1 — Editor-compatible output

**Goal:** Downloads import into Premiere Pro (and Resolve / Final Cut) without manual conversion.

This is the app's core value proposition. Treat correctness here as non-negotiable.

### 1.1 Introduce an output profile setting

Add to [`AppSettings.kt`](../shared/src/commonMain/kotlin/courier/model/AppSettings.kt):

```kotlin
enum class OutputProfile {
    /** H.264 + AAC, no re-encode. Always imports. Capped ~1080p on YouTube. */
    EDITING_NATIVE,
    /** Highest resolution available (AV1/VP9). Will NOT import into Premiere. */
    MAX_QUALITY,
    /** Highest resolution, then transcode to an edit-ready codec. Slow. */
    EDITING_TRANSCODE
}
```

**`EDITING_NATIVE` is the default**, including for existing users — migrate any absent value to it. The app's purpose is feeding an edit timeline; a file that will not import is not a successful download.

### 1.2 Rewrite format selection

Replace [`DownloadEngineDesktop.kt:161-169`](../shared/src/desktopMain/kotlin/courier/engine/DownloadEngineDesktop.kt#L161-L169). Build the format string from the profile rather than hardcoding one chain.

**`EDITING_NATIVE`** — every alternative in the chain must stay codec-constrained. This is where the current code fails: its fallbacks silently drop the constraint.

```kotlin
// height = 1080 / 720 / 480 / 360, or omit the [height<=N] clause for "best"
"bestvideo[vcodec^=avc1][height<=$h][ext=mp4]+bestaudio[acodec^=mp4a][ext=m4a]" +
"/bestvideo[vcodec^=avc1][height<=$h]+bestaudio[acodec^=mp4a]" +
"/best[vcodec^=avc1][acodec^=mp4a][height<=$h]" +
"/best[ext=mp4][height<=$h]"
```

**`MAX_QUALITY`** — keep current reach but stop muxing Opus into MP4:

```kotlin
"bestvideo[height<=$h]+bestaudio[acodec^=mp4a]" +
"/bestvideo[height<=$h]+bestaudio" +
"/best[height<=$h]"
```

**`EDITING_TRANSCODE`** — select as `MAX_QUALITY`, then add post-processing (§1.4).

> Never emit an unconstrained `bestvideo+bestaudio` alternative while `--merge-output-format mp4` is active. That combination produced `av01 + opus` in an `.mp4` — a file no editor can open.

### 1.3 Plumb codecs through `VideoFormat`

`VideoFormat` already has `vcodec` and `acodec` fields ([`VideoFormat.kt:13-14`](../shared/src/commonMain/kotlin/courier/model/VideoFormat.kt#L13-L14)) that `YtDlpJsonParser.buildFormats` never populates. Two bugs follow from this:

1. The resolution ladder at [`YtDlpJsonParser.kt:161-170`](../shared/src/commonMain/kotlin/courier/engine/YtDlpJsonParser.kt#L161-L170) is built from **whatever format ID happens to win per height** — frequently the AV1 one. Picking "1080p" from the list can hand back format `399` (AV1).
2. `formatId = "$fmtId+bestaudio/best"` at [line 163](../shared/src/commonMain/kotlin/courier/engine/YtDlpJsonParser.kt#L163) uses unconstrained `bestaudio` — Opus again.

Fix in `buildFormats`:

- Read and store `vcodec` / `acodec` on every `VideoFormat`
- Change the audio suffix to `"+bestaudio[acodec^=mp4a]/bestaudio/best"`
- When de-duplicating by height, **prefer `avc1`** over `vp09`/`av01` rather than taking the first seen
- Keep non-avc1 entries, tagged, so `MAX_QUALITY` users still see 1440p/4K

Add a derived helper for the UI:

```kotlin
val isEditorFriendly: Boolean
    get() = vcodec?.startsWith("avc1") == true || vcodec?.startsWith("h264") == true
```

### 1.4 Transcode path for above 1080p

Only reachable via `EDITING_TRANSCODE`. Sub-option for the target codec:

| Target | yt-dlp arguments | Trade-off |
|---|---|---|
| **H.264** (default) | `--recode-video mp4` + `--postprocessor-args "VideoConvertor:-c:v libx264 -preset medium -crf 18 -pix_fmt yuv420p -c:a aac -b:a 192k -movflags +faststart -fps_mode cfr"` | Lossy re-encode, moderate size, slow |
| **ProRes 422 HQ** | `--recode-video mov` + `VideoConvertor:-c:v prores_ks -profile:v 3 -pix_fmt yuv422p10le -c:a pcm_s16le` | Very large, edit-native, best scrubbing |
| **DNxHR HQ** | `--recode-video mov` + `VideoConvertor:-c:v dnxhd -profile:v dnxhr_hq -pix_fmt yuv422p -c:a pcm_s16le` | Very large, Avid/Premiere-native |

Implementation notes:

- `-fps_mode cfr` matters. Some source is variable-frame-rate, which Premiere handles badly (progressive audio drift). Forcing constant frame rate avoids it.
- `-pix_fmt yuv420p` for H.264 guards against 10-bit output that some players and older Premiere builds reject.
- `-movflags +faststart` is hygiene, not strictly required for local import.
- **Only transcode when needed.** If the selected stream is already `avc1`, skip post-processing entirely — never re-encode H.264 into H.264. Decide from the `VideoFormat.vcodec` now available from §1.3; do not add an ffprobe round-trip.
- Transcoding is long-running with no yt-dlp download progress. Reuse the `MERGING` status already in [`DownloadStatus`](../shared/src/commonMain/kotlin/courier/model/DownloadStatus.kt) and label it "Converting for editing…".

### 1.5 Make the tradeoff visible in the UI

The 1080p ceiling will otherwise read as a regression ("it used to give me 4K").

- In `QualityPickerDialog`, badge each resolution: **"Editor-ready"** for `avc1`, **"Needs conversion"** for `av01`/`vp09`.
- Under `EDITING_NATIVE`, still *list* 1440p/4K but show them disabled with an inline note: *"Only available as AV1 — switch output profile to download."* Hiding them silently invites the same confusion in reverse.
- In Settings, describe the profiles in outcome terms, not codec terms:
  - *"Editing (recommended) — always imports into Premiere, Resolve and Final Cut. Up to 1080p on YouTube."*
  - *"Maximum quality — up to 4K. Will not import into most editors without conversion."*
  - *"Editing, any resolution — up to 4K, converted for editing. Much slower, larger files."*

### 1.6 Android parity

Mirror §1.2 and §1.3 in [`DownloadEngineAndroid.kt:268-277`](../shared/src/androidMain/kotlin/courier/engine/DownloadEngineAndroid.kt#L268-L277) via `request.addOption("-f", …)`.

`EDITING_NATIVE` and `MAX_QUALITY` work identically. **`EDITING_TRANSCODE` should be hidden or gated behind an explicit warning on Android** — a 4K H.264 re-encode on a phone is thermally throttled and can take longer than the video's runtime. Do not offer ProRes/DNxHR on mobile at all; the file sizes are impractical for device storage.

### 1.7 Optional — repair existing downloads

Users have a library of unimportable files. A "Convert for editing" action on a completed `DownloadItem` that runs the §1.4 ffmpeg command on the existing `outputPath` is a small addition with high value. Scope it as optional; ship Phases 0-2 first.

### Acceptance criteria

- [ ] Default profile downloads YouTube 1080p as `avc1` + `mp4a` — verify with `ffprobe`, not by eye.
- [ ] The downloaded file imports into Premiere Pro with no error. **This is the only acceptance test that actually matters — perform it manually.**
- [ ] No format string can produce Opus audio inside an MP4 container.
- [ ] Selecting a specific resolution from the picker respects the codec constraint.
- [ ] `MAX_QUALITY` still reaches 2160p.
- [ ] `EDITING_TRANSCODE` at 4K produces an importable H.264 file.
- [ ] Already-H.264 downloads are never re-encoded.
- [ ] Audio-only (MP3) downloads are unchanged.

---

## Phase 2 — Recents card UX

**Goal:** Tap the thumbnail to view. The button beside it copies the source link.

Target: [`DownloadItemCard.kt`](../shared/src/commonMain/kotlin/courier/ui/components/DownloadItemCard.kt).

### 2.1 Signature change

```kotlin
@Composable
fun DownloadItemCard(
    item: DownloadItem,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
    onRemove: () -> Unit,
    onPreviewMedia: () -> Unit,   // renamed from onOpenFile
    onCopyLink: () -> Unit,       // new
    onOpenFolder: () -> Unit,
    modifier: Modifier = Modifier
)
```

Update the call site at [`HomeScreen.kt:499-513`](../shared/src/commonMain/kotlin/courier/ui/screens/HomeScreen.kt#L499-L513):

```kotlin
onPreviewMedia = { homeViewModel.openMediaPreview(item) },   // was onOpenFile
onCopyLink = { getPlatformActions().setClipboardText(item.url) },
```

`setClipboardText` already exists on [`PlatformActions`](../shared/src/commonMain/kotlin/courier/platform/PlatformActions.kt) and is implemented on both platforms — no new platform code.

### 2.2 Make the thumbnail the preview affordance

The thumbnail `Box` at [`DownloadItemCard.kt:97-103`](../shared/src/commonMain/kotlin/courier/ui/components/DownloadItemCard.kt#L97-L103) gains:

```kotlin
.clickable(
    enabled = item.status == DownloadStatus.COMPLETED,
    role = Role.Button,
    onClickLabel = if (isPhotoOrGallery) "View photo" else "Play video"
) { onPreviewMedia() }
```

**Discoverability matters here.** Removing a labelled button in favour of an unmarked tap target loses affordance unless it is replaced. On completed items, overlay the thumbnail with a semi-transparent scrim and a centred `PlayArrow` (or `Image` for photos) at ~28dp, `Color.White.copy(alpha = 0.9f)` over `Color.Black.copy(alpha = 0.28f)`. That is the familiar video-thumbnail idiom and reads as tappable without a separate button.

### 2.3 Replace the play button with copy-link

Delete the `IconButton` at [lines 334-348](../shared/src/commonMain/kotlin/courier/ui/components/DownloadItemCard.kt#L334-L348) and put the link button in that slot, keeping the existing 40dp / `CircleShape` / `SurfaceVariantDark` + `CardBorderDark` styling used by the folder button beside it.

```kotlin
var justCopied by remember(item.id) { mutableStateOf(false) }
LaunchedEffect(justCopied) {
    if (justCopied) { delay(1500); justCopied = false }
}

IconButton(
    onClick = { onCopyLink(); justCopied = true },
    modifier = Modifier
        .size(40.dp)
        .background(if (justCopied) SuccessGreen.copy(alpha = 0.22f) else SurfaceVariantDark, CircleShape)
        .border(1.dp, if (justCopied) SuccessGreen else CardBorderDark, CircleShape)
) {
    Icon(
        imageVector = if (justCopied) Icons.Default.Check else Icons.Default.Link,
        contentDescription = if (justCopied) "Link copied" else "Copy link",
        tint = if (justCopied) SuccessGreen else TextPrimary,
        modifier = Modifier.size(20.dp)
    )
}
```

`SuccessGreen` already exists in [`Theme.kt`](../shared/src/commonMain/kotlin/courier/ui/theme/Theme.kt). The transient check is enough feedback — no snackbar needed, and it works identically on desktop and Android.

**Show the copy-link button for every status, not only `COMPLETED`.** A failed download's URL is precisely what a user wants to grab — to retry elsewhere or report a problem. Move it outside the `if (item.status == COMPLETED)` block; keep the folder button inside it.

### 2.4 Icon import

Add `androidx.compose.material.icons.filled.Link` and `.Check`. Remove the `PlayArrow` import only if §2.2's overlay doesn't use it — it does, so keep it.

### Acceptance criteria

- [ ] No play button in the action row.
- [ ] Tapping a completed item's thumbnail opens the media preview modal.
- [ ] Completed thumbnails show a play/view overlay; incomplete ones do not and are not tappable.
- [ ] The link button copies `item.url` and confirms with a 1.5s check state.
- [ ] The link button is present on queued, downloading, failed and completed items.
- [ ] Folder, retry, cancel and delete buttons are unchanged.
- [ ] Verified on both desktop and Android.

---

## Phase 3 — Regression verification

Run against a **freshly packaged** `release/Courier-Desktop-latest.jar` (Phase 0), not a Gradle run.

| # | Case | Expected |
|---|---|---|
| 1 | YouTube 1080p, default profile | `ffprobe` shows `h264` + `aac`; **imports into Premiere** |
| 2 | YouTube 4K, `MAX_QUALITY` | 2160p `av01`; UI warned it would not import |
| 3 | YouTube 4K, `EDITING_TRANSCODE` | 2160p `h264` + `aac`; imports into Premiere |
| 4 | YouTube, audio-only | MP3, unchanged |
| 5 | Instagram single photo | Photo dialog → `.jpg` on disk |
| 6 | Instagram carousel, subset | Gallery dialog → only selected items |
| 7 | Instagram reel, no cookies | Clear sign-in error, **no** fabricated resolution picker |
| 8 | Facebook photo | Clear "not supported yet" message |
| 9 | Recents card | Thumbnail tap previews; link button copies |
| 10 | Settings | Build timestamp matches the build just made |

Cases 5-8 are Plan-001's criteria, re-run here because they have **never been executed against a build that contained the code**.

---

## Appendix A — Verified command reference

```bash
# Inspect what a format string actually selects — use this before trusting any change
yt-dlp -s --no-warnings -f "<format-string>" \
       --print "%(format_id)s | vcodec=%(vcodec)s | acodec=%(acodec)s | %(width)sx%(height)s" <url>

# Full per-codec inventory
yt-dlp -F --no-warnings <url>

# Verify a finished file (the real acceptance check)
ffprobe -v error -select_streams v:0 -show_entries stream=codec_name,profile,pix_fmt,width,height -of default=nw=1 <file>
ffprobe -v error -select_streams a:0 -show_entries stream=codec_name,sample_rate,channels -of default=nw=1 <file>
```

## Appendix B — Reference data

**Codec ceiling, YouTube** — H.264 stops at 1080p; 1440p/2160p exist only as VP9/AV1. Verified 2026-09-01 on `youtube.com/watch?v=aqz-KE-bpKQ`.

**Editor support:**

| Codec | Premiere Pro | Resolve | Final Cut |
|---|---|---|---|
| H.264 (`avc1`) in MP4 | ✅ | ✅ | ✅ |
| HEVC (`hvc1`) in MP4 | ✅ | ✅ | ✅ |
| **VP9 (`vp09`)** | ❌ *"unsupported compression type VP09"* | ⚠️ partial | ❌ |
| **AV1 (`av01`)** | ❌ *"unsupported compression type av01"* | ⚠️ partial | ❌ |
| AAC (`mp4a`) | ✅ | ✅ | ✅ |
| **Opus in MP4** | ❌ | ❌ | ❌ |
| ProRes / DNxHR in MOV | ✅ | ✅ | ✅ |

## Appendix C — Rules for the implementer

1. **Verify against a packaged release jar, never a Gradle run.** The entire Plan-001 cycle was lost to this. Check the Settings build timestamp before reporting any result.
2. **`ext=mp4` says nothing about the codec.** Always constrain `vcodec^=avc1` when compatibility matters.
3. **Constrain every alternative in a format chain.** A trailing unconstrained fallback silently reintroduces the bug the first alternative prevents — that is the current defect exactly.
4. **Never mux Opus into MP4.**
5. **Do not re-encode H.264 into H.264.** Check `VideoFormat.vcodec` before invoking the transcoder.
6. **Confirm the Premiere fix by importing into Premiere.** `ffprobe` output is necessary, not sufficient.
7. **A tap target that replaces a button needs a visual affordance.** The thumbnail overlay in §2.2 is required, not decoration.
