package com.alexvid.player.data

import android.content.Context
import com.alexvid.player.model.HistoryItem
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
        private const val KEY_HW_DECODE = "hw_decode_mode" // 0=Auto, 1=Full, 2=Software
        private const val KEY_NETWORK_CACHE = "network_cache_ms"
        private const val KEY_SUB_SIZE = "subtitle_rel_size" // 20=small, 16=normal, 13=large, 10=huge
        private const val KEY_SUB_COLOR = "subtitle_color" // 16777215=white, 16776960=yellow, 65535=cyan
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

    // --- Preferences ---

    var sortOrder: SortOrder
        get() = runCatching {
            SortOrder.valueOf(prefs.getString(KEY_SORT, SortOrder.DATE_DESC.name) ?: SortOrder.DATE_DESC.name)
        }.getOrDefault(SortOrder.DATE_DESC)
        set(value) = prefs.edit().putString(KEY_SORT, value.name).apply()

    var hwDecodingMode: Int
        get() = prefs.getInt(KEY_HW_DECODE, 0)
        set(value) = prefs.edit().putInt(KEY_HW_DECODE, value).apply()

    var networkCachingMs: Int
        get() = prefs.getInt(KEY_NETWORK_CACHE, 2000)
        set(value) = prefs.edit().putInt(KEY_NETWORK_CACHE, value.coerceIn(500, 10000)).apply()

    // LibVLC freetype-rel-fontsize: smaller number = larger font! (20=small, 16=normal, 13=large, 10=huge)
    var subtitleRelFontSize: Int
        get() = prefs.getInt(KEY_SUB_SIZE, 16)
        set(value) = prefs.edit().putInt(KEY_SUB_SIZE, value).apply()

    // LibVLC freetype-color decimal RGB (16777215 = white, 16776960 = yellow, 65535 = cyan)
    var subtitleColor: Int
        get() = prefs.getInt(KEY_SUB_COLOR, 16777215)
        set(value) = prefs.edit().putInt(KEY_SUB_COLOR, value).apply()
}
