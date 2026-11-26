package com.alexvid.player.model

import android.net.Uri
import java.util.Locale

data class LocalVideo(
    val id: Long,
    val uri: Uri,
    val title: String,
    val displayName: String,
    val folderName: String,
    val durationMs: Long,
    val sizeBytes: Long,
    val dateAdded: Long,
    val width: Int,
    val height: Int,
    val extension: String
)

data class StreamItem(
    val id: String,
    val title: String,
    val url: String,
    val userAgent: String = "",
    val referer: String = "",
    val isBuiltIn: Boolean = false
)

data class HistoryItem(
    val uriString: String,
    val title: String,
    val isOnline: Boolean,
    val positionMs: Long,
    val durationMs: Long,
    val timestamp: Long,
    val userAgent: String = "",
    val referer: String = ""
)

enum class SortOrder {
    DATE_DESC,
    NAME_ASC,
    SIZE_DESC,
    DURATION_DESC
}

object FormatUtils {
    fun formatDuration(ms: Long): String {
        if (ms <= 0L) return "00:00"
        val totalSeconds = ms / 1000L
        val hours = totalSeconds / 3600L
        val minutes = (totalSeconds % 3600L) / 60L
        val seconds = totalSeconds % 60L
        return if (hours > 0) {
            String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(Locale.US, "%02d:%02d", minutes, seconds)
        }
    }

    fun formatSize(bytes: Long): String {
        if (bytes <= 0L) return ""
        val kb = 1024.0
        val mb = kb * 1024.0
        val gb = mb * 1024.0
        return when {
            bytes >= gb -> String.format(Locale.US, "%.2f ГБ", bytes / gb)
            bytes >= mb -> String.format(Locale.US, "%.1f МБ", bytes / mb)
            bytes >= kb -> String.format(Locale.US, "%.0f КБ", bytes / kb)
            else -> "$bytes Б"
        }
    }

    fun detectProtocolBadge(url: String): String {
        val lower = url.lowercase(Locale.US)
        return when {
            lower.contains(".m3u8") -> "HLS"
            lower.contains(".m3u") -> "IPTV"
            lower.contains(".mpd") -> "DASH"
            lower.startsWith("rtsp://") -> "RTSP"
            lower.startsWith("rtmp://") || lower.startsWith("rtmps://") -> "RTMP"
            lower.startsWith("udp://") -> "UDP"
            lower.contains(".mkv") -> "MKV"
            lower.contains(".mp4") -> "MP4"
            lower.contains(".webm") -> "WEBM"
            lower.contains(".avi") -> "AVI"
            lower.contains(".ts") -> "TS"
            else -> "WEB"
        }
    }

    fun isNetworkUrl(uriString: String): Boolean {
        val lower = uriString.trim().lowercase(Locale.US)
        return lower.startsWith("http://") ||
            lower.startsWith("https://") ||
            lower.startsWith("rtsp://") ||
            lower.startsWith("rtmp://") ||
            lower.startsWith("rtmps://") ||
            lower.startsWith("mms://") ||
            lower.startsWith("mmsh://") ||
            lower.startsWith("udp://")
    }

    fun extractUrlFromText(text: String?): String? {
        if (text.isNullOrBlank()) return null
        val regex = Regex("""(https?|rtsp|rtmps?|mmsh?|udp)://[^\s"'<>]+""", RegexOption.IGNORE_CASE)
        return regex.find(text)?.value
    }
}
