package com.ryose.videoplayer

import android.os.Bundle
import android.view.View
import androidx.activity.OnBackPressedCallback
import androidx.lifecycle.lifecycleScope
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import kotlinx.coroutines.Dispatchers
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

    private val backCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() = closeGroup()
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        mode = savedInstanceState?.getString(KEY_MODE)?.let { runCatching { Mode.valueOf(it) }.getOrNull() } ?: Mode.SONGS
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
                        opened = null
                        render()
                    }
                }
            })
        }
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, backCallback)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(KEY_MODE, mode.name)
    }

    override fun onResume() {
        super.onResume()
        backCallback.isEnabled = opened != null
        if (!loaded || requireContext().hasStorageAccess() != loadedWithAccess) load()
    }

    override fun onPause() {
        super.onPause()
        backCallback.isEnabled = false
    }

    override fun subtitle(): String? = opened?.title ?: if (songs.isEmpty()) null else "${songs.size} 曲"

    private fun load() {
        val ctx = requireContext()
        loadedWithAccess = ctx.hasStorageAccess()
        if (!loadedWithAccess) {
            showNeedPermission()
            return
        }
        if (!loaded) loading.visibility = View.VISIBLE
        viewLifecycleOwner.lifecycleScope.launch {
            songs = withContext(Dispatchers.IO) { MediaFiles.queryAllSongs(ctx) }
            loaded = true
            render()
        }
    }

    private fun songMeta(s: MediaFiles.Song) = "${s.artist} · ${s.album}"

    private fun render() {
        backCallback.isEnabled = isResumed && opened != null
        val group = opened
        val rows: List<Row> = when {
            group != null -> group.songs.map { Row.Media(it.item, songMeta(it)) }
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
        showRows(rows, "端末内に音楽が見つかりませんでした。", R.drawable.ic_music_note)
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
        render()
        savedScroll?.let { list.layoutManager?.onRestoreInstanceState(it) }
        savedScroll = null
    }

    override fun onRowClick(position: Int) {
        when (val row = adapter.rows.getOrNull(position)) {
            is Row.Folder -> {
                savedScroll = list.layoutManager?.onSaveInstanceState()
                opened = Group(row.name, songsOf(row))
                render()
                list.scrollToPosition(0)
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
