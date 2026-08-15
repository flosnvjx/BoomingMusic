# Opening an Audio File with Booming Music ("Open with" / `ACTION_VIEW`)

**Question:** What happens when an audio file is opened with this app — considering
whether the app is running or not, whether the queue is empty or not, and whether the
file lives behind a `DocumentsProvider` in a location **not** handled by MediaStore?

**Answer (short):** The app always *replaces* the current queue and tries to play the
file immediately. It never plays the incoming content URI directly — it first resolves
the URI to a MediaStore row. If the file cannot be resolved to MediaStore (e.g. a cloud
or OTG provider URI that `MediaStore.getMediaUri` cannot map), the open silently no-ops:
the queue is wiped, nothing plays, no error toast is shown, and nothing is persisted.

> **Source snapshot:** behavior verified against commit `be7316ba` (2026-08-15).
> All code references are `file:line` in `app/src/main/java/`.

---

## 1. Intent entry & process lifecycle

`MainActivity` receives all `ACTION_VIEW` intents via an exported `activity-alias`
(no intent-filter is declared on the activity itself):

- Manifest intent-filters (`app/src/main/AndroidManifest.xml:108-153`): `ACTION_VIEW`
  with `audio/*` (plus `application/ogg`, `application/x-ogg`, `application/itunes`)
  for `content://`, `file://`, and `http(s)://` schemes, plus playlist/album/artist
  `content://` types. All target `ui.screen.MainActivity` through the alias
  (`AndroidManifest.xml:88-184`, `launchMode="singleTop"` at `:60-63`).

Both cold and warm starts converge on the same handler, with one difference:

| Case | Path |
|---|---|
| App **not running** | `onCreate` → MediaController connects asynchronously (`AbsSlidingMusicPanelActivity.kt:215-221` collects `isConnected` → `onConnected`) → `MainActivity.onConnected` → `handlePlaybackIntent(intent, canRestorePlayback = true)` (`MainActivity.kt:56-59`). If the intent is unhandled, `restorePlayback()` runs (`MainActivity.kt:222-224`). |
| App **running** (activity on top) | `onNewIntent` → `handlePlaybackIntent(intent, canRestorePlayback = false)` (`MainActivity.kt:188-191`). No restore fallback. `setIntent(Intent())` clears the intent afterwards. |
| No `READ_MEDIA_AUDIO` permission (cold start) | Bails to `PermissionsActivity` and finishes (`AbsSlidingMusicPanelActivity.kt:174-177`) — the open-with intent is dropped. |

There is no `ACTION_SEND` / "send to queue" / "play now" audio handling; the
`MUSIC_PLAYER`/search/pick aliases carry no data URI and are ignored by
`handleIntent` (`LibraryViewModel.kt:541-543`).

## 2. URI resolution pipeline

`handleIntent` → `repository.songsByUri(uri)` (`LibraryViewModel.kt:539-547`) →
`RealSongRepository.songsByUri` (`data/local/repository/SongRepository.kt:96-146`):

1. `content://media/...` → parse trailing `_ID` → MediaStore query (`SongRepository.kt:101-105`).
2. **Any other authority (DocumentsProviders):**
   - API 29+: `MediaStore.getMediaUri(context, uri)` → MediaStore row (`SongRepository.kt:110-116`).
   - API 26–28: only `com.android.providers.media.documents` via
     `DocumentsContract.getDocumentId` (`SongRepository.kt:117-124`).
   - `getMediaUri` returns `null` for providers MediaStore cannot map (cloud, OTG);
     exceptions are swallowed and logged (`SongRepository.kt:125-127`).
3. `file://` → MediaStore query by `DATA = path` (`SongRepository.kt:130-135`).
4. Fallback (`SongRepository.kt:137-139`): query the **incoming URI itself** for
   `OpenableColumns.DISPLAY_NAME` + `SIZE` (`getDisplayNameAndSize`, `SongRepository.kt:276-287`)
   and search MediaStore for an exact display-name+size match (`findSongFromFileProviderUri`,
   `SongRepository.kt:289-298`).
5. No match → `Song.emptySong` (`data/model/Song.kt:181`).

Notably **absent** in this path: no file copy, no MediaStore insert, no
`MediaScannerConnection` (that is only the manual "Scan media" feature,
`LibraryViewModel.kt:236-251`), no `DocumentFile`, and no
`takePersistableUriPermission` for the open-with URI.

## 3. Queue semantics — always "replace & play now"

There is **no** empty-queue check for open-with. If the intent resolves to ≥1 song:

- `MainActivity.kt:213-219` → `playerViewModel.openQueue(songs, position, shuffle = Off)`
- `PlayerViewModel.openQueue` (`ui/screen/player/PlayerViewModel.kt:393-417`) →
  `controller.setMediaItems(mediaItems, position, C.TIME_UNSET)` +
  `playWhenReady = true` + `prepare()`.

The previous queue is replaced and the file plays immediately, regardless of whether
the queue was empty. (The "append if queue non-empty" logic exists only for
user-initiated play-next / add-to-queue actions, `PlayerViewModel.kt:529-580`.)

## 4. What the player actually receives

The raw content URI is never passed to playback. `Song.toMediaItem`
(`data/model/Song.kt:93-117`) builds a Media3 `MediaItem` from the MediaStore URI
(`content://media/external/audio/media/<id>`, `mediaId = id`). For an unresolved file
it yields `MediaItem.EMPTY` (`Song.kt:94-95`), which is filtered out by
`LibraryProvider.getMediaItemsForPlayback` (items need `localConfiguration != null`,
`playback/library/LibraryProvider.kt:33-37`) → empty timeline → nothing plays.

## 5. The failure mode for non-MediaStore DocumentsProvider files

This is a silent failure — the error toast does **not** fire:

- `songsByUri` returns `listOf(emptySong)` (a **non-empty** list), so
  `failed = songs.isEmpty()` is `false` (`LibraryViewModel.kt:547`).
- `openQueue([emptySong])` still runs (`result.songs.isNotEmpty()`), replacing the
  current queue (`MainActivity.kt:214-219`).
- The `unplayable_file` toast (`MainActivity.kt:225-227`) only fires when the list is
  genuinely empty — e.g. for `http` audio URLs.

Net effect: queue cleared, nothing plays, no user-visible error, nothing persisted.

## 6. Persistence

- **MediaStore-resolved file:** the song `_ID` is saved to Room `QueueEntity` on every
  timeline change (`playback/PlaybackService.kt:676-678`); on restart the queue is
  restored by re-resolving MediaStore IDs (`playback/PersistentStorage.kt:101-148`).
  History entry is written on `onMediaItemTransition`, guarded by
  `newSong != Song.emptySong` (`PlaybackService.kt:715-729`).
- **Non-MediaStore file:** never enters the timeline → nothing persisted. The app never
  takes URI permissions for open-with, so the source URI grant is not retained either.

## 7. Behavior matrix

| Scenario | Result |
|---|---|
| Cold start, MediaStore-indexed file | Resolves → queue replaced, plays immediately, persisted |
| App running, MediaStore-indexed file | Same, via `onNewIntent` (no restore fallback if unhandled) |
| DocumentsProvider URI mappable via `getMediaUri` (API 29+) | Treated as its MediaStore row → plays |
| DocumentsProvider URI **not** in MediaStore (cloud/OTG, or any provider on API 26–28) | `emptySong` → silent no-op: queue cleared, nothing plays, no toast, nothing saved |
| No `READ_MEDIA_AUDIO` on cold start | Redirects to `PermissionsActivity`, intent dropped |
