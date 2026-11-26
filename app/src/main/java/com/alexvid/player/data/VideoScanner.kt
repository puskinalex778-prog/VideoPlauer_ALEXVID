package com.alexvid.player.data

import android.content.ContentUris
import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.MediaStore
import android.provider.OpenableColumns
import com.alexvid.player.model.LocalVideo
import java.util.Locale

object VideoScanner {

    fun scanDeviceVideos(context: Context): List<LocalVideo> {
        val videos = mutableListOf<LocalVideo>()
        val collection = MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.TITLE,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.BUCKET_DISPLAY_NAME,
            MediaStore.Video.Media.DURATION,
            MediaStore.Video.Media.SIZE,
            MediaStore.Video.Media.DATE_ADDED,
            MediaStore.Video.Media.WIDTH,
            MediaStore.Video.Media.HEIGHT
        )

        val sortOrder = "${MediaStore.Video.Media.DATE_ADDED} DESC"

        runCatching {
            context.contentResolver.query(
                collection,
                projection,
                null,
                null,
                sortOrder
            )?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
                val titleCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.TITLE)
                val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
                val bucketCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.BUCKET_DISPLAY_NAME)
                val durCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
                val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
                val dateCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_ADDED)
                val widthCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.WIDTH)
                val heightCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.HEIGHT)

                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idCol)
                    val displayName = cursor.getString(nameCol) ?: "video_$id"
                    val title = cursor.getString(titleCol)?.takeIf { it.isNotBlank() }
                        ?: displayName.substringBeforeLast('.')
                    val folder = cursor.getString(bucketCol)?.takeIf { it.isNotBlank() } ?: "Устройство"
                    val duration = cursor.getLong(durCol)
                    val size = cursor.getLong(sizeCol)
                    val dateAdded = cursor.getLong(dateCol)
                    val width = cursor.getInt(widthCol)
                    val height = cursor.getInt(heightCol)
                    val ext = displayName.substringAfterLast('.', "VID").uppercase(Locale.US).take(5)
                    val uri = ContentUris.withAppendedId(collection, id)

                    videos.add(
                        LocalVideo(
                            id = id,
                            uri = uri,
                            title = title,
                            displayName = displayName,
                            folderName = folder,
                            durationMs = duration,
                            sizeBytes = size,
                            dateAdded = dateAdded,
                            width = width,
                            height = height,
                            extension = ext
                        )
                    )
                }
            }
        }
        return videos
    }

    fun resolveDisplayName(context: Context, uri: Uri): String {
        if (uri.scheme == "content") {
            runCatching {
                context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                    if (c.moveToFirst()) {
                        val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (idx >= 0) {
                            val name = c.getString(idx)
                            if (!name.isNullOrBlank()) return name
                        }
                    }
                }
            }
        }
        val last = uri.lastPathSegment?.substringAfterLast('/')?.substringBefore('?')
        return last?.takeIf { it.isNotBlank() } ?: uri.toString()
    }

    /**
     * Builds a LocalVideo entry from a gallery / SAF Uri without requiring full storage access.
     */
    fun extractVideoMetadata(context: Context, uri: Uri, folderName: String = "Из галереи"): LocalVideo {
        var displayName = resolveDisplayName(context, uri)
        var sizeBytes = 0L

        if (uri.scheme == "content") {
            runCatching {
                context.contentResolver.query(
                    uri,
                    arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
                    null,
                    null,
                    null
                )?.use { c ->
                    if (c.moveToFirst()) {
                        val nameIdx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (nameIdx >= 0) {
                            c.getString(nameIdx)?.takeIf { it.isNotBlank() }?.let { displayName = it }
                        }
                        val sizeIdx = c.getColumnIndex(OpenableColumns.SIZE)
                        if (sizeIdx >= 0 && !c.isNull(sizeIdx)) {
                            sizeBytes = c.getLong(sizeIdx)
                        }
                    }
                }
            }
        }

        var durationMs = 0L
        var width = 0
        var height = 0
        val retriever = MediaMetadataRetriever()
        runCatching {
            retriever.setDataSource(context, uri)
            durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
        }
        runCatching { retriever.release() }

        val title = displayName.substringBeforeLast('.').ifBlank { displayName }
        val ext = displayName.substringAfterLast('.', "VID").uppercase(Locale.US).take(5)

        return LocalVideo(
            id = uri.toString().hashCode().toLong(),
            uri = uri,
            title = title,
            displayName = displayName,
            folderName = folderName,
            durationMs = durationMs,
            sizeBytes = sizeBytes,
            dateAdded = System.currentTimeMillis() / 1000L,
            width = width,
            height = height,
            extension = ext
        )
    }
}
