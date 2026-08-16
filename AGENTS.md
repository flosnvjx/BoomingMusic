# Booming Music

Open-source Android music player (GPL-3.0), inspired by Retro Music Player. Kotlin, single `:app` module. Package `com.mardous.booming`, app id `com.mardous.booming`.

## Project
- Stack: Kotlin 2.3.21, AGP 9.2.0, KSP, Gradle Kotlin DSL + version catalog (`gradle/libs.versions.toml`), jvmToolchain(21), compileSdk 37 / minSdk 26 / targetSdk 36.
- Libraries: Media3 ExoPlayer, Room, Koin, Coil 3, Ktor client, Material 3, Navigation (fragment-based), Glance widgets. UI is a hybrid of Android Views (RecyclerView list screens) and Jetpack Compose.
- Entry points: `app/src/main/java/com/mardous/booming/App.kt` (Application: Koin, Coil ImageLoader, crash activity, StrictMode in DEBUG), `ui/screen/MainActivity.kt` (View-based activity + NavHostFragment with sliding player panel).
- Only one flavor active: `normal` (fdroid flavor is commented out in `app/build.gradle.kts`).

## Commands
- **No JRE in this environment — never attempt to run JRE-dependent commands** (`./gradlew`, `java`, `javac`, `kotlinc`, or anything requiring a JVM). The sandbox has no JDK, so such runs fail with `JAVA_HOME is not set` / `Permission denied`. If a step needs confirmation from a JRE-dependent run (build, lint, unit test, etc.), do not execute it — prompt the user to run the exact command manually and report the result back.
- Build: `./gradlew assemble` (variants `normalDebug` / `normalRelease`; APKs at `app/build/outputs/apk/*/release/BoomingMusic-*.apk`, ABI-split arm64-v8a only)
- Lint: `./gradlew lint` (CI runs this; `lint { abortOnError = true }`)
- No test source sets exist — there is no test command.
- Release signing: reads `keystore.properties` at repo root; Last.fm keys come from `local.properties` (`LASTFM_API_KEY`/`LASTFM_SECRET`) or env vars; falls back to debug signing/empty keys.

## Architecture
- `App.kt` / `MainModule.kt` — Koin setup; `MainModule.kt` defines all DI modules (`networkModule`, `mainModule`, `roomModule`, `dataModule`, `viewModule`).
- `core/` — `BoomingDatabase` (Room, version 6, manual `Migration` objects), domain models, Glance appwidgets, audio/palette/sort helpers.
- `data/` — repositories (Room DAOs + MediaStore + network) in `data/local/repository` (`Repository` interface + `Real*` impls), mappers, remote clients (`remote/deezer`, `remote/github`, `remote/lastfm`, `remote/listenbrainz`, `remote/lyrics`).
- `playback/` — `PlaybackService` (Media3 session), equalizer engine, shuffle, audio processors (ReplayGain, Balance), `MediaIDs`.
- **External-song support** (audio files MediaStore doesn't index, e.g. DocumentsProvider): session path = `Song.externalUri`, `RealSongRepository` resolution + LRU cache, `LibraryProvider` branch, `PersistentStorage` write gate; **imported** songs = `ExternalSongEntity`/`ExternalSongDao` (Room v9), `RealExternalSongRepository`, merge in `RealRepository`, import/remove in `LibraryViewModel`, picker in `AbsRecyclerViewFragment`; reference: `doc/open-with-audio-intent.md`.
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
- **External songs — two kinds**, both with `Song.externalUri` and `MediaItem.mediaId` = the content/file URI string (the only identity surviving the controller↔session Media3 hop; tags/`localConfiguration` don't). Use the shared `Uri.isExternalMediaUri()` / `String.isExternalMediaId()` classifiers (`extensions/media/MediaExt.kt`), never re-implement the URI rule. **URI canonicalization:** the same file can surface as two strings — the picker returns/stores the document form (`content://<authority>/document/<id>`), while ACTION_VIEW on tree-based providers may deliver the tree form (`content://<authority>/tree/<root>/document/<id>`); `String.asExternalIdentityUri()` (`MediaExt.kt`) normalizes tree→document for identity matching (`ExternalSongRepository.songByUri` canonicalizes queries, the session probe derives its id from the canonical URI), so a session-only open of an imported file resolves to the imported row.
- **Session-only external songs** (ACTION_VIEW open-with, transient grant): never persisted (`PersistentStorage` write gate), never resolve unreadable URIs (`Context.isUriReadable`), skip history/play-count/ReplayGain/scrobbling (`PlaybackService.onMediaItemTransition`); also **gated from durable writes** — add-to-playlist (song menu / multi-select / Now-Playing) and the Favorites toggle (`PlaybackService.toggleFavorite`, incl. notification + widgets) are blocked for `isSessionOnlyExternal` (`externalUri != null && albumId == -1L`, `MediaExt.kt`), since their playlist/Favorites snapshot would become a dead row after the grant expires. Restore position bookkeeping assumes persisted orders + `LAST_INDEX` are in external-free coordinates.
- **Imported external songs** (system file picker, persistable grant): durable in Room `external_songs` with synthetic IDs in the **negative** band `≤ -EXTERNAL_ID_BASE` (1e9, `data/local/repository/ExternalSongRepository.kt` — disjoint from MediaStore ids and the -1/-2 sentinels, unlike the old positive band which large/restored MediaStore DBs can overlap); merged into Songs/Albums/search/playlists/Favorites via `RealRepository` (`allAlbums()` sorts the merged list by the active `AlbumSortMode`); write ops (tag editor/delete/ringtone/cover) and go-to-artist/genre are gated off for `externalUri != null` — in the Now-Playing menu (`AbsPlayerFragment.onMenuInflated`, re-applied on every song change) these are hidden, "Remove from library" is shown for imported songs, "Set as ringtone" for MediaStore ones; ReplayGain **is** applied to imported songs (only session-only external songs reset the gain, `PlaybackService.onMediaItemTransition`); unreadable imports are auto-pruned; "Remove from library" purges the row + playlist snapshots + play-count stats + releases the grant. Artists/genres/years/folders/History stay MediaStore-only; play statistics (play count/skip count/last played) are recorded for imported songs in `PlayCountEntity.external_uri` (DB v10) and surface in Most-Played.

## Notes
- Open-with / `ACTION_VIEW` handling incl. external + imported songs: see `doc/open-with-audio-intent.md` (kept in sync with the code; update it when behavior changes).
