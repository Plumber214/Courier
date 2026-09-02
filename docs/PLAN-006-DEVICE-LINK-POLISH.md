# Plan 006 — v1.6.0: Device Link Polish

**Status:** Ready to build — decisions settled
**Written:** 2026-09-02
**Target:** `courier.versionName=1.6.0`, `courier.buildNumber=23`
**Follows:** [`PLAN-005-DEVICE-LINK-AND-RETINT.md`](PLAN-005-DEVICE-LINK-AND-RETINT.md)

Three requested changes, plus defects found while verifying current behaviour:

1. **Scanning** — it runs continuously from app launch. Make it purposeful.
2. **Friendly device names** — user-settable, exchanged at pairing, shown
   instead of an IP, stable across networks.
3. **Clipboard** — replace the background sync with an explicit Send button.

---

## Decisions (settled — do not re-open)

| # | Decision | Where |
| --- | --- | --- |
| F1 | **Nothing runs until a paired device exists.** With zero paired devices the link subsystem is fully dormant — no listener, no broadcast, no ticks. | Stage 2 |
| F2 | **A received clipboard is applied, with a brief confirmation** ("Clipboard received from Pixel 9 Pro"). Not silent, not a prompt. | Stage 4 |
| F3 | **Broadcast a generic name; reveal the friendly name only over TLS after pairing.** Discovery advertises "Courier device" plus the short device id. | Stage 1 |
| F4 | **Wire the Android link to the foreground service.** Ongoing notification only while devices are paired, so non-users never see it. | Stage 3 |
| F5 | **Announce continuously while the Devices tab is open**, stop on leaving. Not a fixed burst. | Stage 2 |
| F6 | **Cross-network reachability is out of scope.** Paired-but-unreachable devices show name, last-seen and an honest offline state; the outbox holds queued work. No relay, no hole punching. | Stage 5 |
| F7 | **Desktop defaults to a generic "Courier Desktop", with a prompt to rename on first pair.** Never the hostname by default. | Stage 1 |
| F8 | **In scope:** ASLEEP + last-seen, discovery pruning, outbox hardening. **Out:** outbox visibility in UI, configurable port. | Stages 5–6 |

---

## Part 0 — Verified state, 2026-09-02

Read from the code today. Cited by file and line. **Do not re-investigate.**

### 0.1 Everything starts at app launch, whether or not you use Device Link

`App.kt:61` constructs the Devices view model at the top level of `App()`, not
per tab:

```kotlin
val devicesViewModel = remember { AppModule.provideDevicesViewModel() }
```

`DevicesViewModel.init` calls `linkManager.start()` (`DevicesViewModel.kt:31-33`),
and `AppModule.provideDevicesViewModel()` touches `clipboardSyncManager` to force
it alive (`AppModule.kt:73`). So opening Courier — to download one video, with no
paired devices at all — starts:

| Loop | Cost |
| --- | --- |
| UDP listener, blocking receive | a thread, always bound to port 1816 |
| UDP broadcast, `Discovery.kt:123` | every **10 s**, to 255.255.255.255 *and* every interface broadcast address, forever |
| mDNS register + service listener, `Discovery.kt:163` | jmdns instance, always registered |
| Reconnect tick, `DeviceLinkManager.kt` | every **1 s**, iterating paired devices, forever |
| Desktop clipboard poll, `ClipboardSyncManager.kt:34-40` | every **1.5 s**, reads the system clipboard, forever |

On Android the 10 s broadcast keeps waking the radio and the 1 s tick works
against deep idle. None of it is gated on having a paired device.

### 0.2 Discovery and reconnection are conflated

They are different jobs with different needs:

- **Discovery** finds devices you have *not* paired with. Only useful when the
  user is actually trying to add one.
- **Reconnection** re-establishes links to devices you *have* paired with. It
  needs to be prompt and reliable, and needs no broadcast at all — a paired
  device has a last-known address, and mDNS resolves it.

Today both run continuously and unconditionally.

### 0.3 The device name is a hardcoded constant

`DeviceLinkManager.kt:60-67`:

```kotlin
val defaultName = if (isAndroid) "Courier on Android" else "Courier on Desktop"
myIdentity = DeviceIdentity(deviceId = certStore.deviceId, deviceName = defaultName, ...)
```

- Not user-editable, not persisted, identical on every install.
- `myIdentity` is a `val` captured by `LinkServer` (`:100`) and `Discovery`
  (`:71`) at construction, so a rename cannot currently propagate anywhere.
- `PairedDevice.deviceName` is captured once at pair time. **A peer that renames
  itself never updates on your device.**
- `DiscoveredDeviceCard` (`DevicesScreen.kt:618-619`) leads with
  `"${device.hostAddress}:${device.tcpPort}"` — the list is IP-centric.

### 0.4 Pairing already survives a network change; connectivity does not

Trust is keyed on `deviceId` and a pinned certificate
(`TrustStore.validatePinnedCertificate`), neither of which involves an address.
So a paired device **stays paired** across Wi-Fi networks, reboots and IP changes.

What does not survive is *reachability*. Both transports are LAN-only: UDP
broadcast and mDNS do not cross subnets, and there is no relay. Two devices on
different networks remain paired and display their friendly name, but are
offline to each other, and the outbox holds anything queued until they share a
network again. Per F6 that is the intended behaviour, stated honestly in the UI.

### 0.5 Clipboard sync is a background poll with a per-device toggle

`ClipboardSyncManager.kt`:

- On desktop, a coroutine polls `getClipboardText()` every 1.5 s forever
  (`:34-40`) and pushes any change to every device with
  `isClipboardSyncEnabled` (`:77-82`).
- On Android the poll is skipped, because the OS blocks background clipboard
  reads from API 29 — so the feature is already half-manual there, and
  `DevicesScreen.kt:554` says so in the UI.
- Loop prevention is hash-based (`:71`, `:89`). The `timestamp` field is sent but
  never read on receive.

A manual Send removes the poll, the toggle, the loop-prevention machinery and
the privacy surprise in one move.

### 0.6 The Android link has no foreground service — the manifest says otherwise

`AndroidManifest.xml:13,48` declares `FOREGROUND_SERVICE_CONNECTED_DEVICE` and
`android:foregroundServiceType="dataSync|connectedDevice"`.

**`DownloadService.kt` contains zero references to the link.** Grep for
`link`/`Link`/`connectedDevice` returns nothing. The service is started and
stopped around downloads only.

So on Android the link is alive only while the app is foregrounded or a download
is running. The permission and service type were added in v1.5.0 Stage 7 in
anticipation and never wired. "Connected all the time" is not true on Android
today.

### 0.7 `ConnectionStatus.ASLEEP` is rendered but never set

Defined at `LinkProtocol.kt:99`, styled at `DevicesScreen.kt:452`, labelled
`"Asleep — will deliver on wake"` at `:459`. **Nothing ever assigns it.**
PLAN-005 §1.6 wanted this state so a dozing phone was not shown as "Connected";
the label exists, the logic does not. Devices show `DISCONNECTED` instead.

### 0.8 Discovered devices never expire

`Discovery.registerDiscoveredDevice` (`:205-216`) records `lastSeenEpochMs` and
nothing ever reads it. A device that leaves the network stays listed until the
app restarts. The `MAX_DISCOVERED_DEVICES` cap evicts `current.removeAt(0)` —
first-inserted, not least-recently-seen — so a long-lived real device can be
pushed out by churn.

### 0.9 Outbox retry is unbounded, and the sequence seed is fragile

- No maximum attempt count. A packet the peer always rejects is retried on every
  reconnect forever. v1.4.0 Stage 4 set the precedent of a bounded
  `resumeAttempts` for exactly this.
- `seqGenerator = AtomicLong(System.currentTimeMillis())` (`Outbox.kt:26`) is
  seeded from the clock, not the highest persisted seq. A backwards clock step
  reissues sequence numbers that `ReplayGuard` then discards as replays,
  silently dropping real requests.

### 0.10 The friendly name would be broadcast in cleartext

The identity packet is sent over **UDP broadcast before any TLS**
(`Discovery.sendUdpBroadcast`), and `deviceName` is one of its fields. mDNS is
worse: `Discovery.kt:168` uses the name as the **service instance name**, visible
to anything doing service discovery.

Today that leaks a constant. Once names are user-set, "Nathan's Laptop" would be
broadcast on every network you join, including public Wi-Fi — the exact leak
CVE-2020-26164 flagged in kdeconnectd. F3 prevents it.

---

# Part 1 — Execution order

Eight stages, in sequence. Each ends with a build, a test run, a commit and a
written report.

**Global rules**

- **One commit per stage**, prefixed `Stage N:`. Never squash.
- `./gradlew :shared:desktopTest` must pass and both apps must build after every
  stage.
- **A successful compile is not evidence.** Desktop changes get the app run;
  Android changes get installed.
- **Part 0 and the Decisions table are settled.** If Part 0 is factually wrong,
  stop and report rather than working around it.
- Device Link needs a **physical phone on a real LAN** (PLAN-005 E3). An emulator
  cannot see host broadcasts, so discovery, roaming and Doze — most of this plan
  — are untestable on it.

---

## Stage 0 — Version bump and baseline

**Why first:** VERSIONING.md now requires it. v1.5.0 left the bump until the last
stage, so eight stages of work were built and published as `v1.4.0` artifacts and
the genuine v1.4.0 build was overwritten. Bump first so every test build is
identifiable.

1. Bump to `1.6.0` / build `23` in **both** `gradle.properties` and
   `AppVersion.kt`. Set `RELEASE_DATE`. `checkVersionConsistency` enforces this.
2. Record the baseline: test count, both artifact sizes, and — for the power
   work in Stage 2 — a note of the current idle behaviour (broadcast every 10 s,
   reconnect tick every 1 s, clipboard poll every 1.5 s) so the improvement is
   measurable rather than asserted.

---

## Stage 1 — Identity and friendly names

**Why here:** `Discovery` and `LinkServer` both capture `myIdentity` at
construction (§0.3). Everything downstream depends on this shape, so it changes
first.

1. **Persist a user-settable name.** Stored alongside the device identity, not in
   `AppSettings` — it belongs with `deviceId` and the keypair.
   Defaults: Android `Build.MODEL` ("Pixel 9 Pro"); desktop **"Courier Desktop"**
   (F7). **Never the hostname** — it usually embeds the OS username.
2. **Make identity mutable.** Replace the immutable `val myIdentity` with a
   `StateFlow<DeviceIdentity>`, or have `LinkServer` and `Discovery` read it
   through a provider, so a rename takes effect without restarting.
3. **Split public and private identity (F3).** The UDP/mDNS announcement carries
   a **generic** name — "Courier device" plus the short device id — and never the
   friendly name. The friendly name is exchanged **over TLS during pairing** and
   stored in `TrustStore`.
   - mDNS service instance name must also be generic (§0.10).
4. **Propagate a rename** to peers: send an identity update over every live link;
   peers update `PairedDevice.deviceName` and persist it. This is what makes a
   rename visible on the other device rather than only locally.
5. **UI:** rename field for this device; prompt to rename on first successful
   pair (F7). Paired and discovered lists lead with the name, IP as secondary
   detail.

**Tests:** a rename reaches a connected peer and survives restart on both sides;
the UDP/mDNS announcement never contains the friendly name.

**Done when:** renaming on desktop updates the name shown on the phone while
connected, and a packet capture of discovery traffic shows only the generic name.

---

## Stage 2 — Lifecycle: gating, on-demand discovery, event-driven reconnect

The core of the request. Merges gating and discovery because they are one
question: *when should anything run?*

1. **Gate the subsystem on having a paired device (F1).** With none paired,
   nothing listens, broadcasts or ticks. Opening the Devices tab or pressing Scan
   wakes it. Move link startup out of `App()` (§0.1) so it is not tied to app
   launch.
2. **Announce while the Devices tab is open (F5)** — every ~1.5 s, stopping on
   leave. Not a fixed burst: pairing means walking to the other device with this
   one still on screen, and a burst would expire in transit.
3. **Keep the UDP listener on whenever the subsystem is awake.** It is a blocked
   thread costing nothing and it is what makes *this* device findable. Without it
   two devices could only meet if both scanned in the same instant.
4. **Scan button becomes honest.** Today `DevicesScreen.kt:327` shows
   "Scanning LAN for Courier devices…" permanently, because scanning is always
   true. It should reflect actual announcement activity.
5. **Reconnection is event-driven,** not a 1 s poll: on network change (already
   wired via `NetworkChangeMonitor`), app foreground, link drop, and manual Scan.
   Per-device backoff already exists and resets on success. Tick only while
   something is disconnected; idle completely when all links are up.
6. **Prune discovered devices** not seen for ~60 s, and evict by
   `lastSeenEpochMs` rather than insertion order (§0.8, F8).

**Done when:** with no paired devices and the Devices tab closed, a packet
capture shows **zero** Courier traffic and no periodic wakeups; with a paired
device connected, the reconnect loop is idle; and a Wi-Fi bounce still restores
the link within 5 s (the PLAN-005 Stage B criterion, which must not regress).

---

## Stage 3 — Android foreground service binding

**Why here:** depends on Stage 2 settling when the link should be alive.

1. Bind the link lifetime to `DownloadService`, which already declares
   `dataSync|connectedDevice` (§0.6). **Do not add a second service.**
2. Ongoing notification **only while devices are paired** (F4), so people who
   never use Device Link never see it.
3. Notification reflects link state and offers a way into the Devices screen.
4. Handle the `POST_NOTIFICATIONS` refusal path gracefully — a denied permission
   must not silently disable the link with no explanation.

**Done when — on a physical phone:** a download request sent from the desktop
arrives with the phone's screen off and Courier backgrounded, and the
notification disappears entirely once the last device is unpaired.

---

## Stage 4 — Clipboard: explicit Send only

1. **Delete** the desktop 1.5 s poll (`ClipboardSyncManager.kt:34-40`) and the
   hash-based loop prevention, which exists only to serve it.
2. **Delete** `isClipboardSyncEnabled` as an auto-send switch and the "Sync
   Clipboard" toggle (`DevicesScreen.kt:547`).
3. **Add a per-device Send Clipboard action** on the paired-device card — not the
   current broadcast-to-everyone `pushClipboardToPairedDevices()`, so it is never
   ambiguous where the clipboard went.
4. Confirm on send ("Sent to Pixel 9 Pro"); report clearly when the device is
   offline rather than silently queueing something expected to be instant.
5. **On receive (F2):** apply it and show a brief confirmation naming the sender.

**Done when:** no clipboard read happens without a button press, and sending
between two devices confirms on both ends.

---

## Stage 5 — Honest offline states

1. **Implement `ASLEEP` (§0.7).** The label is already written and styled. Set it
   when a paired device was recently connected but has become unreachable, so a
   dozing phone reads honestly instead of "Disconnected".
2. **Last-seen timestamp** on paired-but-offline devices.
3. **Offline devices still show their friendly name** from `TrustStore` (F6) —
   this is what makes a device on another network meaningful rather than a
   nameless dead row.

---

## Stage 6 — Outbox hardening

1. **Bound retries** with a maximum attempt count, then a terminal failed state
   surfaced to the user. Follows the `resumeAttempts` precedent from v1.4.0.
2. **Seed the sequence generator from the highest persisted seq** rather than the
   clock (§0.9), so a backwards clock step cannot reissue numbers the
   `ReplayGuard` will discard.

**Tests:** a permanently rejected packet terminates instead of retrying forever;
a simulated backwards clock does not cause dropped requests.

---

## Stage 7 — Release

1. Update `README.md` — scanning behaviour, friendly names, Send Clipboard, and
   the honest statement that Device Link is LAN-only (F6).
2. `./gradlew publishRelease`; confirm `checkVersionConsistency` passes.
3. Re-run Stage 2, 3 and 4 acceptance on the release build with the physical
   phone.
4. Tag `v1.6.0`. **Stop before tagging and report** — tagging is the user's call.

---

## Deferred

- Outbox visibility in the UI and a configurable port (F8).
- Cross-network reachability — relay or hole punching (F6).
- Trusted-network awareness, which would allow broadcasting the friendly name on
  home Wi-Fi only.
- Everything still deferred in PLAN-004 and PLAN-005.

---

## Risks

- **Stage 3 changes Android behaviour visibly.** A durable link requires an
  ongoing notification; F4 limits it to when devices are paired, but it is still
  new UI in the user's shade.
- **Stage 1 touches the identity path** captured by `LinkServer` and `Discovery`
  at construction. Getting it wrong risks announcing a stale name or breaking
  discovery — hence the test that a rename reaches a connected peer.
- **Stage 2 must not regress reconnection.** Making things lazier is exactly how
  "it stopped reconnecting" bugs appear. The 5 s Wi-Fi-bounce criterion is the
  guard.
- **A physical phone is required.** If it is unavailable, Stages 2–4 stall; flag
  it rather than substituting emulator results.
