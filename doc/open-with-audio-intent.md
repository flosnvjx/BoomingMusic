# Opening an Audio File with Booming Music ("Open with" / `ACTION_VIEW`)

**Question:** What happens when an audio file is opened with this app — considering
whether the app is running or not, whether the queue is empty or not, and whether the
file lives behind a `DocumentsProvider` in a location **not** handled by MediaStore?

**Answer (short):** The app always *replaces* the current queue and plays the file
immediately. MediaStore-indexed files are played through their MediaStore row. Files
that MediaStore cannot resolve (e.g. a cloud or OTG provider URI that
`MediaStore.getMediaUri` cannot map) are now turned into an **external song** whose raw
content URI is streamed directly by the player — so they actually play, appear in the
queue and now-playing UI, and show up in the notification. External files are
session-only: the queue entry is not restored after an app restart, and MediaStore-backed
features (history, play counts, scrobbling, ReplayGain, artwork) are skipped for them.

> **Source snapshot:** behavior verified against the current working tree (post `aa2ddcf6`,
> 2026-08-15). All code references are `file:line` in `app/src/main/java/`.

---

## 1. Intent entry & process lifecycle

`MainActivity` receives all `ACTION_VIEW` intents via an exported `activity-alias`
(no intent-filter is declared on the activity itself):

- Manifest intent-filters (`app/src/main/AndroidManifest.xml:109-153`): `ACTION_VIEW`
  with `audio/*` (plus `application/ogg`, `application/x-ogg`, `application/itunes`)
  for `content://`, `file://`, and `http(s)://` schemes, plus playlist/album/artist
  `content://` types. All target `ui.screen.MainActivity` through the alias
  (`AndroidManifest.xml:88-184`, `launchMode="singleTop"` at `:62`).

Both cold and warm starts converge on the same handler, with one difference:

| Case | Path |
|---|---|
| App **not running** | `onCreate` → MediaController connects asynchronously (`AbsSlidingMusicPanelActivity.kt:215-217` collects `isConnected` → `onConnected`) → `MainActivity.onConnected` → `handlePlaybackIntent(intent, canRestorePlayback = true)` (`MainActivity.kt:56-59`). If the intent is unhandled, `restorePlayback()` runs. |
| App **running** (activity on top) | `onNewIntent` → `handlePlaybackIntent(intent, canRestorePlayback = false)` (`MainActivity.kt:188-191`). No restore fallback. `setIntent(Intent())` clears the intent afterwards. |
| No `READ_MEDIA_AUDIO` permission (cold start) | Bails to `PermissionsActivity` and finishes (`AbsSlidingMusicPanelActivity.kt:175-177`) — the open-with intent is dropped. |

There is no `ACTION_SEND` / "send to queue" / "play now" audio handling; the
`MUSIC_PLAYER`/search/pick aliases carry no data URI and are ignored by
`handleIntent` (`LibraryViewModel.kt:541-543`).

## 2. URI resolution pipeline

`handleIntent` → `repository.songsByUri(uri)` (`LibraryViewModel.kt:539-547`) →
`RealSongRepository.songsByUri` (`data/local/repository/SongRepository.kt:110-165`):

1. `content://media/...` → parse trailing `_ID` → MediaStore query (`SongRepository.kt:115-119`).
2. **Any other authority (DocumentsProviders):**
   - API 29+: `MediaStore.getMediaUri(context, uri)` → MediaStore row (`SongRepository.kt:126-128`).
   - API 26–28: only `com.android.providers.media.documents` via
     `DocumentsContract.getDocumentId` (`SongRepository.kt:131-136`).
   - `getMediaUri` returns `null` for providers MediaStore cannot map (cloud, OTG);
     exceptions are swallowed and logged (`SongRepository.kt:139-141`).
3. `file://` → MediaStore query by `DATA = path` (`SongRepository.kt:144-148`).
4. Fallback for `content://` (`SongRepository.kt:152-159`): query the **incoming URI
   itself** for `OpenableColumns.DISPLAY_NAME` + `SIZE`
   (`getDisplayNameAndSize`, `SongRepository.kt:326-344`) and search MediaStore for an
   exact display-name+size match (`findSongFromFileProviderUri`, `SongRepository.kt:344-352`).
5. **External fallback** (`SongRepository.kt:397-432`): if steps 1–4 produce no
   MediaStore row (unmapped DocumentsProvider, non-indexed `file://`, name+size miss),
   `externalSongFromUri(uri)` builds an **external song** from the raw URI — see §4.
   It is fully `runCatching`-guarded; if even the URI cannot be inspected it returns
   `Song.emptySong` and the open degrades to the old silent no-op.

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

## 4. External songs: model & MediaItem

`Song` (`data/model/Song.kt`) gained `open val externalUri: String? = null` (`:52`).
When set, `Song.uri` returns the raw content URI instead of the MediaStore URI (`:55-56`).

`Song.toMediaItem` (`Song.kt:95-132`) has three cases:

- `emptySong` → `MediaItem.EMPTY` (`:96-97`).
- **External song** (`externalUri != null`, `:98-128`): builds the MediaItem with
  `setUri(contentUri)`, `setMediaId(externalUri)` — the URI string **is** the
  mediaId — `setTag(this)` (the Song is attached as the `MediaItem` tag), and metadata
  from the probed title/artist/album/duration. No `setArtworkUri` (no MediaStore
  artwork to resolve).
- MediaStore song: as before, `content://media/external/audio/media/<id>` mediaId.

`externalSongFromUri` (`SongRepository.kt:397-432`) builds the external song with:

- `id = uri.toString().hashCode().toLong()` (stable per URI within the session; used
  only as an identity key, never for MediaStore lookups).
- `title` from `OpenableColumns.DISPLAY_NAME` (extension stripped) with an optional
  in-place `MediaMetadataRetriever` probe (`probeExternalMetadata`,
  `SongRepository.kt:437-455`) for real title/artist/album/duration; `data = ""`,
  all other MediaStore fields `-1`/empty.
- `ExpandedSong` forwards `externalUri` through both constructors
  (`data/model/ExpandedSong.kt:26,43,72`).

## 5. Resolution across the controller ↔ service boundary

The controller (`PlayerViewModel`) and `PlaybackService` communicate through the Media3
session, which serializes `MediaItem`s over the connection. **Neither the `Song` tag nor
`localConfiguration` survive that hop** — only the `mediaId` does. All resolution below
therefore keys on the URI-as-mediaId:

- `RealSongRepository.songsByMediaItems` (`SongRepository.kt:168-213`): for each item,
  resolves in order — `localConfiguration.tag` as a `Song`, then `externalUriOf(item)`
  (surviving `localConfiguration.uri` or `mediaId` parsed as an external content/file
  URI, `SongRepository.kt:361-378`), then the MediaStore `_ID` query. Order-preserving.
  This stops `PlayerViewModel.onGenerateQueue` (`PlayerViewModel.kt:233-264`) from
  reporting the external item as "missing" and removing it from the timeline.
- `RealSongRepository.songByMediaItem` (`SongRepository.kt:214-234`): same order —
  tag → external URI → MediaStore `_ID`. This is what now-playing UI and
  `PlaybackService.onMediaItemTransition` use to recover the real external song.
- `LibraryProvider.getMediaItemsForPlayback` (`playback/library/LibraryProvider.kt:32-85`):
  session-side, items without `localConfiguration` are resolved via `songsByMediaItems`
  first; as defense in depth, remaining items whose `mediaId` parses as an external
  content/file URI are rebuilt into playable URI `MediaItems` (via
  `songByMediaItem` + `Song.toMediaItem`) **before** the auto/AAOS complex-path branch,
  which cannot resolve them (`LibraryProvider.kt:46-60`).
- **Caching:** external resolution queries the provider and reads metadata, and queue
  generation re-resolves on every media event, so results are cached per URI in a
  session-scoped, bounded LRU (`SongRepository.kt:73-79`, max 64 entries,
  `MAX_EXTERNAL_SONG_CACHE_SIZE` at `:494`). Both successes and failures are cached so
  an unresolvable URI is not re-probed (`externalSong`, `SongRepository.kt:380-395`).

## 6. Playback side effects & guards

`PlaybackService.onMediaItemTransition` (`playback/PlaybackService.kt:715-761`) skips
MediaStore-dependent features for external songs (`externalUri != null`):

- No history upsert, no play-count/skip-count bump, no Last.fm/ListenBrainz
  now-playing or scrobbling (`:728-742`).
- ReplayGain is reset to `null` so the previous track's gain does not leak onto the
  external file (`:730`); the previous-song guard also excludes external songs (`:743`).

The notification and widgets need no special handling: they render from
`MediaItem.mediaMetadata`, which survives bundling and carries the probed
title/artist/duration.

## 7. Persistence

- **MediaStore-resolved file:** the song `_ID` is saved to Room `QueueEntity` on every
  timeline change (`playback/PlaybackService.kt:677`); on restart the queue is restored
  by re-resolving MediaStore IDs (`playback/PersistentStorage.kt:101-148`). History is
  written on `onMediaItemTransition` (`PlaybackService.kt:731-735`).
- **External file (session-only):** never persisted. External songs are excluded from
  `QueueEntity` when the queue is persisted (`PersistentStorage.kt:280-302`), and a URI
  that cannot be read never resolves into a song (`Context.isUriReadable`), so restore
  drops such items instead of restoring a dead, unplayable entry. `LAST_INDEX` is saved
  in persisted-queue coordinates so resume positions stay valid. The app never calls
  `takePersistableUriPermission` for ACTION_VIEW files, so the source URI grant is not
  retained either. **Imported** external songs (file picker) are durable — see §10.

## 8. Behavior matrix

| Scenario | Result |
|---|---|
| Cold start, MediaStore-indexed file | Resolves → queue replaced, plays immediately, persisted |
| App running, MediaStore-indexed file | Same, via `onNewIntent` (no restore fallback if unhandled) |
| DocumentsProvider URI mappable via `getMediaUri` (API 29+) | Treated as its MediaStore row → plays, persisted |
| DocumentsProvider URI **not** in MediaStore (cloud/OTG, or any provider on API 26–28) | External song → raw URI streamed, queue replaced, now-playing + notification show probed title; session-only, no history/play-count/artwork/ReplayGain |
| External file, app restarted | Not persisted and unreadable after restart → restore drops it; the queue resumes with the remaining persisted songs at the correct position |
| **Imported** external song (file picker) | Durable: persists in Room + queue, appears in Songs/Albums/search/playlists/Favorites, survives restarts; write ops disabled; removable; auto-pruned if the provider is unplugged |
| `file://` not indexed by MediaStore | Same external-song path → plays |
| URI completely unreadable (provider query + probe both fail) | `emptySong` → silent no-op: queue cleared, nothing plays, no toast, nothing saved |
| No `READ_MEDIA_AUDIO` on cold start | Redirects to `PermissionsActivity`, intent dropped |

## 9. Known limitations

- **Session-only:** an opened external file does not survive an app restart, and the
  URI grant is not persisted. (Imported songs — see §10 — are durable.)
- **No artwork:** external songs fall back to the default placeholder (`CoverProvider`
  resolves only MediaStore album art).
- **No Android Auto/AAOS:** external items are resolved for the in-app controller
  path only; the auto/AAOS complex-path branch does not handle them.
- **Identity:** external songs use a URI hash as their `Song.id`; `distinctUntilChangedBy`
  keying and any per-song feature are scoped to the session.
- If the metadata probe fails, the subtitle may show `0:00` until ExoPlayer reports the
  real duration; the seek bar is unaffected.

## 10. Imported external songs (system file picker)

Besides the transient ACTION_VIEW path, users can **permanently import** external audio
files into the in-app library via the system file picker ("Add from file…" in the library
menu, `res/menu/menu_library.xml`). Because the picker grant is **persistable**
(`takePersistableUriPermission`), imported songs survive restarts and behave like
first-class library entries:

- **Storage:** `ExternalSongEntity` (Room `external_songs` table, PK = uri, DB v6) —
  `data/local/room/ExternalSongEntity.kt`, `ExternalSongDao`, migration
  `MIGRATION_5_6` (`core/BoomingDatabase.kt`).
- **Identities:** synthetic stable IDs in a reserved high band
  (`EXTERNAL_ID_BASE` = 1e9 + hash, `data/local/repository/ExternalSongRepository.kt`)
  — never collide with MediaStore `_ID`s or the -1/-2 sentinels; album ids derive from
  `albumName|albumArtist` (matching MediaStore's album semantics).
- **Import flow** (`ui/screen/library/LibraryViewModel.kt` `importExternalSong`):
  dedup via `songsByUri` (rejects files MediaStore already maps or that match a
  MediaStore row by display-name+size), rejects already-imported URIs, inserts the
  entity, reloads Songs/Albums. Non-audio or unreadable picks are rejected with a toast.
- **Library integration:** merged into `Repository.allSongs()` / `searchSongs()`
  (Songs tab + search) and `allAlbums()` / `albumById()` (Albums tab + Go-to-album
  detail) — `data/local/repository/Repository.kt`. Artists, genres, years, folders,
  Home lists, History and Most-Played stay MediaStore-only.
- **Playlists & Favorites:** `SongEntity` gained an `external_uri` column (DB v6), so
  imported songs round-trip through user playlists and Favorites and play via the
  external `toMediaItem` path.
- **Queue persistence:** `PersistentStorage` persists imported external mediaIds
  (readable on restart via the persistable grant) but still excludes session-only ones;
  the persisted order/`LAST_INDEX` translation uses the same predicate.
- **Read-only gating:** for `externalUri != null` songs the app hides/disables tag
  editor, delete-from-device, set-as-ringtone, custom cover and go-to-artist/genre
  (`SongAdapter.onPrepareSongMenu`, `MenuItemClickExt` guards,
  `AbsPlayerFragment.onQuickActionEvent`); album menus gate the same for external
  albums (id ≥ `EXTERNAL_ID_BASE`). Go-to-album stays enabled.
- **Remove from library** (song menu): deletes the Room row + playlist snapshots and
  calls `releaseUriPermission`.
- **Unplugged providers (e.g. OTG):** on library load, imported songs whose URI can no
  longer be read (`Context.isUriReadable`) are auto-removed (external_songs row +
  playlist snapshots) — `pruneUnreadable` in `RealExternalSongRepository`.
