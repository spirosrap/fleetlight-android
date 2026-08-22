# Fleetlight for Android

Fleetlight is a fast Android companion for the Fleetlight macOS fleet monitor. It shows current machine health, simultaneous issue types, update status, incidents, and recent metrics. An explicitly paired controller can also initiate allowlisted Codex CLI, ChatGPT Desktop App, and Linux OS update jobs or restart an eligible Linux machine without placing SSH keys or administrative credentials on the phone. Desktop-app status and updates cover both macOS and Linux machines.

This repository is the sanitized public edition. It contains no fleet names, addresses, endpoint defaults, tailnet details, device identifiers, or private credentials.

## Highlights

- Native Kotlin and Jetpack Compose Material 3 interface with light, dark, and dynamic color
- Issue-first Fleet view with separate Offline, Slow, Access, Alert, Update, and Restart signals
- Pinned-machine priority with a visible pin marker while preserving issue ordering within each priority group
- Per-machine details for latency, health, resources, services, warnings, software versions, and restart status
- Native Insights tab with Now, 1h, 6h, and 24h fleet rankings for Ping, SSH-ready, Checks, and Full probe timing, including Speed or Change ordering, current-vs-previous badges, evidence counts, and truthful biggest-improvement/slowdown callouts
- Cadence-aware gap-safe charts with shared tap-or-drag inspection for exact timestamps and values
- Always-visible per-machine installed versions and availability for Codex CLI, ChatGPT Desktop App on macOS and Linux, and Linux OS, with individual Install or Update controls
- Concise Update Center totals that keep actionable update operations, restart requests, offline machines, and machines needing a check distinct
- Authenticated **Check all** audit with determinate stage progress on compatible controllers, exact latest Codex versions, check freshness, Linux verification coverage, and resilient process-death recovery
- Sequential Update all for eligible updates, plus Linux restart controls that intentionally operate on exactly one machine at a time
- Exact confirmation before every update or restart, durable job progress, partial-result reporting, and idempotent recovery
- Read-only fleet-wide and per-machine rechecks, including offline targets, without installing or restarting anything
- Read-only recent operation receipts with timestamps and per-machine outcomes from the paired controller journal
- Read-only status and Events remain available without command pairing
- Up to four runtime-configured HTTPS endpoints; the freshest valid schema 1 response wins
- Lightweight fleet-snapshot refresh every 60 seconds, manual snapshot refresh, serialized crash-safe atomic last-good caching, and clear stale/offline state
- Safe retry of transient controller timeouts and rate limits using the original idempotency identity
- Failure-isolated controller notices, so a healthy fallback feed stays live while an unavailable paired update controller is explained only in Updates and Settings
- `fleetlight://configure` endpoint links without compiling private addresses into the app
- Optional stable release signing from an ignored properties file or environment variables

- Version: **1.15.2 (24)**
- Application ID: `app.fleetlight.mobile`
- Minimum Android: 8.0 / API 26
- Compile and target SDK: 36

## Configure a feed

Open **Settings**, add one or more complete HTTPS feed URLs, then choose **Save & refresh**. HTTP, URL credentials, and fragments are rejected.

Endpoints can also be proposed with a private deep link. Repeat `endpoint` for fallbacks:

```text
fleetlight://configure?endpoint=https%3A%2F%2Fobserver.example%2Fmobile-feed.json&endpoint=https%3A%2F%2Fbackup.example%2Fmobile-feed.json
```

Fleetlight always shows an in-app confirmation before a deep-link endpoint is saved or contacted. No endpoint is included in the application package. Endpoint settings and cached feeds stay in Android internal app storage.

## Pair update controls

Enable Android command authority in Fleetlight on the observer Mac and generate an 8-digit, short-lived pairing code. In Android **Settings → Update controller**, enter that observer's feed endpoint and the code. Fleetlight shows an explicit confirmation before exchanging it.

The app derives the same-origin control route while preserving the feed prefix: `/fleetlight/mobile-feed.json` becomes `/fleetlight/control/v1`. The paired controller is pinned independently from whichever observer supplies the freshest status feed, so feed failover never redirects a command.

Each update sends one fixed action (`codex-cli`, the backward-compatible `codex-mac-app` desktop-app action, or `linux-os`) and an exact list of eligible machine IDs. The desktop-app action covers supported macOS and Linux targets; Android also accepts the generic `codex-desktop-app` spelling in controller responses. “Update all” is one server-side sequential job. A Linux restart uses the separate `restart-linux` action and is accepted only for exactly one machine that currently reports restart required; there is deliberately no restart-all control. Before restarting, Fleetlight names the target and warns that active work and services will be interrupted while the controller waits for the machine to go offline and return. A UUID request is persisted before submission; if a response is lost, recovery uses the same idempotency key rather than creating a second job.

Modern controllers report desktop platform, provider, installed version, candidate version, explicit state, nullable availability, and per-machine check time. The state distinguishes Current, Update available, Not installed, Offline, and Unavailable; if both generic state and availability are missing or null, Android keeps the machine at **Check required** and excludes it from Update all. Update rows prefer the controller capability's `codexDesktopAppCheckedAt` and fall back to the feed host timestamp. Older controllers remain compatible: their explicit legacy `codexMacAppUpdateAvailable: false` continues to mean Current.

**Recheck fleet** and each machine's **Recheck** button use the separate read-only `refresh-hosts` action. Rechecks run through the same durable idempotent job journal, but they only request fresh probes; offline machines remain eligible and a fresh offline result is still a completed recheck. Because the action cannot install software or restart a machine, it starts directly without update or restart confirmation copy.

The top **Reload** button only downloads the latest lightweight fleet snapshot; it does not probe machines. **Recheck fleet** requests fresh host probes. **Updates → Check all** starts a separate authenticated, read-only controller audit that freshly probes installed versions, the Codex CLI registry, official ChatGPT Desktop App release sources for macOS and Linux, and configured Linux package sources. The controller-wide latest desktop version/build describes the macOS appcast; Linux candidates are reported per machine because package repositories may differ. Compatible controllers report three bounded stages—Installed versions, Linux packages, and Publish results—so Android can show determinate completed/total progress and the current stage. Older controllers remain compatible and use indeterminate progress. Android polls the same idempotent request across disconnects or process restarts, safely retries transient timeouts and rate limits, and reloads the paired controller's exact feed before reporting completion. Neither rechecks nor the audit install an update or restart a machine.

## Feed contract

The app accepts lower-camel-case JSON with these top-level fields:

```json
{
  "schemaVersion": 1,
  "generatedAt": "2026-01-15T12:00:00Z",
  "metricsWindowHours": 24,
  "metricsSampleIntervalSeconds": 1800,
  "observer": {},
  "summary": {},
  "hosts": [],
  "linuxUpdates": [],
  "incidents": [],
  "timingComparisons": [
    {
      "hostId": "generic-host",
      "metric": "ping",
      "windowHours": 6,
      "currentAverageMs": 24,
      "currentSampleCount": 12,
      "currentCoverageSeconds": 16200,
      "previousAverageMs": 31,
      "previousSampleCount": 12,
      "previousCoverageSeconds": 14400
    }
  ],
  "metrics": []
}
```

`schemaVersion` and a valid ISO-8601 `generatedAt` are required. `metricsWindowHours` reports how much history the observer intends to publish, and `metricsSampleIntervalSeconds` reports the effective cadence of the published, potentially downsampled series. Trends uses both with the samples' real timestamps to measure coverage, report freshness, and avoid drawing through missing intervals.

`timingComparisons` is optional. Each entry identifies one host, one metric (`ping`, `sshReady`, `checks`, or `fullProbe`), and a `windowHours` value. It carries the controller's verified average, sample count, and optional timestamp coverage in seconds for the selected interval ending at `generatedAt`, plus the immediately previous equal interval. Insights prefers coherent controller aggregates because a chart-oriented sample series may be downsampled; malformed average/count/coverage pairs fall back to raw history. Older aggregates without coverage remain compatible, but are visibly classified as Limited evidence rather than Strong.

Historical comparisons classify paired evidence as **Limited**, **Fair**, or **Strong**. Both periods need a valid average and positive sample count. Either period having fewer than two samples, unknown coverage, or less than 25% coverage is Limited; fewer than four samples or less than 65% coverage is Fair; otherwise it is Strong. Missing period pairs are Unpaired. With older feeds lacking controller aggregates, Insights falls back to `metrics`, treats a later duplicate host/timestamp as a correction, and uses non-overlapping current `[end-window, end]` and previous `[end-2window, end-window)` intervals. Raw coverage spans the earliest to latest canonical valid online sample in each period, is zero below two samples, and is clamped to the period length. Values and coverage must be finite and nonnegative; Checks also requires full-probe time to be at least SSH-ready time. Raw fallbacks visibly warn when declared history is short or unknown.

Hosts may set `isPinned: true`; pinned hosts appear first and retain issue-first ordering within the pinned group. Optional `codexDesktopAppPlatform`, `codexDesktopAppProvider`, `codexDesktopAppVersion`, `codexDesktopAppBuild`, `codexDesktopAppAvailableVersion`, `codexDesktopAppState`, tri-state `codexDesktopAppUpdateAvailable`, and `codexDesktopAppCheckedAt` fields describe the installed ChatGPT Desktop App on either macOS or Linux. Desktop state accepts `current`, `update-available`, `missing`, `offline`, or `unavailable`; only the explicit `missing` state is presented as **Not installed**. When generic state and availability are absent, null, or unrecognized, Android presents **Check required**. Provider values `macos-appcast` and `linux-apt` are shown as friendly signed-appcast and OpenAI-repository labels. Legacy `codexMacAppVersion`, `codexMacAppBuild`, and explicit `codexMacAppUpdateAvailable` booleans remain accepted, with an explicit legacy `false` still presented as Current. Feeds dated more than five minutes in the future are rejected so a misconfigured or untrusted observer cannot indefinitely outrank healthy sources. Incidents are immutable event-log entries rather than active/resolved records. Unknown fields are ignored and optional fields default safely. See [`fixtures/demo-feed.json`](fixtures/demo-feed.json) for a complete generic example.

## Build and test

Use JDK 17 and Android SDK 36:

```bash
export JAVA_HOME=/path/to/jdk-17
export ANDROID_HOME=/path/to/android-sdk
./gradlew --no-daemon testDebugUnitTest lintDebug assembleDebug
./scripts/privacy-check.sh
```

The debug APK is written to `app/build/outputs/apk/debug/app-debug.apk`.

## Stable release signing

Debug builds and CI do not require a keystore. Device releases must use the same stable signing key so updates preserve app data and encrypted controller pairing. Configure ignored `keystore.properties`:

```properties
storeFile=/absolute/path/to/fleetlight-release.jks
storePassword=use-a-secret-store
keyAlias=fleetlight
keyPassword=use-a-secret-store
```

or provide all four environment variables:

```text
FLEETLIGHT_ANDROID_KEYSTORE
FLEETLIGHT_ANDROID_STORE_PASSWORD
FLEETLIGHT_ANDROID_KEY_ALIAS
FLEETLIGHT_ANDROID_KEY_PASSWORD
```

Then run `./gradlew assembleRelease`. The release task fails if stable signing is absent; it never silently emits an unsigned device APK. Keystores, properties, APKs, AABs, runtime feeds, and endpoint configuration are ignored by Git.

## Privacy and security model

Fleet status uses credential-free HTTPS snapshots. Update control is optional and requires an explicit pairing confirmation. The resulting scoped bearer is encrypted with AES-GCM using a non-exportable Android Keystore key, bound to the exact controller base and controller identity, and excluded from Android backups. Control requests reject redirects and never fail over to another observer. The Mac accepts only known action enums and machine IDs; it retains all SSH keys, sudo access, package commands, and raw command output.

Every update and restart requires an exact in-app confirmation. Restart is independently constrained to one eligible Linux machine, with no fleet-wide restart action. Controls are disabled for cached or unavailable feeds, unpaired controllers, ineligible targets, and while a controller job is busy. Pairing codes are single-use and server-enforced with a short expiry and attempt limit. **Forget controller on this phone** removes the local encrypted credential; revoke the Android device in Fleetlight on the Mac to invalidate the server-side token.

The privacy check rejects common secrets, private/tailnet addressing, home-directory paths, tailnet domains, keystores, runtime feeds, and endpoint configuration. GitHub Actions runs the privacy gate, unit tests, lint, and a debug build for every change.

## License

MIT. See [`LICENSE`](LICENSE).
