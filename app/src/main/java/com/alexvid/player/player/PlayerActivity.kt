package com.alexvid.player.player

import android.app.PictureInPictureParams
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.provider.Settings
import android.util.Rational
import android.view.View
import android.widget.RadioButton
import android.widget.SeekBar
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.alexvid.player.R
import com.alexvid.player.data.PlayerPreferences
import com.alexvid.player.data.VideoScanner
import com.alexvid.player.databinding.ActivityPlayerBinding
import com.alexvid.player.databinding.DialogSubtitlesBinding
import com.alexvid.player.model.FormatUtils
import com.alexvid.player.model.HistoryItem
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.interfaces.IMedia
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

class PlayerActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_URI = "extra_uri"
        const val EXTRA_TITLE = "extra_title"
        const val EXTRA_USER_AGENT = "extra_user_agent"
        const val EXTRA_REFERER = "extra_referer"
        const val EXTRA_CACHE_MS = "extra_cache_ms"
        const val EXTRA_PLAYLIST_URIS = "extra_playlist_uris"
        const val EXTRA_PLAYLIST_TITLES = "extra_playlist_titles"
        const val EXTRA_PLAYLIST_INDEX = "extra_playlist_index"

        fun createIntent(
            context: Context,
            uriString: String,
            title: String = "",
            userAgent: String = "",
            referer: String = "",
            cacheMs: Int = -1,
            playlistUris: ArrayList<String>? = null,
            playlistTitles: ArrayList<String>? = null,
            playlistIndex: Int = 0
        ): Intent {
            return Intent(context, PlayerActivity::class.java).apply {
                putExtra(EXTRA_URI, uriString)
                putExtra(EXTRA_TITLE, title)
                putExtra(EXTRA_USER_AGENT, userAgent)
                putExtra(EXTRA_REFERER, referer)
                putExtra(EXTRA_CACHE_MS, cacheMs)
                if (playlistUris != null) {
                    putStringArrayListExtra(EXTRA_PLAYLIST_URIS, playlistUris)
                    putStringArrayListExtra(EXTRA_PLAYLIST_TITLES, playlistTitles)
                    putExtra(EXTRA_PLAYLIST_INDEX, playlistIndex)
                }
            }
        }
    }

    private lateinit var binding: ActivityPlayerBinding
    private lateinit var prefs: PlayerPreferences
    private lateinit var audioManager: AudioManager

    private var libVLC: LibVLC? = null
    private var mediaPlayer: MediaPlayer? = null
    private var activePfd: ParcelFileDescriptor? = null

    private var currentUriString: String = ""
    private var currentTitle: String = ""
    private var currentUserAgent: String = ""
    private var currentReferer: String = ""
    private var currentCacheMs: Int = 2000
    private var isOnlineStream: Boolean = false

    private var playlistUris: List<String> = emptyList()
    private var playlistTitles: List<String> = emptyList()
    private var playlistIndex: Int = 0

    private var isControlsVisible = true
    private var isLocked = false
    private var isRepeatEnabled = false
    private var isSeekingFromBar = false
    private var pendingResumePosition = 0L
    private var hasAppliedResume = false
    private var normalPlaybackRate = 1.0f
    private var softwareVolumeBoost = 100 // 100..200

    private val scaleModes = listOf(
        MediaPlayer.ScaleType.SURFACE_BEST_FIT to R.string.scale_best_fit,
        MediaPlayer.ScaleType.SURFACE_FIT_SCREEN to R.string.scale_fit_screen,
        MediaPlayer.ScaleType.SURFACE_FILL to R.string.scale_fill,
        MediaPlayer.ScaleType.SURFACE_16_9 to R.string.scale_16_9,
        MediaPlayer.ScaleType.SURFACE_4_3 to R.string.scale_4_3,
        MediaPlayer.ScaleType.SURFACE_ORIGINAL to R.string.scale_original
    )
    private var currentScaleIndex = 0

    private val handler = Handler(Looper.getMainLooper())
    private val hideControlsRunnable = Runnable { setControlsVisible(false) }
    private val hideOsdRunnable = Runnable { binding.layoutOsd.visibility = View.GONE }
    private val hideDoubleTapRunnable = Runnable {
        binding.tvDoubleTapLeft.visibility = View.GONE
        binding.tvDoubleTapRight.visibility = View.GONE
    }
    private val hideUnlockBtnRunnable = Runnable {
        if (isLocked) binding.btnUnlock.visibility = View.GONE
    }
    private var sleepTimerRunnable: Runnable? = null

    private val pickSubtitleLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            loadExternalSubtitle(uri)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = PlayerPreferences(this)
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager

        setupImmersiveSystemUi()
        initVlcEngine()
        setupControls()
        setupGestures()
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        saveCurrentProgress()
        handleIntent(intent)
    }

    private fun setupImmersiveSystemUi() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowInsetsControllerCompat(window, binding.root)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }

    private fun initVlcEngine() {
        val options = arrayListOf(
            "--aout=opensles",
            "--audio-time-stretch",
            "--sub-autodetect-file",
            "--freetype-rel-fontsize=${prefs.subtitleRelFontSize}",
            "--freetype-color=${prefs.subtitleColor}",
            "--freetype-outline-thickness=4",
            "--freetype-background-opacity=96",
            "--http-reconnect",
            "--avcodec-fast"
        )
        libVLC = LibVLC(this, options)
        mediaPlayer = MediaPlayer(libVLC).apply {
            attachViews(binding.vlcVideoLayout, null, true, false)
            videoScale = scaleModes[currentScaleIndex].first
            setEventListener { event -> handleVlcEvent(event) }
        }
    }

    private fun handleIntent(intent: Intent) {
        playlistUris = intent.getStringArrayListExtra(EXTRA_PLAYLIST_URIS) ?: emptyList()
        playlistTitles = intent.getStringArrayListExtra(EXTRA_PLAYLIST_TITLES) ?: emptyList()
        playlistIndex = intent.getIntExtra(EXTRA_PLAYLIST_INDEX, 0)

        var uriString = intent.getStringExtra(EXTRA_URI).orEmpty()
        var title = intent.getStringExtra(EXTRA_TITLE).orEmpty()
        currentUserAgent = intent.getStringExtra(EXTRA_USER_AGENT).orEmpty()
        currentReferer = intent.getStringExtra(EXTRA_REFERER).orEmpty()
        val extraCache = intent.getIntExtra(EXTRA_CACHE_MS, -1)
        currentCacheMs = if (extraCache > 0) extraCache else prefs.networkCachingMs

        if (uriString.isBlank()) {
            when (intent.action) {
                Intent.ACTION_VIEW -> {
                    val dataUri = intent.data
                    if (dataUri != null) {
                        uriString = dataUri.toString()
                        title = VideoScanner.resolveDisplayName(this, dataUri)
                    }
                }

                Intent.ACTION_SEND -> {
                    val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT)
                    val extracted = FormatUtils.extractUrlFromText(sharedText)
                    if (!extracted.isNullOrBlank()) {
                        uriString = extracted
                        title = intent.getStringExtra(Intent.EXTRA_SUBJECT)
                            ?.takeIf { it.isNotBlank() }
                            ?: VideoScanner.resolveDisplayName(this, Uri.parse(extracted))
                    }
                }
            }
        }

        if (uriString.isBlank()) {
            Toast.makeText(this, R.string.invalid_url, Toast.LENGTH_LONG).show()
            finish()
            return
        }

        if (title.isBlank()) {
            title = VideoScanner.resolveDisplayName(this, Uri.parse(uriString))
        }

        updatePlaylistButtons()
        playMedia(uriString, title)
    }

    private fun playMedia(uriString: String, title: String) {
        val player = mediaPlayer ?: return
        val vlc = libVLC ?: return

        currentUriString = uriString
        currentTitle = title
        isOnlineStream = FormatUtils.isNetworkUrl(uriString)
        hasAppliedResume = false
        pendingResumePosition = prefs.getPosition(uriString)
        normalPlaybackRate = 1.0f
        softwareVolumeBoost = 100

        binding.tvVideoTitle.text = title
        binding.tvStreamBadge.visibility = if (isOnlineStream) View.VISIBLE else View.GONE
        binding.btnSpeed.text = "1.0x"
        binding.layoutError.visibility = View.GONE
        binding.layoutBuffering.visibility = View.VISIBLE
        binding.tvBuffering.text = getString(R.string.loading_video)
        binding.seekBar.progress = 0
        binding.tvCurrentTime.text = "00:00"
        binding.tvTotalTime.text = "00:00"

        player.stop()
        closeActivePfd()

        val uri = Uri.parse(uriString)
        val media: Media = try {
            if (uri.scheme == "content") {
                val pfd = contentResolver.openFileDescriptor(uri, "r")
                    ?: throw IllegalStateException("Cannot open content descriptor")
                activePfd = pfd
                Media(vlc, pfd.fileDescriptor)
            } else {
                Media(vlc, uri)
            }
        } catch (e: Exception) {
            showPlaybackError(e.localizedMessage)
            return
        }

        // Configure hardware decoding mode
        when (prefs.hwDecodingMode) {
            0 -> media.setHWDecoderEnabled(true, false) // Auto with SW fallback
            1 -> media.setHWDecoderEnabled(true, true)  // Full HW
            2 -> media.setHWDecoderEnabled(false, false) // Software (FFmpeg)
        }

        // Streaming & buffering options
        media.addOption(":network-caching=$currentCacheMs")
        media.addOption(":file-caching=500")
        media.addOption(":live-caching=$currentCacheMs")
        if (currentUserAgent.isNotBlank()) {
            media.addOption(":http-user-agent=$currentUserAgent")
        }
        if (currentReferer.isNotBlank()) {
            media.addOption(":http-referrer=$currentReferer")
        }

        player.media = media
        media.release()
        player.rate = normalPlaybackRate
        player.setVolume(softwareVolumeBoost)
        player.play()

        // Auto-detect companion subtitle files for file:// videos
        if (!isOnlineStream) {
            val companionSubs = SubtitleHelper.findCompanionSubtitles(uri)
            companionSubs.firstOrNull()?.let { subUri ->
                player.addSlave(IMedia.Slave.Type.Subtitle, subUri, true)
            }
        }

        // Record in history immediately
        prefs.addToHistory(
            HistoryItem(
                uriString = uriString,
                title = title,
                isOnline = isOnlineStream,
                positionMs = pendingResumePosition,
                durationMs = 0L,
                timestamp = System.currentTimeMillis(),
                userAgent = currentUserAgent,
                referer = currentReferer
            )
        )

        setControlsVisible(true)
    }

    private fun handleVlcEvent(event: MediaPlayer.Event) {
        val player = mediaPlayer ?: return
        runOnUiThread {
            if (isFinishing || isDestroyed) return@runOnUiThread
            when (event.type) {
                MediaPlayer.Event.Opening -> {
                    binding.layoutError.visibility = View.GONE
                    binding.layoutBuffering.visibility = View.VISIBLE
                    binding.tvBuffering.text = getString(R.string.loading_video)
                }

                MediaPlayer.Event.Buffering -> {
                    val pct = event.buffering.roundToInt().coerceIn(0, 100)
                    if (pct >= 100) {
                        binding.layoutBuffering.visibility = View.GONE
                    } else {
                        binding.layoutBuffering.visibility = View.VISIBLE
                        binding.tvBuffering.text = "${getString(R.string.buffering)} $pct%"
                    }
                }

                MediaPlayer.Event.Playing -> {
                    binding.layoutBuffering.visibility = View.GONE
                    binding.layoutError.visibility = View.GONE
                    binding.btnPlayPause.setImageResource(R.drawable.ic_pause)
                    if (!hasAppliedResume && pendingResumePosition > 3000L && player.length > pendingResumePosition + 5000L) {
                        hasAppliedResume = true
                        player.setTime(pendingResumePosition)
                    }
                    scheduleHideControls()
                }

                MediaPlayer.Event.Paused -> {
                    binding.btnPlayPause.setImageResource(R.drawable.ic_play)
                    handler.removeCallbacks(hideControlsRunnable)
                }

                MediaPlayer.Event.LengthChanged -> {
                    val len = event.lengthChanged
                    if (len > 0L) {
                        binding.tvTotalTime.text = FormatUtils.formatDuration(len)
                        if (!hasAppliedResume && pendingResumePosition > 3000L && len > pendingResumePosition + 5000L) {
                            hasAppliedResume = true
                            player.setTime(pendingResumePosition)
                        }
                    }
                }

                MediaPlayer.Event.TimeChanged -> {
                    val time = event.timeChanged
                    val len = player.length
                    if (!isSeekingFromBar) {
                        binding.tvCurrentTime.text = FormatUtils.formatDuration(time)
                        if (len > 0L) {
                            binding.tvTotalTime.text = FormatUtils.formatDuration(len)
                            val progress = ((time.toDouble() / len.toDouble()) * 1000.0).roundToInt()
                                .coerceIn(0, 1000)
                            binding.seekBar.progress = progress
                        }
                    }
                }

                MediaPlayer.Event.EndReached -> {
                    prefs.savePosition(currentUriString, 0L, player.length)
                    when {
                        isRepeatEnabled -> playMedia(currentUriString, currentTitle)
                        playlistUris.isNotEmpty() && playlistIndex + 1 < playlistUris.size -> {
                            playlistIndex++
                            updatePlaylistButtons()
                            playMedia(
                                playlistUris[playlistIndex],
                                playlistTitles.getOrElse(playlistIndex) { "" }
                            )
                        }

                        else -> {
                            binding.btnPlayPause.setImageResource(R.drawable.ic_play)
                            setControlsVisible(true)
                        }
                    }
                }

                MediaPlayer.Event.EncounteredError -> {
                    binding.layoutBuffering.visibility = View.GONE
                    showPlaybackError(null)
                }
            }
        }
    }

    private fun showPlaybackError(detail: String?) {
        binding.layoutError.visibility = View.VISIBLE
        binding.tvErrorMessage.text = if (detail.isNullOrBlank()) {
            getString(R.string.error_playback)
        } else {
            "${getString(R.string.error_playback)}\n($detail)"
        }
    }

    private fun setupControls() {
        binding.btnBack.setOnClickListener { finish() }

        binding.btnPlayPause.setOnClickListener {
            togglePlayPause()
            scheduleHideControls()
        }

        binding.btnRewind.setOnClickListener {
            seekBy(-10_000L)
            showDoubleTapFeedback(left = true)
            scheduleHideControls()
        }

        binding.btnForward.setOnClickListener {
            seekBy(10_000L)
            showDoubleTapFeedback(left = false)
            scheduleHideControls()
        }

        binding.btnPrevVideo.setOnClickListener {
            if (playlistIndex > 0) {
                saveCurrentProgress()
                playlistIndex--
                updatePlaylistButtons()
                playMedia(playlistUris[playlistIndex], playlistTitles.getOrElse(playlistIndex) { "" })
            }
        }

        binding.btnNextVideo.setOnClickListener {
            if (playlistIndex + 1 < playlistUris.size) {
                saveCurrentProgress()
                playlistIndex++
                updatePlaylistButtons()
                playMedia(playlistUris[playlistIndex], playlistTitles.getOrElse(playlistIndex) { "" })
            }
        }

        binding.btnLock.setOnClickListener {
            isLocked = true
            setControlsVisible(false)
            binding.btnUnlock.visibility = View.VISIBLE
            scheduleHideUnlockButton()
            showOsd(R.drawable.ic_lock, getString(R.string.screen_locked))
        }

        binding.btnUnlock.setOnClickListener {
            isLocked = false
            binding.btnUnlock.visibility = View.GONE
            setControlsVisible(true)
            showOsd(R.drawable.ic_unlock, getString(R.string.screen_unlocked))
        }

        binding.btnRotate.setOnClickListener {
            requestedOrientation = if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) {
                ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
            } else {
                ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            }
            scheduleHideControls()
        }

        binding.btnPip.visibility = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) View.VISIBLE else View.GONE
        binding.btnPip.setOnClickListener { enterPipMode() }

        binding.btnSpeed.setOnClickListener { showSpeedDialog() }
        binding.btnSleepTimer.setOnClickListener { showSleepTimerDialog() }
        binding.btnSubtitles.setOnClickListener { showSubtitlesDialog() }
        binding.btnAudioTrack.setOnClickListener { showAudioTracksDialog() }

        binding.btnRepeat.setOnClickListener {
            isRepeatEnabled = !isRepeatEnabled
            binding.btnRepeat.alpha = if (isRepeatEnabled) 1.0f else 0.45f
            showOsd(
                R.drawable.ic_repeat,
                getString(if (isRepeatEnabled) R.string.repeat_on else R.string.repeat_off)
            )
            scheduleHideControls()
        }
        binding.btnRepeat.alpha = 0.45f

        binding.btnAspectRatio.setOnClickListener {
            currentScaleIndex = (currentScaleIndex + 1) % scaleModes.size
            applyCurrentScaleMode()
            scheduleHideControls()
        }

        binding.btnErrorRetry.setOnClickListener {
            playMedia(currentUriString, currentTitle)
        }
        binding.btnErrorClose.setOnClickListener { finish() }

        binding.seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onStartTrackingTouch(seekBar: SeekBar) {
                isSeekingFromBar = true
                handler.removeCallbacks(hideControlsRunnable)
            }

            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    val len = mediaPlayer?.length ?: 0L
                    if (len > 0L) {
                        val target = (len * (progress / 1000.0)).toLong()
                        binding.tvCurrentTime.text = FormatUtils.formatDuration(target)
                    }
                }
            }

            override fun onStopTrackingTouch(seekBar: SeekBar) {
                isSeekingFromBar = false
                val len = mediaPlayer?.length ?: 0L
                if (len > 0L) {
                    val target = (len * (seekBar.progress / 1000.0)).toLong()
                    mediaPlayer?.setTime(target)
                }
                scheduleHideControls()
            }
        })
    }

    private fun updatePlaylistButtons() {
        val hasPlaylist = playlistUris.size > 1
        binding.btnPrevVideo.visibility = if (hasPlaylist && playlistIndex > 0) View.VISIBLE else View.GONE
        binding.btnNextVideo.visibility = if (hasPlaylist && playlistIndex + 1 < playlistUris.size) View.VISIBLE else View.GONE
    }

    private fun setupGestures() {
        val gestureHelper = PlayerGestureHelper(
            this,
            binding.gestureOverlay,
            object : PlayerGestureHelper.GestureListener {
                override fun isLocked(): Boolean = isLocked

                override fun onSingleTap() {
                    if (isLocked) {
                        binding.btnUnlock.visibility = View.VISIBLE
                        scheduleHideUnlockButton()
                    } else {
                        setControlsVisible(!isControlsVisible)
                    }
                }

                override fun onDoubleTapLeft() {
                    seekBy(-10_000L)
                    showDoubleTapFeedback(left = true)
                }

                override fun onDoubleTapRight() {
                    seekBy(10_000L)
                    showDoubleTapFeedback(left = false)
                }

                override fun onDoubleTapCenter() {
                    togglePlayPause()
                    showOsd(
                        if (mediaPlayer?.isPlaying == true) R.drawable.ic_play else R.drawable.ic_pause,
                        if (mediaPlayer?.isPlaying == true) "Воспроизведение" else "Пауза"
                    )
                }

                override fun onBrightnessStart(): Float {
                    val current = window.attributes.screenBrightness
                    if (current in 0f..1f) return current
                    val sys = runCatching {
                        Settings.System.getInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS) / 255f
                    }.getOrDefault(0.5f)
                    return sys.coerceIn(0.05f, 1.0f)
                }

                override fun onBrightnessChange(newBrightness: Float) {
                    val lp = window.attributes
                    lp.screenBrightness = newBrightness
                    window.attributes = lp
                    val pct = (newBrightness * 100f).roundToInt().coerceIn(1, 100)
                    showOsd(R.drawable.ic_brightness, "$pct%", progress = pct, max = 100)
                }

                override fun onVolumeStart(): Float {
                    val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
                    val curVol = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                    return if (curVol >= maxVol && softwareVolumeBoost > 100) {
                        1f + (softwareVolumeBoost - 100) / 100f
                    } else {
                        curVol.toFloat() / maxVol.toFloat()
                    }
                }

                override fun onVolumeChange(newVolume: Float) {
                    val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
                    if (newVolume <= 1.0f) {
                        val targetSys = (newVolume * maxVol).roundToInt().coerceIn(0, maxVol)
                        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, targetSys, 0)
                        softwareVolumeBoost = 100
                        mediaPlayer?.setVolume(100)
                        val pct = (newVolume * 100f).roundToInt().coerceIn(0, 100)
                        showOsd(R.drawable.ic_volume, "$pct%", progress = pct, max = 100)
                    } else {
                        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, maxVol, 0)
                        softwareVolumeBoost = (newVolume * 100f).roundToInt().coerceIn(100, 200)
                        mediaPlayer?.setVolume(softwareVolumeBoost)
                        showOsd(
                            R.drawable.ic_volume,
                            "$softwareVolumeBoost%",
                            secondary = "Усиление звука",
                            progress = softwareVolumeBoost,
                            max = 200
                        )
                    }
                }

                override fun onSeekStart(): Long = mediaPlayer?.time ?: 0L

                override fun onSeekPreview(targetMs: Long, deltaMs: Long) {
                    val sign = if (deltaMs >= 0) "+" else "-"
                    val deltaStr = "$sign${FormatUtils.formatDuration(abs(deltaMs))}"
                    val totalStr = FormatUtils.formatDuration(mediaPlayer?.length ?: 0L)
                    showOsd(
                        if (deltaMs >= 0) R.drawable.ic_forward_10 else R.drawable.ic_replay_10,
                        FormatUtils.formatDuration(targetMs),
                        secondary = "$deltaStr  /  $totalStr"
                    )
                }

                override fun onSeekCommit(targetMs: Long) {
                    mediaPlayer?.setTime(targetMs)
                }

                override fun getDurationMs(): Long = mediaPlayer?.length ?: 0L

                override fun onPinchZoom(zoomIn: Boolean) {
                    currentScaleIndex = if (zoomIn) 2 else 0 // FILL vs BEST_FIT
                    applyCurrentScaleMode()
                }

                override fun onLongPressStart() {
                    if (mediaPlayer?.isPlaying == true) {
                        mediaPlayer?.rate = 2.0f
                        binding.tvSpeedBoostBadge.visibility = View.VISIBLE
                    }
                }

                override fun onLongPressEnd() {
                    mediaPlayer?.rate = normalPlaybackRate
                    binding.tvSpeedBoostBadge.visibility = View.GONE
                }

                override fun onGestureEnd() {
                    handler.removeCallbacks(hideOsdRunnable)
                    handler.postDelayed(hideOsdRunnable, 700L)
                }
            }
        )
        binding.gestureOverlay.setOnTouchListener(gestureHelper)
    }

    private fun togglePlayPause() {
        val player = mediaPlayer ?: return
        if (player.isPlaying) {
            player.pause()
        } else {
            player.play()
        }
    }

    private fun seekBy(deltaMs: Long) {
        val player = mediaPlayer ?: return
        val len = player.length
        if (len <= 0L) return
        val target = (player.time + deltaMs).coerceIn(0L, len)
        player.setTime(target)
    }

    private fun applyCurrentScaleMode() {
        val (scaleType, labelRes) = scaleModes[currentScaleIndex]
        mediaPlayer?.videoScale = scaleType
        showOsd(R.drawable.ic_aspect_ratio, getString(labelRes))
    }

    private fun showDoubleTapFeedback(left: Boolean) {
        binding.tvDoubleTapLeft.visibility = if (left) View.VISIBLE else View.GONE
        binding.tvDoubleTapRight.visibility = if (left) View.GONE else View.VISIBLE
        handler.removeCallbacks(hideDoubleTapRunnable)
        handler.postDelayed(hideDoubleTapRunnable, 650L)
    }

    private fun showOsd(
        iconRes: Int,
        primary: String,
        secondary: String? = null,
        progress: Int? = null,
        max: Int = 100
    ) {
        binding.ivOsdIcon.setImageResource(iconRes)
        binding.tvOsdPrimary.text = primary
        if (secondary != null) {
            binding.tvOsdSecondary.text = secondary
            binding.tvOsdSecondary.visibility = View.VISIBLE
        } else {
            binding.tvOsdSecondary.visibility = View.GONE
        }
        if (progress != null) {
            binding.pbOsd.max = max
            binding.pbOsd.progress = progress
            binding.pbOsd.visibility = View.VISIBLE
        } else {
            binding.pbOsd.visibility = View.GONE
        }
        binding.layoutOsd.visibility = View.VISIBLE
        handler.removeCallbacks(hideOsdRunnable)
        handler.postDelayed(hideOsdRunnable, 1100L)
    }

    private fun setControlsVisible(visible: Boolean) {
        isControlsVisible = visible
        binding.layoutControls.visibility = if (visible) View.VISIBLE else View.GONE
        handler.removeCallbacks(hideControlsRunnable)
        if (visible && mediaPlayer?.isPlaying == true) {
            scheduleHideControls()
        }
    }

    private fun scheduleHideControls() {
        handler.removeCallbacks(hideControlsRunnable)
        if (mediaPlayer?.isPlaying == true) {
            handler.postDelayed(hideControlsRunnable, 4000L)
        }
    }

    private fun scheduleHideUnlockButton() {
        handler.removeCallbacks(hideUnlockBtnRunnable)
        handler.postDelayed(hideUnlockBtnRunnable, 3000L)
    }

    // --- Dialogs: Subtitles, Audio, Speed, Sleep Timer ---

    private fun showSubtitlesDialog() {
        val player = mediaPlayer ?: return
        val dialogBinding = DialogSubtitlesBinding.inflate(layoutInflater)
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.subtitles_title)
            .setView(dialogBinding.root)
            .setPositiveButton(R.string.close, null)
            .create()

        // Track radio list
        val currentSpu = player.spuTrack
        val tracks = player.spuTracks

        val offRb = RadioButton(this).apply {
            id = View.generateViewId()
            text = getString(R.string.subtitles_off)
            isChecked = currentSpu == -1
            setOnClickListener { player.setSpuTrack(-1) }
        }
        dialogBinding.rgSubtitleTracks.addView(offRb)

        if (tracks != null && tracks.isNotEmpty()) {
            for (track in tracks) {
                if (track.id == -1) continue
                val rb = RadioButton(this).apply {
                    id = View.generateViewId()
                    text = track.name?.takeIf { it.isNotBlank() } ?: "Субтитры #${track.id}"
                    isChecked = track.id == currentSpu
                    setOnClickListener { player.setSpuTrack(track.id) }
                }
                dialogBinding.rgSubtitleTracks.addView(rb)
            }
        }

        // Load external subtitle file button
        dialogBinding.btnLoadExternalSub.setOnClickListener {
            dialog.dismiss()
            pickSubtitleLauncher.launch(arrayOf("*/*"))
        }

        // Subtitle Delay Controls
        fun updateDelayLabel() {
            val sec = player.spuDelay / 1_000_000.0
            dialogBinding.tvSubDelayValue.text =
                String.format(Locale.US, "%s: %+.1f сек", getString(R.string.subtitle_delay), sec)
        }
        updateDelayLabel()

        dialogBinding.btnDelayMinus500.setOnClickListener {
            player.setSpuDelay(player.spuDelay - 500_000L)
            updateDelayLabel()
        }
        dialogBinding.btnDelayMinus100.setOnClickListener {
            player.setSpuDelay(player.spuDelay - 100_000L)
            updateDelayLabel()
        }
        dialogBinding.btnDelayReset.setOnClickListener {
            player.setSpuDelay(0L)
            updateDelayLabel()
        }
        dialogBinding.btnDelayPlus100.setOnClickListener {
            player.setSpuDelay(player.spuDelay + 100_000L)
            updateDelayLabel()
        }
        dialogBinding.btnDelayPlus500.setOnClickListener {
            player.setSpuDelay(player.spuDelay + 500_000L)
            updateDelayLabel()
        }

        // Font size selection
        when (prefs.subtitleRelFontSize) {
            20 -> dialogBinding.chipSizeSmall.isChecked = true
            13 -> dialogBinding.chipSizeLarge.isChecked = true
            10 -> dialogBinding.chipSizeHuge.isChecked = true
            else -> dialogBinding.chipSizeNormal.isChecked = true
        }
        dialogBinding.chipGroupSubSize.setOnCheckedStateChangeListener { _, checkedIds ->
            val id = checkedIds.firstOrNull() ?: return@setOnCheckedStateChangeListener
            prefs.subtitleRelFontSize = when (id) {
                R.id.chipSizeSmall -> 20
                R.id.chipSizeLarge -> 13
                R.id.chipSizeHuge -> 10
                else -> 16
            }
        }

        // Color selection
        when (prefs.subtitleColor) {
            16776960 -> dialogBinding.chipColorYellow.isChecked = true
            65535 -> dialogBinding.chipColorCyan.isChecked = true
            else -> dialogBinding.chipColorWhite.isChecked = true
        }
        dialogBinding.chipGroupSubColor.setOnCheckedStateChangeListener { _, checkedIds ->
            val id = checkedIds.firstOrNull() ?: return@setOnCheckedStateChangeListener
            prefs.subtitleColor = when (id) {
                R.id.chipColorYellow -> 16776960
                R.id.chipColorCyan -> 65535
                else -> 16777215
            }
        }

        dialog.show()
    }

    private fun loadExternalSubtitle(uri: Uri) {
        val cachedUri = SubtitleHelper.copySubtitleToCache(this, uri)
        if (cachedUri == null) {
            Toast.makeText(this, R.string.subtitle_load_error, Toast.LENGTH_SHORT).show()
            return
        }
        val ok = mediaPlayer?.addSlave(IMedia.Slave.Type.Subtitle, cachedUri, true) == true
        Toast.makeText(
            this,
            if (ok) R.string.subtitle_loaded else R.string.subtitle_load_error,
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun showAudioTracksDialog() {
        val player = mediaPlayer ?: return
        val tracks = player.audioTracks
        if (tracks.isNullOrEmpty()) {
            Toast.makeText(this, R.string.no_audio_tracks, Toast.LENGTH_SHORT).show()
            return
        }
        val currentId = player.audioTrack
        val labels = tracks.map { track ->
            if (track.id == -1) getString(R.string.audio_off)
            else track.name?.takeIf { it.isNotBlank() } ?: "Дорожка #${track.id}"
        }.toTypedArray()
        val checked = tracks.indexOfFirst { it.id == currentId }.coerceAtLeast(0)

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.audio_tracks_title)
            .setSingleChoiceItems(labels, checked) { dialog, which ->
                player.setAudioTrack(tracks[which].id)
                dialog.dismiss()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showSpeedDialog() {
        val speeds = floatArrayOf(0.25f, 0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 1.75f, 2.0f, 2.5f, 3.0f)
        val labels = speeds.map { "${it}x" }.toTypedArray()
        val currentIdx = speeds.indexOfFirst { abs(it - normalPlaybackRate) < 0.05f }.coerceAtLeast(3)

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.playback_speed_title)
            .setSingleChoiceItems(labels, currentIdx) { dialog, which ->
                normalPlaybackRate = speeds[which]
                mediaPlayer?.rate = normalPlaybackRate
                binding.btnSpeed.text = "${normalPlaybackRate}x"
                dialog.dismiss()
            }
            .show()
    }

    private fun showSleepTimerDialog() {
        val options = arrayOf(
            getString(R.string.sleep_timer_off),
            "15 мин",
            "30 мин",
            "45 мин",
            "60 мин",
            "90 мин"
        )
        val minutes = intArrayOf(0, 15, 30, 45, 60, 90)

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.sleep_timer_title)
            .setItems(options) { _, which ->
                sleepTimerRunnable?.let { handler.removeCallbacks(it) }
                sleepTimerRunnable = null
                val mins = minutes[which]
                if (mins > 0) {
                    val task = Runnable {
                        mediaPlayer?.pause()
                        Toast.makeText(this, "Таймер сна: пауза", Toast.LENGTH_LONG).show()
                    }
                    sleepTimerRunnable = task
                    handler.postDelayed(task, mins * 60_000L)
                    Toast.makeText(this, getString(R.string.sleep_timer_set, mins), Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, R.string.sleep_timer_off, Toast.LENGTH_SHORT).show()
                }
            }
            .show()
    }

    private fun enterPipMode() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            setControlsVisible(false)
            val params = PictureInPictureParams.Builder()
                .setAspectRatio(Rational(16, 9))
                .build()
            enterPictureInPictureMode(params)
        }
    }

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: Configuration
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        if (isInPictureInPictureMode) {
            setControlsVisible(false)
            binding.btnUnlock.visibility = View.GONE
        } else {
            setupImmersiveSystemUi()
        }
    }

    private fun saveCurrentProgress() {
        val player = mediaPlayer ?: return
        if (currentUriString.isBlank()) return
        val pos = player.time
        val len = player.length
        prefs.savePosition(currentUriString, pos, len)
        prefs.addToHistory(
            HistoryItem(
                uriString = currentUriString,
                title = currentTitle,
                isOnline = isOnlineStream,
                positionMs = pos.coerceAtLeast(0L),
                durationMs = len.coerceAtLeast(0L),
                timestamp = System.currentTimeMillis(),
                userAgent = currentUserAgent,
                referer = currentReferer
            )
        )
    }

    override fun onPause() {
        super.onPause()
        saveCurrentProgress()
        val inPip = Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && isInPictureInPictureMode
        if (!inPip) {
            mediaPlayer?.pause()
        }
    }

    override fun onStop() {
        super.onStop()
        val inPip = Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && isInPictureInPictureMode
        if (!inPip) {
            mediaPlayer?.pause()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        saveCurrentProgress()
        mediaPlayer?.apply {
            stop()
            detachViews()
            release()
        }
        mediaPlayer = null
        libVLC?.release()
        libVLC = null
        closeActivePfd()
    }

    private fun closeActivePfd() {
        runCatching { activePfd?.close() }
        activePfd = null
    }
}
