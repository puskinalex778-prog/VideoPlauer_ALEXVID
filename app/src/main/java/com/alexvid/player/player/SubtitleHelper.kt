package com.alexvid.player.player

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.util.Locale

object SubtitleHelper {

    private val SUPPORTED_EXTENSIONS = listOf("srt", "ass", "ssa", "vtt", "sub", "smi", "idx")

    /**
     * Copies a content:// or file:// subtitle Uri into app cache so LibVLC's native
     * C++ engine can always read it reliably across all Android Scoped Storage versions.
     */
    fun copySubtitleToCache(context: Context, uri: Uri): Uri? {
        return runCatching {
            val subDir = File(context.cacheDir, "subtitles").apply { mkdirs() }
            // Keep cache tidy (remove older temp subtitles beyond 10)
            subDir.listFiles()?.sortedByDescending { it.lastModified() }?.drop(10)?.forEach { it.delete() }

            val originalName = resolveName(context, uri) ?: "subtitle_${System.currentTimeMillis()}.srt"
            val ext = originalName.substringAfterLast('.', "srt").lowercase(Locale.US)
            val safeExt = if (ext in SUPPORTED_EXTENSIONS) ext else "srt"
            val outFile = File(subDir, "sub_${System.currentTimeMillis()}.$safeExt")

            context.contentResolver.openInputStream(uri)?.use { input ->
                outFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            } ?: return null

            Uri.fromFile(outFile)
        }.getOrNull()
    }

    /**
     * Searches the same directory for companion subtitle files with matching base name
     * when opening a local file:// video.
     */
    fun findCompanionSubtitles(videoUri: Uri): List<Uri> {
        if (videoUri.scheme != "file") return emptyList()
        val path = videoUri.path ?: return emptyList()
        val videoFile = File(path)
        val parent = videoFile.parentFile ?: return emptyList()
        val baseName = videoFile.nameWithoutExtension
        val found = mutableListOf<Uri>()
        for (ext in SUPPORTED_EXTENSIONS) {
            val candidate = File(parent, "$baseName.$ext")
            if (candidate.exists() && candidate.isFile && candidate.canRead()) {
                found.add(Uri.fromFile(candidate))
            }
        }
        return found
    }

    private fun resolveName(context: Context, uri: Uri): String? {
        if (uri.scheme == "content") {
            runCatching {
                context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                    if (c.moveToFirst()) {
                        val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (idx >= 0) return c.getString(idx)
                    }
                }
            }
        }
        return uri.lastPathSegment?.substringAfterLast('/')
    }
}
