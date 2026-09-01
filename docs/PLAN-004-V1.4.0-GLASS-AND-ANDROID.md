# Plan 004 — v1.4.0: Desktop Glass, Android Locations, and Queue Correctness

**Status:** Ready to build — ordered for sequential execution
**Written:** 2026-09-01
**Scope decisions taken:** 2026-09-01 (see "Decisions" below — these are settled)
**Target:** `courier.versionName=1.4.0`, `courier.buildNumber=21`
**Follows:** [`PLAN-003-ANDROID-AND-HARDENING.md`](PLAN-003-ANDROID-AND-HARDENING.md)

Two user-reported problems drive this release:

1. The desktop window is too see-through and its corners are not actually round.
2. Android "Add Location" in Settings does nothing.

Investigating those surfaced a set of **live correctness bugs that are worse than
either reported symptom** — the advertised speed meter has never worked, and
queued downloads silently lose the quality and destination the user picked.
Those are Stages 2–3 and must not slip to 1.5.0.

**How to use this document.** Part 0 is the evidence base: every root cause was
established by reading the code on 2026-09-01 and is cited by file and line.
**Do not re-investigate Part 0.** Part 1 is the execution order — work Stage 0
through Stage 12 in sequence. The ordering encodes real dependencies; do not
reorder without reading "Why this order" in each stage.

---

## Decisions (settled — do not re-open)

| # | Decision | Consequence |
| --- | --- | --- |
| D1 | **Native window effects: timeboxed.** Prototype the documented Win11 backdrop, fall back to `ACRYLICBLURBEHIND`, stop at a bounded effort. | Stage 9. The corner fix ships regardless of how the blur lands. |
| D2 | **1.4.0 cuts after size/perf work.** | Release is Stage 12. General UI polish and the remaining feature menu are deferred to 1.5.0. |
| D3 | **Android locations: five media roots + subfolder + mandatory write probe.** Full SAF is out of scope. | Stage 6. No new permissions, no engine change. |
| D4 | **Default window opacity: Frosted, 90 % (`0xE6`).** Solid/Frosted/Sheer all ship as settings. | Stage 8. |
| D5 | **Three items promoted from the feature menu into 1.4.0:** resume interrupted downloads, Android foreground service + notification, Android launcher icon. | Stages 4 and 7. All three are closer to defects than features. |
| D6 | **ABIs: keep `armeabi-v7a`, `arm64-v8a`, `x86_64`. Drop `x86` only.** | Stage 0. `x86_64` is retained because verification runs on an emulator. |
| D7 | **No R8/minify.** The 208 MB is almost entirely native Python/FFmpeg payload, which R8 does not touch; reflection in youtubedl-android and kotlinx-serialization makes a missing keep rule a release-only runtime crash. Risk without reward. | Stage 11 ships ABI splits only. |
| D8 | **Android verification runs on an emulator.** | See "Emulator caveat" below — this bounds what Stages 6, 7 and 12 can claim. |

### Emulator caveat — read before claiming any Android stage complete

An emulator **can** verify: the location picker works, a directory is created,
the write probe returns the right answer, downloads land at the chosen path, the
foreground service survives backgrounding, the notification renders, the launcher
icon renders.

An emulator **cannot** verify: OEM storage restrictions (Samsung and Xiaomi are
the usual offenders), real-device behaviour at other API levels, thermal or
battery behaviour, actual throughput, or hardware codec availability.

Therefore:

- **Test Stage 6 on two API levels — 30 and 35.** Scoped-storage behaviour is
  precisely what changed between them, and it is the exact thing this stage
  depends on. One API level is not a verification.
- **Record the emulator's API level and ABI in every Android stage report**, so a
  later physical-device failure is diagnosable rather than mysterious.
- Say "verified on emulator API N", never "verified on Android". The difference
  is the residual risk, and it belongs in the report.
- FFmpeg transcodes are slow under emulation. Budget for it when exercising
  `EDITING_TRANSCODE`, or exercise that path on desktop instead.

---

## Part 0 — Verified state

### 0.1 Android "Add Location" is a hard-coded stub

`shared/src/androidMain/kotlin/courier/platform/PlatformActionsAndroid.kt:106`

```kotlin
override fun chooseDirectory(): String? {
    // Android downloads default to Downloads/Courier
    return null
}
```

The button in `SettingsScreen.kt:212` calls `viewModel.browseAndAddLocation()`,
which is `SettingsViewModel.kt:76`:

```kotlin
fun browseAndAddLocation() {
    val chosen = getPlatformActions().chooseDirectory()
    if (!chosen.isNullOrBlank()) { updateDownloadDirectory(chosen) }
}
```

`chosen` is always `null` on Android, the `if` never runs, and **nothing is
reported to the user**. The button is inert by construction, not broken by a bug.

The same dead call exists in two more places, so "Browse" is also inert on
Android inside both download dialogs:

- `QualityPickerDialog.kt:478`
- `PhotoPickerDialog.kt:314`

**The interface shape is also wrong for Android.** `chooseDirectory(): String?`
is synchronous. Any Android system picker (`ACTION_OPEN_DOCUMENT_TREE`) is an
`Activity` result — inherently asynchronous — so this signature cannot be
implemented on Android even in principle.

### 0.2 The Android engine writes real filesystem paths, not URIs

`DownloadEngineAndroid.kt:134-136` passes an absolute path to yt-dlp:

```kotlin
r.addOption("-o", "${outDir.absolutePath}/%(title).100s.%(ext)s")
```

This constrains the fix: a Storage Access Framework tree URI (`content://…`) is
**not usable** as a destination without a staging-and-copy step, because yt-dlp
writes through libc, not through `ContentResolver`. See Stage 6.

### 0.3 The desktop window is 70 % opaque with a rectangular window region

`Theme.kt:14`

```kotlin
val GlassBackground = Color(0xB30B0D18) // 70% opacity
```

`0xB3` = 179/255 = 70 %. That is the entire "frosting": a flat translucent fill
with **no blur of any kind**. What is behind the window is not softened, only
dimmed — which is exactly the reported complaint.

The corners are rounded only in Compose's *painting*, at `Main.kt:200`:

```kotlin
.clip(RoundedCornerShape(windowCornerRadius))
```

The **window itself** is still a rectangle. `Window(undecorated = true,
transparent = true)` at `Main.kt:73-74` gives per-pixel alpha, so the corner
pixels are transparent — but the window *region*, its shadow, and its
composition rectangle are unchanged. That is the "sharp angle behind the rounded
corners."

`Main.kt:64` forces a renderer:

```kotlin
System.setProperty("skiko.renderApi", "DIRECTX_12")
```

DirectX + per-pixel-transparent windows is a known-fragile combination in Skiko.
This may be contributing to the artifact and is the cheapest thing to rule out.

### 0.4 The speed meter and ETA have never worked

`DownloadManager.kt:264-296`:

```kotlin
var shouldUpdateMetrics = false
scope.launch {                          // <- asynchronous
    jobsMutex.withLock {
        if (now - lastUpdate >= 600L || progress >= 99f) {
            shouldUpdateMetrics = true
        }
    }
}
if (shouldUpdateMetrics) { … }          // <- read before the launch ever runs
```

`scope` is `CoroutineScope(Dispatchers.Default)` (`DownloadManager.kt:32`).
`Dispatchers.Default` always dispatches, so the coroutine body cannot have
executed by the time the `if` is evaluated on the calling thread — and the
captured `Boolean` has no memory barrier anyway. `shouldUpdateMetrics` is
effectively always `false`.

Consequence: `updateProgress` is always called on the `else` branch with
`speed = null, eta = null, downloaded = null, total = null`. In the `combine` at
`DownloadManager.kt:42-45`, `progress.speedFormatted ?: item.speedFormatted`
resolves to `item.speedFormatted`, which nothing ever sets. So
`DownloadItemCard.kt:257` renders `"42%"` and never `"42% • 3.2MiB/s"`, and
`DownloadItemCard.kt:263` never shows an ETA.

The README advertises "speed meter (`MB/s`), ETA". It has never shipped working.

Secondary defect in the same block: a coroutine is launched **on every progress
callback** — hundreds per download, thousands across a queue — purely to compute
a throttle flag that is then ignored.

### 0.5 Queued downloads lose the user's quality and destination

`DownloadManager.kt:223-241`:

```kotlin
private fun triggerQueueProcessing(preferredFormatId: String?, destinationDir: String?) {
    …
    val queuedItems = repository.downloads.value
        .filter { it.status == DownloadStatus.QUEUED }
        .take(slotsAvailable)
    for (queuedItem in queuedItems) {
        startDownloadJob(queuedItem, preferredFormatId, destinationDir)  // same args for all
    }
}
```

`formatId` and `destinationDir` are **call parameters, not item state**.
`DownloadItem` (`DownloadItem.kt:6-44`) has no `formatId` and no
`destinationDir` field. Three concrete failures follow:

| Trigger | Result |
| --- | --- |
| Enqueue B while A is queued | B's format/destination is applied to A |
| Queue more than `maxConcurrentDownloads` | Overflow items start via `triggerQueueProcessing(null, null)` — **default quality, default folder** |
| `retryDownload` (`:181`) | Passes `null, null` — retry silently downgrades quality and ignores the chosen folder |

The doc comment at `DownloadItem.kt:37-39` claims "a retry after restart
reproduces the original request exactly." It does not, because the two fields
that define the request are not on the item.

### 0.6 The concurrency limit is not reliably enforced

`DownloadManager.kt:336-340` registers the job in a **separate** coroutine:

```kotlin
scope.launch {
    jobsMutex.withLock { activeJobs[item.id] = job }
}
```

while `triggerQueueProcessing:227` counts slots from `activeJobs.size`. Between
starting a job and its registration landing, `activeJobs` under-reports, so a
burst of enqueues can start more concurrent yt-dlp processes than
`maxConcurrentDownloads` permits. The `queueMutex` does not prevent this — it
serialises the *checks*, not the *registrations*.

### 0.7 `removeDownload` races its own cancellation

`DownloadManager.kt:196-207` calls `cancelDownload(id)`, which launches a
coroutine that ends by writing `status = CANCELLED` back through
`repository.addOrUpdate`. `removeDownload` then synchronously calls
`repository.remove(id)`. If the cancel coroutine lands second, the item is
**re-inserted after deletion** — a zombie entry in history whose file is already
gone.

### 0.8 Disk I/O runs inside `MutableStateFlow.update` and on the UI thread

`DownloadRepository.kt:60-74` and `:101-116` call `persistDownloads()` — a full
JSON serialisation plus a synchronous `writeText` — **inside the `update`
lambda**. `update` is a compare-and-set loop: under contention the lambda
re-executes, so the entire history file can be serialised and written to disk
two or more times per state change.

`SettingsRepository.kt:32-40` writes synchronously on whatever thread called it,
which for every Settings toggle is the UI thread.

Neither write is atomic (`FileStoreDesktop.kt:12`, `FileStoreAndroid.kt:12` both
`writeText` in place), so a crash mid-write truncates settings or history.

### 0.9 yt-dlp is downloaded once and never updated

`BinaryManagerDesktop.kt:81-86` short-circuits when the binary exists:

```kotlin
if (ytDlp.exists() && ffmpeg != null && ffmpeg.exists()) {
    _isReady.value = true
    return@withLock Result.success(Unit)
}
```

`updateBinaries()` exists but only runs when the user presses the button in
Settings. The README claims the engine "self-updates". It does not. A stale
yt-dlp is the single most common cause of extractor failure, and this app will
degrade silently over weeks with no prompt.

### 0.10 Measured artifact sizes

```
release/Courier-Android-latest.apk   218,564,167 bytes  (208 MB)
release/Courier-Desktop-latest.jar    89,249,353 bytes  ( 85 MB)
```

The APK carries yt-dlp + Python + FFmpeg for **four ABIs**
(`androidApp/build.gradle.kts:23`): `armeabi-v7a, arm64-v8a, x86, x86_64`.
`isMinifyEnabled = false` (`:38`).

The desktop jar bundles **2,784 JavaFX entries** (`shared/build.gradle.kts:69-73`,
five `:win`-classified artifacts) for the in-app video player only.

### 0.11 Thumbnail cache is unbounded in bytes

`NetworkImage.kt:25-29` caps the cache at **50 entries** — not 50 megabytes.
Thumbnails are fetched at source resolution and decoded to `ImageBitmap` with no
downsampling; a 1280×720 ARGB bitmap is ~3.7 MB, so a full cache can hold
~185 MB of bitmaps. On Android that is an OOM risk. There is also no request
de-duplication (two cards with the same URL each issue a fetch) and no disk
cache, so every thumbnail is re-downloaded on every launch.

### 0.12 Dead source file

`desktopApp/src/jvmMain/kotlin/courier/desktop/Main.kt` (434 bytes) declares a
second `courier.desktop.main()` — a plain decorated window. `desktopApp` uses
the `kotlin("jvm")` plugin, whose source set is `src/main/kotlin`, so
`src/jvmMain` **is not compiled**. It is a stale leftover that will mislead
anyone opening the project.

### 0.13 Android app-shell gaps

- `AndroidManifest.xml:13` — launcher icon is `@android:drawable/sym_def_app_icon`.
  The app ships **with no icon of its own**.
- `AndroidManifest.xml:8` — `POST_NOTIFICATIONS` is declared but never requested,
  and no notification is ever posted.
- `MainActivity` has no `launchMode`, so a share intent starts a **new activity
  instance** rather than routing through `onNewIntent`.
- The share handler (`MainActivity.kt:31-42`) does not open the link — it writes
  it to the clipboard and relies on the clipboard banner to notice.
- There is **no foreground service**. Backgrounding the app can have Android kill
  an in-flight download with no notice.

---

# Part 1 — Execution order

Thirteen stages. Work them in sequence. Each ends with a build, a test run, a
commit, and a written report. Stages marked **[GATE]** stop for a human decision.

**Global rules**

- **One commit per stage**, message prefixed `Stage N:`. Never squash stages —
  the user needs to bisect this.
- **After every stage**: `./gradlew :shared:desktopTest` must pass, and both apps
  must still build. A stage is not done until both are true.
- **Never mark a stage complete on a successful compile alone.** Desktop changes
  need the app run; Android changes need an emulator install. PLAN-003 §0.1
  records an entire release cycle lost to exactly that assumption.
- **Part 0 is settled**, and so are the Decisions above. Do not re-derive either.
  If you find Part 0 is factually *wrong*, stop and report — do not quietly work
  around it.
- New behaviour gets a test where the surface is testable
  (`shared/src/commonTest`). Three of the Stage 2–3 bugs shipped because nothing
  asserted them.
- Android reports name the emulator API level and ABI (see Emulator caveat).

---

## Stage 0 — Baseline and cheap cleanup

**Why first:** the version bump makes every subsequent test build identifiable as
1.4.0 (PLAN-003 §0.1 documents a stale artifact being mistaken for broken code),
and cutting the APK now makes every later emulator install cycle faster. Both are
near-zero risk.

1. Bump to `1.4.0` / build `21` in **both** `gradle.properties` and
   `shared/src/commonMain/kotlin/courier/util/AppVersion.kt`. Set `RELEASE_DATE`.
   `checkVersionConsistency` fails the build if these drift.
2. Delete `desktopApp/src/jvmMain/` entirely (§0.12).
3. In `androidApp/build.gradle.kts:23`, set `abiFilters` to
   `armeabi-v7a, arm64-v8a, x86_64`. **Drop `x86` only** (D6) — `x86_64` is
   retained for emulator verification.
   *Optional, report before doing:* `armeabi-v7a` serves 32-bit ARM devices only,
   which is essentially nothing built in the last eight years. Dropping it would
   save more, but `minSdk = 24` technically still admits such devices. Measure
   the saving and ask rather than deciding unilaterally.
4. Record the **baseline** before touching anything else:
   - `./gradlew :shared:desktopTest` — note pass/fail count.
   - `./gradlew publishRelease` — note both artifact sizes.
   - Run the desktop app. Screenshot the window over a **high-contrast photo
     wallpaper**, close-up on a corner. This is the before-image for Stages 8–9.
   - Install on the emulator, confirm it launches and reports v1.4.0 in Settings.
     Record the API level and ABI.

**Done when:** both artifacts build at 1.4.0, APK is materially smaller than
208 MB, baseline screenshot saved, existing tests pass.

---

## Stage 1 — Desktop renderer diagnostic  **[GATE]**

**Why here:** five minutes of investigation that may resize Stage 9 to nothing.
Cheapest possible test of the leading hypothesis. **Write no production code.**

Launch the desktop app three times, overriding `Main.kt:64`:

```
-Dskiko.renderApi=DIRECTX_12   (current)
-Dskiko.renderApi=OPENGL
-Dskiko.renderApi=SOFTWARE_FAST
```

For each: screenshot a window corner over the same high-contrast wallpaper, at
100 % **and** 150 % display scaling. Note any difference in the rectangular
artifact, and note frame smoothness while dragging.

**Report the three screenshots and your read before proceeding.** If OpenGL
removes the sharp corner, Stage 9's native work may reduce to the corner-radius
call alone. That is a decision for the user, not for you.

---

## Stage 2 — `DownloadItem` schema migration (§0.5, plus groundwork for Stages 4 and 6)

**Why here:** Stages 2, 3 and 4 all rewrite `DownloadManager`. Doing the schema
change first means they edit a file that already has the right shape — and all
three schema needs are batched into **one** migration rather than three.

1. Add to `DownloadItem`, all with defaults so existing
   `courier_downloads.json` files still deserialise:
   - `formatId: String? = null`
   - `destinationDir: String? = null`
   - `outputPaths: List<String> = emptyList()`
   - `partialPath: String? = null` and `resumeAttempts: Int = 0` — **groundwork
     for Stage 4.** Add the fields now even though nothing reads them yet; a
     second migration later is strictly worse.
2. Set `formatId` and `destinationDir` once in `DownloadManager.enqueueDownload`.
3. **Delete** the `preferredFormatId` and `destinationDir` parameters from
   `triggerQueueProcessing` and `startDownloadJob`. Read them from the item.
   Deleting the parameters is the point — it makes the bug unrepresentable.
4. Have both engines return every file they wrote, not just the first
   (`DownloadEngineDesktop.kt:229` `allWritten.first()`;
   `DownloadEngineAndroid.kt:281`). Populate `outputPaths`; keep `outputPath` as
   the primary for display.
5. `removeDownload` deletes **all** of `outputPaths`. Today a 20-photo gallery
   leaves 19 files behind.

**Tests (required):**
- Enqueue 5 items with `maxConcurrentDownloads = 2`; assert each downloads at its
  own `formatId` into its own `destinationDir`.
- Assert `retryDownload` reproduces the original `formatId`.
- Assert an old `courier_downloads.json` without the new fields still loads.

**Done when:** tests pass, and a real 2-item queue at different qualities lands
two correctly-encoded files (check with `ffprobe`).

---

## Stage 3 — `DownloadManager` concurrency and lifecycle (§0.4, §0.6, §0.7)

**Why here:** three defects in one file. One pass avoids three rounds of
conflicting edits in the same 100 lines.

1. **Speed meter (§0.4).** Delete the `scope.launch` from the progress callback
   entirely. Replace the throttle with an inline per-item `@Volatile`/atomic
   timestamp read-and-write. Throttling a 600 ms UI update does not warrant a
   coroutine or a mutex.
2. **Concurrency limit (§0.6).** Register the job into `activeJobs`
   **synchronously, inside the same lock section that decided to start it**,
   before launching. Collapse `queueMutex` and `jobsMutex` into one lock — two
   mutexes guarding one invariant is what let them disagree.
3. **Remove/cancel race (§0.7).** Make `removeDownload` a single suspending
   sequence: cancel → await → remove. Have `cancelDownload` skip the `CANCELLED`
   write when the item is already gone.

**Tests (required):**
- A progress callback carrying a non-null speed produces a non-null
  `speedFormatted` on the emitted `DownloadItem`. *This is the assertion whose
  absence let §0.4 ship.*
- 20 simultaneous enqueues with `maxConcurrentDownloads = 2` never exceed 2
  concurrently `DOWNLOADING`.
- Remove-during-cancel leaves the item absent from history.

**Done when:** a real download visibly shows `"42% • 3.2MiB/s"` and a live ETA in
the running desktop app. Screenshot it.

---

## Stage 4 — Resume interrupted downloads  *(promoted, D5)*

**Why here:** immediately after the `DownloadManager` rewrite, while that code is
fresh and its schema fields (Stage 2.1) already exist. Doing this before Stage 5
also means the persistence work has the final shape of the data to optimise for.

The failure path already exists: `DownloadManager.kt:63-73` marks anything left
`DOWNLOADING`, `MERGING` or `FETCHING_INFO` as `FAILED` on restart, and the
`.part` file is orphaned on disk. This stage turns that into a resume.

1. Add `DownloadStatus.INTERRUPTED`, distinct from `FAILED`. Restart maps the
   three in-flight statuses to `INTERRUPTED`, not `FAILED`.
2. Pass `--continue` (and keep `--no-part` **off**) so yt-dlp resumes into the
   existing `.part`. Record `partialPath` when the engine reports a destination.
3. On resume, verify the `.part` still exists and belongs to the same URL and
   format before continuing; otherwise fall back to a clean restart. A stale
   `.part` from a different format produces a corrupt file, which is worse than
   re-downloading.
4. Cap `resumeAttempts` (3 is reasonable) and fall through to `FAILED` after
   that, so a permanently broken URL cannot loop.
5. UI: `INTERRUPTED` items get a "Resume" action distinct from "Retry". Retry
   restarts from zero; Resume continues. Both must be reachable.
6. Offer resume-all on launch when interrupted items exist, rather than
   auto-starting downloads the user may not want on a metered connection.

**Tests (required):**
- An item killed mid-download comes back as `INTERRUPTED`, not `FAILED`.
- Resume with a valid `.part` continues; resume with a mismatched `.part`
  restarts cleanly.
- `resumeAttempts` exceeding the cap terminates in `FAILED`.

**Done when:** on desktop, killing the app mid-download and relaunching resumes
the file to a byte-correct result (verify with `ffprobe`, not just file size).

---

## Stage 5 — Persistence hardening (§0.8)

**Why here:** Stages 2–4 settled what gets written; make writing safe before
building on top of it.

1. Move `persistDownloads` **out** of the `MutableStateFlow.update` lambda.
   Mutate state, then persist once from a single-threaded write dispatcher.
2. Make `saveTextFile` atomic: write `<name>.tmp`, then `Files.move` with
   `ATOMIC_MOVE`.
3. `FileStoreDesktop.kt` and `FileStoreAndroid.kt` are byte-identical — collapse
   into one shared `jvmMain` source set.
4. Debounce history writes; a burst of status transitions should not rewrite the
   whole file each time.
5. Cap history at ~500 entries with a documented trim policy.
6. Take `SettingsRepository.updateSettings` off the UI thread.

**Done when:** killing the app mid-download leaves both JSON files parseable — and
Stage 4's resume still works afterwards, which is the real test of both stages.

---

## Stage 6 — Android download locations (§0.1, §0.2) + composition I/O

**Why here:** the second reported bug. It rewrites `SettingsScreen`'s storage
section, so the composition-I/O fix is folded in rather than done as a separate
touch of the same code.

**Design, and why it is this design (D3).** Per §0.2 the engine writes filesystem
paths, so SAF tree URIs cannot be the primary mechanism. Per §0.1 the synchronous
`chooseDirectory()` cannot host an Android picker at all. The design that works
on every supported API level (24 → 35) with **no new permissions and no engine
change** is an in-app picker over known-writable roots:

- **Root** — Downloads, Movies, Music, Pictures, DCIM.
- **Subfolder** — free text, validated: no path separators, no `..`, no reserved
  characters.
- **Probe before saving** — `mkdirs()`, then create and delete a probe file. Only
  a location that demonstrably accepts a write gets added. **The probe is
  mandatory, not an optimisation** — writability outside app-private storage
  genuinely varies by API level and OEM, this code path has never been exercised,
  and an emulator cannot prove it for real devices (see Emulator caveat).

Arbitrary paths outside the media collections are deliberately **not** offered.

**Work:**
1. Change `PlatformActions.chooseDirectory()` to `suspend`. Desktop keeps
   `JFileChooser` wrapped in `withContext(Dispatchers.Swing)`; Android returns
   `null` and is no longer called.
2. New shared composable `DownloadLocationPickerDialog`: root chips + subfolder
   field + live resolved-path preview + probe-on-confirm.
3. `SettingsViewModel.browseAndAddLocation()` becomes state-driven — raises the
   dialog on Android, calls `chooseDirectory()` on desktop. **In either branch, a
   failure or a `null` must produce a visible message.** The silent no-op *is*
   the reported bug and must not survive in any path.
4. Wire the same dialog into `QualityPickerDialog.kt:478` and
   `PhotoPickerDialog.kt:314`, gated on `getPlatformActions().isAndroid()`.
5. Add a "no longer writable" state to the saved-locations list — an unmounted SD
   card currently surfaces as an opaque download failure.
6. **Fold in the composition-I/O fix:** `SettingsScreen.kt:179` calls
   `getDefaultDownloadDirectory()` during composition, and that method calls
   `mkdirs()` — a filesystem write on every recomposition. Hoist to the
   ViewModel, compute once. Also make `getPlatformActions()` a lazy singleton
   instead of allocating per call.

**Done when — verified on emulators at API 30 *and* API 35 (D8):** adding a custom
location succeeds, it appears in the list, downloading to it produces a file at
that path, and an unwritable location shows an error rather than doing nothing.
Report the API levels explicitly; do not write "verified on Android".

---

## Stage 7 — Android app shell: foreground service, notification, icon  *(promoted, D5)*

**Why here:** batched with Stage 6 so the Android verification cycles happen
back-to-back rather than being paid for twice. Depends on `DownloadManager` being
stable (Stages 2–4).

1. **Foreground service (§0.13).** Downloads currently die silently when the app
   is backgrounded. Add a foreground service bound to the queue's lifetime,
   started when the first download begins and stopped when the queue drains.
2. **Notification.** Progress notification with the active count and a cancel
   action. `POST_NOTIFICATIONS` is already declared (`AndroidManifest.xml:8`) but
   never requested — add the runtime request on API 33+, and handle refusal
   gracefully rather than assuming a grant.
3. **Launcher icon.** Replace `@android:drawable/sym_def_app_icon`. The app
   already has a visual identity — the white `VideoLibrary` glyph on a
   `PrimaryIndigo` rounded square used in the title bar (`Main.kt:222-233`) and
   home header (`HomeScreen.kt:138-151`). Derive an adaptive icon from that
   rather than inventing new artwork; report it for approval before finalising.
4. While in the manifest: set `launchMode="singleTask"` on `MainActivity` so a
   share intent routes through `onNewIntent` instead of spawning a second
   activity instance (§0.13).

**Done when:** a download started in the app survives backgrounding and screen
lock on the emulator, the notification shows live progress and cancels correctly,
and the launcher shows the Courier mark. Record the API level.

---

## Stage 8 — Desktop frosted glass, no native code (§0.3)

**Why here:** ships regardless of how Stage 9 lands, and is the fallback if
Stage 9's native route proves unworkable. Build the guaranteed win first.

1. Raise `GlassBackground` from `0xB3` (70 %) to **`0xE6` (90 %) — the new
   default (D4)**; add `GlassBackgroundHazy` at `0xF2` (95 %) and
   `GlassBackgroundSheer` at `0xCC` (80 %).
2. Replace the flat fill with a layered backdrop in `Main.kt`: a vertical
   `Brush.linearGradient` from `0xF20B0D18` to `0xE0141830`, plus a low-alpha
   diagonal sheen. Flat translucency reads as "dimmed window"; a gradient with a
   highlight reads as "pane of glass" at the same opacity.
3. Add a static noise/grain overlay at ~3 % alpha. This is the difference between
   "tinted" and "frosted" perceptually — Windows and macOS acrylic both include a
   noise layer — and it costs one tiling shader.
4. Add **Settings → Appearance → Window opacity**: Solid / Frosted / Sheer,
   persisted on `AppSettings`, defaulting to **Frosted**.
5. **Re-verify contrast.** Raising opacity changes the effective background;
   confirm body text still clears 7:1 over a white wallpaper.

**Done when:** side-by-side with the Stage 0 baseline screenshot, no shape behind
the window is identifiable at the default setting.

---

## Stage 9 — Native backdrop and true rounded window region (§0.3)  **[GATE]**

**Why here:** highest technical risk in the plan, and Stage 1's result may have
already reduced it. Everything else is done, so if this stalls, 1.4.0 still
ships. **Timeboxed by D1.**

Add `net.java.dev.jna:jna-platform` to `desktopMain` — verified **not currently
on the classpath** (0 `com/sun/jna/*` entries in the shipped jar), so this is a
new ~1.5 MB dependency.

**Do the corner fix first — higher confidence, and independent of the blur:**

```
DwmSetWindowAttribute(hwnd, DWMWA_WINDOW_CORNER_PREFERENCE=33, DWMWCP_ROUND=2)
```

This clips the actual window region and its shadow, which is the only way to
remove a rectangular remnant. Windows 10 fallback: `SetWindowRgn` with
`CreateRoundRectRgn`.

> `java.awt.Window.setShape()` is **not** a usable shortcut. It is documented as
> ignored on per-pixel-translucent windows, which is exactly what this window is.
> Do not spend time on it.

**Then the blur. Prototype both routes before committing to either:**

**A. Windows 11 system backdrop (preferred, documented).**
```
DwmSetWindowAttribute(hwnd, DWMWA_SYSTEMBACKDROP_TYPE=38, DWMSBT_TRANSIENTWINDOW=3)
DwmExtendFrameIntoClientArea(hwnd, MARGINS{-1,-1,-1,-1})
```
Real acrylic, DWM-composited, correctly throttled during drag.
**Known risk:** DWM backdrops are documented as incompatible with layered
(per-pixel-translucent) windows, and Compose's `transparent = true` makes the
window layered. Resolving this likely means `transparent = false` plus a
non-opaque Skia surface. **This is the single biggest unknown in the plan.**

**B. `SetWindowCompositionAttribute` with `ACCENT_ENABLE_ACRYLICBLURBEHIND` (fallback).**
Undocumented, stable since Windows 10 1803, and — unlike A — works on layered
windows, so it drops into the existing `transparent = true` setup unchanged.
Cost: Windows 11 throttles it during drag, producing visible blur lag.

Gate all of it behind `courier.desktop.nativeBackdrop` — default on, auto-off on
non-Windows and on any `UnsatisfiedLinkError`, falling back to Stage 8.

**Per D1, stop and report if route A resists.** Shipping route B, or shipping
Stage 8 plus the corner fix alone, are both acceptable outcomes for 1.4.0. Do not
spend unbounded effort forcing A.

**Done when:** over a photo wallpaper, no shape behind the window is
identifiable; no rectangular edge is visible at any corner at 100 % **and** 150 %
scaling; drag stays above 50 fps; the app still starts with the flag off.

---

## Stage 10 — Engine auto-update (§0.9)

1. Add `lastEngineUpdateCheckEpochMs` to `AppSettings`.
2. On launch, if older than 7 days, run the update in the background and surface
   the result quietly.
3. Show "last checked" in Settings so a stale engine is visible, not inferred.
4. Either make the README's "self-updates" claim true, or **delete the claim**.

**Done when:** a forced-stale timestamp triggers an update on next launch, and
Settings reports the new version.

---

## Stage 11 — Size and performance

**No R8/minify (D7).** Do not enable it, and do not spend Android verification
budget on it.

1. **APK — ABI splits.** Configure splits so each device gets one architecture's
   payload. **Watch out:** `publishAndroidVersioned` and `publishAndroidLatest`
   (`androidApp/build.gradle.kts:67-79`) copy `*.apk` and `rename` to a single
   fixed filename. With splits producing three APKs that rename becomes
   ambiguous and will silently pick one. Fix the publish tasks in the same
   change, or splits will quietly break the release pipeline.
   Also re-evaluate `useLegacyPackaging = true` — `false` avoids install-time
   native-lib extraction on API 23+.
2. **Desktop jar (§0.10):** 2,784 JavaFX entries exist for the in-app video
   player alone. Narrow to the modules `PlatformVideoPlayerDesktop` actually
   uses, or evaluate replacing JavaFX Media with the FFmpeg already shipped. Note
   in the README that the `:win` classifiers make the jar Windows-only.
3. **Thumbnails (§0.11):** downsample on decode to ~320 px wide; bound the cache
   by **bytes** (~24 MB) not entry count; de-duplicate in-flight requests; add a
   small disk cache under app storage so restarts do not re-download.

**Done when:** both artifact sizes recorded against the Stage 0 baseline, the
release pipeline still produces correctly-named artifacts, and the Android app
survives scrolling a 100-item history without an OOM.

---

## Stage 12 — Release  **[GATE]**

1. Update `README.md`: the speed meter now works; downloads resume; the engine
   self-updates (or the claim is gone); Android has custom download locations, a
   background service and an icon; the desktop jar is Windows-only; note the new
   appearance setting.
2. Update `VERSIONING.md` if the release process changed.
3. `./gradlew publishRelease` — confirm `checkVersionConsistency` passes and that
   Stage 11's ABI splits did not break artifact naming.
4. Record final artifact sizes against the Stage 0 baseline.
5. Re-run the Stage 6 and Stage 7 acceptance checks against the **release**
   build, not a debug build.
6. Tag: `git tag -a v1.4.0 -m "Release v1.4.0 (Build 21)"`.

**Stop before tagging and report.** Tagging and pushing is the user's call.

**Carry into the release notes:** every Android claim is emulator-verified only.
Say so.

---

## Deferred to 1.5.0 — do not build without explicit sign-off

Kept here as the standing backlog. **Nothing in this section is in scope for
1.4.0** (D2).

**UI polish**

1. Search and filter on the download list — a 200-item history is currently an
   unfiltered `LazyColumn`.
2. Human-readable failures — `ExtractionError.friendlyMessage` covers only the
   analyse path; the download path writes raw yt-dlp stderr onto the card
   (`DownloadManager.kt:321`).
3. Desktop keyboard shortcuts — Ctrl+V paste-and-analyse, Enter, Esc, Ctrl+,.
4. Drag-and-drop a link onto the window.
5. Taskbar progress — `java.awt.Taskbar.setWindowProgressValue`, pure JDK.
6. Native Windows file dialog via the JNA dependency Stage 9 adds — `JFileChooser`
   is a 1998 Swing dialog in the middle of a glass UI.
7. Accessibility — most interactive surfaces are `Box` + `clickable` with no
   `contentDescription`; the 20 dp `RadioButton` at `SettingsScreen.kt:281` is
   below the 48 dp minimum.
8. Light theme — `CourierTheme` hard-codes `darkColorScheme` (`Theme.kt:52-81`).
   A light *glass* variant is a genuine palette project, not a flag.

**Features**

| Feature | Why it fits | Cost |
| --- | --- | --- |
| Playlist / channel batch download | `Platform.kt` already recognises playlist URLs; the queue already handles N items. Mostly UI. | M |
| Subtitle download | `--write-subs --sub-langs` plus a language picker. Often the whole reason people reach for a downloader. | S |
| Clip extraction (start/end trim) | `--download-sections`. Fits the "Editing" framing the app leans on. | M |
| Chapter split | `--split-chapters`. Companion to the above. | S |
| Bandwidth cap / scheduling | `--limit-rate` and a quiet-hours window. | S |
| History export (CSV/JSON) | Data is already serialised; a file dialog and a formatter. | S |
| Duplicate detection | Warn when a URL is already in history. | S |
| Full SAF arbitrary folders | Deferred by D3. Requires staging + copy — a second full write of every file. | L |

---

## Risks carried through the whole plan

- **Stage 9 route A may be unimplementable as specified.** Route B and Stage 8
  exist because of this. Timeboxed by D1; 1.4.0 must not block on it.
- **Stage 6's writability guarantees cannot be proven on an emulator.** The
  probe-file check is what makes a real-device failure graceful instead of
  silent. Do not drop it, and do not overstate what the emulator proved.
- **Stage 2 changes the persisted schema.** Defaults keep old files loading, but
  verify against a real `courier_downloads.json` before shipping.
- **Stage 11's ABI splits interact with the release tasks.** See Stage 11.1.
- **Android is emulator-verified only this cycle.** Per PLAN-003 §0.1 the Android
  build has historically lagged desktop by whole releases; an emulator narrows
  that gap but does not close it.
