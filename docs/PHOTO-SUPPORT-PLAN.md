# Photo & Gallery Support — Implementation Plan

**Status:** ⚠️ **Phases 1-5 are CODE-COMPLETE but were never verified against a real build.**
The jar being tested was 37 minutes older than the build and contained none of these classes; the
desktop launcher also pointed at a nonexistent file. See
[`PLAN-002-RELEASE-CODEC-UX.md` §0.1](PLAN-002-RELEASE-CODEC-UX.md). Fix the release pipeline
(Plan-002 Phase 0), then re-run the acceptance criteria below before diagnosing anything further.
Phase 6 (Facebook photos) remains unstarted.

**Target:** Instagram photos (single + carousel) working end-to-end on Desktop and Android; Facebook photos deferred to Phase 6.
**Written:** 2026-09-01
**Audience:** An implementing agent. Everything in Part 0 has been empirically verified against `yt-dlp 2026.08.19` — do not re-investigate it, build on it.

---

## Part 0 — Verified evidence (do not re-derive)

All commands below were run against the bundled binary at
`C:\Users\natha\AppData\Roaming\Courier\bin\yt-dlp.exe` (version `2026.08.19`), **logged out, no cookies**.

### 0.1 The current command fails on Instagram photo posts

```
$ yt-dlp --dump-single-json --no-warnings "https://www.instagram.com/p/BsOGulcndj-/"
ERROR: [Instagram] BsOGulcndj-: There is no video in this post
EXIT=1
```

Source of the error, from yt-dlp `extractor/instagram.py`:

```python
if not is_playlist and not info_dict.get('formats'):
    self.raise_no_formats('There is no video in this post', expected=True)
```

`raise_no_formats` **degrades to a warning** when `--ignore-no-formats-error` is passed. Note the `not is_playlist` guard — carousels never hit this branch at all.

### 0.2 Adding one flag makes it succeed

```
$ yt-dlp --dump-single-json --no-warnings --ignore-no-formats-error "https://www.instagram.com/p/BsOGulcndj-/"
EXIT=0
```

Returned JSON, abridged:

| Field | Value | Notes |
|---|---|---|
| `formats` | `[]` | **The photo signal.** Empty array. |
| `thumbnails` | 11 entries | Last entry is the full-resolution image |
| `duration` | absent/null | |
| `vcodec` / `acodec` / `ext` | absent/null | Current detection reads these — they are useless here |
| `_type` | `"video"` | **A lie.** Ignore this field. |
| `title` | `"Video by world_record_egg"` | **A lie.** Must be overridden. |
| `channel` | `"world_record_egg"` | Reliable |
| `uploader` | `"Just An Egg 🥚"` | Reliable |
| `timestamp`, `like_count`, `description` | populated | Reliable |

`thumbnails[-1].url` was downloaded directly and verified: **33,734 bytes, 584×584 JPEG**. That is the maximum resolution that exists for this post — Instagram's own `vencode_tag` in the URL's `efg` parameter decodes to `{"vencode_tag":"FEED.xpids.584.sdr.regular_photo.C3"}`, confirming 584px is the stored width.

### 0.3 The full download works with no extra tooling

```
$ yt-dlp --ignore-no-formats-error --write-thumbnail --skip-download --no-warnings \
    -o "<dir>\%(title).80s.%(ext)s" "https://www.instagram.com/p/BsOGulcndj-/"

[Instagram] BsOGulcndj-: Downloading JSON metadata
[info] Downloading video thumbnail 10 ...
[info] Writing video thumbnail 10 to: <dir>\Video by world_record_egg.jpg
EXIT=0
```

Produced `Video by world_record_egg.jpg`, 33,734 bytes — byte-identical to the direct CDN fetch.

**Two consequences the implementation must handle:**
1. The output line is `[info] Writing video thumbnail N to: <path>` — **not** `[download] Destination:`. The current parser will not see it.
2. `--skip-download` emits **zero `[download]` progress lines**. Progress must be synthesised.

### 0.4 Carousels already work and support selective download

```
$ yt-dlp --dump-single-json --ignore-no-formats-error "https://www.instagram.com/p/BQ0eAlwhDrw/"
_type   : playlist
title   : Post by instagram
entries : 3
  entry 1 : id=BQ0dSaohpPW formats=4 thumbs=12 ext=mp4 vcodec=avc1.4d401e
  entry 2 : id=BQ0dTpOhuHT formats=4 thumbs=12 ext=mp4 vcodec=avc1.4d401e
  entry 3 : id=BQ0dT7RBFeF formats=4 thumbs=12 ext=mp4 vcodec=avc1.4d401e
```

(This is yt-dlp's own documented multi-**video** carousel test case. A photo carousel has the same shape with `formats=0` and `ext`/`vcodec` absent per entry.)

Selective download verified:

```
$ yt-dlp --ignore-no-formats-error --write-thumbnail --skip-download --playlist-items "1,3" \
    -o "<dir>\%(title).60s_%(playlist_index)s.%(ext)s" "https://www.instagram.com/p/BQ0eAlwhDrw/"

[info] Writing video thumbnail 11 to: <dir>\Video by instagram_1.jpg
[info] Writing video thumbnail 11 to: <dir>\Video by instagram_3.jpg
EXIT=0
```

Exactly items 1 and 3 written, `%(playlist_index)s` honoured.

### 0.5 What does NOT work — hard constraints

| Constraint | Evidence |
|---|---|
| **Facebook photos are impossible via yt-dlp.** No extractor matches the URL. | `yt-dlp "https://www.facebook.com/photo/?fbid=..."` → `ERROR: Unsupported URL`. Same for `photo.php`. |
| **Anonymous scraping is dead.** | `instagram.com/p/<code>/embed/captioned/` returns HTTP 200 / 621 KB but contains **zero** media URLs — all 694 `cdninstagram` hits are `static.cdninstagram.com/rsrc.php` bundle assets. No `display_url`, no `og:image`, no `shortcode_media`. |
| **gallery-dl logged out is blocked too.** | `gallery-dl "https://www.instagram.com/p/BsOGulcndj-/"` → `AbortExtraction: HTTP redirect to login page`. |
| **Share links break both tools.** | `instagram.com/share/p/...` and `facebook.com/share/p/...` → `Unsupported URL`. They must be redirect-resolved first. |
| **gallery-dl on Android is not viable.** | `library-0.18.1.aar` bundles CPython 3.12 with `mutagen` only — **no `requests`/`urllib3`/`certifi`/`idna`**, which gallery-dl hard-requires. `classes.jar` exposes only `YoutubeDL.execute` — no API to run an arbitrary Python module. |
| **Login-walled *videos* also return empty formats.** | This is why detection must check `formats.isEmpty()` **AND** `thumbnails.isNotEmpty()` **AND** `duration == null`, never `formats.isEmpty()` alone. (In practice IG reels hard-error before producing JSON — `"Instagram sent an empty media response"` — but do not rely on that.) |

### 0.6 Facebook photo path (Phase 6 only)

`gallery-dl` **does** have `FacebookPhotoExtractor` and `FacebookSetExtractor` matching `/photo/?fbid=` and `/media/set/?set=`. It ships a standalone `gallery-dl.exe`:

- **21.5 MB** (`gallery-dl.exe`), also `gallery_dl_x86.exe` 13.1 MB and `gallery-dl.bin` 24.1 MB for Linux
- Published on **Codeberg**, not GitHub — GitHub releases have empty asset lists since development moved: `https://codeberg.org/mikf/gallery-dl/releases/tag/v1.32.10`
- Relevant flags: `-j` (dump JSON), `--cookies-from-browser` (same syntax family as yt-dlp), `--range` (the `--playlist-items` equivalent), `-G` (resolve URLs)
- Requires login cookies when Facebook redirects to `/login` — it raises `AuthRequired: "You must be logged in to continue viewing images."`

---

## Part 1 — Current defects

Four independent bugs. **Any one alone produces the reported symptom** (a video-resolution picker for a photo link).

| # | Defect | Location |
|---|---|---|
| **D1** | Failure fallback **fabricates a fake video** — builds a `VideoInfo` titled `"<Platform> Video"` with a hardcoded 1080p/720p/480p/360p list and shows the quality picker. This is the popup the user sees. It also masks every other extraction failure in the app. | [`HomeViewModel.kt:125-147`](../shared/src/commonMain/kotlin/courier/viewmodel/HomeViewModel.kt#L125-L147) |
| **D2** | `--ignore-no-formats-error` missing, so single-photo posts never return metadata. | [`DownloadEngineDesktop.kt:34-40`](../shared/src/desktopMain/kotlin/courier/engine/DownloadEngineDesktop.kt#L34-L40), [`DownloadEngineAndroid.kt:62-67`](../shared/src/androidMain/kotlin/courier/engine/DownloadEngineAndroid.kt#L62-L67) |
| **D3** | Detection reads `vcodec`/`acodec`/`ext`, all null for photos; `ext` defaults to `"mp4"`, so `isSingleImage` is **always false**. | [`DownloadEngineDesktop.kt:122-132`](../shared/src/desktopMain/kotlin/courier/engine/DownloadEngineDesktop.kt#L122-L132), [`DownloadEngineAndroid.kt:109-119`](../shared/src/androidMain/kotlin/courier/engine/DownloadEngineAndroid.kt#L109-L119) |
| **D4** | UI only branches on `GALLERY`; `IMAGE` falls through to `QualityPickerDialog`, which **re-injects** the standard resolution list whenever the extracted list is short and unconditionally renders Video/Audio tabs. | [`HomeScreen.kt:508`](../shared/src/commonMain/kotlin/courier/ui/screens/HomeScreen.kt#L508), [`QualityPickerDialog.kt:84-114`](../shared/src/commonMain/kotlin/courier/ui/components/QualityPickerDialog.kt#L84-L114) |

Secondary defects, all in scope:

| # | Defect | Location |
|---|---|---|
| **D5** | `IMAGE` download branch passes **no arguments at all**, so it re-triggers the extractor error. | [`DownloadEngineDesktop.kt:255-256`](../shared/src/desktopMain/kotlin/courier/engine/DownloadEngineDesktop.kt#L255-L256), [`DownloadEngineAndroid.kt:265-266`](../shared/src/androidMain/kotlin/courier/engine/DownloadEngineAndroid.kt#L265-L266) |
| **D6** | Output-path parser doesn't recognise `[info] Writing video thumbnail N to:`. Falls back to `findNewestFileInDir`, which is wrong for galleries (returns one file of N). | [`DownloadEngineDesktop.kt:300-321`](../shared/src/desktopMain/kotlin/courier/engine/DownloadEngineDesktop.kt#L300-L321) |
| **D7** | No progress events with `--skip-download`; the UI sits at 0% then jumps. | Both engines |
| **D8** | ~110 lines of JSON parsing + detection logic **duplicated verbatim** between the two engines. This is why fixes drift between platforms. | Both engines |
| **D9** | yt-dlp's `"Video by X"` title is stored as-is for photos. | Both engines |
| **D10** | `/share/` links are rejected as `Unsupported URL`. | [`UrlValidator.kt`](../shared/src/commonMain/kotlin/courier/engine/UrlValidator.kt) |

---

## Phase 1 — Stop the lie

**Goal:** The app never invents a video. Extraction failures surface honestly.
**Ship independently.** Do this first — it makes the real behaviour visible and unblocks debugging everything else.

### 1.1 Delete the fabricated fallback

In [`HomeViewModel.kt`](../shared/src/commonMain/kotlin/courier/viewmodel/HomeViewModel.kt), replace the entire `onFailure` block (lines 125-147) with error surfacing:

```kotlin
onFailure = { err ->
    _uiState.value = _uiState.value.copy(
        isAnalyzing = false,
        previewInfo = null,
        showQualityPicker = false,
        analysisError = ExtractionError.friendlyMessage(err, clean)
    )
}
```

Remove the now-unused `VideoFormat` / `Platform` imports if nothing else in the file needs them.

### 1.2 Add an error-message mapper

New file `shared/src/commonMain/kotlin/courier/engine/ExtractionError.kt`:

```kotlin
package courier.engine

import courier.model.Platform

object ExtractionError {
    fun friendlyMessage(err: Throwable, url: String): String {
        val raw = err.message.orEmpty()
        val platform = Platform.fromUrl(url)
        return when {
            raw.contains("There is no video in this post", ignoreCase = true) ->
                "This post contains no video. Courier could not read its photos — try again, or set a cookie browser in Settings."

            raw.contains("empty media response", ignoreCase = true) ||
            raw.contains("login", ignoreCase = true) ||
            raw.contains("rate-limit", ignoreCase = true) ->
                "${platform.displayName} requires you to be signed in for this post. " +
                "Open Settings and pick the browser you are signed into under \"Cookies from browser\"."

            raw.contains("Unsupported URL", ignoreCase = true) && platform == Platform.FACEBOOK ->
                "Courier cannot download Facebook photos yet. Facebook videos and reels work."

            raw.contains("Unsupported URL", ignoreCase = true) ->
                "This link isn't supported. If it's a share link, try opening it and copying the full URL."

            raw.contains("timed out", ignoreCase = true) ->
                "Timed out reading this link. Check your connection and try again."

            raw.isBlank() -> "Could not read this link."
            else -> raw.removePrefix("ERROR:").trim().take(300)
        }
    }
}
```

### 1.3 Surface `analysisError` in the UI

Confirm [`HomeScreen.kt`](../shared/src/commonMain/kotlin/courier/ui/screens/HomeScreen.kt) renders `uiState.analysisError`. If it does not, add an inline error card below the URL input bar — styled consistently with the existing surfaces (`SurfaceCard`, `CardBorderDark`, `RoundedCornerShape(14.dp)`) using the theme's error/red tone. Include a dismiss affordance that calls `onUrlChanged(inputUrl)` (which already clears `analysisError`).

### Acceptance criteria

- [ ] Pasting an Instagram photo link shows a readable error, **not** a resolution picker.
- [ ] Pasting a Facebook photo link shows the Facebook-specific message.
- [ ] Pasting a valid YouTube link still works unchanged.
- [ ] No path in `HomeViewModel` constructs a `VideoInfo` with invented formats.

---

## Phase 2 — Shared parser and correct detection

**Goal:** One classification implementation, used by both platforms, that correctly identifies `IMAGE`, `GALLERY`, `VIDEO`.

**Fixes D2, D3, D8, D9.**

### 2.1 Add `--ignore-no-formats-error` to both engines

Desktop — [`DownloadEngineDesktop.kt:34-40`](../shared/src/desktopMain/kotlin/courier/engine/DownloadEngineDesktop.kt#L34-L40), add to the `cmd` list:

```kotlin
"--ignore-no-formats-error",
```

Android — [`DownloadEngineAndroid.kt:62-67`](../shared/src/androidMain/kotlin/courier/engine/DownloadEngineAndroid.kt#L62-L67):

```kotlin
request.addOption("--ignore-no-formats-error")
```

### 2.2 Extract the parser into `commonMain`

New file `shared/src/commonMain/kotlin/courier/engine/YtDlpJsonParser.kt`. This replaces the duplicated blocks at `DownloadEngineDesktop.kt:84-199` and `DownloadEngineAndroid.kt:72-221`.

`kotlinx.serialization.json` is already a `commonMain` dependency — no build changes needed.

```kotlin
package courier.engine

import courier.model.GalleryEntry
import courier.model.MediaType
import courier.model.Platform
import courier.model.VideoFormat
import courier.model.VideoInfo
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

object YtDlpJsonParser {

    private val json = Json { ignoreUnknownKeys = true }
    private val VIDEO_EXTS = setOf("mp4", "webm", "mkv", "mov", "m4v", "avi")
    private val IMAGE_EXTS = setOf("jpg", "jpeg", "png", "webp", "gif", "heic")

    /** Throws IllegalArgumentException if [rawJson] is not a JSON object. */
    fun parse(rawJson: String, url: String): VideoInfo {
        require(rawJson.trimStart().startsWith("{")) { "Not a JSON object" }
        val root = json.parseToJsonElement(rawJson).jsonObject
        val platform = Platform.fromUrl(url)

        val entriesArray = root["entries"]?.let { runCatching { it.jsonArray }.getOrNull() }
        val isPlaylist = entriesArray != null && entriesArray.isNotEmpty()

        val galleryEntries = entriesArray.orEmpty().mapIndexed { idx, elem ->
            val obj = elem.jsonObject
            GalleryEntry(
                index = idx + 1,
                id = obj.str("id") ?: "item_${idx + 1}",
                title = obj.str("title"),
                thumbnailUrl = obj.bestThumbnail(),
                directUrl = obj.str("url"),
                isVideo = obj.looksLikeVideo()
            )
        }

        val mediaType = when {
            galleryEntries.size > 1 -> MediaType.GALLERY
            galleryEntries.size == 1 ->
                if (galleryEntries.first().isVideo) MediaType.VIDEO else MediaType.IMAGE
            root.looksLikeImage() -> MediaType.IMAGE
            else -> MediaType.VIDEO
        }

        val channel = root.str("channel") ?: root.str("uploader")
        val rawTitle = root.str("title")
        val title = cleanTitle(rawTitle, mediaType, channel, platform)

        return VideoInfo(
            id = root.str("id") ?: "media_${url.hashCode()}",
            url = url,
            title = title,
            uploader = root.str("uploader") ?: channel,
            durationSeconds = root["duration"]?.jsonPrimitive?.longOrNull,
            thumbnailUrl = galleryEntries.firstOrNull()?.thumbnailUrl ?: root.bestThumbnail(),
            platform = platform,
            formats = buildFormats(root, mediaType),
            directVideoUrl = if (mediaType == MediaType.IMAGE) root.bestThumbnail() else null,
            mediaType = mediaType,
            galleryEntries = galleryEntries
        )
    }

    // --- classification ------------------------------------------------------

    /**
     * A post is an image when yt-dlp found no playable formats but did find
     * thumbnails, and reported no duration.
     *
     * All three conditions are required: a login-walled *video* also yields an
     * empty formats array, and must NOT be misfiled as a photo.
     */
    private fun JsonObject.looksLikeImage(): Boolean {
        val ext = str("ext")?.lowercase()
        if (ext != null && ext in IMAGE_EXTS) return true
        if (ext != null && ext in VIDEO_EXTS) return false

        val hasFormats = this["formats"]?.let { runCatching { it.jsonArray } .getOrNull() }
            ?.isNotEmpty() == true
        val hasThumbs = this["thumbnails"]?.let { runCatching { it.jsonArray }.getOrNull() }
            ?.isNotEmpty() == true
        val hasDuration = this["duration"]?.jsonPrimitive?.longOrNull != null

        return !hasFormats && hasThumbs && !hasDuration
    }

    private fun JsonObject.looksLikeVideo(): Boolean = !looksLikeImage()

    /** Largest available thumbnail. yt-dlp orders `thumbnails` smallest-first. */
    private fun JsonObject.bestThumbnail(): String? =
        this["thumbnails"]?.let { runCatching { it.jsonArray }.getOrNull() }
            ?.lastOrNull()?.jsonObject?.str("url")
            ?: str("thumbnail")

    /**
     * yt-dlp labels every Instagram post "Video by <user>", including photos.
     * See `format_field(product_info, [('user','username',...)], 'Video by %s')`.
     */
    private fun cleanTitle(
        raw: String?,
        mediaType: MediaType,
        channel: String?,
        platform: Platform
    ): String {
        val fallbackNoun = when (mediaType) {
            MediaType.IMAGE -> "Photo"
            MediaType.GALLERY -> "Post"
            MediaType.AUDIO -> "Audio"
            MediaType.VIDEO -> "Video"
        }
        if (raw.isNullOrBlank()) {
            return if (channel != null) "$fallbackNoun by $channel"
                   else "${platform.displayName} $fallbackNoun"
        }
        if (mediaType == MediaType.IMAGE || mediaType == MediaType.GALLERY) {
            if (raw.startsWith("Video by ", ignoreCase = true)) {
                return "$fallbackNoun by ${raw.removePrefix("Video by ").removePrefix("video by ")}"
            }
        }
        return raw
    }

    // --- formats -------------------------------------------------------------

    private fun buildFormats(root: JsonObject, mediaType: MediaType): List<VideoFormat> {
        when (mediaType) {
            MediaType.IMAGE -> return listOf(
                VideoFormat(
                    formatId = "photo",
                    qualityLabel = "Original Photo",
                    resolution = "Original",
                    ext = root.str("ext")?.takeIf { it in IMAGE_EXTS } ?: "jpg"
                )
            )
            MediaType.GALLERY -> return listOf(
                VideoFormat("gallery", "All Items", resolution = "Original", ext = "jpg")
            )
            else -> Unit
        }

        val out = mutableListOf(
            VideoFormat("best", "Best Available Quality", resolution = "Highest", ext = "mp4")
        )
        val seenHeights = mutableSetOf<Int>()
        root["formats"]?.let { runCatching { it.jsonArray }.getOrNull() }?.forEach { elem ->
            val f = elem.jsonObject
            val height = f["height"]?.jsonPrimitive?.intOrNull ?: return@forEach
            val fmtId = f.str("format_id") ?: return@forEach
            if (height < 240 || !seenHeights.add(height)) return@forEach
            val tier = when {
                height >= 1080 -> " Full HD"
                height >= 720 -> " HD"
                else -> " SD"
            }
            out.add(
                VideoFormat(
                    formatId = "$fmtId+bestaudio/best",
                    qualityLabel = "${height}p$tier",
                    resolution = "${height}p",
                    ext = f.str("ext") ?: "mp4",
                    fileSizeBytes = f["filesize"]?.jsonPrimitive?.longOrNull,
                    fps = f["fps"]?.jsonPrimitive?.intOrNull
                )
            )
        }
        if (out.size <= 1) {
            out += listOf(
                VideoFormat("1080p", "1080p Full HD", resolution = "1080p", ext = "mp4"),
                VideoFormat("720p", "720p HD", resolution = "720p", ext = "mp4"),
                VideoFormat("480p", "480p SD", resolution = "480p", ext = "mp4"),
                VideoFormat("360p", "360p Standard", resolution = "360p", ext = "mp4")
            )
        }
        out += VideoFormat("bestaudio", "Best Audio Quality (M4A)", ext = "m4a", isAudioOnly = true)
        out += VideoFormat("mp3", "MP3 Audio (Converted 320kbps)", ext = "mp3", isAudioOnly = true)
        return out
    }

    private fun JsonObject.str(key: String): String? =
        this[key]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() && it != "null" }
}
```

### 2.3 Rewire both engines

Desktop `fetchVideoInfo` — replace lines 84-200 with:

```kotlin
val rawJson = stdoutBuilder.toString().trim()
if (rawJson.startsWith("{")) {
    Result.success(YtDlpJsonParser.parse(rawJson, url))
} else {
    val error = stderrBuilder.toString().trim().ifBlank { "Could not fetch media info" }
    Result.failure(Exception(error))
}
```

Android `fetchVideoInfo` — same shape. **Keep** the existing `YoutubeDL.getInstance().getInfo(url)` fallback branch for non-JSON output, but route its result through the same `MediaType` vocabulary.

Delete the now-dead parsing code from both engines.

### 2.4 Unit tests

`kotlin("test")` is already wired into `commonTest`. Add `shared/src/commonTest/kotlin/courier/engine/YtDlpJsonParserTest.kt` with fixtures captured from the verified commands in Part 0:

| Fixture | Expected |
|---|---|
| IG single photo (`formats: []`, 11 thumbnails, no duration) | `MediaType.IMAGE`, title `"Photo by world_record_egg"`, one `photo` format, `thumbnailUrl` = last thumbnail |
| IG 3-item carousel (`_type: playlist`, entries with `formats: 4`) | `MediaType.GALLERY`, 3 entries, all `isVideo = true` |
| Synthetic photo carousel (entries with `formats: []`, thumbnails present) | `MediaType.GALLERY`, all `isVideo = false` |
| Normal YouTube video JSON | `MediaType.VIDEO`, resolution ladder built, audio options appended |
| **Login-walled video** (`formats: []`, `duration: 137`, thumbnails present) | `MediaType.VIDEO` — **must not** be classified as a photo |

That last case is the regression guard for the most dangerous misclassification. Do not skip it.

### Acceptance criteria

- [ ] An Instagram photo link returns `MediaType.IMAGE` with a real thumbnail URL and a sane title.
- [ ] An Instagram carousel returns `MediaType.GALLERY` with correct per-entry `isVideo`.
- [ ] YouTube/TikTok video behaviour is byte-for-byte unchanged.
- [ ] Zero JSON-parsing logic remains in either platform engine.
- [ ] All five unit tests pass.

---

## Phase 3 — Download execution

**Goal:** Photos and photo galleries actually download, with progress and correct output paths.

**Fixes D5, D6, D7.**

### 3.1 Desktop — `downloadVideo` argument construction

Replace the branch at [`DownloadEngineDesktop.kt:252-273`](../shared/src/desktopMain/kotlin/courier/engine/DownloadEngineDesktop.kt#L252-L273).

The critical case is a **mixed gallery** (photos + videos): `--skip-download` would skip the videos too. Handle it with **two passes**.

```kotlin
// Partition the requested gallery indices by media kind (from item / VideoInfo).
// Pass 1 — video entries: normal format args, no --skip-download.
// Pass 2 — photo entries: --write-thumbnail --skip-download.
// A pure-photo gallery runs only pass 2; a pure-video gallery only pass 1.
```

Photo pass arguments:

```kotlin
cmd.addAll(listOf(
    "--ignore-no-formats-error",
    "--write-thumbnail",
    "--skip-download"
))
// For a gallery, additionally:
//   cmd.addAll(listOf("--playlist-items", photoIndices.joinToString(",")))
```

Do **not** add `--convert-thumbnails jpg` by default — it re-encodes through ffmpeg and is lossy. Instagram may occasionally serve `.webp`; keep the native extension. Revisit only if `.webp` files prove to be a real user problem.

Output templates stay as they are today ([lines 232-236](../shared/src/desktopMain/kotlin/courier/engine/DownloadEngineDesktop.kt#L232-L236)): `%(title).80s_%(playlist_index)s.%(ext)s` for galleries, `%(title).100s.%(ext)s` otherwise. Both were verified working in Part 0.

### 3.2 Capture thumbnail output paths

In the line reader at [`DownloadEngineDesktop.kt:300-321`](../shared/src/desktopMain/kotlin/courier/engine/DownloadEngineDesktop.kt#L300-L321), add a branch **before** the existing `[download] Destination:` case:

```kotlin
} else if (trimmed.contains("Writing video thumbnail") && trimmed.contains(" to: ")) {
    val extracted = trimmed.substringAfter(" to: ").trim()
    if (extracted.isNotBlank()) {
        writtenFiles.add(extracted)
        finalFilePath = extracted
    }
}
```

Maintain `writtenFiles` as a `MutableList<String>` for the whole invocation. On success return `writtenFiles.firstOrNull() ?: finalFilePath ?: findNewestFileInDir(outDir)`.

> `DownloadItem.outputPath` is a single path and drives "open file". For a gallery, the first file is the right choice — `openDownloadFolder` already falls back to the containing directory, which is the sensible action for a multi-file download. Do not widen the model for this.

### 3.3 Synthesise progress

`--skip-download` emits no `[download]` lines (verified). Without this the UI sits at 0% and jumps to done.

- **Single photo:** emit `onProgress(50f, …)` when `Downloading video thumbnail` is seen, then `onProgress(100f, …)` on `Writing video thumbnail`.
- **Gallery:** expected count = `item.selectedGalleryIndices.size`, or `item.galleryCount`, or `videoInfo.galleryEntries.size`. Emit `onProgress(writtenFiles.size * 100f / expected, …)` after each `Writing video thumbnail` line.
- Pass `null` for speed/eta/downloaded/total — the existing UI already handles nulls.

### 3.4 Android — mirror all of the above

[`DownloadEngineAndroid.kt:260-278`](../shared/src/androidMain/kotlin/courier/engine/DownloadEngineAndroid.kt#L260-L278) needs the same argument branching via `request.addOption(...)`.

Two Android-specific items:

1. The library's `execute(request, id) { progress, eta, line }` callback receives raw output lines. Parse `Writing video thumbnail` from `line` there to drive the same synthesised progress.
2. `findNewestFileInDir` at [line 329](../shared/src/androidMain/kotlin/courier/engine/DownloadEngineAndroid.kt#L329) returns **one** file — wrong for galleries. Collect written paths from the callback and prefer those, exactly as on desktop.
3. `MediaScannerConnection.scanFile` at [line 291](../shared/src/androidMain/kotlin/courier/engine/DownloadEngineAndroid.kt#L291) currently scans a single path. Pass the **full array** of written files so every gallery image appears in the system gallery.

### Acceptance criteria

- [ ] Single Instagram photo downloads to a real `.jpg` and opens from the app.
- [ ] Photo carousel with a subset selected downloads exactly those items, suffixed `_1`, `_3`, etc.
- [ ] Mixed gallery downloads videos as MP4 **and** photos as images in one queue item.
- [ ] Progress bar advances rather than jumping 0 → 100.
- [ ] Android: all gallery images appear in the system gallery.
- [ ] Video and audio-only downloads are unchanged.

---

## Phase 4 — UI

**Goal:** A photo link shows a photo dialog. No resolution controls anywhere near an image.

**Fixes D4.**

### 4.1 New `PhotoPickerDialog`

New file `shared/src/commonMain/kotlin/courier/ui/components/PhotoPickerDialog.kt`.

Model it structurally on [`GalleryPickerDialog.kt`](../shared/src/commonMain/kotlin/courier/ui/components/GalleryPickerDialog.kt) — same `Dialog` + `Surface(shape = RoundedCornerShape(26.dp), color = SurfaceDark, border = BorderStroke(1.5.dp, GlassBorderGradient))` shell, same platform-colour chip, same destination-folder row.

```kotlin
@Composable
fun PhotoPickerDialog(
    videoInfo: VideoInfo,
    defaultDownloadDir: String,
    savedLocations: List<String>,
    onDismiss: () -> Unit,
    onConfirm: (destinationDir: String?) -> Unit
)
```

Contents:
- Header: `"Save Photo"`
- A real image preview via the existing `NetworkImage` component, using `videoInfo.thumbnailUrl` — this is the full-resolution URL, so the user sees exactly what they will get
- Metadata row: platform chip, uploader, `Icons.Default.Image` accent
- **No** Video/Audio tabs. **No** resolution list.
- One format line: `"Original Photo (JPG)"`, informational, not a radio group
- Destination folder selector — reuse the block from `QualityPickerDialog.kt:400-458` verbatim
- Cancel / Download buttons matching the existing styling

### 4.2 Route `IMAGE` in `HomeScreen`

At [`HomeScreen.kt:506-536`](../shared/src/commonMain/kotlin/courier/ui/screens/HomeScreen.kt#L506-L536), extend the branch to a three-way `when`:

```kotlin
when (preview.mediaType) {
    MediaType.GALLERY -> GalleryPickerDialog(/* unchanged */)

    MediaType.IMAGE -> PhotoPickerDialog(
        videoInfo = preview,
        defaultDownloadDir = settings.downloadDirectory,
        savedLocations = settings.savedDownloadLocations,
        onDismiss = homeViewModel::dismissQualityPicker,
        onConfirm = { destinationDir ->
            homeViewModel.confirmDownload(
                format = preview.formats.firstOrNull(),
                isAudioOnly = false,
                destinationDir = destinationDir,
                mediaType = MediaType.IMAGE
            )
        }
    )

    else -> QualityPickerDialog(/* unchanged */)
}
```

### 4.3 Defence in depth inside `QualityPickerDialog`

Even with correct routing, `QualityPickerDialog` should refuse to invent resolutions. At [lines 84-114](../shared/src/commonMain/kotlin/courier/ui/components/QualityPickerDialog.kt#L84-L114), gate the `standardList` injection:

```kotlin
val isVideoLike = videoInfo.mediaType == MediaType.VIDEO || videoInfo.mediaType == MediaType.AUDIO
```

Only fall back to `standardList` when `isVideoLike`. Hide the Video/Audio tab row and change the `"Select Video Resolution"` header when it is not video-like.

### 4.4 Card presentation

[`DownloadItemCard.kt:111-115`](../shared/src/commonMain/kotlin/courier/ui/components/DownloadItemCard.kt#L111-L115) already maps `MediaType.IMAGE` → `Icons.Default.Image` and line 341 already has `isPhotoOrGallery`. Verify these render correctly now that `IMAGE` actually occurs — this path has never executed before.

`DownloadManager.enqueueDownload` at [lines 85-97](../shared/src/commonMain/kotlin/courier/manager/DownloadManager.kt#L85-L97) already produces `"Photo (JPG)"` labels and `"<Platform> Photo"` fallback titles. No change expected; confirm.

### Acceptance criteria

- [ ] Instagram photo link → photo dialog with a visible image preview.
- [ ] No resolution radio buttons or Video/Audio tabs appear for a photo.
- [ ] Carousel link → existing gallery picker, still working.
- [ ] Video link → quality picker, visually unchanged.
- [ ] Completed photo shows the image icon and a `"Photo (JPG)"` label in the list.

---

## Phase 5 — Link handling and cookie guidance

**Goal:** Share links work. Users understand when sign-in is required.

**Fixes D10.**

### 5.1 Resolve `/share/` redirects

Both `instagram.com/share/p/...` and `facebook.com/share/p/...` are rejected as `Unsupported URL` (verified). They 30x-redirect to canonical URLs.

Ktor client core is already a `commonMain` dependency and CIO is wired for both Android and Desktop — implement this once in `commonMain`.

New file `shared/src/commonMain/kotlin/courier/engine/ShareLinkResolver.kt`:

```kotlin
suspend fun resolve(url: String): String
```

- Trigger only when the URL matches `/share/`, `fb.watch/`, `instagr.am/`, or `pin.it/` — do not add a network round-trip to every paste
- Issue a HEAD (fall back to GET) with `followRedirects = true` and a desktop User-Agent, return the final URL
- Cap at 5 redirects, 10s timeout
- **On any failure return the original URL unchanged** — never let this block extraction

Call it from `HomeViewModel.analyzeUrl` immediately after `UrlValidator.cleanUrl`, before `engine.fetchVideoInfo`.

### 5.2 URL classification hint

Add to `UrlValidator`:

```kotlin
enum class MediaHint { LIKELY_VIDEO, LIKELY_PHOTO, UNKNOWN }

fun hintFor(url: String): MediaHint
```

- `/reel/`, `/reels/`, `/tv/`, `watch/?v=`, `/videos/`, `fb.watch` → `LIKELY_VIDEO`
- `/photo/`, `photo.php`, `fbid=`, `/media/set/` → `LIKELY_PHOTO`
- `/p/` → `UNKNOWN` (genuinely ambiguous on Instagram)

Use this **only** for the loading-state label ("Reading photo…" vs "Reading video…") and for error message wording. **Never** use it to classify actual media — extraction output is the only source of truth.

### 5.3 Cookie guidance

Instagram now requires sign-in for the large majority of posts, photos and videos alike. The `selectedCookieBrowser` setting already exists in [`SettingsScreen.kt`](../shared/src/commonMain/kotlin/courier/ui/screens/SettingsScreen.kt).

- When an extraction fails with a login/empty-media error **and** `selectedCookieBrowser` is `"None"`, the error card from Phase 1 should carry a "Open Settings" action.
- Add a short explanatory line under the cookie-browser setting: *"Instagram and Facebook require a signed-in browser session for most posts."*

### Acceptance criteria

- [ ] An `instagram.com/share/p/...` link resolves and extracts.
- [ ] A `fb.watch/...` link resolves and extracts.
- [ ] A non-share link makes **no** extra network request.
- [ ] A resolver failure falls back to the original URL rather than erroring.
- [ ] A login-required failure with no cookie browser set offers a route to Settings.

---

## Phase 6 — Facebook photos via gallery-dl (deferred, desktop only)

**Do not start this until Phases 1-5 ship.** It is a separate milestone with its own binary-management surface and a hard platform limitation.

**Android is out of scope permanently** — see §0.5. Facebook photo support will be desktop-only. The Android build must degrade gracefully with the Phase 1 message: *"Courier cannot download Facebook photos yet."*

### 6.1 Binary management

Extend [`BinaryManagerDesktop`](../shared/src/desktopMain/kotlin/courier/engine/BinaryManagerDesktop.kt), which already implements download-with-progress, temp-file-then-rename, and PATH probing.

- Add `getGalleryDlExecutable()` mirroring `getYtDlpExecutable()`
- Source: `https://codeberg.org/mikf/gallery-dl/releases/download/v<version>/gallery-dl.exe` — **Codeberg, not GitHub**; GitHub release assets are empty since development moved
- ~21.5 MB. Make it an **opt-in** download from Settings ("Enable Facebook photo support"), not part of first-run setup — the existing setup already pulls ~163 MB of yt-dlp + ffmpeg
- Adjust the progress weighting in `ensureBinariesReady` only if it becomes part of the default flow

### 6.2 Engine routing

Introduce a small strategy layer rather than branching inside `DownloadEngineDesktop`:

```
PhotoExtractor
 ├── YtDlpPhotoExtractor      (Instagram, TikTok, and anything yt-dlp handles)
 └── GalleryDlPhotoExtractor  (Facebook photos/sets; desktop only, opt-in)
```

Route to `GalleryDlPhotoExtractor` when: platform is `FACEBOOK` **and** the URL matches `/photo/`, `photo.php`, `fbid=`, or `/media/set/` **and** the binary is present. Otherwise fall through to yt-dlp.

### 6.3 gallery-dl invocation

- Metadata: `gallery-dl -j --cookies-from-browser <browser> <url>` → JSON array of `[type, url, metadata]` triples. **This is a different schema from yt-dlp** — write a separate `GalleryDlJsonParser`, do not attempt to share `YtDlpJsonParser`.
- Selective download: `--range 1,3` (the `--playlist-items` equivalent)
- Cookies: `--cookies-from-browser` accepts the same browser names already collected by the existing setting
- Auth failure: gallery-dl raises `AuthRequired: "You must be logged in to continue viewing images."` — map this to the Phase 1 cookie-guidance message
- Update path: no `-U` self-update equivalent; re-download the binary from Codeberg

### Acceptance criteria

- [ ] `facebook.com/photo/?fbid=...` downloads with a signed-in cookie browser configured.
- [ ] Facebook albums (`/media/set/?set=...`) route through the gallery picker.
- [ ] Without the binary installed, the user gets a clear opt-in prompt, not a crash.
- [ ] Android shows the graceful "not supported" message.
- [ ] Instagram continues to use yt-dlp and is entirely unaffected.

---

## Appendix A — Test URLs

| URL | Kind | Logged-out behaviour |
|---|---|---|
| `https://www.instagram.com/p/BsOGulcndj-/` | Single photo | Works with `--ignore-no-formats-error`. 584×584 JPEG, 33,734 bytes. |
| `https://www.instagram.com/p/BQ0eAlwhDrw/` | 3-item video carousel | Works. `_type: playlist`, 3 entries, 4 formats each. yt-dlp's own test case. |
| `https://www.instagram.com/reel/C8YJEcXR3Zo/` | Reel | Hard-errors: *"Instagram sent an empty media response"*. Needs cookies. |
| `https://www.instagram.com/share/p/<code>/` | Share link | `Unsupported URL` until resolved (Phase 5). |
| `https://www.facebook.com/photo/?fbid=<id>` | FB photo | `Unsupported URL` — yt-dlp has no matching extractor at all. |

A **photo carousel** URL was not captured during investigation. Source one during Phase 2 and add it as a fixture; the expected shape is `_type: playlist` with `formats: []` and populated `thumbnails` per entry.

## Appendix B — Command reference

```bash
# Metadata, any media kind
yt-dlp --dump-single-json --no-warnings --no-check-certificates \
       --ignore-no-formats-error [--cookies-from-browser <b>] <url>

# Single photo
yt-dlp --ignore-no-formats-error --write-thumbnail --skip-download \
       -o "<dir>/%(title).100s.%(ext)s" <url>

# Selected gallery photos
yt-dlp --ignore-no-formats-error --write-thumbnail --skip-download \
       --playlist-items 1,3,4 \
       -o "<dir>/%(title).80s_%(playlist_index)s.%(ext)s" <url>

# Facebook photo — Phase 6, desktop only
gallery-dl -j --cookies-from-browser <b> <url>
gallery-dl --range 1,3 --cookies-from-browser <b> -D <dir> <url>
```

## Appendix C — Rules for the implementer

1. **Never invent formats.** If extraction fails, show the error. D1 is the root of the reported bug and the failure mode most likely to reappear.
2. **Never trust `_type` or `title`** from yt-dlp for Instagram. Both say "video" for photos.
3. **Classification requires all three signals** — no formats, has thumbnails, no duration. A login-walled video has no formats either. There is a mandatory unit test for this.
4. **`thumbnails` is smallest-first.** Take `.last()` for full resolution.
5. **`--skip-download` produces no progress output.** Progress must be synthesised or the UI appears frozen.
6. **Keep classification in `commonMain`.** The pre-existing duplication (D8) is why Android and Desktop drift; do not reintroduce it.
7. Do not add `--convert-thumbnails` by default — it is a lossy ffmpeg re-encode.
