/*
 * Copyright (c) 2025 Christians Martínez Alvarado
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.mardous.booming.data.local.repository

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.util.Log
import java.io.File

/**
 * Provider-side metadata of an external file (a content/file URI that MediaStore does
 * not index). Shared by the external-song session probe ([RealSongRepository]) and the
 * imported-song metadata refresh ([RealExternalSongRepository]) so the SIZE /
 * last-modified column handling lives in exactly one place.
 */
internal object ExternalFileMetadata {

    private const val TAG = "ExternalFileMetadata"

    // Any real timestamp in seconds is far below this (current epoch seconds are
    // ~1.7e9; the threshold is year 2286); millisecond timestamps are above it.
    private const val LAST_MODIFIED_MILLIS_THRESHOLD = 10_000_000_000L

    /** File size in bytes, or null when the provider does not expose it (or it is 0). */
    fun size(resolver: ContentResolver, uri: Uri): Long? = runCatching {
        resolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst() && !cursor.isNull(0)) {
                cursor.getLong(0).takeIf { it > 0L }
            } else null
        }
    }.onFailure { Log.e(TAG, "Failed to retrieve size from Uri: $uri", it) }.getOrNull()

    /**
     * Best-effort last-modified time of an external file, in seconds since epoch (the
     * [MediaStore.MediaColumns.DATE_MODIFIED] unit), or null when the provider does not
     * expose it. Documents providers report it in milliseconds under
     * [DocumentsContract.Document.COLUMN_LAST_MODIFIED]; other providers may expose it
     * in seconds under [MediaStore.MediaColumns.DATE_MODIFIED] — the magnitude check
     * normalizes both to seconds.
     */
    fun lastModifiedSeconds(resolver: ContentResolver, uri: Uri): Long? = when (uri.scheme) {
        ContentResolver.SCHEME_FILE -> uri.path
            ?.takeIf { it.isNotEmpty() }
            ?.let(::File)
            ?.lastModified()
            ?.takeIf { it > 0L }
            ?.div(1000L)
        ContentResolver.SCHEME_CONTENT -> arrayOf(
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
            MediaStore.MediaColumns.DATE_MODIFIED
        ).firstNotNullOfOrNull { column ->
            try {
                resolver.query(uri, arrayOf(column), null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst() && !cursor.isNull(0)) {
                        cursor.getLong(0).takeIf { it > 0L }
                    } else null
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to retrieve last modified from Uri: $uri", e)
                null
            }
        }?.let { value ->
            // Seconds values stay below the threshold well past year 2286; millis
            // values (post-1973) are above it, so dividing identifies millis safely.
            if (value > LAST_MODIFIED_MILLIS_THRESHOLD) value / 1000L else value
        }
        else -> null
    }
}
