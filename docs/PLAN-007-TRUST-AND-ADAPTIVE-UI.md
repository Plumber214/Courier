# Plan 007 — v1.7.0: Trust, Correctness & Adaptive UI

**Status:** Ready to build — decisions settled
**Written:** 2026-09-03
**Target:** `courier.versionName=1.7.0`, `courier.buildNumber=24`
**Follows:** [`PLAN-006-DEVICE-LINK-POLISH.md`](PLAN-006-DEVICE-LINK-POLISH.md)

An audit of the v1.6.0 tree found 24 defects. This plan closes them, plus two
features that were already half-built.

The headline is that **any device on the LAN can pair itself with this one
without a human ever confirming it** (§0.1). Everything else in this plan is
ordinary finishing work; that is not.

---

## Decisions (settled — do not re-open)

| # | Decision | Where |
| --- | --- | --- |
| G1 | **Pairing is session-bound and receiver-confirmed.** An `accepted` packet is honoured only on the active pairing link while this device is in `OutgoingRequest`. The initiator's dialog becomes a *waiting* state; only the receiving device has a Confirm button. | Stage 1 |
| G2 | **Remote downloads always land in the receiver's own folder.** `destinationHint` is ignored entirely, not sanitised. Each device owns its storage. | Stage 1 |
| G3 | **Nothing unverified is executed.** The update artifact and yt-dlp are checksum-verified before use; FFmpeg moves off the rolling `latest` tag to a pinned release with a recorded hash. | Stage 2 |
| G4 | **The managed binary is the only binary.** The `PATH` lookup for `yt-dlp` is removed, so the version reported in Settings is always the one that runs. | Stage 2 |
| G5 | **Certificate verification stays on.** `--no-check-certificates` is removed from both yt-dlp invocations, with no per-download escape hatch. | Stage 2 |
| G6 | **Last-seen is not durable state.** It is held in memory and persisted on disconnect, not on every received packet. | Stage 4 |
| G7 | **Single video by default.** `--no-playlist` is passed and `&list=` stripped; a detected playlist offers an explicit "download all" toggle in the picker. | Stage 5 |
| G8 | **Removing from history never deletes media.** The trash icon forgets the row; a separate, confirmed "Delete file" action removes the file. | Stage 6 |
| G9 | **The banner only volunteers on known platforms.** Heuristic URL matching is dropped from `isSupportedVideoUrl`; the Fetch button stays willing to attempt anything pasted deliberately. | Stage 5 |
| G10 | **Layout reads a width class, once.** `compact` / `medium` / `expanded` resolved at the root and threaded down — not per-screen `isAndroid()` checks. | Stage 7 |
| G11 | **In scope:** subtitles/chapters/metadata, pause & resume, Settings restructure with a Device Link section. **Out:** light theme, string extraction, multi-URL queue, file transfer over the link, remote queue control. | Stage 8 |

---

## Part 0 — Verified state, 2026-09-03

Read from the code today. Cited by file and line. **Do not re-investigate.**

### 0.1 Pairing authorises on a flag the peer controls

`PairingManager.kt:57-75`:

```kotlin
if (isPair) {
    if (isAccepted) {
        // Outgoing request accepted by peer
        trustStore.addOrUpdatePairedDevice(pairedDevice)
```

The branch tests `packet.body["accepted"]` and nothing else. It does not check
that the packet arrived on `activePairingLink`, and it does not check
`_pairingState`. `DeviceLinkManager.kt:249-255` deliberately admits `TYPE_PAIR`
from unpaired peers — it has to, or pairing could never start — so this is
reachable before any authorisation:

> A peer that completes the TLS handshake and sends
> `{"pair":true,"accepted":true}` is written into `TrustStore` with its
> certificate pinned. No dialog appears. From that point it can send download
> requests and clipboard payloads.

The same missing guard breaks the intended flow. `DevicesScreen.kt:453-462`
gives the **initiator** a Confirm button wired to `acceptPairing()`, which pairs
locally *and* sends `accepted` — so whichever side presses first pairs both, and
the 8-character code on the second screen is never compared by anyone.

Certificate pinning itself is sound: `LinkServer.kt:146,168,214,235` validate
against `TrustStore` for already-paired ids, so a peer cannot impersonate an
existing pairing. The hole is only in becoming a *new* one.

### 0.2 The receiving side drops the sender's format choice

`LinkDownloadBridge.kt:40-44`:

```kotlin
val itemId = downloadManager.enqueueDownload(
    url = req.url,
    isAudioOnly = req.audioOnly,
    destinationDir = req.destinationHint
)
```

`RemoteDownloadRequest.formatHint` is populated by `HomeViewModel.kt:180`,
transmitted, and parsed back out at `DeviceLinkManager.kt:276`. It is never read
here. Choosing 1080p on the phone downloads at the desktop's default.

`destinationHint` *is* read — passed straight through to
`DownloadManager.kt:297` with no containment check, so the sender picks the write
path.

### 0.3 The status watcher never stops

`LinkDownloadBridge.kt:54-75` collects `downloadManager.downloads` — the whole
list — so one progress tick on *any* download emits a status packet for *this*
item. On a terminal status it calls `activeRemoteWatchers.remove(itemId)`
without cancelling the job, so the collector keeps running for the life of the
process. `activeRemoteWatchers` is a plain `mutableMapOf` mutated from
`Dispatchers.Default`.

### 0.4 A timestamp costs a durable three-file write

`DeviceLinkManager.kt:224` calls `trustStore.updateLastSeen(peerId)` inside the
packet loop — every packet. `TrustStore.kt:93-101` then calls
`saveTrustedDevices()`, and `FileStoreJvm.kt:5-47` serialises the full list,
writes a temp file, calls `fd.sync()`, copies the previous file to `.bak`, and
atomically moves.

Combined with §0.3 that is several fsync'd writes per second on the phone during
a single remote download. None of it needs to be durable.

`DownloadRepository.kt:58-79` has a related problem: `persistDownloads` is called
*inside* `MutableStateFlow.update`, whose lambda re-runs on contention.

### 0.5 An unverified jar is copied over the running one

`AppUpdateManager.kt:108-117` takes the first asset whose name ends `.jar` or
`.zip`. `:142-231` downloads it with no checksum and no signature.
`:233-282` copies it over `codeSource.location` and relaunches.

Two further problems in that method:

- For the packaged distribution the README recommends first
  (`Courier-Windows/Courier.exe`), `codeSource.location` resolves to a dependency
  jar inside `app/`. Copying the uber jar over it produces a broken install.
- On any non-Windows host the `isWindows` branch is skipped and control falls to
  `exitProcess(0)` — the app quits having applied nothing.

`BinaryManagerDesktop.kt:99,130` download `yt-dlp.exe` and the FFmpeg archive with
no integrity check either, and `:46-59` falls back to any `yt-dlp.exe` on `PATH`.

### 0.6 Android's entry points are wired to the clipboard

`MainActivity.kt:46-57` delivers a shared link by calling `setClipboardText`.
The only reader is `HomeScreen.kt:107-110`, a `LaunchedEffect(Unit)` that runs
once at composition. The activity is `singleTask`, so every share after the first
arrives through `onNewIntent` — where nothing re-reads it. The share silently
does nothing, having overwritten the user's clipboard.

`PlatformActionsAndroid.kt:69-81` discards `openFolder`'s argument and targets
the public Downloads directory; it also uses `ACTION_VIEW` on a bare path, which
most devices do not resolve.

`UrlValidator.kt:20-34` ends with `lowercase.startsWith("http")`, so
`isSupportedVideoUrl` returns true for every URL.

### 0.7 The output file is guessed, and then deleted

`DownloadEngineDesktop.kt:409-419` falls back to *the newest file in the
destination directory* when the path cannot be parsed from yt-dlp's log. Anything
the user saved there in the previous three minutes qualifies.

`HomeScreen.kt:508` then calls `removeDownload(item.id, deleteDiskFile = true)`
— always true — and `DownloadManager.kt:243-254` deletes every path recorded on
the item. There is no confirmation and no undo.

### 0.8 Nothing in the UI reads available width

The only adaptive decision in the tree is `App.kt:64,71` choosing a
`NavigationBar` over a `NavigationRail`. Below that, `HomeScreen.kt:126` and
`SettingsScreen.kt:147` set a fixed 22 dp gutter on a single column for every
size.

`DownloadItemCard.kt:118-182,371-464` composes a 100 dp thumbnail, two 16 dp
gaps and up to three 40 dp buttons on 12 dp spacing. On a 360 dp phone that
leaves roughly 90 dp for a `maxLines = 1` title, the format badge and the
progress row.

`QualityPickerDialog.kt:212-226` draws a generic icon where the thumbnail
belongs; `VideoFormat.fileSizeBytes` is parsed and never displayed; and only the
160 dp format list scrolls, not the dialog column — so on a short screen the
Download button is unreachable.

### 0.9 Smaller confirmed defects

| Ref | File | Problem |
| --- | --- | --- |
| C7 | `BinaryManagerDesktop.kt:141-151` | A failed FFmpeg download is logged, then `_isReady = true` and "Engine Ready". |
| C8 | `DeviceLinkManager.kt:474-486` | `unpair` clears backoff and replay marks but leaves queued outbox packets. |
| C9 | `QualityPickerDialog.kt:126` | `if (extracted.isEmpty()) standardAudio else standardAudio` — both branches identical. |
| C11 | `NetworkImage.kt:48-60` | Disk cache keyed on 32-bit `url.hashCode()`, never evicted. |
| P3 | `Discovery.kt:167-201` | A new `DatagramSocket` per broadcast, every 1.5 s while the tab is open. |
| U8 | `DownloadManager.kt:155-175` | Cancel destroys the process; the only way back is Retry from zero. |

### 0.10 What is already good and must not regress

The transport is the strongest part of this codebase and this plan touches it
carefully:

- `BoundedLineReader` and its CVE-2020-26164 reasoning.
- `LinkServer`'s global **and** per-IP connection caps.
- The TLS role inversion, and the comment at `CertificateStore.kt:169-184`
  explaining why `getAcceptedIssuers()` must stay empty.
- `ReplayGuard` persistence, and the v1.6.0 dormancy work — zero traffic with no
  paired devices and the Devices tab closed.

---

## Working rules

- **A successful compile is not evidence.** Desktop changes get the app run;
  Android changes get installed.
- **Part 0 and the Decisions table are settled.** If Part 0 is factually wrong,
  stop and report rather than working around it.
- Device Link needs a **physical phone on a real LAN** (PLAN-005 E3).

---

## Stage 0 — Version bump and baseline

**Why first:** VERSIONING.md requires it, for the reason recorded there.

1. Bump to `1.7.0` / build `24` in **both** `gradle.properties` and
   `AppVersion.kt`; set `RELEASE_DATE`. `checkVersionConsistency` enforces this.
2. Record the baseline: current test count, both artifact sizes.

---

## Stage 1 — Close the pairing bypass

**Why here:** it is the only critical finding, and Stage 2 onward is ordinary
work that can wait behind it.

1. **Bind acceptance to the session (G1).** In `handleIncomingPairPacket`, honour
   the `accepted` branch only when `link === activePairingLink` **and**
   `_pairingState.value is OutgoingRequest`. Anything else is logged and dropped
   like the other unauthorised-packet paths.
2. **Confirmation moves to the receiver only (G1).** `PairingSessionState`
   gains a waiting shape for the initiator. `DevicesScreen` renders the code with
   "Waiting for <device> to confirm…" and a Cancel button — no Confirm. Only
   `IncomingRequest` gets Confirm.
3. **Ignore `destinationHint` (G2).** Drop it from the enqueue call in
   `LinkDownloadBridge`. Keep the field on the wire format so a v1.6.0 peer still
   parses, but never act on it.
4. **Purge the outbox on unpair (C8).** `Outbox.forgetDevice(deviceId)`, called
   from `DeviceLinkManager.unpair` beside the existing `replayGuard.forget`.

**Tests** (`jvmCommonTest`, beside `LinkHardeningTest`):

- An `accepted` packet on a link that is not the active pairing link does **not**
  add a paired device.
- An `accepted` packet received while `Idle` does **not** add a paired device.
- The existing `IdentityAndRenameTest` loopback pairing still completes, with
  confirmation driven only from the receiving side.
- `unpair` leaves no outbox items for that device.

**Done when:** a scripted peer that sends `{"pair":true,"accepted":true}`
immediately after the handshake is refused, and normal pairing between two real
devices still requires a tap on the receiving device.

---

## Stage 2 — Make what we execute verifiable

1. **Verify the update artifact (G3).** Require a `SHA256SUMS` asset (or a hash
   in the release body) on the release; verify the staged file before offering
   Restart & Apply. A mismatch surfaces as an error, and the staged file is
   deleted.
2. **Refuse to corrupt the packaged install (G3).** Detect that
   `codeSource.location` sits inside a jpackage `app/` directory. In that shape,
   do not copy: report that an installer update is required and link the release.
   Also fix the non-Windows path so it reports "unsupported on this platform"
   rather than silently quitting.
3. **Verify yt-dlp (G3).** Check the download against the release's published
   `SHA2-256SUMS`.
4. **Pin FFmpeg (G3).** Move off `releases/download/latest/` to a specific tagged
   build with its hash recorded in the repo, updated deliberately.
5. **Managed binary only (G4).** Remove the `PATH` search from
   `getYtDlpExecutable` and `getFfmpegExecutable`.
6. **Restore certificate verification (G5).** Remove `--no-check-certificates`
   from both invocations in `DownloadEngineDesktop`.
7. **Honest engine state (C7).** A failed FFmpeg download sets an explicit
   "merger unavailable" state; `_isReady` stays false until it is present.

**Done when:** a tampered staged artifact is rejected, an EXE-distribution
install is told to use the installer rather than being overwritten, and a
download still succeeds with certificate checking on.

---

## Stage 3 — Honest remote downloads

1. **Apply the format hint (§0.2).** Map `formatHint` to the enqueue call on the
   receiving side, so the sender's resolution choice is what downloads.
2. **Scope the watcher (§0.3).** Collect a per-item flow rather than the whole
   list; `distinctUntilChanged` on the fields actually transmitted; throttle to
   the ~600 ms cadence `DownloadManager` already uses for metrics.
3. **Cancel on terminal status (§0.3).** Cancel the job, then remove it. Make
   `activeRemoteWatchers` a `ConcurrentHashMap`.

**Done when:** a 1080p request from the phone downloads 1080p on the desktop, and
a completed remote download stops emitting packets.

---

## Stage 4 — Take the write path off the hot loop

1. **Last-seen in memory (G6).** Keep a `ConcurrentHashMap<String, Long>` on
   `DeviceLinkManager`; persist to `TrustStore` on disconnect, on unpair, and on
   a coarse (≥60 s) debounce. The UI reads the in-memory value merged over the
   persisted one, so relative times stay live.
2. **Serialise history persistence (§0.4).** Move `persistDownloads` out of the
   `update` lambda onto a single-threaded writer that coalesces bursts.
3. **Hold one broadcast socket (P3).** Open it in `startActiveAnnouncing`, close
   it in `stopActiveAnnouncing`.

**Done when:** a remote download on the phone produces no per-packet disk writes,
and last-seen still reads correctly after a force-stop.

---

## Stage 5 — Android entry points and URL precision

1. **Route shares through app state (§0.6).** A shared link goes into a pending
   state the app observes, not the clipboard. Handle `onNewIntent` so a share
   into a running Courier opens the picker.
2. **Honour the folder argument (§0.6).** `openFolder` uses the path it is given.
   Where the platform will not open a directory, fall back to opening the file's
   location via `MediaStore` rather than lying about success.
3. **Tighten detection (G9).** `isSupportedVideoUrl` matches known platforms, the
   explicit site list, and direct media extensions only. Fetch stays permissive.
4. **Single video by default (G7).** Strip `&list=` in `cleanUrl`, pass
   `--no-playlist`, and when a playlist parameter was present show a "part of a
   N-video playlist — download all?" toggle in the picker.

**Done when:** sharing to an already-open Courier opens the picker with the
clipboard untouched, and a link copied from inside a playlist downloads one video.

---

## Stage 6 — Trustworthy output attribution and delete

1. **Constrain the fallback (§0.7).** Only adopt files created after this pass
   started; if none match, fail with "output file could not be located" rather
   than adopting a stranger.
2. **Split the actions (G8).** Trash removes the history row only. "Delete file"
   moves to an overflow menu with a confirmation naming the file.
3. **Fix the dead audio branch (C9)** and **cap the thumbnail cache (C11)** —
   SHA-256 key, LRU eviction over a size budget.

**Done when:** deleting a history row leaves the media on disk, and a download
whose output cannot be identified reports that instead of claiming an unrelated
file.

---

## Stage 7 — Adaptive layout

1. **One width class (G10).** Resolve `compact | medium | expanded` at the root
   from `BoxWithConstraints`, expose via `CompositionLocal`. Existing
   `isAndroid()` layout checks migrate to it.
2. **Two download-row compositions.** Compact stacks thumbnail and metadata with
   actions in an overflow; expanded keeps today's row. Title gets two lines on
   compact.
3. **Content max-width and two-column list** on `expanded`.
4. **Picker repairs (§0.8).** Real thumbnail via `NetworkImage`; file size from
   `fileSizeBytes` on each row; the dialog column scrolls with the action row
   pinned.
5. **Send-to-device confirmation (U5).** Reuse the Devices banner: sent, queued
   for later, or failed.
6. **Device cards lead with type and name (U7)**, IP demoted to a detail line.

**Done when:** the app is usable at 360 dp and does not waste a 1200 dp window,
and the picker's Download button is always reachable.

---

## Stage 8 — Settings, pause/resume, and media options

1. **Restructure Settings (G11).** Grouped, collapsible sections; a **Device
   Link** section carrying rename, enable/disable and paired count; replace the
   Swing `JFileChooser` with a Compose picker matching the app's chrome.
2. **Pause and resume (G11).** Pause terminates the process but keeps the item in
   a `PAUSED` state with its partial file; resume re-runs yt-dlp, which continues
   from the `.part`. Distinct from Cancel.
3. **Subtitles, chapters, metadata (G11).** Settings toggles for
   `--write-subs` / `--embed-subs` (with a language list), `--embed-chapters`,
   `--embed-thumbnail` and `--embed-metadata`, threaded through `DownloadItem` so
   a queued item keeps the settings it was created with — the same pattern
   `outputProfile` already uses.

**Done when:** a paused download resumes without re-downloading, and a YouTube
video arrives with its subtitles, chapters and thumbnail embedded.

---

## Stage 9 — Release

1. `gradlew publishRelease`; verify both artifacts report v1.7.0 (Build 24) in
   Settings.
2. README "What's New" for v1.7.0.
3. Tag `v1.7.0`.
