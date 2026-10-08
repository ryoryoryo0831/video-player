package com.ryose.videoplayer

import android.os.Bundle
import android.os.Parcelable
import android.view.View
import androidx.activity.OnBackPressedCallback
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** 「フォルダ」タブ：ストレージの中をフォルダごとにたどる */
class FoldersFragment : BaseListFragment() {

    /** 今いるフォルダ（null ならストレージ一覧） */
    private var currentDir: File? = null
    private var roots: List<MediaFiles.Root> = emptyList()
    /** フォルダに入る前のスクロール位置（戻ったときに元の位置に戻すため） */
    private val scrollStates = mutableMapOf<String, Parcelable?>()

    private val backCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() = goUp()
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        currentDir = savedInstanceState?.getString(KEY_DIR)?.let { File(it) }
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, backCallback)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        currentDir?.let { outState.putString(KEY_DIR, it.path) }
    }

    override fun onResume() {
        super.onResume()
        load()
    }

    override fun onPause() {
        super.onPause()
        // 他のタブを見ているときは「戻る」でフォルダを上がらない
        backCallback.isEnabled = false
    }

    override fun subtitle(): String {
        val dir = currentDir ?: return "ストレージ"
        // 「/storage/emulated/0/Movies」→「内部共有ストレージ/Movies」のように表示
        val root = roots.find { dir.path.startsWith(it.dir.path) }
        return if (root != null) root.name + dir.path.removePrefix(root.dir.path) else dir.path
    }

    private fun load(restoreScroll: Parcelable? = null) {
        backCallback.isEnabled = isResumed && currentDir != null
        val ctx = requireContext()
        if (!ctx.hasStorageAccess()) {
            showNeedPermission()
            return
        }
        val dir = currentDir
        viewLifecycleOwner.lifecycleScope.launch {
            val rows = withContext(Dispatchers.IO) {
                roots = MediaFiles.roots(ctx)
                if (dir == null) {
                    roots.map { Row.Folder(it.name, it.dir.path, R.drawable.ic_storage, it.dir, isStorage = true) }
                } else {
                    val listing = MediaFiles.list(ctx, dir)
                    listing.folders.map { Row.Folder(it.dir.name, folderInfo(it), dir = it.dir) } +
                        listing.media.map { Row.Media(it, "") }
                }
            }
            if (dir != currentDir) return@launch
            showRows(rows, "このフォルダには動画や音楽がありません。", R.drawable.ic_folder)
            restoreScroll?.let { list.layoutManager?.onRestoreInstanceState(it) }
        }
    }

    private fun folderInfo(f: MediaFiles.Folder): String {
        val parts = mutableListOf<String>()
        if (f.folderCount > 0) parts += "${f.folderCount} フォルダ"
        if (f.videoCount > 0) parts += "${f.videoCount} 本の動画"
        if (f.audioCount > 0) parts += "${f.audioCount} 曲"
        return if (parts.isEmpty()) "空" else parts.joinToString(" · ")
    }

    private fun open(dir: File) {
        scrollStates[currentDir?.path ?: ROOT_KEY] = list.layoutManager?.onSaveInstanceState()
        currentDir = dir
        list.scrollToPosition(0)
        load()
    }

    private fun goUp() {
        val dir = currentDir ?: return
        currentDir = if (roots.any { it.dir.path == dir.path }) null else dir.parentFile
        load(restoreScroll = scrollStates.remove(currentDir?.path ?: ROOT_KEY))
    }

    override fun onRowClick(position: Int) {
        when (val row = adapter.rows.getOrNull(position)) {
            is Row.Folder -> row.dir?.let { open(it) }
            is Row.Media -> playMediaAt(position)
            null -> {}
        }
    }

    override fun extraActions(row: Row): List<Pair<String, () -> Unit>> {
        if (row !is Row.Folder || row.isStorage) return emptyList()
        val dir = row.dir ?: return emptyList()
        return listOf(
            "このフォルダを再生" to { withFolderVideos(dir) { requireActivity().playItems(it, 0) } },
            "シャッフル再生" to { withFolderVideos(dir) { requireActivity().playItems(it, it.indices.random(), shuffle = true) } },
            "プレイリストに追加" to { withFolderVideos(dir) { PlaylistDialogs.addToPlaylist(requireContext(), it) } },
        )
    }

    /** フォルダ直下の動画を読み込んでから処理する（空なら知らせる） */
    private fun withFolderVideos(dir: File, action: (List<PlaylistItem>) -> Unit) {
        val ctx = requireContext()
        viewLifecycleOwner.lifecycleScope.launch {
            val videos = withContext(Dispatchers.IO) { MediaFiles.mediaIn(ctx, dir) }
            if (videos.isEmpty()) {
                android.widget.Toast.makeText(ctx, "このフォルダの直下には動画や音楽がありません", android.widget.Toast.LENGTH_SHORT).show()
            } else {
                action(videos)
            }
        }
    }

    private companion object {
        const val KEY_DIR = "dir"
        const val ROOT_KEY = "<root>"
    }
}
