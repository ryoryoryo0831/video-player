package com.ryose.videoplayer

import android.os.Bundle
import android.provider.MediaStore
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.widget.SearchView
import androidx.core.view.MenuProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 「音楽」タブ：曲・アルバム・アーティスト */
class MusicFragment : BaseListFragment() {

    private enum class Mode(val label: String) { SONGS("曲"), ALBUMS("アルバム"), ARTISTS("アーティスト") }

    /** アルバム・アーティストを開いているときの中身 */
    private data class Group(val title: String, val songs: List<MediaFiles.Song>)

    private var songs: List<MediaFiles.Song> = emptyList()
    private var mode = Mode.SONGS
    private var opened: Group? = null
    private var loaded = false
    private var loadedWithAccess = false
    private var savedScroll: android.os.Parcelable? = null
    private var query = ""
    private var renderJob: Job? = null

    private val backCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() = closeGroup()
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        mode = (savedInstanceState?.getString(KEY_MODE) ?: AppSettings.musicMode(requireContext()))
            .let { runCatching { Mode.valueOf(it) }.getOrNull() } ?: Mode.SONGS
        val chips = view.findViewById<ChipGroup>(R.id.chips)
        chips.visibility = View.VISIBLE
        Mode.entries.forEach { m ->
            chips.addView(Chip(requireContext()).apply {
                id = View.generateViewId()
                text = m.label
                isCheckable = true
                isChecked = m == mode
                setOnClickListener {
                    if (mode != m) {
                        mode = m
                        AppSettings.setMusicMode(requireContext(), m.name)
                        opened = null
                        render()
                    }
                }
            })
        }
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, backCallback)
        requireActivity().addMenuProvider(menuProvider, viewLifecycleOwner, Lifecycle.State.RESUMED)
        // 曲が増えた・消えたら自動で読み込み直す
        viewLifecycleOwner.lifecycle.addObserver(
            MediaWatcher(requireContext().applicationContext, MediaStore.Audio.Media.EXTERNAL_CONTENT_URI) { if (loaded) load() }
        )
    }

    private val menuProvider = object : MenuProvider {
        override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) {
            menuInflater.inflate(R.menu.music_menu, menu)
            val searchItem = menu.findItem(R.id.action_search)
            val searchView = searchItem.actionView as SearchView
            searchView.queryHint = "曲名・アーティスト・アルバムで検索"
            if (query.isNotEmpty()) {
                searchItem.expandActionView()
                searchView.setQuery(query, false)
            }
            searchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
                override fun onQueryTextSubmit(q: String?) = true
                override fun onQueryTextChange(q: String?): Boolean {
                    if (query == q.orEmpty()) return true
                    query = q.orEmpty()
                    if (loaded) render(debounce = true)
                    return true
                }
            })
        }

        override fun onMenuItemSelected(menuItem: MenuItem): Boolean {
            if (menuItem.itemId != R.id.action_refresh) return false
            load()
            return true
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(KEY_MODE, mode.name)
    }

    override fun onResume() {
        super.onResume()
        backCallback.isEnabled = opened != null
        if (!loaded || requireContext().hasMediaAccess(audio = true) != loadedWithAccess) load()
    }

    override fun onPause() {
        super.onPause()
        backCallback.isEnabled = false
    }

    override fun subtitle(): String? = opened?.title ?: if (songs.isEmpty()) null else "${songs.size} 曲"

    override fun onFilesChanged() = load()

    private fun load() {
        val ctx = requireContext()
        loadedWithAccess = ctx.hasMediaAccess(audio = true)
        if (!loadedWithAccess) {
            showNeedPermission()
            return
        }
        if (!loaded) loading.visibility = View.VISIBLE
        viewLifecycleOwner.lifecycleScope.launch {
            songs = withContext(Dispatchers.IO) { MediaFiles.queryAllSongs(ctx) }
            loaded = true
            // 開いていたアルバムなどは、読み込み直した曲で作り直す
            opened = opened?.let { g -> Group(g.title, g.songs.mapNotNull { old -> songs.find { it.item.key == old.item.key } }) }
            render()
        }
    }

    private fun songMeta(s: MediaFiles.Song) = "${s.artist} · ${s.album}"

    private fun matches(s: MediaFiles.Song, q: String) =
        q.isBlank() || s.title.contains(q, true) || s.artist.contains(q, true) || s.album.contains(q, true)

    /** 一覧を作って表示する（曲が多くても固まらないように、画面の処理とは別のところで作る） */
    private fun render(debounce: Boolean = false, onShown: (() -> Unit)? = null) {
        backCallback.isEnabled = isResumed && opened != null
        val group = opened
        val songs = songs
        val mode = mode
        val q = query
        renderJob?.cancel()
        renderJob = viewLifecycleOwner.lifecycleScope.launch {
            if (debounce) delay(200)
            val rows = withContext(Dispatchers.Default) { buildRows(group, songs.filter { matches(it, q) }, mode, q) }
            showRows(rows, if (q.isBlank()) "端末内に音楽が見つかりませんでした。" else "「$q」に一致する曲はありません。", R.drawable.ic_music_note)
            onShown?.invoke()
        }
    }

    private fun buildRows(group: Group?, songs: List<MediaFiles.Song>, mode: Mode, q: String): List<Row> {
        return when {
            group != null -> group.songs.filter { matches(it, q) }.map { Row.Media(it.item, songMeta(it)) }
            mode == Mode.SONGS -> songs.sortedWith(compareBy(NaturalOrder) { it.title }).map { Row.Media(it.item, songMeta(it)) }
            mode == Mode.ALBUMS -> songs.groupBy { it.albumId }.values
                .sortedWith(compareBy(NaturalOrder) { it.first().album })
                .map { list ->
                    val artists = list.map { it.artist }.distinct()
                    val artist = if (artists.size == 1) artists[0] else "さまざまなアーティスト"
                    Row.Folder(list.first().album, "$artist · ${list.size} 曲", R.drawable.ic_album, id = "album:${list.first().albumId}")
                }
            else -> songs.groupBy { it.artist }.entries
                .sortedWith(compareBy(NaturalOrder) { it.key })
                .map { (artist, list) ->
                    val albums = list.map { it.albumId }.distinct().size
                    Row.Folder(artist, "$albums 枚のアルバム · ${list.size} 曲", R.drawable.ic_person, id = "artist:$artist")
                }
        }
    }

    /** アルバム・アーティストの行に含まれる曲（アルバムは曲順、アーティストはアルバムごとに曲順） */
    private fun songsOf(row: Row.Folder): List<MediaFiles.Song> {
        val id = row.id ?: return emptyList()
        return when {
            id.startsWith("album:") -> {
                val albumId = id.removePrefix("album:").toLongOrNull()
                songs.filter { it.albumId == albumId }
                    .sortedWith(compareBy<MediaFiles.Song> { it.track }.thenBy(NaturalOrder) { it.title })
            }
            id.startsWith("artist:") -> {
                val artist = id.removePrefix("artist:")
                songs.filter { it.artist == artist }
                    .sortedWith(compareBy(NaturalOrder) { s: MediaFiles.Song -> s.album }.thenBy { it.track }.thenBy(NaturalOrder) { it.title })
            }
            else -> emptyList()
        }
    }

    private fun closeGroup() {
        opened = null
        val scroll = savedScroll
        savedScroll = null
        render { scroll?.let { list.layoutManager?.onRestoreInstanceState(it) } }
    }

    override fun onRowClick(position: Int) {
        when (val row = adapter.rows.getOrNull(position)) {
            is Row.Folder -> {
                savedScroll = list.layoutManager?.onSaveInstanceState()
                opened = Group(row.name, songsOf(row))
                render { list.scrollToPosition(0) }
            }
            is Row.Media -> playMediaAt(position)
            else -> {}
        }
    }

    override fun extraActions(row: Row): List<Pair<String, () -> Unit>> {
        if (row !is Row.Folder) return emptyList()
        val items = songsOf(row).map { it.item }
        if (items.isEmpty()) return emptyList()
        return listOf(
            "再生" to { requireActivity().playItems(items, 0) },
            "シャッフル再生" to { requireActivity().playItems(items, items.indices.random(), shuffle = true) },
            "プレイリストに追加" to { PlaylistDialogs.addToPlaylist(requireContext(), items) },
        )
    }

    private companion object {
        const val KEY_MODE = "mode"
    }
}
