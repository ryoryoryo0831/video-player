package com.ryose.videoplayer

import android.os.Bundle
import android.os.Parcelable
import android.text.format.Formatter
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.widget.SearchView
import androidx.core.view.MenuProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** 「動画」タブ：端末内のすべての動画（すべて／フォルダ別、リスト／グリッド） */
class VideosFragment : BaseListFragment() {

    private enum class Sort { DATE, NAME, DURATION }

    private var all: List<Video> = emptyList()
    private var shownCount = 0
    private var query = ""
    private var sort = Sort.DATE
    private var loaded = false
    private var loadedWithAccess = false
    /** フォルダ別表示で開いているフォルダ（null ならフォルダの一覧） */
    private var openedFolder: String? = null
    private var savedScroll: Parcelable? = null
    private lateinit var allChip: Chip
    private lateinit var folderChip: Chip

    private val backCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() = closeFolder()
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        requireActivity().addMenuProvider(menuProvider, viewLifecycleOwner, Lifecycle.State.RESUMED)
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, backCallback)
        openedFolder = savedInstanceState?.getString(KEY_FOLDER)

        // 「すべて」「フォルダ別」の切り替え
        val chips = view.findViewById<ChipGroup>(R.id.chips)
        chips.visibility = View.VISIBLE
        fun chip(label: String, byFolder: Boolean) = Chip(requireContext()).apply {
            id = View.generateViewId()
            text = label
            isCheckable = true
            setOnClickListener {
                if (AppSettings.videosByFolder(requireContext()) != byFolder) {
                    AppSettings.setVideosByFolder(requireContext(), byFolder)
                    openedFolder = null
                    if (loaded) applyFilter()
                }
                updateChips()
            }
        }
        allChip = chip("すべて", false)
        folderChip = chip("フォルダ別", true)
        chips.addView(allChip)
        chips.addView(folderChip)
        updateChips()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        openedFolder?.let { outState.putString(KEY_FOLDER, it) }
    }

    private fun updateChips() {
        val byFolder = AppSettings.videosByFolder(requireContext())
        allChip.isChecked = !byFolder
        folderChip.isChecked = byFolder
    }

    override fun onResume() {
        super.onResume()
        applyLayout()
        updateChips()
        if (!loaded || requireContext().hasStorageAccess() != loadedWithAccess) load() else applyFilter()
    }

    override fun onPause() {
        super.onPause()
        backCallback.isEnabled = false
    }

    override fun subtitle() = when {
        openedFolder != null -> "${File(openedFolder!!).name}（${shownCount} 本）"
        all.isEmpty() -> null
        AppSettings.videosByFolder(requireContext()) -> "${shownCount} フォルダ"
        else -> "${shownCount} 本の動画"
    }

    /** リスト／グリッドの切り替え（グリッドでは見出しやフォルダの行は横いっぱいに） */
    private fun applyLayout() {
        val grid = AppSettings.videosGrid(requireContext())
        adapter.grid = grid
        if (grid) {
            val widthDp = resources.displayMetrics.widthPixels / resources.displayMetrics.density
            val span = (widthDp / 180).toInt().coerceAtLeast(2)
            if ((list.layoutManager as? GridLayoutManager)?.spanCount != span) {
                list.layoutManager = GridLayoutManager(requireContext(), span).apply {
                    spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
                        override fun getSpanSize(position: Int) =
                            if (adapter.rows.getOrNull(position) is Row.Media) 1 else span
                    }
                }
            }
        } else if (list.layoutManager !is LinearLayoutManager || list.layoutManager is GridLayoutManager) {
            list.layoutManager = LinearLayoutManager(requireContext())
        }
    }

    private fun load() {
        val ctx = requireContext()
        loadedWithAccess = ctx.hasStorageAccess()
        if (!loadedWithAccess) {
            showNeedPermission()
            return
        }
        if (!loaded) loading.visibility = View.VISIBLE
        viewLifecycleOwner.lifecycleScope.launch {
            all = withContext(Dispatchers.IO) { MediaFiles.queryAllVideos(ctx) }
            // 以前のバージョンで保存した「続きから再生」の位置を引き継ぐ
            all.forEach { v -> v.path?.let { resume.migrate(v.uri.toString(), it) } }
            loaded = true
            applyFilter()
        }
    }

    private fun folderOf(v: Video) = v.path?.let { File(it).parent } ?: v.folder

    private fun applyFilter() {
        val ctx = context ?: return
        val matched = all.filter {
            query.isBlank() || it.title.contains(query, ignoreCase = true) || it.folder.contains(query, ignoreCase = true)
        }
        fun sorted(list: List<Video>) = when (sort) {
            Sort.DATE -> list.sortedByDescending { it.dateAdded }
            Sort.NAME -> list.sortedWith(compareBy(NaturalOrder) { it.title })
            Sort.DURATION -> list.sortedByDescending { it.durationMs }
        }
        fun mediaRow(v: Video) = Row.Media(v.toItem(), "${v.folder} · ${Formatter.formatShortFileSize(ctx, v.size)}")

        val folder = openedFolder
        val rows: List<Row> = when {
            folder != null -> sorted(matched.filter { folderOf(it) == folder }).map(::mediaRow)
            AppSettings.videosByFolder(ctx) -> matched.groupBy { folderOf(it) }.entries
                .sortedWith(compareBy(NaturalOrder) { File(it.key).name })
                .map { (dir, videos) ->
                    val total = videos.sumOf { it.durationMs }
                    val info = "${videos.size} 本" + if (total > 0) " · ${formatTime(total)}" else ""
                    Row.Folder(File(dir).name.ifEmpty { dir }, info, R.drawable.ic_folder, id = "vfolder:$dir")
                }
            else -> sorted(matched).map(::mediaRow)
        }
        shownCount = rows.size
        backCallback.isEnabled = isResumed && folder != null
        showRows(rows, getString(R.string.no_videos))
    }

    private fun closeFolder() {
        openedFolder = null
        applyFilter()
        savedScroll?.let { list.layoutManager?.onRestoreInstanceState(it) }
        savedScroll = null
    }

    override fun onRowClick(position: Int) {
        when (val row = adapter.rows.getOrNull(position)) {
            is Row.Folder -> row.id?.removePrefix("vfolder:")?.let { dir ->
                savedScroll = list.layoutManager?.onSaveInstanceState()
                openedFolder = dir
                applyFilter()
                list.scrollToPosition(0)
            }
            is Row.Media -> playMediaAt(position)
            else -> {}
        }
    }

    override fun extraActions(row: Row): List<Pair<String, () -> Unit>> {
        val dir = (row as? Row.Folder)?.id?.removePrefix("vfolder:") ?: return emptyList()
        val items = all.filter { folderOf(it) == dir }.sortedWith(compareBy(NaturalOrder) { it.title }).map { it.toItem() }
        if (items.isEmpty()) return emptyList()
        return listOf(
            "再生" to { requireActivity().playItems(items, 0) },
            "シャッフル再生" to { requireActivity().playItems(items, items.indices.random(), shuffle = true) },
            "プレイリストに追加" to { PlaylistDialogs.addToPlaylist(requireContext(), items) },
        )
    }

    private val menuProvider = object : MenuProvider {
        override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) {
            menuInflater.inflate(R.menu.videos_menu, menu)
            val searchItem = menu.findItem(R.id.action_search)
            val searchView = searchItem.actionView as SearchView
            searchView.queryHint = getString(R.string.search)
            if (query.isNotEmpty()) {
                searchItem.expandActionView()
                searchView.setQuery(query, false)
            }
            searchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
                override fun onQueryTextSubmit(q: String?) = true
                override fun onQueryTextChange(q: String?): Boolean {
                    if (query == q.orEmpty()) return true
                    query = q.orEmpty()
                    if (loaded) applyFilter()
                    return true
                }
            })
        }

        override fun onPrepareMenu(menu: Menu) {
            val id = when (sort) {
                Sort.DATE -> R.id.sort_date
                Sort.NAME -> R.id.sort_name
                Sort.DURATION -> R.id.sort_duration
            }
            menu.findItem(id)?.isChecked = true
            menu.findItem(R.id.action_grid)?.isChecked = AppSettings.videosGrid(requireContext())
        }

        override fun onMenuItemSelected(menuItem: MenuItem): Boolean {
            when (menuItem.itemId) {
                R.id.action_refresh -> load()
                R.id.sort_date -> setSort(Sort.DATE)
                R.id.sort_name -> setSort(Sort.NAME)
                R.id.sort_duration -> setSort(Sort.DURATION)
                R.id.action_grid -> {
                    AppSettings.setVideosGrid(requireContext(), !AppSettings.videosGrid(requireContext()))
                    requireActivity().invalidateOptionsMenu()
                    applyLayout()
                }
                else -> return false
            }
            return true
        }
    }

    private fun setSort(s: Sort) {
        sort = s
        requireActivity().invalidateOptionsMenu()
        if (loaded) applyFilter()
    }

    private companion object {
        const val KEY_FOLDER = "folder"
    }
}
