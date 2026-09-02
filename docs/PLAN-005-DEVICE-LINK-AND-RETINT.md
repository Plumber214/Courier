# Plan 005 — v1.5.0: Device Link and Palette Retint

**Status:** Ready to build — decisions settled
**Written:** 2026-09-01
**Target:** `courier.versionName=1.5.0`, `courier.buildNumber=22`
**Follows:** [`PLAN-004-V1.4.0-GLASS-AND-ANDROID.md`](PLAN-004-V1.4.0-GLASS-AND-ANDROID.md) — **shipped and tagged `v1.4.0`**

Two pieces of work:

1. **Palette retint** — purple and neon cyan out, deep steel blue-gray in.
2. **Device Link** — a Devices tab and a persistent, authenticated device-to-device
   channel. Paste a link on the phone, press download, it runs on the desktop.
   Plus shared clipboard.

---

## Decisions (settled — do not re-open)

| # | Decision | Consequence |
| --- | --- | --- |
| E1 | **Own `courier.*` protocol** on KDE Connect's architecture. No wire compatibility. | §1.1. Our own port pair; cannot collide with a real KDE Connect install. |
| E2 | **Paired devices enqueue downloads automatically.** No per-request confirmation. | §2.E. Trust is established at pairing, where an 8-char code is compared on both screens. A notification shows what arrived. |
| E3 | **A physical phone joins the test loop for this feature.** | An emulator sits behind NAT and never sees host LAN broadcasts. Discovery, roaming and Doze are untestable there. |
| E4 | **Palette: steel blue-gray, Material 3 dark conventions.** | §2.A. Exact values below. |
| E5 | **Desktop: close-to-tray, no autostart.** | §2.G. The link survives closing the window; after a reboot Courier must be launched manually. |
| E6 | **Clipboard sync ships in 1.5.0.** File push-back and remote queue control do **not**. | §2.F. Those move to 1.6.0. |
| E7 | **Retint stays in 1.5.0 as Stage A**, not folded back into 1.4.0. | 1.4.0 tuned the glass against the purple base, so glass re-tuning is expected work — see §2.A.5. |
| E8 | **Version 1.5.0, build 22.** | 1.4.0 shipped; this is the next minor. |

---

## Part 0 — Verified state, 2026-09-01

### 0.1 What 1.4.0 actually landed

All twelve stages are committed and tagged `v1.4.0`. Confirmed present:

- `courier.versionName=1.4.0`, build 21, `AppVersion` in sync.
- `abiFilters = ["arm64-v8a", "x86_64"]` — **`armeabi-v7a` was also dropped**, beyond
  the plan's baseline cut.
- `PlatformActions.chooseDirectory()` is now `suspend`;
  `DownloadLocationPickerDialog.kt` exists.
- `androidApp/.../DownloadService.kt` exists; manifest declares
  `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_DATA_SYNC`,
  `foregroundServiceType="dataSync"`, `launchMode="singleTask"`, and a real
  `@mipmap/ic_launcher`.
- `desktopApp/.../NativeWindowEffects.kt` exists, using
  `DWMWA_WINDOW_CORNER_PREFERENCE`, `DWMWA_SYSTEMBACKDROP_TYPE` and
  `DWMSBT_TRANSIENTWINDOW`. **JNA 5.14.0 is a dependency of `desktopApp`.**
- Glass constants retuned: `GlassBackground` is now `0xE6` with `Hazy` (`0xF2`)
  and `Sheer` (`0xCC`) alongside.

### 0.2 Three things this plan assumed that are **not** true

**Check these before designing around them again.**

1. **There is no shared `jvmMain` source set.** `shared/build.gradle.kts` still
   declares only `commonMain`, `commonTest`, `androidMain`, `desktopMain`.
   `FileStoreAndroid.kt` and `FileStoreDesktop.kt` remain separate and identical.
   1.4.0 Stage 5 did the atomic writes and backup recovery but skipped the
   consolidation. **This plan must create that source set itself** — see §1.2.
2. **JNA is in `desktopApp`, not in `shared/desktopMain`.** Anything in `shared`
   that needs it requires its own declaration.
3. **New dependencies are being added with hardcoded version strings**
   (`net.java.dev.jna:jna:5.14.0` in `desktopApp/build.gradle.kts`) rather than
   through `gradle/libs.versions.toml`. This plan adds three more dependencies;
   put all of them in the catalog and move JNA there while passing through.

### 0.3 The palette is untouched

`Theme.kt` still carries `PrimaryIndigo = #6C5CE7` and `AccentCyan = #00E5FF`.
Beyond those two, the retint must also cover:

- `CardBorderFocused` — `#00E5FF`, raw neon cyan.
- `GlassBorderGradient` — hardcodes `0x8000E5FF` cyan and `0x356C5CE7` indigo.
- `TextMuted` — `#A0A6C8`, described in-file as "frosted muted lavender".
- The surface ladder — `SurfaceCard #1B1E32`, `SurfaceVariantDark #262A48`,
  `PlayerSurfaceBg #0E1120` are all blue-violet, not neutral.
- **The glass constants themselves** — `GlassBackground` is `0xE6` over base
  `0B0D18`, a violet-tinted navy. Retinting the base changes every glass surface,
  which is why §2.A.5 re-runs the contrast check rather than trusting 1.4.0's.

### 0.4 Navigation is still two screens

`App.kt` remains a two-value `Screen` enum behind a `Crossfade`, with Settings
pushed from a Home button. The tab restructure in §2.D is unstarted.

---

## Part 1 — Protocol and architecture research

Sources at the end. This section is from the protocol references, not memory.

### 1.1 How KDE Connect works, and what we take from it

**Discovery.** A device broadcasts an identity packet over **UDP** carrying a
`tcpPort` field. KDE Connect uses ports **1714–1764**, default **1716**. A device
receiving the broadcast reads `tcpPort` and opens a **TCP** connection back.

**The TLS role inversion — the detail that breaks reimplementations.** After TCP
connects and identity packets are exchanged in cleartext, the connection upgrades
to TLS with the roles **inverted** relative to TCP. The KDE source carries the log
line *"Starting client ssl (but I'm the server TCP socket)"*: the device that
**accepted** the TCP connection becomes the TLS **client**; the device that
**initiated** it becomes the TLS **server**. Getting this backwards produces a
handshake failure with no useful error message.

**Identity fields:** `deviceId` (UUIDv4, hyphens → underscores), `deviceName`
(1–32 chars, excluding `"',;:.!?()[]<>`), `deviceType`
(`desktop`/`laptop`/`phone`/`tablet`/`tv`), `incomingCapabilities` /
`outgoingCapabilities`, `protocolVersion` (7, with 8 current).

**Packets.** Newline-delimited JSON, JSON-RPC-like:

```json
{ "id": 1693526400000, "type": "kdeconnect.clipboard",
  "body": { "content": "…" }, "payloadSize": 0, "payloadTransferInfo": {} }
```

Binary payloads are **not** inline — `payloadSize` plus `payloadTransferInfo`
(e.g. `{"port": 1739}`) tell the peer to open a **second socket** for the bytes.

**Pairing.** A `pair` packet with `pair: true` requests or accepts, `pair: false`
rejects or unpairs; 30-second timeout by convention. Both public keys are hashed
together and truncated to 8 characters for the user to compare on both screens. On
acceptance the peer certificate is stored against its `deviceId` and **pinned**,
checked on every subsequent connection. Nothing but a pair packet is accepted from
an unpaired device.

**Clipboard.** `kdeconnect.clipboard` (`content`) on change, and
`kdeconnect.clipboard.connect` (`content` + `timestamp`) at connection time, where
content is ignored unless the timestamp is newer. **That timestamp rule is what
stops two devices fighting over whose clipboard wins** — carry it over exactly.

**Per E1 we take the architecture, not the wire format:** UDP identity broadcast →
TCP dial-back → TLS upgrade with inverted roles → certificate pinning →
newline-delimited JSON → capability negotiation. Own `courier.*` namespace, own
port pair, so a real KDE Connect install on the same LAN can never collide.

### 1.2 Ktor cannot carry this, but both targets are JVM

Ktor's raw-socket TLS is **client-only**; server-side socket TLS is unimplemented
and deprioritised upstream. The handshake above requires one side to be a TLS
*server*, so Ktor networking is out.

Both Courier targets are JVM, so `java.net.ServerSocket` +
`javax.net.ssl.SSLSocket`/`SSLContext` behave identically on each. The transport
belongs in **one source set shared by `androidMain` and `desktopMain`**.

**That source set does not exist yet (§0.2) and the default hierarchy template
will not create it** — a set shared between `androidTarget` and `jvm("desktop")`
must be wired explicitly:

```kotlin
val jvmShared by creating { dependsOn(commonMain.get()) }
androidMain.get().dependsOn(jvmShared)
desktopMain.get().dependsOn(jvmShared)
```

Creating it, and moving the duplicated `FileStore` into it as the proving case, is
the **first task of Stage B**.

### 1.3 KDE Connect is fire-and-forget — this is the gap we close

Directly from the protocol reference:

> "Packets are sent by devices with no guarantee that they will be received, or
> that if received there will be a response."

No ack, no sequence numbers, no retry, no documented heartbeat. Handlers are
expected to be idempotent because that is the only defence available.

**"As reliable as KDE Connect" is therefore a weaker bar than "when you press that
button, it sends it."** Meeting the stated requirement means building a delivery
layer KDE Connect does not have. That is §1.5 and it is the point of the feature.

### 1.4 Vulnerabilities to design out

CVE-2020-26164 catalogued real failures in `kdeconnectd`. A reimplementation
inherits every one unless it designs them out from the first commit:

| Issue | Guard |
| --- | --- |
| Unbounded message length — bytes forever with no newline until OOM | Hard cap on control-packet size; drop the connection past it |
| Unlimited parallel TCP connections → fd exhaustion, 100 % CPU | Cap total and per-source-IP connections |
| Malformed message → infinite processing loop | Parse timeouts; never loop on unparseable input |
| Unbounded UDP processing — each spoofed packet allocates a table entry | Rate-limit per source; bound the discovered-device table |
| **Pairing hijack** — spoofed deviceId displaces an existing session | Never let an unauthenticated peer displace an established pinned session |
| **Unpair attack** — a cert mismatch unconditionally unpaired the real device | On mismatch: **refuse and warn. Never auto-unpair** |
| Identity broadcast leaked usernames and hostnames | Default `deviceName` must not embed the OS username |
| Auto-dialling any broadcast address enabled amplification | Only dial addresses on a local interface subnet |

### 1.5 The reliability layer

**Persistent connection.** Heartbeat every 30 s; socket read timeout 90 s. A
missed heartbeat tears the link down and reconnects rather than leaving a
half-open socket silently swallowing packets — the classic failure that makes
these tools feel unreliable.

**Reconnect.** Exponential backoff capped at ~30 s, **plus** an immediate retry on
network-change events (Wi-Fi join, interface up, screen unlock). Backoff alone
feels broken; the event-driven kick is what makes it feel instant.

**Outbox, at-least-once.** Every user action gets a monotonic `seq`, is
**persisted before the UI confirms**, and is retried on every reconnect until
acked. Press download with Wi-Fi briefly dropped and it lands when the link
returns instead of vanishing.

**Dedupe on receive.** Track the highest `seq` per `deviceId` and drop replays.
At-least-once plus idempotent handling gives effectively-once without two-phase
commit.

**Honest UI state.** Show which stage a request reached. "Queued locally, not yet
delivered" is a legitimate state and belongs on screen, not behind a spinner.

### 1.6 Android background reality

- The existing `DownloadService` is declared `foregroundServiceType="dataSync"`.
  A persistent device link should additionally declare **`connectedDevice`** with
  the matching `FOREGROUND_SERVICE_CONNECTED_DEVICE` permission. Extend that
  service; do not add a second one.
- **Doze still applies.** Screen off and idle suspends network regardless of the
  foreground service. Reconnect on wake is fast; a phone in deep doze is not
  reachable in real time.

**The headline use case avoids this entirely.** Scrolling Instagram means the
phone is awake and is the *sender*; the desktop is the *receiver* and does not
doze the same way. **Phone → desktop is the reliable direction, and it is the one
you asked for.** Desktop → phone with the phone asleep is best-effort — the outbox
holds it and it lands on wake. Label it honestly: a device shown "Connected" while
actually dozing is worse than one marked "Asleep — will deliver on wake".

### 1.7 Certificate generation

Self-signed X.509, CN = deviceId. **Start with BouncyCastle (`bcpkix-jdk18on`) on
both platforms** for one code path and one keystore format — this is what KDE
Connect Android does. If it clashes with the stripped BouncyCastle Android ships,
fall back to `expect`/`actual`: Android generates via `AndroidKeyStore` (a
`KeyGenParameterSpec` yields a self-signed cert natively, no dependency, key
non-exportable) and desktop keeps BouncyCastle.

### 1.8 Discovery fallbacks

UDP broadcast fails more often than expected: Windows Firewall blocks the bind by
default, and AP isolation on guest Wi-Fi drops broadcast entirely. Three tiers:

1. **UDP broadcast** — the fast path.
2. **mDNS / DNS-SD** (`jmdns`, works on both JVM targets) — survives some networks
   broadcast does not. KDE Connect added this for the same reason.
3. **Manual pairing by IP** — a text field. Unglamorous, and the only thing that
   always works. **Ship it in v1**; it is also how the feature gets debugged.

First desktop launch triggers a Windows Firewall prompt. Document the manual fix;
have the MSI add the rule if practical.

---

## Part 2 — Staged execution

Stage A is independent. Stages B → G are sequential.

### Stage A — Palette retint

Ships on its own; no dependency on Device Link.

**Target palette (E4):**

```
primary            #7D9BB8    steel blue
primaryContainer   #2C3E52
secondary          #8FA8B8    slate — replaces neon cyan
background         #0E1116
surface            #161A21
card               #1D222B
surfaceVariant     #262C37
text               #F1F4F8 / #C3CCD6 / #8B96A3
success            #6BBF8A
warning            #D4A857
error              #D97066
```

1. **Audit for hard-coded colours outside `Theme.kt` before starting.** A retint
   that misses call sites looks worse than no retint.
2. Retint every constant listed in §0.3 — not just `PrimaryIndigo` and
   `AccentCyan`, but `CardBorderFocused`, `GlassBorderGradient`, `TextMuted` and
   the violet-tinted surface ladder.
3. Rebase the glass constants (`GlassBackground`, `Hazy`, `Sheer`,
   `GlassBackgroundDeep`) onto the new neutral base, preserving the 1.4.0 alpha
   values (`0xE6` / `0xF2` / `0xCC`).
4. Desaturate status colours; leave `Platform.brandColorHex` alone — YouTube red
   and friends are third-party marks, not theme colours.
5. **Re-run 1.4.0 Stage 8's contrast check.** It was performed against the purple
   base and does not carry over. Body text must still clear 7:1 over a white
   wallpaper at the default `Frosted` opacity.

**Done when:** the app is retinted with no violet or neon remaining, contrast
re-verified, screenshots before/after at all three opacity settings.

---

### Stage B — Transport core

The hard part. No user-visible feature at the end of it — resist shortcutting.

0. **Create the shared JVM source set** (§1.2) and move `FileStoreAndroid`/
   `FileStoreDesktop` into it as the proving case. Everything below lives there.
   Add `bcpkix-jdk18on` and `jmdns` **via `libs.versions.toml`**, and move the
   hardcoded JNA coordinates into the catalog while passing through (§0.2).
1. `DeviceIdentity` — persisted `deviceId`, user-editable name, device type,
   capabilities. **Default name must not embed the OS username** (§1.4).
2. `CertificateStore` — self-signed X.509, CN = deviceId, PKCS12 persistence.
3. `TrustStore` — pinned certs and pairing state, written atomically (reuse
   1.4.0 Stage 5's atomic-write helper).
4. `Discovery` + `LinkServer` with **every §1.4 cap in place from the first
   commit.** Retrofitting limits onto a working transport never happens.
5. `SecureLink` — TLS upgrade with **inverted roles** (§1.1). Expect to lose time
   here; it is the least intuitive part of the design.
6. `PairingManager` — 8-char verification code from both fingerprints, shown on
   both devices. **On cert mismatch: refuse and warn, never auto-unpair.**

**Acceptance:** two instances on one LAN discover each other, pair with matching
codes, hold TLS with heartbeats for 10 minutes without dropping, and reconnect
within 5 s of a Wi-Fi bounce. Confirm by packet capture that nothing after the
identity exchange is cleartext.

---

### Stage C — Reliability layer

1. `Outbox` — persisted, sequenced, retried until acked (§1.5).
2. `courier.ack` and per-device high-water-mark dedupe.
3. Heartbeat, read timeout, backoff, network-change kick.
4. Connection state machine exposed as a `StateFlow` for the UI.

**Packet types:**

```
courier.identity          deviceId, deviceName, deviceType, capabilities, protocolVersion, tcpPort
courier.pair              pair: Boolean, timestamp
courier.ack               ackSeq
courier.ping              heartbeat
courier.clipboard         content, timestamp
courier.download.request  url, seq, formatHint?, audioOnly?, destinationHint?
courier.download.accepted seq, localItemId
courier.download.status   localItemId, status, percent, title, error?
```

`request` → `ack` (received) → `accepted` (queued) → a stream of `status`. That
three-step is what lets the phone show **Sent → Delivered → Downloading → Done**
instead of hoping.

**Acceptance — this stage decides whether the feature is trustworthy:**
- Send with the peer's Wi-Fi off; restore it; the action lands unattended.
- Kill the peer app mid-send; relaunch; the action lands exactly once.
- Airplane-mode toggle 20 times: no duplicates, no losses, no zombie links.
- Both idle 8 hours: link still up, or cleanly reconnected.

---

### Stage D — Devices tab and navigation

1. Restructure `App.kt` (§0.4) into three destinations — Downloads · Devices ·
   Settings. **`NavigationBar` (bottom) on Android, `NavigationRail` (left) on
   desktop**: a wide window has horizontal room to spare and vertical room at a
   premium, and the custom title bar already owns the top 40 px.
2. Settings graduates from a pushed screen to a tab.
3. **Per-tab scroll state must survive tab switches** — losing your place in a
   long download list on every switch is an obvious regression.
4. Devices screen: discovered / paired / connected states, pair and unpair,
   verification-code dialog, per-device detail, **manual add-by-IP** (§1.8).
5. Honest state labels including "Asleep — will deliver on wake" (§1.6).

---

### Stage E — Remote download

The headline feature.

1. `courier.download.request` → validate through the existing `UrlValidator` →
   enqueue via `DownloadManager` → reply `courier.download.accepted`.
   **Paired devices are auto-accepted (E2)**; post a notification showing what
   arrived so nothing happens silently.
2. Mirror progress back with `courier.download.status`; render remote downloads on
   the sender with a "downloading on «device»" badge.
3. Sender UI: a device chooser on the download button — "Download here" vs.
   "Download on Desktop-PC".

**Acceptance (E3 — physical phone, real LAN):** paste an Instagram link on the
phone, choose the desktop, and the file appears in the desktop's download folder
with live progress mirrored on the phone.

---

### Stage F — Clipboard sync

1. `courier.clipboard` on change; the connect-time variant with **the timestamp
   rule** (§1.1) — that comparison is what stops two devices fighting.
2. **Android blocks background clipboard reads from API 29.** Auto-sync
   phone → desktop is impossible while backgrounded; a manual "push clipboard"
   button and receiving both work, and desktop → phone works normally.
   **This limit belongs in the UI**, not discovered by the user.
3. Per-device send/receive toggles, **defaulting to off** — silent clipboard
   exfiltration between devices is a genuine privacy surprise.

---

### Stage G — Desktop tray presence

Per E5: **close-to-tray, no autostart.**

1. Closing the window hides to a system tray icon; the link stays up. Compose
   Desktop's `Tray` composable covers this.
2. A real quit path from the tray menu, plus show/hide. **A window that cannot be
   genuinely closed is a bug, not a feature** — make Quit unambiguous.
3. Tray tooltip and icon state reflect link status (connected / disconnected /
   downloading).
4. Settings toggle for "close to tray" vs "close to exit", so the behaviour is not
   forced on anyone.
5. **No startup entry** — after a reboot Courier must be launched manually before
   the phone can reach it. Say so in the Devices tab rather than letting people
   discover it.

---

### Stage H — Release

1. Update `README.md` and `VERSIONING.md`; bump to 1.5.0 / build 22 in **both**
   `gradle.properties` and `AppVersion.kt`.
2. `./gradlew publishRelease`; confirm `checkVersionConsistency` passes.
3. Re-run Stage E and F acceptance on the release build with the physical phone.
4. Tag `v1.5.0`. **Stop before tagging and report** — tagging is the user's call.

---

## Risks

- **Stage B is the schedule risk.** TLS role inversion, pinning and pairing are
  all subtle and none produce a visible feature. It will take longer than it looks.
- **A physical phone is required (E3).** The emulator is fine for UI work but
  cannot see host LAN broadcasts, so discovery, roaming and Doze are untestable
  on it. If the phone becomes unavailable, Stages B–F stall — flag it early
  rather than substituting emulator results.
- **Windows Firewall will block the first bind** and users will read that as the
  feature being broken (§1.8).
- **Scope.** This is larger than 1.4.0, and Stages B and C produce nothing
  demo-able — exactly the stages it is tempting to rush, and exactly the ones that
  decide whether the result is trustworthy.
- **Licensing.** KDE Connect is GPL. **Implement from the protocol documentation;
  never copy KDE Connect source into this repo.** Protocols are not copyrightable;
  implementations are.

---

## Deferred to 1.6.0

- **File push-back** — desktop finishes a download, offers to send the file to the
  phone. Needs the second-socket payload mechanism (§1.1) with its own progress
  and resume handling.
- **Remote queue control** — cancel/retry/clear the other device's queue.
- Everything still listed as deferred in PLAN-004 (UI polish, light theme,
  playlist batch, subtitles, clip extraction, full SAF).

---

## Sources

- [Valent — KDE Connect Protocol Reference](https://valent.andyholmes.ca/documentation/protocol.html)
- [KDE/kdeconnect-meta — protocol.md](https://github.com/KDE/kdeconnect-meta/blob/work/protocol-schemas/protocol.md)
- [KDE Connect iOS Dev Diary (2): Identity Protocol](https://blog.inoki.cc/2020/04/19/KDEConnect-iOS-dev-dairy-2/)
- [oss-sec: CVE-2020-26164 — multiple security issues in kdeconnectd](https://seclists.org/oss-sec/2020/q4/56)
- [KDE Connect — KDE UserBase Wiki](https://userbase.kde.org/KDEConnect)
- [KDE/kdeconnect-kde — lanlinkprovider.cpp](https://github.com/KDE/kdeconnect-kde/blob/master/core/backends/lan/lanlinkprovider.cpp)
- [KTOR-4085 — Multiplatform Client/Server SSL(TLS) configuration](https://youtrack.jetbrains.com/issue/KTOR-4085/Multiplatform-Client-Server-SSLTLS-configuration)
