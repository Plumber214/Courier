# Plan 003 — Android Verification and Codec Hardening

**Status:** Ready to build
**Written:** 2026-09-01
**Follows:** [`PHOTO-SUPPORT-PLAN.md`](PHOTO-SUPPORT-PLAN.md) and [`PLAN-002-RELEASE-CODEC-UX.md`](PLAN-002-RELEASE-CODEC-UX.md), both shipped and confirmed working **on desktop only**.

**Three phases, in priority order:**
1. **Phase 1** — Android has never been built with any of this work. Verify it end to end on a device.
2. **Phase 2** — `FormatSelector` has zero tests, and preparing this plan found a live Opus-in-MP4 bug in it.
3. **Phase 3** — small cleanups already flagged and deferred.

Everything in Part 0 was verified on 2026-09-01. Do not re-investigate it.

---

## Part 0 — Verified state

### 0.1 The Android APK predates every change

```
release/Courier-Android-latest.apk    8/31/2026  7:44 AM
```

Plan-001, Plan-002 and the follow-up fixes all landed between **09:50 and 12:06 on 9/1**. That APK contains none of it: no photo support, no codec fix, no mixed-carousel handling, no 48 kHz audio, no system-viewer handoff.

Every verification so far has been desktop: the uber jar, `ffprobe` on downloaded files, the two-pass carousel test. **The Android engine has been compiled but never run.**

`publishAndroidRelease` is registered and visible in the `courier` task group, but **has never been executed**. It is unproven. It depends on `assembleDebug` because the `release` buildType has no `signingConfig` — confirmed by inspection of `androidApp/build.gradle.kts` — so an `assembleRelease` APK would be unsigned and non-installable.

### 0.2 A device is already connected and provisioned

```
model:Pixel_11_Pro   device:grizzly   transport_id:1   (adb-tls, wireless)
Android release: 17
adb: C:\Program Files (x86)\Touch Portal\plugins\adb\platform-tools\adb.exe
package:com.courier.app   <- an older build is already installed
```

`adb` is on PATH and the device is authorised. Because `com.courier.app` is already present from an older build, **`adb install -r` may fail on a signature or downgrade mismatch** — see Phase 1.3.

### 0.3 `FormatSelector` has no test coverage

Confirmed: **0 references** to `FormatSelector`, `OutputProfile` or `ExtractionError` anywhere in `shared/src/commonTest`.

Existing tests:

```
shared/src/commonTest/kotlin/courier/UrlValidatorTest.kt
shared/src/commonTest/kotlin/courier/engine/YtDlpJsonParserTest.kt   (9 tests, passing)
```

This is backwards. `YtDlpJsonParser` is well covered; `FormatSelector` — which encodes every Premiere-compatibility decision as untyped format strings where one dropped `[vcodec^=avc1]` silently reintroduces AV1 with no crash and no error — has none.

### 0.4 Live bug: `MAX_QUALITY` can still mux Opus into MP4

Found while writing this plan. `videoFormatArg` for `MAX_QUALITY` emits:

```
bestvideo[height<=N]+bestaudio[acodec^=mp4a]      <- constrained
bestvideo[height<=N]+bestaudio                     <- UNCONSTRAINED
best[height<=N]
```

and `mergeOutputFormat(MAX_QUALITY, …)` returns **`mp4`**, because `needsTranscode` is false for that profile.

So when no AAC rendition matches the first alternative, the second selects Opus and it gets muxed into MP4 — the exact failure Plan-002 set out to eliminate, still live in this path. Reproduced by forcing the fallback:

```
$ yt-dlp -s -f "bestvideo[height<=720][acodec^=nope]+bestaudio[acodec^=mp4a][ext=xyz]/bestvideo[height<=720]+bestaudio/best[height<=720]" --print "..."
398+251 | vcodec=av01.0.08M.08 | acodec=opus
```

Opus-in-MP4 is worse than AV1-in-MP4: some players refuse it outright, not just editors. `MAX_QUALITY` is documented as "will not import into most editors", which is not a licence to emit a broken container.

---

## Phase 1 — Android verification

**Goal:** A device-installed build that demonstrably contains this work, with the Android-specific paths exercised.

**Nothing else in this plan matters until this is done.** Android is currently a completely unverified platform carrying ~2000 lines of changed code.

### 1.1 Prove the publish task works

```
gradlew publishAndroidRelease
```

Expect `release/Courier-Android-v1.3.0.apk` and `release/Courier-Android-latest.apk`, both freshly timestamped.

If it fails, likely causes in order: the `assembleDebug` output path differs from `androidApp/build/outputs/apk/debug` on this AGP version; the `Copy` task matching `*.apk` picks up an unexpected file; or the version-stamped rename collides.

### 1.2 Verify the APK actually contains the new code

Do not trust a green build — this is precisely how the desktop cycle was lost. Inspect the artifact:

```powershell
Add-Type -AssemblyName System.IO.Compression.FileSystem
$z=[System.IO.Compression.ZipFile]::OpenRead("release\Courier-Android-latest.apk")
$z.Entries | Where-Object { $_.FullName -like "*classes*.dex" } | Select-Object FullName, Length
$z.Dispose()
```

DEX is compiled, so class names are not greppable the way jar entries were. Verify instead by **behaviour** in 1.4, and by confirming the APK timestamp and `versionCode` (should be **19**, from `courier.buildNumber`):

```powershell
& adb shell dumpsys package com.courier.app | Select-String "versionCode|versionName"
```

### 1.3 Install

```powershell
adb install -r "release\Courier-Android-latest.apk"
```

`com.courier.app` is already installed from an older build. If `adb install -r` fails with `INSTALL_FAILED_UPDATE_INCOMPATIBLE` or a signature mismatch, uninstall first:

```powershell
adb uninstall com.courier.app
adb install "release\Courier-Android-latest.apk"
```

> Uninstalling clears app data — the download history and settings. That is acceptable here and actually useful: it exercises the first-run path and default `OutputProfile` migration.

### 1.4 Device test matrix

The Android engine differs from desktop in ways that are not covered by desktop testing, so these must be run on the device.

| # | Case | Expected | Android-specific risk |
|---|---|---|---|
| 1 | Settings footer | Build 19, "Built" timestamp matches the APK | `getBuildTimestamp()` uses `PackageInfo.lastUpdateTime`; a different code path from desktop |
| 2 | YouTube 1080p, default profile | Plays; audio in sync | Format string is shared, but merging runs under the bundled ffmpeg |
| 3 | Instagram single photo | Photo dialog → `.jpg` saved | `--write-thumbnail` under `youtubedl-android` |
| 4 | Instagram carousel, subset | Only selected items | `--playlist-items` |
| 5 | **Mixed carousel** | Videos as `.mp4`, photos as `.jpg` | **Two-pass loop is newly written for Android and never run** |
| 6 | Cancel mid-download | Stops cleanly | Both passes reuse `item.id` as the process id; `destroyProcessById` must still work |
| 7 | Tap a photo thumbnail | Opens the system gallery | New `image/*` MIME mapping in `openFile` |
| 8 | Tap a video thumbnail | In-app player | Unchanged behaviour |
| 9 | Downloaded media appears in Photos/Gallery | All gallery items, not just one | `MediaScannerConnection.scanFile` is passed an array |
| 10 | Audio-only (MP3) | Plays | Bundled ffmpeg extract path |

**Cases 5 and 6 are the highest risk.** The two-pass gallery loop in `DownloadEngineAndroid` was written blind and compiled but never executed. Case 6 specifically guards a bug caught during review: the pass loop originally used `"${item.id}_p$passIndex"` as the process id, which would have silently broken cancellation, since `cancelDownload` destroys by `item.id`.

### 1.5 Storage permissions

Android 17 scoped storage is stricter than the `minSdk 24` baseline this code was written against. Confirm downloads land in `Downloads/Courier` and are visible to other apps. If they are not, that is a real defect for Phase 1 — not something to defer.

### Acceptance criteria

- [ ] `publishAndroidRelease` produces both APKs
- [ ] Installed build reports versionCode 19 and a matching build timestamp
- [ ] All ten matrix cases pass, or failures are documented with logcat
- [ ] Mixed carousel produces both `.mp4` and `.jpg` on device
- [ ] Cancel works during both passes

---

## Phase 2 — `FormatSelector` tests, and fix the Opus bug

**Goal:** The codec guarantees are enforced by tests rather than by reading the source.

### 2.1 Fix `MAX_QUALITY`'s unconstrained fallback

Two defensible options. **Prefer (a)**:

**(a) Constrain the container instead of the codec.** Keep the Opus fallback — it genuinely is the best audio available when no AAC exists — but stop putting it in MP4. Make `mergeOutputFormat` return `mkv` when the selected audio may not be MP4-safe. MKV holds Opus correctly and plays everywhere.

**(b) Drop the unconstrained alternative** so `MAX_QUALITY` falls through to `best[height<=N]`. Simpler, but loses the best-available-audio property that makes the profile meaningful.

Whichever is chosen, the invariant to encode is: **no format chain may pair an Opus-capable selector with an MP4 merge target.**

### 2.2 Test file

Add `shared/src/commonTest/kotlin/courier/engine/FormatSelectorTest.kt`, matching the style of the existing `YtDlpJsonParserTest`.

**Write property tests, not golden-string comparisons.** Asserting the exact format string would make every cosmetic edit a test failure while still not expressing what actually matters. Assert the invariants:

| Invariant | Why it exists |
|---|---|
| In `EDITING_NATIVE`, every alternative containing `bestvideo` also contains `vcodec^=avc1` | The original bug: `ext=mp4` does not imply H.264 |
| In `EDITING_NATIVE`, every alternative containing `bestaudio` also contains `acodec^=mp4a` | Prevents Opus |
| No profile pairs an unconstrained-audio alternative with an `mp4` merge target | §0.4, the live bug |
| Each preset height appears as `[height<=N]` in every alternative that filters | A missing cap silently downloads 4K on a 480p request |
| A raw ladder expression (contains `+` or `/`) passes through unmodified | `YtDlpJsonParser` already constrained it |
| `needsTranscode` is false when `selectedVcodec` is already `avc1` and target is H264 | Never re-encode H.264 into H.264 |
| `needsTranscode` is true when `selectedVcodec` is null and profile is `EDITING_TRANSCODE` | Unknown codec must not be gambled on |
| `needsTranscode` is always true for ProRes/DNxHR targets | Mezzanine formats are requested for edit performance, not import |
| `mergeOutputFormat` returns `mkv` exactly when `needsTranscode` is true | §0.4 and the `--recode-video` container trap |
| `audioNormalisationArgs` is empty for `MAX_QUALITY`, non-empty otherwise | The "original streams untouched" contract |
| Every `transcodeArgs` variant contains `-ar 48000` | 48 kHz is the point of the change |
| Every `transcodeArgs` variant contains `-fps_mode cfr` | VFR causes progressive audio drift in Premiere |

A helper that splits a format string on `/` and asserts per-alternative makes most of these one-liners.

### 2.3 Guard the documented exceptions

`EDITING_NATIVE`'s last two alternatives (`best[height<=N][ext=mp4]`, `best[height<=N]`) are **deliberately unconstrained** — the documented last resort for sites publishing no H.264 at all. Tests must not fail on those. Encode the exception explicitly so a future reader sees it is intentional rather than an oversight.

### Acceptance criteria

- [ ] `gradlew :shared:desktopTest` passes with the new tests
- [ ] Test count confirmed from `shared/build/test-results/desktopTest/*.xml`, not from "BUILD SUCCESSFUL" — a task can pass having run nothing
- [ ] Deleting `[vcodec^=avc1]` from `EDITING_NATIVE` makes a test fail (verify the test actually bites)
- [ ] The §0.4 Opus case is fixed and covered

---

## Phase 3 — Deferred cleanups

Small, independent, safe to do in any order.

### 3.1 `.gitattributes`

Every commit emits `LF will be replaced by CRLF` warnings for every file. Harmless on a Windows-only checkout, but a Linux or CI build would produce whole-file spurious diffs. Add:

```
* text=auto eol=lf
*.bat text eol=crlf
*.jar binary
*.apk binary
```

`.bat` must stay CRLF — some Windows shells mishandle LF-only batch files.

### 3.2 Gate `EDITING_TRANSCODE` on Android

Plan-002 §1.6 called for this and it was not done. A 4K x264 re-encode on a phone is thermally throttled and can exceed the video's own runtime. Hide the profile on Android, or show it behind an explicit warning. Do **not** offer ProRes or DNxHR on mobile at all — the file sizes are impractical for device storage.

### 3.3 Consider `--concurrent-fragments` on Android

Desktop passes `--concurrent-fragments 5`; Android does not. Likely deliberate for battery and thermals, but it is an undocumented asymmetry. Either add a comment saying why, or match desktop.

---

## Deferred — Facebook photos

Unchanged from [`PLAN-002` Phase 6](PLAN-002-RELEASE-CODEC-UX.md). Real feature work, not cleanup: bundling `gallery-dl` (21.5 MB, from **Codeberg** — GitHub release assets are empty since development moved), a separate `GalleryDlJsonParser` for its different schema, and an opt-in download flow. **Desktop only** — the bundled CPython in `youtubedl-android` lacks `requests` and the library exposes no way to run an arbitrary Python module.

Start it only after Phases 1–3 land.

---

## Appendix A — Commands

```powershell
# Phase 1
gradlew publishAndroidRelease
adb devices -l
adb install -r "release\Courier-Android-latest.apk"
adb shell dumpsys package com.courier.app | Select-String "versionCode|versionName"
adb logcat -s Courier:V *:E            # engine logs use the "Courier" tag

# Phase 2
gradlew :shared:desktopTest --rerun-tasks
# then read the real result, not the build status:
[xml]$d = Get-Content "shared\build\test-results\desktopTest\TEST-courier.engine.FormatSelectorTest.xml"
"$($d.testsuite.tests) tests, $($d.testsuite.failures) failures"

# Inspect what a format string actually selects
yt-dlp -s -f "<chain>" --print "%(format_id)s | vcodec=%(vcodec)s | acodec=%(acodec)s" <url>
```

## Appendix B — Rules for the implementer

1. **Verify the artifact, not the build status.** A green Gradle run proved nothing on desktop and will prove nothing here. Check the installed versionCode and the in-app build timestamp before testing anything.
2. **"BUILD SUCCESSFUL" from a test task can mean zero tests ran.** Read the XML report.
3. **The Android two-pass gallery loop has never executed.** Treat matrix cases 5 and 6 as the real deliverable of Phase 1.
4. **Do not weaken a codec constraint to make a test pass.** The constraints are the feature; if a test fails, the chain is wrong.
5. **`ext=mp4` says nothing about the codec**, and a trailing unconstrained fallback silently undoes the alternative before it. That is how §0.4 survived.
6. `--recode-video` is a container operation and is skipped when the file already matches the target, which is why the transcode path merges to MKV first. Do not "simplify" that away.
