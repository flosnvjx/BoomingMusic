# Booming Music

Open-source Android music player (GPL-3.0), inspired by Retro Music Player. Kotlin, single `:app` module. Package `com.mardous.booming`, app id `com.mardous.booming`.

## Project
- Stack: Kotlin 2.3.21, AGP 9.2.0, KSP, Gradle Kotlin DSL + version catalog (`gradle/libs.versions.toml`), jvmToolchain(21), compileSdk 37 / minSdk 26 / targetSdk 36.
- Libraries: Media3 ExoPlayer, Room, Koin, Coil 3, Ktor client, Material 3, Navigation (fragment-based), Glance widgets. UI is a hybrid of Android Views (RecyclerView list screens) and Jetpack Compose.
- Entry points: `app/src/main/java/com/mardous/booming/App.kt` (Application: Koin, Coil ImageLoader, crash activity, StrictMode in DEBUG), `ui/screen/MainActivity.kt` (View-based activity + NavHostFragment with sliding player panel).
- Only one flavor active: `normal` (fdroid flavor is commented out in `app/build.gradle.kts`).

## Commands
- Build: `./gradlew assemble` (variants `normalDebug` / `normalRelease`; APKs at `app/build/outputs/apk/*/release/BoomingMusic-*.apk`, ABI-split arm64-v8a only)
- Lint: `./gradlew lint` (CI runs this; `lint { abortOnError = true }`)
- No test source sets exist — there is no test command.
- Release signing: reads `keystore.properties` at repo root; Last.fm keys come from `local.properties` (`LASTFM_API_KEY`/`LASTFM_SECRET`) or env vars; falls back to debug signing/empty keys.

## Architecture
- `App.kt` / `MainModule.kt` — Koin setup; `MainModule.kt` defines all DI modules (`networkModule`, `mainModule`, `roomModule`, `dataModule`, `viewModule`).
- `core/` — `BoomingDatabase` (Room, version 5, manual `Migration` objects), domain models, Glance appwidgets, audio/palette/sort helpers.
- `data/` — repositories (Room DAOs + MediaStore + network) in `data/local/repository` (`Repository` interface + `Real*` impls), mappers, remote clients (`remote/deezer`, `remote/github`, `remote/lastfm`, `remote/listenbrainz`, `remote/lyrics`).
- `playback/` — `PlaybackService` (Media3 session), equalizer engine, shuffle, audio processors (ReplayGain, Balance), `MediaIDs`.
- `ui/` — screens by feature (`screen/library`, `screen/player`, `screen/lyrics`, `screen/settings`, …), RecyclerView adapters (`ui/adapters`), shared View base classes (`ui/component/base`), Compose components (`ui/component/compose`).
- `coil/` — custom Coil fetchers/mappers for audio covers, artist/playlist images, auto-generated artwork.
- `extensions/` — Kotlin extension functions; `util/` — `Preferences` (setting keys/accessors), `MusicUtil`, helpers (`BackupHelper`, `CryptoUtil`, `FileUtil`, `StorageUtil`, `SongPlayCountHelper`, `SwipeAndDragHelper.java`).

## Conventions
- Every `.kt` file starts with the GPL-3.0 copyright header (Copyright © 2024/2025 Christians Martínez Alvarado) — keep it on new files.
- ViewModels expose state as `MutableStateFlow` + `asStateFlow()` named `<x>Flow` (see `PlayerViewModel`).
- Koin constructor injection everywhere; ViewModels registered in `MainModule.kt` via `viewModel { }`.
- ViewBinding is enabled; prefer existing patterns: RecyclerView + `ui/adapters/*` for lists, Compose for new interactive screens.
- User-visible strings go into `res/values*/strings.xml` (app is translated via Weblate) — never hardcode.
- Versioning is handled by the `Version` sealed class in `app/build.gradle.kts` (Stable/Beta/RC/Alpha); `versionCode` must match `currentVersion.code` (enforced by `check()`).
- Android lint `abortOnError = true`; expected warnings are allowlisted in `app/build.gradle.kts` — don't add new lint errors.
- Keep UI state in `ViewModel`s, media/tag access behind repositories; Remote lyric/Last.fm features are gated by `enable_lastfm_integration` resValue where applicable.

## Notes
- (Add cross-cutting notes here as they come up.)
