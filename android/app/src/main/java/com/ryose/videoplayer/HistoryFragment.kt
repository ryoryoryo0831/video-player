package com.ryose.videoplayer

import android.os.Bundle
import android.text.format.DateUtils
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import androidx.core.view.MenuProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** 「履歴」タブ：最近再生した動画 */
class HistoryFragment : BaseListFragment() {

    private lateinit var history: HistoryStore

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        history = HistoryStore(requireContext())
        requireActivity().addMenuProvider(object : MenuProvider {
            override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) {
                menuInflater.inflate(R.menu.history_menu, menu)
            }

            override fun onMenuItemSelected(menuItem: MenuItem): Boolean {
                if (menuItem.itemId != R.id.action_clear_history) return false
                PlaylistDialogs.confirm(requireContext(), "再生履歴をすべて削除しますか？", "削除") {
                    history.clear()
                    load()
                }
                return true
            }
        }, viewLifecycleOwner, Lifecycle.State.RESUMED)
    }

    override fun onResume() {
        super.onResume()
        load()
    }

    override fun onFilesChanged() = load()

    private fun load() {
        viewLifecycleOwner.lifecycleScope.launch {
            val rows = withContext(Dispatchers.IO) {
                val now = System.currentTimeMillis()
                // 以前のバージョンで記録された音楽は出さない
                history.all().filterNot { it.item.isAudio }.map { e ->
                    val ago = DateUtils.getRelativeTimeSpanString(e.playedAt, now, DateUtils.MINUTE_IN_MILLIS)
                    val folder = e.item.path?.let { File(it).parentFile?.name }
                    Row.Media(e.item, listOfNotNull(ago.toString(), folder).joinToString(" · "))
                }
            }
            showRows(rows, "再生した動画がここに表示されます。", R.drawable.ic_history)
        }
    }

    /** 履歴はその動画だけを再生する */
    override fun onRowClick(position: Int) {
        val row = adapter.rows.getOrNull(position) as? Row.Media ?: return
        requireActivity().playItems(listOf(row.item), 0)
    }

    override fun extraActions(row: Row): List<SheetItem> {
        if (row !is Row.Media) return emptyList()
        return listOf(SheetItem(R.drawable.ic_history, "履歴から削除") {
            history.remove(row.item.key)
            load()
        })
    }
}
