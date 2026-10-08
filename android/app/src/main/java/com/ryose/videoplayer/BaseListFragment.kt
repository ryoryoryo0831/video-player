package com.ryose.videoplayer

import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.floatingactionbutton.FloatingActionButton

/** 動画・フォルダ・履歴・プレイリストの各タブに共通する一覧画面 */
abstract class BaseListFragment : Fragment(R.layout.fragment_list) {

    protected lateinit var list: RecyclerView
    protected lateinit var emptyView: View
    protected lateinit var emptyIcon: ImageView
    protected lateinit var emptyText: TextView
    protected lateinit var grantButton: Button
    protected lateinit var loading: ProgressBar
    protected lateinit var fab: FloatingActionButton
    protected lateinit var resume: ResumeStore
    protected lateinit var adapter: MediaAdapter

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        list = view.findViewById(R.id.list)
        emptyView = view.findViewById(R.id.emptyView)
        emptyIcon = view.findViewById(R.id.emptyIcon)
        emptyText = view.findViewById(R.id.emptyText)
        grantButton = view.findViewById(R.id.grantButton)
        loading = view.findViewById(R.id.loading)
        fab = view.findViewById(R.id.fab)
        resume = ResumeStore(requireContext())
        adapter = MediaAdapter(resume, ::onRowClick, ::onRowLongClick)
        list.layoutManager = LinearLayoutManager(requireContext())
        list.adapter = adapter
        grantButton.setOnClickListener { onGrantClick() }
    }

    override fun onResume() {
        super.onResume()
        // 再生画面から戻ったとき、視聴位置のバーを更新
        adapter.refreshProgress()
        updateSubtitle()
    }

    /** ツールバーに表示する補足（件数や今いるフォルダなど） */
    protected open fun subtitle(): String? = null

    protected fun updateSubtitle() {
        if (isResumed) (activity as? AppCompatActivity)?.supportActionBar?.subtitle = subtitle()
    }

    protected fun showRows(rows: List<Row>, emptyMessage: String, emptyIconRes: Int = R.drawable.ic_movie) {
        loading.visibility = View.GONE
        adapter.rows = rows
        if (rows.isEmpty()) {
            emptyText.text = emptyMessage
            emptyIcon.setImageResource(emptyIconRes)
            grantButton.visibility = View.GONE
            emptyView.visibility = View.VISIBLE
            list.visibility = View.GONE
        } else {
            emptyView.visibility = View.GONE
            list.visibility = View.VISIBLE
        }
        updateSubtitle()
    }

    /** 「アクセスを許可」が押されたとき（動画・音楽タブは一覧を読む許可、フォルダタブはすべてのファイル） */
    protected open fun onGrantClick() {
        (activity as? MainActivity)?.requestMediaAccess()
    }

    protected open val needPermissionText: Int get() = R.string.need_media_permission

    protected fun showNeedPermission() {
        loading.visibility = View.GONE
        adapter.rows = emptyList()
        emptyText.setText(needPermissionText)
        emptyIcon.setImageResource(R.drawable.ic_folder)
        grantButton.visibility = View.VISIBLE
        emptyView.visibility = View.VISIBLE
        list.visibility = View.GONE
        updateSubtitle()
    }

    protected open fun onRowClick(position: Int) {}

    /** 一覧に並んでいる動画をまとめてプレイリストにして、指定の行から再生する */
    protected fun playMediaAt(position: Int) {
        val rows = adapter.rows
        val target = rows.getOrNull(position) as? Row.Media ?: return
        val media = rows.filterIsInstance<Row.Media>()
        requireActivity().playItems(media.map { it.item }, media.indexOf(target))
    }

    /** ファイルの名前を変えた・消したあとに一覧を読み込み直す（画面ごとに上書き） */
    protected open fun onFilesChanged() {}

    /** Android の確認画面で削除しようとしているもの */
    private var pendingDelete: PlaylistItem? = null

    private val systemDelete = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        val item = pendingDelete
        pendingDelete = null
        if (result.resultCode == android.app.Activity.RESULT_OK && item != null) {
            FileActions.forget(requireContext(), item)
            onFilesChanged()
        }
    }

    /** 長押しメニューに追加する項目（画面ごとに上書き） */
    protected open fun extraActions(row: Row): List<Pair<String, () -> Unit>> = emptyList()

    private fun onRowLongClick(position: Int) {
        val row = adapter.rows.getOrNull(position) ?: return
        val actions = mutableListOf<Pair<String, () -> Unit>>()
        if (row is Row.Media) {
            actions += "再生" to { onRowClick(position) }
            actions += "プレイリストに追加" to { PlaylistDialogs.addToPlaylist(requireContext(), listOf(row.item)) }
        }
        actions += extraActions(row)
        if (row is Row.Media && FileActions.isLocal(row.item)) {
            val ctx = requireContext()
            val item = row.item
            actions += "共有" to { FileActions.share(ctx, item) }
            actions += "詳細" to { viewLifecycleOwner.lifecycleScope.launch { FileActions.showDetails(ctx, item) } }
            if (FileActions.canRename(ctx, item)) actions += "名前を変更" to { FileActions.rename(ctx, item) { onFilesChanged() } }
            if (FileActions.canDelete(ctx, item)) actions += "削除" to {
                pendingDelete = item
                FileActions.delete(ctx, item, { systemDelete.launch(it) }) { onFilesChanged() }
            }
        }
        if (actions.isEmpty()) return
        val title = when (row) {
            is Row.Media -> row.item.title
            is Row.Folder -> row.name
            is Row.Header -> return
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(title)
            .setItems(actions.map { it.first }.toTypedArray()) { _, i -> actions[i].second() }
            .show()
    }
}
