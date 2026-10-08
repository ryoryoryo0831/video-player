package com.ryose.videoplayer

import android.Manifest
import android.content.ContentUris
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.provider.Settings
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SearchView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private enum class Sort { DATE, NAME, DURATION }

    private lateinit var adapter: VideoAdapter
    private lateinit var resume: ResumeStore
    private lateinit var listView: RecyclerView
    private lateinit var emptyView: View
    private lateinit var emptyText: TextView
    private lateinit var grantButton: Button
    private lateinit var loading: ProgressBar

    private var allVideos: List<Video> = emptyList()
    private var query = ""
    private var sort = Sort.DATE
    private var hadAccess = false

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { loadVideos() }

    private val openDocuments =
        registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            if (uris.isEmpty()) return@registerForActivityResult
            uris.forEach {
                try {
                    contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                } catch (_: SecurityException) {
                }
            }
            play(uris.map { PlaylistItem(it, queryDisplayName(it) ?: it.lastPathSegment ?: "動画", resolvePath(it)) }, 0)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        setSupportActionBar(findViewById<MaterialToolbar>(R.id.toolbar))

        resume = ResumeStore(this)
        listView = findViewById(R.id.list)
        emptyView = findViewById(R.id.emptyView)
        emptyText = findViewById(R.id.emptyText)
        grantButton = findViewById(R.id.grantButton)
        loading = findViewById(R.id.loading)

        adapter = VideoAdapter(resume) { position ->
            val shown = adapter.items
            if (position in shown.indices) play(shown.map { PlaylistItem(it.uri, it.title, it.path) }, position)
        }
        listView.layoutManager = LinearLayoutManager(this)
        listView.adapter = adapter

        grantButton.setOnClickListener { requestStorageAccess() }
        findViewById<Button>(R.id.openButton).setOnClickListener { openDocuments.launch(arrayOf("video/*")) }

        // Android 15 以降の全画面表示に合わせて、ステータスバー等の分だけ余白をとる
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.root)) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.updatePadding(left = bars.left, top = bars.top, right = bars.right)
            listView.updatePadding(bottom = bars.bottom)
            insets
        }

        hadAccess = hasStorageAccess()
        loadVideos()
    }

    override fun onResume() {
        super.onResume()
        // 設定画面でアクセスを許可して戻ってきたら読み込み直す
        val access = hasStorageAccess()
        if (access != hadAccess) {
            hadAccess = access
            loadVideos()
        }
        // 再生画面から戻ったとき、視聴位置のバーを更新
        adapter.notifyDataSetChanged()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        val searchView = menu.findItem(R.id.action_search).actionView as SearchView
        searchView.queryHint = getString(R.string.search)
        searchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(q: String?) = true
            override fun onQueryTextChange(q: String?): Boolean {
                query = q.orEmpty()
                applyFilter()
                return true
            }
        })
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.action_open -> openDocuments.launch(arrayOf("video/*"))
            R.id.action_refresh -> if (hasStorageAccess()) loadVideos() else requestStorageAccess()
            R.id.sort_date -> setSort(item, Sort.DATE)
            R.id.sort_name -> setSort(item, Sort.NAME)
            R.id.sort_duration -> setSort(item, Sort.DURATION)
            else -> return super.onOptionsItemSelected(item)
        }
        return true
    }

    private fun setSort(item: MenuItem, s: Sort) {
        item.isChecked = true
        sort = s
        applyFilter()
    }

    private fun requestStorageAccess() {
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                startActivity(
                    Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName"))
                )
            } catch (_: Exception) {
                startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
            }
        } else {
            permissionLauncher.launch(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
    }

    private fun play(items: List<PlaylistItem>, index: Int) {
        Playlist.items = items
        startActivity(
            Intent(this, PlayerActivity::class.java)
                .setData(items[index].uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        )
    }

    private fun loadVideos() {
        if (!hasStorageAccess()) {
            showEmpty(getString(R.string.need_permission), showGrant = true)
            return
        }
        loading.visibility = View.VISIBLE
        lifecycleScope.launch {
            allVideos = withContext(Dispatchers.IO) { queryVideos() }
            loading.visibility = View.GONE
            applyFilter()
        }
    }

    private fun applyFilter() {
        if (!hasStorageAccess()) return
        val filtered = allVideos
            .filter { query.isBlank() || it.title.contains(query, ignoreCase = true) || it.folder.contains(query, ignoreCase = true) }
            .let { list ->
                when (sort) {
                    Sort.DATE -> list.sortedByDescending { it.dateAdded }
                    Sort.NAME -> list.sortedBy { it.title.lowercase() }
                    Sort.DURATION -> list.sortedByDescending { it.durationMs }
                }
            }
        adapter.items = filtered
        if (filtered.isEmpty()) showEmpty(getString(R.string.no_videos), showGrant = false)
        else {
            emptyView.visibility = View.GONE
            listView.visibility = View.VISIBLE
        }
        supportActionBar?.subtitle = if (allVideos.isEmpty()) null else "${filtered.size} 本の動画"
    }

    private fun showEmpty(message: String, showGrant: Boolean) {
        emptyText.text = message
        grantButton.visibility = if (showGrant) View.VISIBLE else View.GONE
        emptyView.visibility = View.VISIBLE
        listView.visibility = View.GONE
    }

    @Suppress("DEPRECATION")
    private fun queryVideos(): List<Video> {
        val collection: Uri =
            if (Build.VERSION.SDK_INT >= 29) MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
            else MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DATA,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.DURATION,
            MediaStore.Video.Media.SIZE,
            MediaStore.Video.Media.BUCKET_DISPLAY_NAME,
            MediaStore.Video.Media.DATE_ADDED,
        )
        val result = mutableListOf<Video>()
        try {
            contentResolver.query(collection, projection, null, null, null)?.use { c ->
                val idCol = c.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
                val dataCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.DATA)
                val nameCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
                val durCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
                val sizeCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
                val folderCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.BUCKET_DISPLAY_NAME)
                val dateCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_ADDED)
                while (c.moveToNext()) {
                    result += Video(
                        uri = ContentUris.withAppendedId(collection, c.getLong(idCol)),
                        path = c.getString(dataCol),
                        title = c.getString(nameCol) ?: "(名前なし)",
                        durationMs = c.getLong(durCol),
                        size = c.getLong(sizeCol),
                        folder = c.getString(folderCol) ?: "",
                        dateAdded = c.getLong(dateCol),
                    )
                }
            }
        } catch (_: Exception) {
        }
        return result
    }
}
