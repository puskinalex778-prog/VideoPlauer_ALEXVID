package com.alexvid.player.data

import android.content.Context
import android.net.Uri
import com.alexvid.player.model.HistoryItem
import com.alexvid.player.model.LocalVideo
import com.alexvid.player.model.SortOrder
import com.alexvid.player.model.StreamItem
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

class PlayerPreferences(context: Context) {

    private val prefs = context.getSharedPreferences("alexvid_prefs", Context.MODE_PRIVATE)

    companion object {
        private const val KEY_POSITIONS = "playback_positions"
        private const val KEY_STREAMS = "saved_streams"
        private const val KEY_HISTORY = "watch_history"
        private const val KEY_SORT = "sort_order"

        // Playback
        private const val KEY_RESUME_PLAYBACK = "resume_playback"
        private const val KEY_AUTO_PLAY_NEXT = "auto_play_next"
        private const val KEY_DEFAULT_SPEED = "default_speed"
        private const val KEY_SEEK_STEP_SEC = "seek_step_sec"
        private const val KEY_ORIENTATION_MODE = "orientation_mode" // 0=Auto, 1=Landscape, 2=Portrait, 3=System
        private const val KEY_DEFAULT_ASPECT = "default_aspect_index" // 0..5
        private const val KEY_BACKGROUND_PLAY = "background_play"
        private const val KEY_KEEP_SCREEN_ON = "keep_screen_on"

        // Video & Decoder
        private const val KEY_HW_DECODE = "hw_decode_mode" // 0=Auto, 1=Full, 2=Software
        private const val KEY_DEINTERLACE = "deinterlace_mode" // 0=Auto, 1=Off, 2=On

        // Audio
        private const val KEY_VOLUME_BOOST = "volume_boost_200"
        private const val KEY_AUDIO_TIME_STRETCH = "audio_time_stretch"
        private const val KEY_AUDIO_DELAY_MS = "audio_delay_ms"

        // Gestures & HUD
        private const val KEY_GESTURE_BRIGHTNESS = "gesture_brightness"
        private const val KEY_GESTURE_VOLUME = "gesture_volume"
        private const val KEY_GESTURE_SEEK = "gesture_seek"
        private const val KEY_GESTURE_DOUBLE_TAP = "gesture_double_tap"
        private const val KEY_GESTURE_LONG_PRESS_2X = "gesture_long_press_2x"
        private const val KEY_HUD_TIMEOUT_SEC = "hud_timeout_sec" // 2, 4, 6, 10, 0=never

        // Subtitles
        private const val KEY_SUB_AUTO_LOAD = "subtitle_auto_load"
        private const val KEY_SUB_SIZE = "subtitle_rel_size" // 20=small, 16=normal, 13=large, 10=huge
        private const val KEY_SUB_COLOR = "subtitle_color" // 16777215=white, 16776960=yellow, 65535=cyan, 65280=green
        private const val KEY_SUB_ENCODING = "subtitle_encoding" // ""=UTF-8/Auto, "CP1251", "ISO-8859-1", "UTF-16"
        private const val KEY_SUB_BG_OPACITY = "subtitle_bg_opacity" // 0, 96, 200

        // Network
        private const val KEY_NETWORK_CACHE = "network_cache_ms"
        private const val KEY_HTTP_RECONNECT = "http_reconnect"
        private const val KEY_DEFAULT_USER_AGENT = "default_user_agent"

        // Screenshots & Review
        private const val KEY_SCREENSHOT_FORMAT = "screenshot_format" // "PNG" or "JPEG"
        private const val KEY_SCREENSHOT_FLASH = "screenshot_flash"

        // Gallery Imports & Cache
        private const val KEY_GALLERY_VIDEOS = "gallery_added_videos"
        private const val KEY_AUTO_CLEAR_CACHE = "auto_clear_cache"

        private const val MAX_HISTORY = 40
    }

    // --- Playback Positions ---

    fun savePosition(key: String, positionMs: Long, durationMs: Long) {
        val obj = loadPositionsJson()
        if (durationMs > 0 && positionMs > durationMs - 5000L) {
            obj.remove(key)
        } else if (positionMs > 3000L) {
            obj.put(key, positionMs)
        }
        prefs.edit().putString(KEY_POSITIONS, obj.toString()).apply()
    }

    fun getPosition(key: String): Long {
        return loadPositionsJson().optLong(key, 0L)
    }

    private fun loadPositionsJson(): JSONObject {
        val raw = prefs.getString(KEY_POSITIONS, null) ?: return JSONObject()
        return runCatching { JSONObject(raw) }.getOrDefault(JSONObject())
    }

    // --- History ---

    fun addToHistory(item: HistoryItem) {
        val list = getHistory().toMutableList()
        list.removeAll { it.uriString == item.uriString }
        list.add(0, item)
        val trimmed = if (list.size > MAX_HISTORY) list.subList(0, MAX_HISTORY) else list
        val arr = JSONArray()
        for (h in trimmed) {
            arr.put(
                JSONObject().apply {
                    put("uri", h.uriString)
                    put("title", h.title)
                    put("online", h.isOnline)
                    put("pos", h.positionMs)
                    put("dur", h.durationMs)
                    put("ts", h.timestamp)
                    put("ua", h.userAgent)
                    put("ref", h.referer)
                }
            )
        }
        prefs.edit().putString(KEY_HISTORY, arr.toString()).apply()
    }

    fun getHistory(): List<HistoryItem> {
        val raw = prefs.getString(KEY_HISTORY, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            val result = mutableListOf<HistoryItem>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                result.add(
                    HistoryItem(
                        uriString = o.optString("uri"),
                        title = o.optString("title"),
                        isOnline = o.optBoolean("online", false),
                        positionMs = o.optLong("pos", 0L),
                        durationMs = o.optLong("dur", 0L),
                        timestamp = o.optLong("ts", 0L),
                        userAgent = o.optString("ua", ""),
                        referer = o.optString("ref", "")
                    )
                )
            }
            result
        }.getOrDefault(emptyList())
    }

    fun clearHistory() {
        prefs.edit().remove(KEY_HISTORY).apply()
    }

    // --- Saved Online Streams ---

    fun getStreams(): List<StreamItem> {
        val userStreams = loadUserStreams()
        val defaults = listOf(
            StreamItem(
                id = "demo_hls_bbb",
                title = "Big Buck Bunny (Тест HLS потока)",
                url = "https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8",
                isBuiltIn = true
            ),
            StreamItem(
                id = "demo_mp4_sintel",
                title = "Sintel Trailer (Тест HD MP4)",
                url = "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/Sintel.mp4",
                isBuiltIn = true
            )
        )
        return userStreams + defaults
    }

    fun saveStream(title: String, url: String, userAgent: String = "", referer: String = "") {
        val current = loadUserStreams().toMutableList()
        current.removeAll { it.url == url }
        current.add(
            0,
            StreamItem(
                id = UUID.randomUUID().toString(),
                title = title.ifBlank { url.substringAfterLast('/').substringBefore('?').ifBlank { "Онлайн поток" } },
                url = url,
                userAgent = userAgent,
                referer = referer,
                isBuiltIn = false
            )
        )
        persistUserStreams(current)
    }

    fun deleteStream(id: String) {
        val current = loadUserStreams().toMutableList()
        current.removeAll { it.id == id }
        persistUserStreams(current)
    }

    private fun loadUserStreams(): List<StreamItem> {
        val raw = prefs.getString(KEY_STREAMS, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            val result = mutableListOf<StreamItem>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                result.add(
                    StreamItem(
                        id = o.optString("id"),
                        title = o.optString("title"),
                        url = o.optString("url"),
                        userAgent = o.optString("ua", ""),
                        referer = o.optString("ref", ""),
                        isBuiltIn = false
                    )
                )
            }
            result
        }.getOrDefault(emptyList())
    }

    private fun persistUserStreams(list: List<StreamItem>) {
        val arr = JSONArray()
        for (s in list) {
            arr.put(
                JSONObject().apply {
                    put("id", s.id)
                    put("title", s.title)
                    put("url", s.url)
                    put("ua", s.userAgent)
                    put("ref", s.referer)
                }
            )
        }
        prefs.edit().putString(KEY_STREAMS, arr.toString()).apply()
    }

    // --- Library ---

    var sortOrder: SortOrder
        get() = runCatching {
            SortOrder.valueOf(prefs.getString(KEY_SORT, SortOrder.DATE_DESC.name) ?: SortOrder.DATE_DESC.name)
        }.getOrDefault(SortOrder.DATE_DESC)
        set(value) = prefs.edit().putString(KEY_SORT, value.name).apply()

    // --- Playback Settings ---

    var resumePlayback: Boolean
        get() = prefs.getBoolean(KEY_RESUME_PLAYBACK, true)
        set(value) = prefs.edit().putBoolean(KEY_RESUME_PLAYBACK, value).apply()

    var autoPlayNext: Boolean
        get() = prefs.getBoolean(KEY_AUTO_PLAY_NEXT, true)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_PLAY_NEXT, value).apply()

    var defaultSpeed: Float
        get() = prefs.getFloat(KEY_DEFAULT_SPEED, 1.0f).coerceIn(0.25f, 3.0f)
        set(value) = prefs.edit().putFloat(KEY_DEFAULT_SPEED, value.coerceIn(0.25f, 3.0f)).apply()

    var seekStepSeconds: Int
        get() = prefs.getInt(KEY_SEEK_STEP_SEC, 10).coerceIn(3, 120)
        set(value) = prefs.edit().putInt(KEY_SEEK_STEP_SEC, value.coerceIn(3, 120)).apply()

    var orientationMode: Int
        get() = prefs.getInt(KEY_ORIENTATION_MODE, 0)
        set(value) = prefs.edit().putInt(KEY_ORIENTATION_MODE, value).apply()

    var defaultAspectIndex: Int
        get() = prefs.getInt(KEY_DEFAULT_ASPECT, 0).coerceIn(0, 5)
        set(value) = prefs.edit().putInt(KEY_DEFAULT_ASPECT, value.coerceIn(0, 5)).apply()

    var backgroundPlay: Boolean
        get() = prefs.getBoolean(KEY_BACKGROUND_PLAY, false)
        set(value) = prefs.edit().putBoolean(KEY_BACKGROUND_PLAY, value).apply()

    var keepScreenOn: Boolean
        get() = prefs.getBoolean(KEY_KEEP_SCREEN_ON, true)
        set(value) = prefs.edit().putBoolean(KEY_KEEP_SCREEN_ON, value).apply()

    // --- Video & Decoder ---

    var hwDecodingMode: Int
        get() = prefs.getInt(KEY_HW_DECODE, 0)
        set(value) = prefs.edit().putInt(KEY_HW_DECODE, value).apply()

    var deinterlaceMode: Int
        get() = prefs.getInt(KEY_DEINTERLACE, 0)
        set(value) = prefs.edit().putInt(KEY_DEINTERLACE, value).apply()

    // --- Audio ---

    var volumeBoostEnabled: Boolean
        get() = prefs.getBoolean(KEY_VOLUME_BOOST, true)
        set(value) = prefs.edit().putBoolean(KEY_VOLUME_BOOST, value).apply()

    var audioTimeStretch: Boolean
        get() = prefs.getBoolean(KEY_AUDIO_TIME_STRETCH, true)
        set(value) = prefs.edit().putBoolean(KEY_AUDIO_TIME_STRETCH, value).apply()

    var defaultAudioDelayMs: Int
        get() = prefs.getInt(KEY_AUDIO_DELAY_MS, 0).coerceIn(-1000, 1000)
        set(value) = prefs.edit().putInt(KEY_AUDIO_DELAY_MS, value.coerceIn(-1000, 1000)).apply()

    // --- Gestures & HUD ---

    var gestureBrightness: Boolean
        get() = prefs.getBoolean(KEY_GESTURE_BRIGHTNESS, true)
        set(value) = prefs.edit().putBoolean(KEY_GESTURE_BRIGHTNESS, value).apply()

    var gestureVolume: Boolean
        get() = prefs.getBoolean(KEY_GESTURE_VOLUME, true)
        set(value) = prefs.edit().putBoolean(KEY_GESTURE_VOLUME, value).apply()

    var gestureSeek: Boolean
        get() = prefs.getBoolean(KEY_GESTURE_SEEK, true)
        set(value) = prefs.edit().putBoolean(KEY_GESTURE_SEEK, value).apply()

    var gestureDoubleTap: Boolean
        get() = prefs.getBoolean(KEY_GESTURE_DOUBLE_TAP, true)
        set(value) = prefs.edit().putBoolean(KEY_GESTURE_DOUBLE_TAP, value).apply()

    var gestureLongPress2x: Boolean
        get() = prefs.getBoolean(KEY_GESTURE_LONG_PRESS_2X, true)
        set(value) = prefs.edit().putBoolean(KEY_GESTURE_LONG_PRESS_2X, value).apply()

    var hudTimeoutSeconds: Int
        get() = prefs.getInt(KEY_HUD_TIMEOUT_SEC, 4)
        set(value) = prefs.edit().putInt(KEY_HUD_TIMEOUT_SEC, value).apply()

    // --- Subtitles ---

    var subtitleAutoLoad: Boolean
        get() = prefs.getBoolean(KEY_SUB_AUTO_LOAD, true)
        set(value) = prefs.edit().putBoolean(KEY_SUB_AUTO_LOAD, value).apply()

    // LibVLC freetype-rel-fontsize: smaller number = larger font! (20=small, 16=normal, 13=large, 10=huge)
    var subtitleRelFontSize: Int
        get() = prefs.getInt(KEY_SUB_SIZE, 16)
        set(value) = prefs.edit().putInt(KEY_SUB_SIZE, value).apply()

    // LibVLC freetype-color decimal RGB (16777215 = white, 16776960 = yellow, 65535 = cyan, 65280 = green)
    var subtitleColor: Int
        get() = prefs.getInt(KEY_SUB_COLOR, 16777215)
        set(value) = prefs.edit().putInt(KEY_SUB_COLOR, value).apply()

    var subtitleEncoding: String
        get() = prefs.getString(KEY_SUB_ENCODING, "") ?: ""
        set(value) = prefs.edit().putString(KEY_SUB_ENCODING, value).apply()

    var subtitleBgOpacity: Int
        get() = prefs.getInt(KEY_SUB_BG_OPACITY, 96).coerceIn(0, 255)
        set(value) = prefs.edit().putInt(KEY_SUB_BG_OPACITY, value.coerceIn(0, 255)).apply()

    // --- Network ---

    var networkCachingMs: Int
        get() = prefs.getInt(KEY_NETWORK_CACHE, 2000)
        set(value) = prefs.edit().putInt(KEY_NETWORK_CACHE, value.coerceIn(500, 10000)).apply()

    var httpReconnect: Boolean
        get() = prefs.getBoolean(KEY_HTTP_RECONNECT, true)
        set(value) = prefs.edit().putBoolean(KEY_HTTP_RECONNECT, value).apply()

    var defaultUserAgent: String
        get() = prefs.getString(KEY_DEFAULT_USER_AGENT, "") ?: ""
        set(value) = prefs.edit().putString(KEY_DEFAULT_USER_AGENT, value).apply()

    // --- Screenshots ---

    var screenshotFormat: String
        get() = prefs.getString(KEY_SCREENSHOT_FORMAT, "PNG") ?: "PNG"
        set(value) = prefs.edit().putString(KEY_SCREENSHOT_FORMAT, value).apply()

    var screenshotFlash: Boolean
        get() = prefs.getBoolean(KEY_SCREENSHOT_FLASH, true)
        set(value) = prefs.edit().putBoolean(KEY_SCREENSHOT_FLASH, value).apply()

    // --- Cache ---

    var autoClearCache: Boolean
        get() = prefs.getBoolean(KEY_AUTO_CLEAR_CACHE, true)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_CLEAR_CACHE, value).apply()

    // --- Gallery Imported Videos ---

    fun getGalleryVideos(): List<LocalVideo> {
        val raw = prefs.getString(KEY_GALLERY_VIDEOS, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            val result = mutableListOf<LocalVideo>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val uriStr = o.optString("uri")
                if (uriStr.isBlank()) continue
                result.add(
                    LocalVideo(
                        id = o.optLong("id", uriStr.hashCode().toLong()),
                        uri = Uri.parse(uriStr),
                        title = o.optString("title", "Видео"),
                        displayName = o.optString("name", "video.mp4"),
                        folderName = "Из галереи",
                        durationMs = o.optLong("dur", 0L),
                        sizeBytes = o.optLong("size", 0L),
                        dateAdded = o.optLong("date", System.currentTimeMillis() / 1000L),
                        width = o.optInt("w", 0),
                        height = o.optInt("h", 0),
                        extension = o.optString("ext", "VID")
                    )
                )
            }
            result
        }.getOrDefault(emptyList())
    }

    fun addGalleryVideos(newVideos: List<LocalVideo>) {
        if (newVideos.isEmpty()) return
        val current = getGalleryVideos().toMutableList()
        for (vid in newVideos) {
            current.removeAll { it.uri.toString() == vid.uri.toString() }
            current.add(0, vid.copy(folderName = "Из галереи"))
        }
        persistGalleryVideos(current)
    }

    fun removeGalleryVideo(uriString: String) {
        val current = getGalleryVideos().toMutableList()
        current.removeAll { it.uri.toString() == uriString }
        persistGalleryVideos(current)
    }

    private fun persistGalleryVideos(list: List<LocalVideo>) {
        val arr = JSONArray()
        for (v in list) {
            arr.put(
                JSONObject().apply {
                    put("id", v.id)
                    put("uri", v.uri.toString())
                    put("title", v.title)
                    put("name", v.displayName)
                    put("dur", v.durationMs)
                    put("size", v.sizeBytes)
                    put("date", v.dateAdded)
                    put("w", v.width)
                    put("h", v.height)
                    put("ext", v.extension)
                }
            )
        }
        prefs.edit().putString(KEY_GALLERY_VIDEOS, arr.toString()).apply()
    }

    /**
     * Resets all player settings to factory defaults while preserving bookmarks, gallery imports & history.
     */
    fun resetAllSettings() {
        val savedPositions = prefs.getString(KEY_POSITIONS, null)
        val savedStreams = prefs.getString(KEY_STREAMS, null)
        val savedHistory = prefs.getString(KEY_HISTORY, null)
        val savedGallery = prefs.getString(KEY_GALLERY_VIDEOS, null)
        prefs.edit().clear().apply()
        prefs.edit().apply {
            if (savedPositions != null) putString(KEY_POSITIONS, savedPositions)
            if (savedStreams != null) putString(KEY_STREAMS, savedStreams)
            if (savedHistory != null) putString(KEY_HISTORY, savedHistory)
            if (savedGallery != null) putString(KEY_GALLERY_VIDEOS, savedGallery)
        }.apply()
    }
}
