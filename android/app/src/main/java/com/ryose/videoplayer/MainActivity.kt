package com.ryose.videoplayer

import android.Manifest
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Menu
import android.view.MenuItem
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.bottomnavigation.BottomNavigationView

class MainActivity : AppCompatActivity() {

    private lateinit var bottomNav: BottomNavigationView
    private var currentTab = R.id.tab_videos

    // 許可の結果は各タブの onResume で確認して読み込み直す
    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    private val openDocuments =
        registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            if (uris.isEmpty()) return@registerForActivityResult
            uris.forEach {
                try {
                    contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                } catch (_: SecurityException) {
                }
            }
            playItems(uris.map { PlaylistItem(it, queryDisplayName(it) ?: it.lastPathSegment ?: "動画", resolvePath(it)) }, 0)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        setSupportActionBar(findViewById<MaterialToolbar>(R.id.toolbar))

        // Android 15 以降の全画面表示に合わせて、ステータスバー等の分だけ余白をとる
        // （下のナビゲーションバーの分は BottomNavigationView が自分で余白をとる）
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.root)) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.updatePadding(left = bars.left, top = bars.top, right = bars.right)
            insets
        }

        bottomNav = findViewById(R.id.bottomNav)
        currentTab = savedInstanceState?.getInt(KEY_TAB, R.id.tab_videos) ?: R.id.tab_videos
        bottomNav.selectedItemId = currentTab
        bottomNav.setOnItemSelectedListener {
            showTab(it.itemId)
            true
        }
        bottomNav.setOnItemReselectedListener { }
        showTab(currentTab)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(KEY_TAB, currentTab)
    }

    /** タブを切り替える。見えていないタブは一時停止状態にして、メニューや「戻る」が混ざらないようにする */
    private fun showTab(id: Int) {
        currentTab = id
        val fm = supportFragmentManager
        val tag = "tab_$id"
        val tx = fm.beginTransaction().setReorderingAllowed(true)
        var target = fm.findFragmentByTag(tag)
        fm.fragments.filter { it != target }.forEach {
            tx.hide(it)
            tx.setMaxLifecycle(it, Lifecycle.State.STARTED)
        }
        if (target == null) {
            target = newTab(id)
            tx.add(R.id.container, target, tag)
        } else {
            tx.show(target)
        }
        tx.setMaxLifecycle(target, Lifecycle.State.RESUMED)
        tx.commit()
        supportActionBar?.subtitle = null
    }

    private fun newTab(id: Int): Fragment = when (id) {
        R.id.tab_folders -> FoldersFragment()
        R.id.tab_history -> HistoryFragment()
        R.id.tab_playlists -> PlaylistsFragment()
        else -> VideosFragment()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == R.id.action_open) {
            openDocuments.launch(arrayOf("video/*"))
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    fun requestStorageAccess() {
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

    private companion object {
        const val KEY_TAB = "tab"
    }
}
