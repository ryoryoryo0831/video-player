package com.ryose.videoplayer

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.bottomnavigation.BottomNavigationView
import coil.load
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.videolan.libvlc.MediaPlayer

class MainActivity : AppCompatActivity() {

    private lateinit var bottomNav: BottomNavigationView
    private var currentTab = R.id.tab_videos

    // ミニプレイヤー
    private lateinit var miniPlayer: View
    private lateinit var miniArt: ImageView
    private lateinit var miniTitle: TextView
    private lateinit var miniSubtitle: TextView
    private lateinit var miniPlay: ImageButton
    private lateinit var miniProgress: com.google.android.material.progressindicator.LinearProgressIndicator
    private var miniArtKey: String? = null

    private val miniListener = object : PlaybackService.Listener {
        override fun onPlayerEvent(e: MediaPlayer.Event) {
            when (e.type) {
                MediaPlayer.Event.Playing, MediaPlayer.Event.Paused, MediaPlayer.Event.Stopped -> updateMini()
                MediaPlayer.Event.TimeChanged -> updateMiniProgress()
            }
        }
        override fun onItemChanged() = updateMini()
        override fun onPlaybackStopped() = updateMini()
    }

    /** 再生サービスが動いているときだけつながる */
    private val connection = PlaybackConnection(
        this, autoCreate = false,
        onConnected = { s ->
            s.addListener(miniListener)
            updateMini()
        },
        onDisconnected = { updateMini() },
    )

    // 許可の結果は各タブの onResume で確認して読み込み直す
    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    /**
     * 動画・音楽の一覧を読む許可。
     * 1 回断られただけなら、タブの「アクセスを許可」からもう一度聞ける。
     * 二度と聞けない状態（「今後表示しない」・2 回断られた）のときだけ、端末の設定など、ほかの方法を案内する
     */
    private val mediaPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            if (result.isNotEmpty() && result.values.none { it } &&
                result.keys.none { shouldShowRequestPermissionRationale(it) }
            ) {
                showAccessChoices()
            }
        }

    /** 通知の許可（再生中の通知・ロック画面の操作用）。結果に関係なく、聞くのは一度だけ */
    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    private val openDocuments =
        registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            if (uris.isEmpty()) return@registerForActivityResult
            // 名前やファイルの場所を調べるのは時間がかかることがあるので、画面の処理とは別のところで行う
            lifecycleScope.launch {
                val items = withContext(Dispatchers.IO) {
                    uris.map {
                        try {
                            contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        } catch (_: SecurityException) {
                        }
                        PlaylistItem(it, queryDisplayName(it) ?: it.lastPathSegment ?: "メディア", resolvePath(it))
                    }
                }
                playItems(items, 0)
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        // 起動中の画面（スプラッシュ画面）。テーマを元に戻すので super.onCreate より前に呼ぶ
        installSplashScreen()
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        setSupportActionBar(findViewById<MaterialToolbar>(R.id.toolbar))

        // Android 15 以降の全画面表示に合わせて、ステータスバー等の分だけ余白をとる
        // （下のナビゲーションバーの分は BottomNavigationView が自分で余白をとる）
        // 横向きのときのカメラの切り欠き（ディスプレイカットアウト）にも重ならないように
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.root)) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
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

        miniPlayer = findViewById(R.id.miniPlayer)
        miniArt = findViewById(R.id.miniArt)
        miniTitle = findViewById(R.id.miniTitle)
        miniSubtitle = findViewById(R.id.miniSubtitle)
        miniPlay = findViewById(R.id.miniPlay)
        miniProgress = findViewById(R.id.miniProgress)
        miniPlayer.setOnClickListener { connection.service?.let { startActivity(it.screenIntent()) } }
        miniPlay.setOnClickListener { connection.service?.togglePlay() }
        findViewById<View>(R.id.miniNext).setOnClickListener { connection.service?.next() }
        findViewById<View>(R.id.miniClose).setOnClickListener { connection.service?.stopPlayback() }
    }

    override fun onStart() {
        super.onStart()
        connection.bind()
    }

    override fun onResume() {
        super.onResume()
        maybeAskNotificationPermission()
    }

    /**
     * Android 13 以降は、再生中の通知を出すのに許可が要る。
     * 起動してすぐに許可の画面を重ねないよう、動画・音楽を読む許可の流れが済んでから（何か読める状態になってから）一度だけ聞く
     */
    private fun maybeAskNotificationPermission() {
        if (Build.VERSION.SDK_INT < 33 || AppSettings.notificationPermissionAsked(this)) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
            AppSettings.setNotificationPermissionAsked(this)
            return
        }
        if (!(hasMediaAccess(audio = false) || hasMediaAccess(audio = true))) return
        AppSettings.setNotificationPermissionAsked(this)
        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    override fun onStop() {
        super.onStop()
        connection.service?.removeListener(miniListener)
        connection.unbind()
    }

    private fun updateMini() {
        val s = connection.service
        val item = s?.currentItem
        if (item == null) {
            miniPlayer.visibility = View.GONE
            miniArtKey = null
            return
        }
        miniPlayer.visibility = View.VISIBLE
        miniTitle.text = s.displayTitle()
        miniSubtitle.text = s.displaySubtitle().ifEmpty { if (item.isAudio) "" else "動画" }
        miniSubtitle.visibility = if (miniSubtitle.text.isEmpty()) View.GONE else View.VISIBLE
        miniPlay.setImageResource(if (s.isPlaying) R.drawable.ic_pause else R.drawable.ic_play)
        updateMiniProgress()
        // 画像は曲が変わったときだけ読み込み直す
        val art = s.meta?.art
        val key = item.key + (if (art != null) "#art" else "")
        if (key != miniArtKey) {
            miniArtKey = key
            when {
                art != null -> miniArt.setImageBitmap(art)
                item.isAudio -> miniArt.setImageResource(R.drawable.ic_music_note)
                item.isNetwork -> miniArt.setImageResource(R.drawable.ic_movie)
                else -> miniArt.load(VideoThumb(item.path, item.uri)) {
                    placeholder(R.drawable.ic_movie)
                    error(R.drawable.ic_movie)
                }
            }
        }
    }

    /** ミニプレイヤーの上の細いバー（再生位置） */
    private fun updateMiniProgress() {
        val s = connection.service ?: return
        val len = s.lengthMs
        miniProgress.progress = if (len > 0) (s.player.time.coerceAtLeast(0) * 1000 / len).toInt().coerceIn(0, 1000) else 0
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
        R.id.tab_music -> MusicFragment()
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
            openDocuments.launch(arrayOf("video/*", "audio/*"))
            return true
        }
        if (item.itemId == R.id.action_settings) {
            startActivity(Intent(this, SettingsActivity::class.java))
            return true
        }
        if (item.itemId == R.id.action_open_url) {
            NetworkDialogs.openUrl(this)
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    /** 動画・音楽タブの「アクセスを許可」 */
    fun requestMediaAccess() {
        mediaPermissionLauncher.launch(mediaPermissions())
    }

    private fun showAccessChoices() {
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle("アクセスが許可されていません")
            .setMessage("端末の設定でこのアプリの「権限」から「音楽とオーディオ」「写真と動画」を許可するか、「すべてのファイルへのアクセス」を許可してください。")
            .setPositiveButton("アプリの設定を開く") { _, _ ->
                runCatching {
                    startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
                }
            }
            .setNeutralButton("すべてのファイル") { _, _ -> requestStorageAccess() }
            .setNegativeButton("キャンセル", null)
            .show()
    }

    fun requestStorageAccess() {
        if (Build.VERSION.SDK_INT >= 30) {
            // 機種（Android TV など）によっては設定画面が無いので、順に試す
            val intents = listOf(
                Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName")),
                Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION),
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")),
            )
            val opened = intents.any { runCatching { startActivity(it) }.isSuccess }
            if (!opened) {
                android.widget.Toast.makeText(this, "この端末では設定画面を開けませんでした", android.widget.Toast.LENGTH_LONG).show()
            }
        } else {
            permissionLauncher.launch(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
    }

    private companion object {
        const val KEY_TAB = "tab"
    }
}
