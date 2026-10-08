package com.ryose.videoplayer

import android.os.Bundle
import android.text.format.Formatter
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import androidx.appcompat.widget.SearchView
import androidx.core.view.MenuProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 「動画」タブ：端末内のすべての動画 */
class VideosFragment : BaseListFragment() {

    private enum class Sort { DATE, NAME, DURATION }

    private var all: List<Video> = emptyList()
    private var shownCount = 0
    private var query = ""
    private var sort = Sort.DATE
    private var loaded = false
    private var loadedWithAccess = false

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        requireActivity().addMenuProvider(menuProvider, viewLifecycleOwner, Lifecycle.State.RESUMED)
    }

    override fun onResume() {
        super.onResume()
        if (!loaded || requireContext().hasStorageAccess() != loadedWithAccess) load()
    }

    override fun subtitle() = if (all.isEmpty()) null else "${shownCount} 本の動画"

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

    private fun applyFilter() {
        val ctx = context ?: return
        val filtered = all
            .filter { query.isBlank() || it.title.contains(query, ignoreCase = true) || it.folder.contains(query, ignoreCase = true) }
            .let { list ->
                when (sort) {
                    Sort.DATE -> list.sortedByDescending { it.dateAdded }
                    Sort.NAME -> list.sortedWith(compareBy(NaturalOrder) { it.title })
                    Sort.DURATION -> list.sortedByDescending { it.durationMs }
                }
            }
        shownCount = filtered.size
        showRows(
            filtered.map { Row.Media(it.toItem(), "${it.folder} · ${Formatter.formatShortFileSize(ctx, it.size)}") },
            getString(R.string.no_videos),
        )
    }

    override fun onRowClick(position: Int) = playMediaAt(position)

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
        }

        override fun onMenuItemSelected(menuItem: MenuItem): Boolean {
            when (menuItem.itemId) {
                R.id.action_refresh -> load()
                R.id.sort_date -> setSort(Sort.DATE)
                R.id.sort_name -> setSort(Sort.NAME)
                R.id.sort_duration -> setSort(Sort.DURATION)
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
}
