package com.alexvid.player.data

import android.content.Context
import com.bumptech.glide.Glide
import java.io.File

object CacheHelper {

    fun getTotalCacheSizeBytes(context: Context): Long {
        var total = dirSize(context.cacheDir)
        context.externalCacheDir?.let { total += dirSize(it) }
        return total
    }

    /**
     * Clears Glide disk thumbnail cache, temporary subtitles, and any leftover cache files.
     * Must be called from a background (IO) thread.
     */
    fun clearAllCache(context: Context) {
        runCatching { Glide.get(context).clearDiskCache() }
        deleteChildren(context.cacheDir)
        context.externalCacheDir?.let { deleteChildren(it) }
    }

    /**
     * Cleans temporary subtitle copies so they never accumulate across sessions.
     */
    fun clearTemporarySubtitles(context: Context) {
        val subDir = File(context.cacheDir, "subtitles")
        if (subDir.exists() && subDir.isDirectory) {
            deleteChildren(subDir)
        }
    }

    private fun dirSize(dir: File?): Long {
        if (dir == null || !dir.exists()) return 0L
        var size = 0L
        val files = dir.listFiles() ?: return 0L
        for (file in files) {
            size += if (file.isDirectory) dirSize(file) else file.length()
        }
        return size
    }

    private fun deleteChildren(dir: File?) {
        if (dir == null || !dir.exists()) return
        val files = dir.listFiles() ?: return
        for (file in files) {
            runCatching {
                if (file.isDirectory) {
                    deleteChildren(file)
                }
                file.delete()
            }
        }
    }
}
