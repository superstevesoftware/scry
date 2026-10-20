# Developer guide

How to build, test and change Scry.

## Layout

```
core/       Go: the tunnel core, bound to Android with gomobile
  core/       the API the app calls (leases, status, upstream, identity, network info)
  tunnel/     userspace WireGuard on gVisor's network stack
  tstunnel/   an embedded Tailscale node (tsnet)
  proxy/      the loopback reverse proxies (one for the web view, one for background jobs)
  wgconfig/   wg-quick .conf parsing and validation
android/    Kotlin: the app (web view, settings, setup wizard, downloads, backup)
tools/      gen_licenses.py builds the in-app licence notices
docs/       the user guide and this guide
```

### How the pieces fit

- The Go core owns the tunnel, the keys and every connection to PhotoPrism. Kotlin hands over a configuration as JSON and never touches keys or sockets afterwards.
- The web view only talks to a **loopback** reverse proxy served by the core. The proxy rewrites hosts, origins, redirects and cookies, passes Range requests and WebSockets through, and only answers requests that carry a session cookie obtained from a one-time bootstrap secret. Background jobs (downloads, backup) use a second listener with its own token.
- The tunnel is held by **leases**: the screen takes one while it is showing, and each download or backup run takes its own. The tunnel stops shortly after the last lease is released. Nothing assumes the process survives.
- `Connector` (Kotlin) picks the route. If a local address is configured and the server's identity matches the one learned earlier over the VPN, it connects directly; otherwise it uses the VPN, which is started in parallel so a failed local check costs little.
- Scry never uses Android's `VpnService`.

## Prerequisites

- **Android Studio** (for the JDK and the SDK), with the Android SDK **platform 35** and **NDK** (r27 or newer; tested with 30).
- **Go** (see `core/go.mod` for the version) and **gomobile**:
  ```
  go install golang.org/x/mobile/cmd/gomobile@latest
  go install golang.org/x/mobile/cmd/gobind@latest
  gomobile init
  ```
- Set `ANDROID_HOME` to the SDK, and `ANDROID_NDK_HOME` to the NDK folder. Create `android/local.properties` with `sdk.dir=...` (Android Studio does this for you; the file is git-ignored).

## Build

1. Build the Go core into an Android library:
   ```
   cd core
   gomobile bind -target=android/arm64,android/amd64 -androidapi 29 -o ../android/libs/core.aar ./core
   ```
   Rebuild this whenever anything under `core/` changes. `android/libs/` is git-ignored.
2. Build and install the app:
   ```
   cd android
   ./gradlew :app:installDebug      # gradlew.bat on Windows
   ```
   Use a JDK 17 or newer (Android Studio's bundled JDK works; point `JAVA_HOME` at it).

## Test

```
cd core    && go vet ./... && go test ./...
cd android && ./gradlew :app:testDebugUnitTest
```

The Go tests cover config parsing, the proxy (host checks, cookies, Range, WebSockets, rewriting), the lease logic and identity checks. The Android unit tests cover the backup progress tracker, batching and the multipart body.

Debug builds log proxy requests (paths and status codes only, never queries, headers or secrets) under the tags `Scry` and `GoLog`, and turn on the WireGuard and Tailscale engines' own logging. Release builds do not.

## Licences

The in-app notices are generated from the Go dependency graph. After changing dependencies:

```
go install github.com/google/go-licenses/v2@latest   # once
python tools/gen_licenses.py
```

The list of Android and Kotlin libraries in that script is maintained by hand; refresh it with `./gradlew :app:dependencies --configuration releaseRuntimeClasspath`.

## Notes for contributors

- Keep secrets out of logs, error messages, crash reports and the repository. Error text that reaches the screen must never contain key material.
- The user guide in `docs/USER_GUIDE.md` is linked from inside the app (the backup reminder points at its "Back up your photos" section). If you rename that heading, update `Links.kt`.
- The project is licensed under the GPL-3.0-or-later. By contributing you agree your contribution is under the same licence.
