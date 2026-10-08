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
        val rows = store.all().map { p ->
            val total = p.items.sumOf { it.durationMs }
            val info = "${p.items.size} 本" + if (total > 0) " · ${formatTime(total)}" else ""
            Row.Folder(p.name, info, R.drawable.ic_playlist, id = p.id)
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

    override fun extraActions(row: Row): List<Pair<String, () -> Unit>> {
        val id = (row as? Row.Folder)?.id ?: return emptyList()
        val p = store.get(id) ?: return emptyList()
        return listOf(
            "再生" to { requireActivity().playItems(p.items, 0) },
            "シャッフル再生" to {
                if (p.items.isNotEmpty()) requireActivity().playItems(p.items, p.items.indices.random(), shuffle = true)
            },
            "名前を変更" to {
                PlaylistDialogs.promptName(requireContext(), "名前を変更", p.name) { name ->
                    store.rename(id, name)
                    load()
                }
            },
            "削除" to {
                PlaylistDialogs.confirm(requireContext(), "「${p.name}」を削除しますか？\n（動画ファイルは削除されません）", "削除") {
                    store.delete(id)
                    load()
                }
            },
        )
    }
}
