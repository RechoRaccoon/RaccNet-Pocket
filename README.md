# RaccNet Pocket — Kotlin Multiplatform

One shared codebase that builds **both** the native Android app and the
website (WebAssembly). Migrated from the legacy Android-only app
(`RaccNet-Pocket-main`, Kotlin + Jetpack Compose, ~25k lines).

- **Android app** keeps its app-exclusive AI features (on-device image
  tagging, on-device translation).
- **Website** gets everything else, built from the same shared UI + logic.

## Module layout

```
kmp/
├── settings.gradle.kts          Root project ("RaccNetPocketKMP")
├── build.gradle.kts             Plugin versions only
├── gradle/libs.versions.toml    Central version catalog
├── gradle.properties
├── .github/workflows/build.yml  CI: APK + web -> GitHub Pages
│
├── shared/                      ★ ALL app code lives here
│   └── src/
│       ├── commonMain/kotlin/com/mediaviewer/
│       │   ├── App.kt           Shared entry (@Composable fun App())
│       │   ├── platform/        expect declarations (the seams, see below)
│       │   ├── model/           @Serializable data models (ATProto, e621, …)
│       │   ├── network/         Ktor API clients (Bluesky, e621, Streamplace)
│       │   ├── repository/      Repositories (Bluesky, e621, Wikipedia, tagging)
│       │   ├── tagging/         Tag DB / aliases / suggestions (engine is a seam)
│       │   ├── viewmodel/       MainViewModel (plain CoroutineScope holder)
│       │   ├── ui/              All screens, overlays, dialogs, theme
│       │   └── util/           Preferences, GIF encode, textshot, thumbnails
│       ├── androidMain/kotlin/…/platform/  Android actuals
│       └── wasmJsMain/kotlin/…/platform/   Web actuals
│
├── androidApp/                  Thin native shell
│   └── src/main/
│       ├── AndroidManifest.xml
│       └── kotlin/com/mediaviewer/android/
│           ├── RaccNetApplication.kt  (holds app Context for actuals)
│           └── MainActivity.kt        (setContent { App() })
│
└── webApp/                      Thin web shell
    └── src/wasmJsMain/
        ├── kotlin/com/mediaviewer/webapp/Main.kt  (ComposeViewport { App() })
        └── resources/index.html
```

Package `com.mediaviewer` and applicationId `rechoraccoon.raccnetlite` are kept
deliberately: the legacy rebrand ("MediaViewer"/"RaccNet Lite" → "RaccNet
Pocket") was never finished in code, and changing them would break updates for
existing installs.

## expect/actual seams (`shared/.../platform/`)

| Seam | commonMain contract | androidMain | wasmJsMain |
|---|---|---|---|
| `VideoPlayer` | `@Composable expect fun VideoPlayer(url, …)` | media3 ExoPlayer (incl. HLS) | `<video>` element wrapper |
| `WebEmbed` | `@Composable expect fun WebEmbed(url, …)` | `android.webkit.WebView` | `<iframe>` |
| `openUrl` / `isWebPlatform` | `expect fun openUrl`, `expect val isWebPlatform` | `ACTION_VIEW` intent / `false` | `window.open` / `true` |
| `PlatformDownloader` | `download(url, fileName, mimeType, onProgress)`, `isAlreadyDownloaded`, `markDownloaded` | OkHttp → MediaStore (`DCIM/RaccNet Pocket`) + progress notifications | in-page fetch → Blob anchor download; tab must stay open |
| `PlatformTranslator` | `isAvailable`, `translate(…)`, `supportedLanguages` | ML Kit Translate + Language ID (real) | `isAvailable = false` (no-op) |
| `PlatformImageTagger` | `isAvailable`, `ensureReady{}`, `tagImage(bytes)` | onnxruntime-android, Z3D-E621-Convnext (real) | `isAvailable = false` (no-op) |
| `PlatformNotifier` | `showDownloadProgress/Complete`, `cancel` | `NotificationManager` | no-ops (web shows progress in-page) |

Shared UI **must** hide AI surfaces (tagging overlay, tag-on-like toggle,
translation toggle) whenever the corresponding `isAvailable` is false / 
`isWebPlatform` is true. That is the "AI stays app-exclusive" requirement.

If you hit an Android-only API with no seam yet, add an `expect` declaration
under `platform/` and implement both actuals (web = best-effort no-op with a
comment explaining why), following the existing pattern.

## Dependency mapping (legacy Android → KMP)

| Legacy (Android-only) | KMP replacement | Where |
|---|---|---|
| Retrofit 2 + OkHttp + Gson | Ktor client + `kotlinx.serialization` | `commonMain` (engine: OkHttp on Android, Js on web) |
| Coil 2 (`coil-compose`) | Coil 3 (`io.coil-kt.coil3:coil-compose`) | `commonMain` |
| DataStore Preferences | `multiplatform-settings` (+ coroutines) | `commonMain` |
| media3 ExoPlayer | `platform.VideoPlayer` seam | actuals |
| WorkManager download workers | `platform.PlatformDownloader` seam | actuals |
| ML Kit Translate / Language ID | `platform.PlatformTranslator` seam | actuals |
| onnxruntime-android (390MB tagger) | `platform.PlatformImageTagger` seam | actuals |
| Android WebView (Twitch/YouTube) | `platform.WebEmbed` seam | actuals |
| `NotificationManager` progress | `platform.PlatformNotifier` seam | actuals |
| `androidx.lifecycle` ViewModel | Plain `CoroutineScope` state holder | `commonMain` (`viewmodel/`) |

Central versions: `gradle/libs.versions.toml`.

## Building

### Prerequisites

- **JDK 17+** (Temurin recommended)
- **Android SDK** with platform 34 + build-tools (for `:androidApp` only).
  Set `ANDROID_HOME` or create `local.properties` with `sdk.dir=…`.
  The web build needs no Android SDK.
- Gradle 8.10.2. The wrapper is committed (`gradlew`, `gradlew.bat`,
  `gradle/wrapper/gradle-wrapper.jar`); use `./gradlew` so everyone builds
  with the same version.

### Android APK

```bash
gradle :androidApp:assembleRelease   # signed if KEYSTORE_* env vars are set (see CI below)
gradle :androidApp:assembleDebug     # unsigned debug build
# outputs: androidApp/build/outputs/apk/<release|debug>/*.apk
```

### Website (Wasm)

```bash
gradle :webApp:wasmJsBrowserDistribution
# outputs: webApp/build/dist/wasmJs/productionExecutable/  (index.html + raccnet-pocket.js)
# serve the directory with any static server to preview locally
```

## CI / GitHub Actions (`.github/workflows/build.yml`)

Triggers on **push and pull requests to `main`** (plus manual dispatch).

| Job | Trigger | What it does |
|---|---|---|
| `android` | push + PR | Push to `main`: verifies signing secrets exist, restores the keystore from `KEYSTORE_BASE64`, runs `:androidApp:assembleRelease` (signed), uploads the APK as artifact `raccnet-pocket-apk`. PRs: runs `:androidApp:assembleDebug` (unsigned full compile check), uploads that instead. |
| `web` | push + PR | Runs `:webApp:wasmJsBrowserDistribution`. On push, uploads the dist folder as a Pages artifact. |
| `deploy-pages` | push only | Deploys the Pages artifact to **GitHub Pages** (`deploy-pages@v4`). |

### Secrets setup (repo owner, one time)

1. **Generate a NEW release keystore.** Do not reuse the legacy
   `raccnetlite-release.jks` (see Security note below).
   ```bash
   keytool -genkeypair -v -keystore raccnet-release.keystore \
     -alias raccnet -keyalg RSA -keysize 2048 -validity 10000
   ```
2. Repo **Settings → Secrets and variables → Actions → New repository secret**:
   | Secret | Value |
   |---|---|
   | `KEYSTORE_BASE64` | `base64 -w0 raccnet-release.keystore` |
   | `KEYSTORE_STORE_PASSWORD` | the keystore password you chose |
   | `KEYSTORE_KEY_ALIAS` | `raccnet` (or your alias) |
   | `KEYSTORE_KEY_PASSWORD` | the key password you chose |
3. Repo **Settings → Pages → Build and deployment → Source: "GitHub Actions"**.

Secrets are not available to pull requests from forks — that is exactly why PR
builds use the unsigned debug path. If any secret is missing on a push to
`main`, the workflow fails fast with an explicit error instead of shipping a
mis-signed APK.

## Security note

The legacy repo committed its release keystore (`raccnetlite-release.jks`)
**and** its password (`RaccNetLite2024`, hardcoded in the old workflow). That
keystore must be treated as compromised: generate a new one for this project,
remove the old file from git history, and if the app was ever distributed,
rotate the signing key (via Play App Signing key upgrade if applicable).

## Verification status

> **Not yet fully verified.** A build environment (Temurin JDK 17, Gradle
> 8.10.2) was available during migration, but the sandbox blocked the direct
> network connections Gradle's dependency resolution needs, so plugin and
> library downloads never completed. The first full compile therefore happens
> in CI on the first push. Pending items:
>
> - `:shared` compiles for `androidMain` + `wasmJsMain`
> - `:androidApp:assembleDebug` / `:assembleRelease` (signed, with secrets)
> - `:webApp:wasmJsBrowserDistribution` output serves correctly
> - Per-endpoint CORS check for direct browser calls to `bsky.social`,
>   `e621.net`, `stream.place` (may require a thin proxy backend — TBD)
>
> What *has* been checked without a compiler: import-level audit of
> `commonMain` (no Android/AndroidX imports remain), expect/actual parity
> across both platform source sets, UI ↔ ViewModel ↔ repository call-site
> signature matching at the known cross-worker seams, complete Gradle wrapper
> files, and a static review of the GitHub Actions workflow (task names, APK
> and web output paths, Pages deployment steps).

## Porting notes

- Behavior is preserved from the legacy app, including its well-commented
  style; no features were invented during migration.
- Unfinished v2.0 features (blog posting, review posting, VRM VTubing,
  go-live toggle) are ported as-is with their stubs clearly marked — same as
  the source.
- Bluesky DMs use the existing 4s polling loop (no WebSocket needed on web).
- `MainViewModel` (~4k lines) is ported as a plain `CoroutineScope`-based
  state holder in `commonMain`, not an AndroidX `ViewModel`.

## Integration reconciliation (post-port fixes)

After the parallel port workers landed, the seams were reconciled:

- `material-icons-extended` added to shared deps; Android-only
  `androidx.activity.compose.BackHandler` replaced with Compose Multiplatform's
  `org.jetbrains.compose.ui:ui-backhandler` (back handling restored in
  `SearchOverlay`).
- Missing `loadFontFamily` actuals added (Android: filesystem `.ttf/.otf`;
  web: no-op returning null with a documented limitation).
- ATProto `uploadBlob`/`uploadVideo` fixed to send raw `ByteArrayContent`
  with `Content-Type` (the spec-correct form) instead of multipart.
- Legacy `TranslationManager` call sites (`MainFeedScreen`) are served by a
  thin `util/TranslationManager` facade — an object whose `Outcome` is a
  typealias of the shared `TranslationOutcome`, delegating to the platform
  `PlatformTranslator`. No outcome model is duplicated.
- `DownloadManager`'s `BlueskyBlobResolver` import corrected to the
  `com.mediaviewer.repository` package the repository worker ported it into.
- `MainFeedScreen`'s `importedDatasets` parameter retyped from the
  androidMain-only `tagging.TagDatabase.DatasetInfo` to the shared
  `platform.DatasetInfo` the ViewModel and `SettingsSheet` both use.
- Missing GlassTheme actuals added: `fetchDominantColor` (Android: legacy
  Coil 16x16 pixel-average, ported to Coil 3; web: canvas 16x16 sampling)
  and `CAN_BLUR` (Android: API 31+; web: true).
- Web settings now persist via a `localStorage`-backed `ObservableSettings`
  (keys namespaced `raccnet.`; string sets as JSON), so login tokens, theme
  and feed preferences survive reloads.

## Known web limitations

- Video transport controls, seek, ±10s skip, buffering state and poster fade
  are not yet wired to the `<video>`-element seam (playback itself works).
- Web HLS relies on native browser support; no hls.js is bundled.
- Web GIF export is an explicit TODO (Android encodes via its platform seam).
- The in-page downloader needs the tab to stay open (no background workers).
- AI image tagging and on-device translation are app-exclusive by design:
  the web actuals report `isAvailable = false` and shared UI hides those
  surfaces on web.
- CORS behavior against Bluesky, e621, Streamplace, embeds and media CDNs is
  unverified in a real browser — test before shipping; a thin proxy backend
  may be needed for endpoints that reject cross-origin calls.
