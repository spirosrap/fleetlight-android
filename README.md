# Fleetlight for Android

Fleetlight is a fast, read-only Android companion for the Fleetlight macOS fleet monitor. It shows current machine health, simultaneous issue types, Linux update status, incidents, and recent metrics without placing SSH keys or administrative credentials on the phone.

This repository is the sanitized public edition. It contains no fleet names, addresses, endpoint defaults, tailnet details, device identifiers, or private credentials.

## Highlights

- Native Kotlin and Jetpack Compose Material 3 interface with light, dark, and dynamic color
- Issue-first Fleet view with separate Offline, Slow, Access, Alert, Update, and Restart signals
- Per-machine details for latency, health, resources, services, warnings, software versions, and restart status
- Read-only Linux Updates and Events tabs
- Up to four runtime-configured HTTPS endpoints; the freshest valid schema 1 response wins
- Automatic refresh every 60 seconds, manual refresh, atomic last-good caching, and clear stale/offline state
- `fleetlight://configure` deep link for private endpoint setup without compiling addresses into the app
- Optional stable release signing from an ignored properties file or environment variables

- Version: **1.0.0 (1)**
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

## Optional release signing

Debug builds and CI do not require a keystore. For a stable signed release, either create ignored `keystore.properties`:

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

Then run `./gradlew assembleRelease`. Keystores, properties, APKs, AABs, runtime feeds, and endpoint configuration are ignored by Git.

## Privacy and security model

The Android app can only download and display HTTPS snapshots. Version 1 deliberately has no package-update, restart, SSH, terminal, or remote-control actions. Transport authentication should be enforced by the operator's private network or HTTPS service; secrets should never be placed in endpoint URLs.

The privacy check rejects common secrets, private/tailnet addressing, home-directory paths, tailnet domains, keystores, runtime feeds, and endpoint configuration. GitHub Actions runs the privacy gate, unit tests, lint, and a debug build for every change.

## License

MIT. See [`LICENSE`](LICENSE).
