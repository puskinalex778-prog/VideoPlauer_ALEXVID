package com.alexvid.player.ui

import android.Manifest
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.SeekBar
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.alexvid.player.R
import com.alexvid.player.data.PlayerPreferences
import com.alexvid.player.data.VideoScanner
import com.alexvid.player.databinding.ActivityMainBinding
import com.alexvid.player.model.FormatUtils
import com.alexvid.player.model.LocalVideo
import com.alexvid.player.model.SortOrder
import com.alexvid.player.player.PlayerActivity
import com.google.android.material.chip.Chip
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: PlayerPreferences

    private lateinit var videoAdapter: VideoAdapter
    private lateinit var streamAdapter: StreamAdapter
    private lateinit var historyAdapter: HistoryAdapter

    private var allVideos: List<LocalVideo> = emptyList()
    private var selectedFolder: String? = null
    private var searchQuery: String = ""
    private var advancedExpanded = false

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            binding.cardPermission.visibility = View.GONE
            loadVideos()
        } else {
            binding.cardPermission.visibility = View.VISIBLE
        }
    }

    private val openDocumentLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            runCatching {
                contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
            val title = VideoScanner.resolveDisplayName(this, uri)
            startActivity(
                PlayerActivity.createIntent(
                    context = this,
                    uriString = uri.toString(),
                    title = title
                )
            )
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = PlayerPreferences(this)

        setupToolbar()
        setupBottomNav()
        setupVideosTab()
        setupOnlineTab()
        setupHistoryTab()

        checkPermissionAndLoad()
    }

    override fun onResume() {
        super.onResume()
        refreshHistory()
        val cacheStep = ((prefs.networkCachingMs - 500) / 500).coerceIn(0, 10)
        binding.seekNetworkCache.progress = cacheStep
        binding.tvCacheValue.text = "${getString(R.string.network_cache_label)}: ${prefs.networkCachingMs} мс"
        if (hasVideoPermission() && allVideos.isNotEmpty()) {
            videoAdapter.notifyDataSetChanged()
        }
    }

    private fun setupToolbar() {
        binding.toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_sort -> {
                    showSortDialog()
                    true
                }

                R.id.action_settings -> {
                    startActivity(Intent(this, SettingsActivity::class.java))
                    true
                }

                else -> false
            }
        }
    }

    private fun setupBottomNav() {
        binding.bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_videos -> {
                    binding.tabVideos.visibility = View.VISIBLE
                    binding.tabOnline.visibility = View.GONE
                    binding.tabHistory.visibility = View.GONE
                    binding.fabOpenFile.show()
                    true
                }

                R.id.nav_online -> {
                    binding.tabVideos.visibility = View.GONE
                    binding.tabOnline.visibility = View.VISIBLE
                    binding.tabHistory.visibility = View.GONE
                    binding.fabOpenFile.hide()
                    refreshStreams()
                    true
                }

                R.id.nav_history -> {
                    binding.tabVideos.visibility = View.GONE
                    binding.tabOnline.visibility = View.GONE
                    binding.tabHistory.visibility = View.VISIBLE
                    binding.fabOpenFile.hide()
                    refreshHistory()
                    true
                }

                else -> false
            }
        }
    }

    // --- TAB 1: LOCAL VIDEOS ---

    private fun setupVideosTab() {
        videoAdapter = VideoAdapter(prefs) { _, index ->
            val currentList = videoAdapter.currentItems()
            val uris = ArrayList(currentList.map { it.uri.toString() })
            val titles = ArrayList(currentList.map { it.title })
            startActivity(
                PlayerActivity.createIntent(
                    context = this,
                    uriString = uris[index],
                    title = titles[index],
                    playlistUris = uris,
                    playlistTitles = titles,
                    playlistIndex = index
                )
            )
        }
        binding.rvVideos.layoutManager = LinearLayoutManager(this)
        binding.rvVideos.adapter = videoAdapter

        binding.swipeRefresh.setColorSchemeResources(R.color.accent_cyan, R.color.accent_violet)
        binding.swipeRefresh.setOnRefreshListener { loadVideos() }

        binding.btnGrantPermission.setOnClickListener { requestVideoPermission() }

        binding.fabOpenFile.setOnClickListener {
            openDocumentLauncher.launch(arrayOf("video/*", "*/*"))
        }

        binding.etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                searchQuery = s?.toString().orEmpty().trim()
                applyVideoFilterAndSort()
            }
        })
    }

    private fun requiredVideoPermission(): String {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_VIDEO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
    }

    private fun hasVideoPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            requiredVideoPermission()
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestVideoPermission() {
        permissionLauncher.launch(requiredVideoPermission())
    }

    private fun checkPermissionAndLoad() {
        if (hasVideoPermission()) {
            binding.cardPermission.visibility = View.GONE
            loadVideos()
        } else {
            binding.cardPermission.visibility = View.VISIBLE
            requestVideoPermission()
        }
    }

    private fun loadVideos() {
        if (!hasVideoPermission()) {
            binding.swipeRefresh.isRefreshing = false
            binding.cardPermission.visibility = View.VISIBLE
            return
        }
        binding.swipeRefresh.isRefreshing = true
        lifecycleScope.launch {
            val scanned = withContext(Dispatchers.IO) {
                VideoScanner.scanDeviceVideos(this@MainActivity)
            }
            allVideos = scanned
            rebuildFolderChips()
            applyVideoFilterAndSort()
            binding.swipeRefresh.isRefreshing = false
        }
    }

    private fun rebuildFolderChips() {
        binding.chipGroupFolders.removeAllViews()
        val folders = allVideos.map { it.folderName }.distinct().sorted()

        val allChip = Chip(this).apply {
            id = View.generateViewId()
            text = "${getString(R.string.all_folders)} (${allVideos.size})"
            isCheckable = true
            isChecked = selectedFolder == null
            setOnClickListener {
                selectedFolder = null
                applyVideoFilterAndSort()
            }
        }
        binding.chipGroupFolders.addView(allChip)

        for (folder in folders) {
            val count = allVideos.count { it.folderName == folder }
            val chip = Chip(this).apply {
                id = View.generateViewId()
                text = "$folder ($count)"
                isCheckable = true
                isChecked = selectedFolder == folder
                setOnClickListener {
                    selectedFolder = folder
                    applyVideoFilterAndSort()
                }
            }
            binding.chipGroupFolders.addView(chip)
        }
    }

    private fun applyVideoFilterAndSort() {
        val q = searchQuery.lowercase(Locale.getDefault())
        val filtered = allVideos.filter { video ->
            val matchFolder = selectedFolder == null || video.folderName == selectedFolder
            val matchQuery = q.isBlank() ||
                video.title.lowercase(Locale.getDefault()).contains(q) ||
                video.displayName.lowercase(Locale.getDefault()).contains(q)
            matchFolder && matchQuery
        }

        val sorted = when (prefs.sortOrder) {
            SortOrder.DATE_DESC -> filtered.sortedByDescending { it.dateAdded }
            SortOrder.NAME_ASC -> filtered.sortedBy { it.title.lowercase(Locale.getDefault()) }
            SortOrder.SIZE_DESC -> filtered.sortedByDescending { it.sizeBytes }
            SortOrder.DURATION_DESC -> filtered.sortedByDescending { it.durationMs }
        }

        videoAdapter.submitList(sorted)
        binding.tvEmptyVideos.visibility = if (sorted.isEmpty()) View.VISIBLE else View.GONE
    }

    // --- TAB 2: ONLINE STREAMING ---

    private fun setupOnlineTab() {
        streamAdapter = StreamAdapter(
            onPlay = { stream ->
                startActivity(
                    PlayerActivity.createIntent(
                        context = this,
                        uriString = stream.url,
                        title = stream.title,
                        userAgent = stream.userAgent,
                        referer = stream.referer,
                        cacheMs = prefs.networkCachingMs
                    )
                )
            },
            onDelete = { stream ->
                prefs.deleteStream(stream.id)
                refreshStreams()
            }
        )
        binding.rvStreams.layoutManager = LinearLayoutManager(this)
        binding.rvStreams.adapter = streamAdapter

        // Network cache seekbar (500ms .. 5500ms in 500ms steps)
        val initialStep = ((prefs.networkCachingMs - 500) / 500).coerceIn(0, 10)
        binding.seekNetworkCache.progress = initialStep
        binding.tvCacheValue.text = "${getString(R.string.network_cache_label)}: ${prefs.networkCachingMs} мс"

        binding.seekNetworkCache.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                val ms = 500 + progress * 500
                prefs.networkCachingMs = ms
                binding.tvCacheValue.text = "${getString(R.string.network_cache_label)}: $ms мс"
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar) = Unit
        })

        binding.tvToggleAdvanced.setOnClickListener {
            advancedExpanded = !advancedExpanded
            binding.layoutAdvancedStream.visibility = if (advancedExpanded) View.VISIBLE else View.GONE
            binding.tvToggleAdvanced.text = if (advancedExpanded) {
                "▾ ${getString(R.string.advanced_options)}"
            } else {
                "▸ ${getString(R.string.advanced_options)}"
            }
        }

        binding.btnPasteUrl.setOnClickListener {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clipText = clipboard.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString().orEmpty()
            val extracted = FormatUtils.extractUrlFromText(clipText) ?: clipText.trim()
            if (extracted.isBlank()) {
                Toast.makeText(this, R.string.clipboard_empty, Toast.LENGTH_SHORT).show()
            } else {
                binding.etStreamUrl.setText(extracted)
            }
        }

        binding.btnPlayStream.setOnClickListener {
            val rawUrl = binding.etStreamUrl.text?.toString().orEmpty().trim()
            val cleanUrl = FormatUtils.extractUrlFromText(rawUrl) ?: rawUrl
            if (!FormatUtils.isNetworkUrl(cleanUrl)) {
                Toast.makeText(this, R.string.invalid_url, Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            val title = binding.etStreamName.text?.toString().orEmpty().trim()
            val ua = binding.etUserAgent.text?.toString().orEmpty().trim()
            val ref = binding.etReferer.text?.toString().orEmpty().trim()

            startActivity(
                PlayerActivity.createIntent(
                    context = this,
                    uriString = cleanUrl,
                    title = title,
                    userAgent = ua,
                    referer = ref,
                    cacheMs = prefs.networkCachingMs
                )
            )
        }

        binding.btnSaveStream.setOnClickListener {
            val rawUrl = binding.etStreamUrl.text?.toString().orEmpty().trim()
            val cleanUrl = FormatUtils.extractUrlFromText(rawUrl) ?: rawUrl
            if (!FormatUtils.isNetworkUrl(cleanUrl)) {
                Toast.makeText(this, R.string.invalid_url, Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            val title = binding.etStreamName.text?.toString().orEmpty().trim()
            val ua = binding.etUserAgent.text?.toString().orEmpty().trim()
            val ref = binding.etReferer.text?.toString().orEmpty().trim()
            prefs.saveStream(title, cleanUrl, ua, ref)
            refreshStreams()
            Toast.makeText(this, R.string.stream_saved, Toast.LENGTH_SHORT).show()
        }

        refreshStreams()
    }

    private fun refreshStreams() {
        streamAdapter.submitList(prefs.getStreams())
    }

    // --- TAB 3: HISTORY ---

    private fun setupHistoryTab() {
        historyAdapter = HistoryAdapter { item ->
            startActivity(
                PlayerActivity.createIntent(
                    context = this,
                    uriString = item.uriString,
                    title = item.title,
                    userAgent = item.userAgent,
                    referer = item.referer,
                    cacheMs = prefs.networkCachingMs
                )
            )
        }
        binding.rvHistory.layoutManager = LinearLayoutManager(this)
        binding.rvHistory.adapter = historyAdapter

        binding.btnClearHistory.setOnClickListener {
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.clear_history)
                .setMessage(R.string.clear_history_confirm)
                .setPositiveButton(R.string.clear_history) { _, _ ->
                    prefs.clearHistory()
                    refreshHistory()
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }

        refreshHistory()
    }

    private fun refreshHistory() {
        val list = prefs.getHistory()
        historyAdapter.submitList(list)
        binding.tvEmptyHistory.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
        binding.btnClearHistory.visibility = if (list.isEmpty()) View.GONE else View.VISIBLE
    }

    // --- DIALOGS ---

    private fun showSortDialog() {
        val options = arrayOf(
            getString(R.string.sort_date_desc),
            getString(R.string.sort_name_asc),
            getString(R.string.sort_size_desc),
            getString(R.string.sort_duration_desc)
        )
        val orders = SortOrder.entries
        val currentIdx = orders.indexOf(prefs.sortOrder).coerceAtLeast(0)

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.sort_title)
            .setSingleChoiceItems(options, currentIdx) { dialog, which ->
                prefs.sortOrder = orders[which]
                applyVideoFilterAndSort()
                dialog.dismiss()
            }
            .show()
    }
}
