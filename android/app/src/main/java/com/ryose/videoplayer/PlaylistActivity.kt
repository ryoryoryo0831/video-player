package com.ryose.videoplayer

import android.graphics.Color
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.TextView
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import java.io.File

/** プレイリストの中身（再生・並べ替え・削除） */
class PlaylistActivity : AppCompatActivity() {

    private lateinit var store: PlaylistStore
    private lateinit var adapter: MediaAdapter
    private lateinit var listView: RecyclerView
    private lateinit var emptyText: TextView
    private lateinit var playlistId: String

    private val touchHelper = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0) {
        override fun onMove(rv: RecyclerView, vh: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder): Boolean {
            adapter.move(vh.bindingAdapterPosition, target.bindingAdapterPosition)
            return true
        }

        override fun onSwiped(vh: RecyclerView.ViewHolder, direction: Int) {}

        override fun isLongPressDragEnabled() = false

        override fun clearView(rv: RecyclerView, vh: RecyclerView.ViewHolder) {
            super.clearView(rv, vh)
            // 並べ替えが終わったら保存
            store.setItems(playlistId, currentItems())
        }
    })

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_playlist)
        setSupportActionBar(findViewById<MaterialToolbar>(R.id.toolbar))
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        playlistId = intent.getStringExtra(EXTRA_ID) ?: run { finish(); return }
        store = PlaylistStore(this)
        listView = findViewById(R.id.list)
        emptyText = findViewById(R.id.emptyText)

        adapter = MediaAdapter(ResumeStore(this), ::onRowClick, ::onRowLongClick)
        adapter.dragListener = { touchHelper.startDrag(it) }
        listView.layoutManager = LinearLayoutManager(this)
        listView.adapter = adapter
        touchHelper.attachToRecyclerView(listView)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.root)) { v, insets ->
            // 横向きのときのカメラの切り欠き（ディスプレイカットアウト）にも重ならないように
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            v.updatePadding(left = bars.left, top = bars.top, right = bars.right)
            listView.updatePadding(bottom = bars.bottom)
            insets
        }
    }

    override fun onResume() {
        super.onResume()
        load()
    }

    private fun load() {
        val p = store.get(playlistId) ?: run { finish(); return }
        supportActionBar?.title = p.name
        // 全部が曲なら「曲」、全部が動画なら「本」、混ざっていれば「件」で数える
        val unit = when {
            p.items.isNotEmpty() && p.items.all { it.isAudio } -> "曲"
            p.items.none { it.isAudio } -> "本"
            else -> "件"
        }
        supportActionBar?.subtitle = "${p.items.size} $unit"
        adapter.rows = p.items.map { Row.Media(it, it.path?.let { path -> File(path).parentFile?.name }.orEmpty()) }
        emptyText.visibility = if (p.items.isEmpty()) View.VISIBLE else View.GONE
        if (playlistId == FavoriteMedia.PLAYLIST_ID) {
            emptyText.text = "お気に入りはまだありません。\n\n動画や曲を長押しして\n「お気に入りに追加」を選ぶと、ここに入ります。"
        }
    }

    private fun currentItems() = adapter.rows.filterIsInstance<Row.Media>().map { it.item }

    private fun onRowClick(position: Int) {
        val items = currentItems()
        if (position in items.indices) playItems(items, position)
    }

    private fun onRowLongClick(position: Int) {
        val item = currentItems().getOrNull(position) ?: return
        val isFavorites = playlistId == FavoriteMedia.PLAYLIST_ID
        ActionSheet.show(
            this, item.title,
            listOf(
                SheetItem(R.drawable.ic_play, "ここから再生") { onRowClick(position) },
                SheetItem(
                    if (isFavorites) R.drawable.ic_star_border else R.drawable.ic_delete,
                    if (isFavorites) "お気に入りから外す" else "プレイリストから外す",
                ) {
                    store.setItems(playlistId, currentItems().filterIndexed { i, _ -> i != position })
                    load()
                },
                SheetItem(R.drawable.ic_playlist_add, "ほかのプレイリストに追加") { PlaylistDialogs.addToPlaylist(this, listOf(item)) },
            ),
        )
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        // お気に入りは名前の変更・削除はできない
        val fixed = playlistId == FavoriteMedia.PLAYLIST_ID
        menu.findItem(R.id.action_rename)?.isVisible = !fixed
        menu.findItem(R.id.action_delete)?.isVisible = !fixed
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.playlist_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        val items = currentItems()
        when (item.itemId) {
            R.id.action_play_all -> playItems(items, 0)
            R.id.action_shuffle_play -> if (items.isNotEmpty()) playItems(items, items.indices.random(), shuffle = true)
            R.id.action_rename -> PlaylistDialogs.promptName(this, "名前を変更", supportActionBar?.title?.toString().orEmpty()) {
                store.rename(playlistId, it)
                load()
            }
            R.id.action_delete -> PlaylistDialogs.confirm(this, "このプレイリストを削除しますか？\n（動画ファイルは削除されません）", "削除") {
                store.delete(playlistId)
                finish()
            }
            else -> return super.onOptionsItemSelected(item)
        }
        return true
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    companion object {
        const val EXTRA_ID = "playlist_id"
    }
}
