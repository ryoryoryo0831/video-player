package com.ryose.videoplayer

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.core.view.updatePadding

/** 「プレイリスト」タブ */
class PlaylistsFragment : BaseListFragment() {

    private lateinit var store: PlaylistStore

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        store = PlaylistStore(requireContext())
        fab.visibility = View.VISIBLE
        fab.setOnClickListener {
            PlaylistDialogs.promptName(requireContext(), getString(R.string.new_playlist), "") { name ->
                store.create(name)
                load()
            }
        }
        // 右下のボタンで最後の行が隠れないように
        list.updatePadding(bottom = (88 * resources.displayMetrics.density).toInt())
    }

    override fun onResume() {
        super.onResume()
        load()
    }

    private fun load() {
        // お気に入りはいつも一番上に
        val lists = store.all().sortedBy { if (it.id == FavoriteMedia.PLAYLIST_ID) 0 else 1 }
        val rows = lists.map { p ->
            val total = p.items.sumOf { it.durationMs }
            // 全部が曲なら「曲」、全部が動画なら「本」、混ざっていれば「件」で数える
            val unit = when {
                p.items.isNotEmpty() && p.items.all { it.isAudio } -> "曲"
                p.items.none { it.isAudio } -> "本"
                else -> "件"
            }
            val info = "${p.items.size} $unit" + if (total > 0) " · ${formatTime(total)}" else ""
            val icon = if (p.id == FavoriteMedia.PLAYLIST_ID) R.drawable.ic_star else R.drawable.ic_playlist
            Row.Folder(p.name, info, icon, id = p.id)
        }
        showRows(
            rows,
            "プレイリストはまだありません。\n\n右下の＋で作るか、動画を長押しして\n「プレイリストに追加」から作れます。",
            R.drawable.ic_playlist,
        )
    }

    override fun onRowClick(position: Int) {
        val id = (adapter.rows.getOrNull(position) as? Row.Folder)?.id ?: return
        startActivity(Intent(requireContext(), PlaylistActivity::class.java).putExtra(PlaylistActivity.EXTRA_ID, id))
    }

    override fun extraActions(row: Row): List<SheetItem> {
        val id = (row as? Row.Folder)?.id ?: return emptyList()
        val p = store.get(id) ?: return emptyList()
        val play = listOf(
            SheetItem(R.drawable.ic_play, "再生") { if (p.items.isNotEmpty()) requireActivity().playItems(p.items, 0) },
            SheetItem(R.drawable.ic_shuffle, "シャッフル再生") {
                if (p.items.isNotEmpty()) requireActivity().playItems(p.items, p.items.indices.random(), shuffle = true)
            },
        )
        // お気に入りは名前の変更・削除はできない
        if (id == FavoriteMedia.PLAYLIST_ID) return play
        return play + listOf(
            SheetItem(R.drawable.ic_edit, "名前を変更") {
                PlaylistDialogs.promptName(requireContext(), "名前を変更", p.name) { name ->
                    store.rename(id, name)
                    load()
                }
            },
            SheetItem(R.drawable.ic_delete, "削除") {
                PlaylistDialogs.confirm(requireContext(), "「${p.name}」を削除しますか？\n（動画ファイルは削除されません）", "削除") {
                    store.delete(id)
                    load()
                }
            },
        )
    }
}
