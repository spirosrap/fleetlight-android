# Fleetlight for Android

Fleetlight is a fast Android companion for the Fleetlight macOS fleet monitor. It shows current machine health, simultaneous issue types, update status, incidents, and recent metrics. An explicitly paired controller can also initiate allowlisted Codex CLI, Codex Mac app, and Linux OS update jobs or restart an eligible Linux machine without placing SSH keys or administrative credentials on the phone.

This repository is the sanitized public edition. It contains no fleet names, addresses, endpoint defaults, tailnet details, device identifiers, or private credentials.

## Highlights

- Native Kotlin and Jetpack Compose Material 3 interface with light, dark, and dynamic color
- Issue-first Fleet view with separate Offline, Slow, Access, Alert, Update, and Restart signals
- Per-machine details for latency, health, resources, services, warnings, software versions, and restart status
- Always-visible per-machine installed versions and availability for Codex CLI, Codex Mac app, and Linux OS, with individual Install or Update controls
- Authenticated **Check all** audit with determinate stage progress on compatible controllers, exact latest Codex versions, check freshness, Linux verification coverage, and resilient process-death recovery
- Sequential Update all for eligible updates, plus Linux restart controls that intentionally operate on exactly one machine at a time
- Exact confirmation before every update or restart, durable job progress, partial-result reporting, and idempotent recovery
- Read-only status and Events remain available without command pairing
- Up to four runtime-configured HTTPS endpoints; the freshest valid schema 1 response wins
- Lightweight fleet-snapshot refresh every 60 seconds, manual snapshot refresh, serialized crash-safe atomic last-good caching, and clear stale/offline state
- Safe retry of transient controller timeouts and rate limits using the original idempotency identity
- `fleetlight://configure` endpoint links without compiling private addresses into the app
- Optional stable release signing from an ignored properties file or environment variables

- Version: **1.4.3 (8)**
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

Each update sends one fixed action (`codex-cli`, `codex-mac-app`, or `linux-os`) and an exact list of eligible machine IDs. “Update all” is one server-side sequential job. A Linux restart uses the separate `restart-linux` action and is accepted only for exactly one machine that currently reports restart required; there is deliberately no restart-all control. Before restarting, Fleetlight names the target and warns that active work and services will be interrupted while the controller waits for the machine to go offline and return. A UUID request is persisted before submission; if a response is lost, recovery uses the same idempotency key rather than creating a second job.

The top refresh icon only reloads the lightweight fleet snapshot. **Updates → Check all** starts a separate authenticated, read-only controller audit that freshly probes installed versions, the Codex CLI registry, the official Codex Mac app feed, and configured Linux package sources. Compatible controllers report three bounded stages—Installed versions, Linux packages, and Publish results—so Android can show determinate completed/total progress and the current stage. Older controllers remain compatible and use indeterminate progress. Android polls the same idempotent request across disconnects or process restarts, safely retries transient timeouts and rate limits, and reloads the paired controller's exact feed before reporting completion. The audit never installs an update or restarts a machine.

## Feed contract

The app accepts lower-camel-case JSON with these top-level fields:

```json
{
  "schemaVersion": 1,
  "generatedAt": "2026-01-15T12:00:00Z",
  "observer": {},
  "summary": {},
  "hosts": [],
  "linuxUpdates": [],
  "incidents": [],
  "metrics": []
}
```

`schemaVersion` and a valid ISO-8601 `generatedAt` are required. Feeds dated more than five minutes in the future are rejected so a misconfigured or untrusted observer cannot indefinitely outrank healthy sources. Incidents are immutable event-log entries rather than active/resolved records. Unknown fields are ignored and optional fields default safely. See [`fixtures/demo-feed.json`](fixtures/demo-feed.json) for a complete generic example.

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
