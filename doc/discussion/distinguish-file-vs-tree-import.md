# Can We Tell Whether an Imported Song Came from a File or a Tree Pick?

**Question:** Suppose we add support for importing songs via a DocumentsProvider
*tree* (a whole folder). In the current codebase, could we distinguish whether a song
was imported by file or by tree by filtering the URI?

**Answer (short):** Yes. DocumentsContract defines two stable URI shapes, and a
URI-path filter — `uri.pathSegments.firstOrNull() == "tree"` — separates them
reliably for well-behaved providers. The current codebase already stores the **raw
picked URI** in Room (canonicalization tree→document happens only at query time), so
the information needed for the filter survives persistence. Caveats: it is a
heuristic (a tree-form URI can also come from a session-only `ACTION_VIEW` open, and
per-document grant release won't revoke a tree grant), so if tree import becomes a
real feature, also record the picked tree root explicitly.

> **Source snapshot:** behavior verified against the current working tree (2026, after
> the external-song import work). All code references are `file:line` in
> `app/src/main/java/`.

---

## 1. The two URI shapes

| Import path | URI form | `pathSegments` |
|---|---|---|
| File picker (`ACTION_OPEN_DOCUMENT` / `OpenMultipleDocuments`) | `content://<authority>/document/<docId>` | `[document, <docId>]` |
| Tree picker (`ACTION_OPEN_DOCUMENT_TREE` + child docs) | `content://<authority>/tree/<rootId>/document/<childId>` | `[tree, <rootId>, document, <childId>]` |

The classifier is therefore trivial:

```kotlin
val importedByTree = uri.pathSegments.firstOrNull() == "tree"
// or: uri.path?.contains("/tree/") == true
```

This is exactly the distinction that `asExternalIdentityUri()`
(`extensions/media/MediaExt.kt:176-186`) currently *collapses* — it rewrites
`tree/.../document/<id>` to `document/<id>` so a session-only tree-form open and a
document-form import share one identity.

## 2. Why it works in this codebase

- The current import flow uses `ActivityResultContracts.OpenMultipleDocuments`
  (`ui/component/base/AbsRecyclerViewFragment.kt:85-102`) — a *file* picker, so
  today every stored row is document-form and the filter would say "file" for
  everything. No false positives; the tree case simply does not exist yet.
- `LibraryViewModel.importExternalSong` stores the **raw picked URI string** in
  `ExternalSongEntity.uri`, with an explicit comment that canonicalization is
  deferred to query time (`ui/screen/library/LibraryViewModel.kt:201-206`).
- `ExternalSongRepository.songByUri` canonicalizes the *query*, not the stored row:
  `dao.byUri(uri.asExternalIdentityUri())` (`data/local/repository/ExternalSongRepository.kt:187-191`).
- Consequence: if tree import is added and stores tree-form child URIs, the same
  filter classifies them correctly, and existing session-only tree-form `ACTION_VIEW`
  opens still resolve to the right imported row (canonicalization untouched).

## 3. Caveats

1. **Heuristic, not a contract.** A tree-form URI is not *proof* of a tree import:
   `ACTION_VIEW` on tree-based providers hands out tree-form URIs for a single file
   (the exact case `asExternalIdentityUri()` was written for,
   `extensions/media/MediaExt.kt:167-175`), and some providers may return tree-form
   URIs even from a document picker. "document form ⇒ file import" is very safe;
   "tree form ⇒ tree import" is a good default but can be wrong at the margins.
2. **Grant release mismatch.** `removeExternalSong` releases the *per-document*
   persistable permission on the song's URI
   (`ui/screen/library/LibraryViewModel.kt:232-240`). A tree import takes a
   persistable grant on the **tree root**; releasing child document URIs does not
   revoke it. Tree imports need their own grant bookkeeping (release the root),
   which URI-shape inference does not provide.
3. **Never canonicalize on write.** The "raw on write, canonical on query" split is
   what makes this question answerable at all; `asExternalIdentityUri()` must stay
   query-time-only, or the origin information is destroyed.

## 4. Recommendation

Use the URI filter for display/classification (a `isTreeImported` on the entity,
alongside the existing `isImportedExternal` / `isSessionOnlyExternal` in
`extensions/media/MediaExt.kt:196-205`), but if tree import is a real feature, also
record the picked tree root (e.g. a `treeRootUri` column on `ExternalSongEntity`) at
import time — that makes grant release and "remove whole imported tree" robust
regardless of provider URI quirks.

If tree import is implemented, `doc/open-with-audio-intent.md` and the AGENTS.md
external-song notes must be updated to match (project convention).
