package com.ryose.videoplayer

import android.annotation.SuppressLint
import android.app.PictureInPictureParams
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.provider.Settings
import android.util.Rational
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.addCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.interfaces.IMedia
import org.videolan.libvlc.util.VLCVideoLayout
import java.io.File
import java.io.FileNotFoundException
import kotlin.math.abs
import kotlin.math.roundToInt

class PlayerActivity : AppCompatActivity() {

    private enum class Gesture { NONE, IGNORE, BRIGHTNESS, VOLUME, SEEK }

    private lateinit var libVLC: LibVLC
    private lateinit var player: MediaPlayer
    private lateinit var videoLayout: VLCVideoLayout
    private lateinit var touchLayer: View
    private lateinit var topBar: View
    private lateinit var bottomBar: View
    private lateinit var titleView: TextView
    private lateinit var seekBar: SeekBar
    private lateinit var timeCurrent: TextView
    private lateinit var timeDuration: TextView
    private lateinit var playButton: ImageButton
    private lateinit var prevButton: ImageButton
    private lateinit var nextButton: ImageButton
    private lateinit var speedButton: TextView
    private lateinit var unlockButton: ImageButton
    private lateinit var gestureInfo: TextView
    private lateinit var resume: ResumeStore
    private lateinit var audioManager: AudioManager

    private val handler = Handler(Looper.getMainLooper())
    private val hasPip by lazy { packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE) }

    // プレイリスト
    private var items: List<PlaylistItem> = emptyList()
    private var index = 0
    private var openFd: ParcelFileDescriptor? = null
    /** 「字幕ファイルを追加」で読み込んだ字幕（動画ごと） */
    private val addedSubtitles = mutableMapOf<String, MutableList<File>>()

    // 再生状態
    private var viewsAttached = false
    private var pendingStart: Long? = null
    private var pendingPaused = false
    private var lengthMs = 0L
    private var rate = 1f
    private var videoW = 0
    private var videoH = 0
    private var scaleIndex = 0
    private var pausedByFocus = false

    // 画面の状態
    private var controlsVisible = true
    private var locked = false
    private var userSeeking = false
    private var inPip = false
    private var orientationLocked = false

    // ジェスチャー
    private var gesture = Gesture.NONE
    private var gestureStartValue = 0f
    private var seekStartPos = 0L
    private var seekTarget = 0L

    private val scales = listOf(
        MediaPlayer.ScaleType.SURFACE_BEST_FIT to "画面に合わせる",
        MediaPlayer.ScaleType.SURFACE_FIT_SCREEN to "画面いっぱい（端をカット）",
        MediaPlayer.ScaleType.SURFACE_FILL to "引き伸ばし",
        MediaPlayer.ScaleType.SURFACE_16_9 to "16:9",
        MediaPlayer.ScaleType.SURFACE_4_3 to "4:3",
        MediaPlayer.ScaleType.SURFACE_ORIGINAL to "元のサイズ",
    )
    private val speeds = floatArrayOf(0.25f, 0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f, 3f, 4f)

    private val saveTask = object : Runnable {
        override fun run() {
            savePosition()
            handler.postDelayed(this, 2000)
        }
    }
    private val hideInfo = Runnable { gestureInfo.visibility = View.GONE }
    private val hideControlsTask = Runnable { hideControls() }
    private val hideUnlockTask = Runnable { unlockButton.visibility = View.GONE }

    private val pickSubtitle =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let { addSubtitleFromUri(it) } }

    // ---------- 音声フォーカス（他のアプリの音や電話との調整） ----------

    private val focusRequest by lazy {
        AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                    .build()
            )
            .setOnAudioFocusChangeListener { change ->
                when (change) {
                    AudioManager.AUDIOFOCUS_LOSS -> player.pause()
                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> if (player.isPlaying) {
                        pausedByFocus = true
                        player.pause()
                    }
                    AudioManager.AUDIOFOCUS_GAIN -> if (pausedByFocus) {
                        pausedByFocus = false
                        player.play()
                    }
                }
            }
            .build()
    }

    /** イヤホンが抜けたら一時停止 */
    private val noisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) player.pause()
        }
    }

    // ---------- ライフサイクル ----------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_player)

        if (Build.VERSION.SDK_INT >= 28) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        WindowCompat.setDecorFitsSystemWindows(window, false)
        hideSystemUi()

        videoLayout = findViewById(R.id.videoLayout)
        touchLayer = findViewById(R.id.touchLayer)
        topBar = findViewById(R.id.topBar)
        bottomBar = findViewById(R.id.bottomBar)
        titleView = findViewById(R.id.titleView)
        seekBar = findViewById(R.id.seekBar)
        timeCurrent = findViewById(R.id.timeCurrent)
        timeDuration = findViewById(R.id.timeDuration)
        playButton = findViewById(R.id.playButton)
        prevButton = findViewById(R.id.prevButton)
        nextButton = findViewById(R.id.nextButton)
        speedButton = findViewById(R.id.speedButton)
        unlockButton = findViewById(R.id.unlockButton)
        gestureInfo = findViewById(R.id.gestureInfo)
        resume = ResumeStore(this)
        audioManager = getSystemService(AudioManager::class.java)

        libVLC = LibVLC(
            this,
            arrayListOf(
                "--audio-time-stretch",     // 速度を変えても声の高さを変えない
                "--no-sub-autodetect-file", // 字幕の自動読み込みはアプリ側で行う（文字コード変換のため）
                "--http-reconnect",
            )
        )
        player = MediaPlayer(libVLC)
        player.setEventListener(object : MediaPlayer.EventListener {
            override fun onEvent(event: MediaPlayer.Event) = onPlayerEvent(event)
        })

        ContextCompat.registerReceiver(
            this, noisyReceiver, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        onBackPressedDispatcher.addCallback(this) {
            if (locked) showUnlockBriefly() else finish()
        }

        setupControls()
        setupGestures()
        setupInsets()
        loadFromIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        savePosition()
        orientationLocked = false
        loadFromIntent(intent)
    }

    override fun onStart() {
        super.onStart()
        player.attachViews(videoLayout, null, true, false)
        viewsAttached = true
        pendingStart?.let {
            pendingStart = null
            playCurrent(it, pendingPaused)
        }
    }

    override fun onStop() {
        super.onStop()
        // バックグラウンドに回ったら止める。戻ってきたら同じ位置から（一時停止状態で）再開する
        if (pendingStart == null && items.isNotEmpty() && !isFinishing) {
            pendingStart = player.time.coerceAtLeast(0)
            pendingPaused = true
        }
        savePosition()
        handler.removeCallbacks(saveTask)
        player.stop()
        player.detachViews()
        viewsAttached = false
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        runCatching { unregisterReceiver(noisyReceiver) }
        audioManager.abandonAudioFocusRequest(focusRequest)
        player.setEventListener(null)
        player.release()
        libVLC.release()
        closeFd()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemUi()
    }

    // ---------- 読み込みと再生 ----------

    private fun loadFromIntent(intent: Intent) {
        val data = intent.data ?: run { finish(); return }
        val list = Playlist.items
        val idx = list.indexOfFirst { it.uri == data }
        if (idx >= 0) {
            items = list
            index = idx
        } else {
            // 他のアプリから開かれた場合は単体再生
            items = listOf(PlaylistItem(data, queryDisplayName(data) ?: data.lastPathSegment ?: "動画", resolvePath(data)))
            index = 0
        }
        val start = resume.get(items[index].key)
        if (viewsAttached) playCurrent(start, false) else {
            pendingStart = start
            pendingPaused = false
        }
        if (start > 0) showInfo("続きから再生  ${formatTime(start)}")
    }

    private fun playCurrent(startMs: Long, paused: Boolean) {
        val item = items.getOrNull(index) ?: return
        closeFd()
        lengthMs = 0
        videoW = 0
        videoH = 0

        val media = try {
            val path = item.path
            if (path != null && File(path).canRead()) {
                Media(libVLC, path)
            } else {
                val pfd = contentResolver.openFileDescriptor(item.uri, "r") ?: throw FileNotFoundException()
                openFd = pfd
                Media(libVLC, pfd.fileDescriptor)
            }
        } catch (_: Exception) {
            Toast.makeText(this, "ファイルを開けませんでした", Toast.LENGTH_LONG).show()
            return
        }
        media.setHWDecoderEnabled(true, false)
        if (startMs > 0) media.addOption(":start-time=${startMs / 1000.0}")
        if (paused) media.addOption(":start-paused")
        player.setMedia(media)
        media.release()
        player.play()

        titleView.text = item.title
        seekBar.progress = 0
        timeCurrent.text = formatTime(startMs)
        timeDuration.text = formatTime(0)
        prevButton.alpha = if (index > 0) 1f else 0.4f
        nextButton.alpha = if (index < items.lastIndex) 1f else 0.4f
        loadSubtitles(item)
    }

    private fun playIndex(i: Int) {
        if (i !in items.indices) return
        savePosition()
        index = i
        playCurrent(resume.get(items[i].key), false)
    }

    private fun closeFd() {
        runCatching { openFd?.close() }
        openFd = null
    }

    private fun onPlayerEvent(e: MediaPlayer.Event) {
        when (e.type) {
            MediaPlayer.Event.Playing -> {
                playButton.setImageResource(R.drawable.ic_pause)
                videoLayout.keepScreenOn = true
                if (player.rate != rate) player.rate = rate
                audioManager.requestAudioFocus(focusRequest)
                handler.removeCallbacks(saveTask)
                handler.post(saveTask)
                scheduleHide()
                updatePipParams()
            }
            MediaPlayer.Event.Paused -> {
                playButton.setImageResource(R.drawable.ic_play)
                videoLayout.keepScreenOn = false
                handler.removeCallbacks(saveTask)
                savePosition()
                showControls(autoHide = false)
                updatePipParams()
            }
            MediaPlayer.Event.Stopped -> {
                playButton.setImageResource(R.drawable.ic_play)
                videoLayout.keepScreenOn = false
            }
            MediaPlayer.Event.LengthChanged -> setLength(e.lengthChanged)
            MediaPlayer.Event.TimeChanged -> {
                if (lengthMs <= 0) setLength(player.length)
                if (!userSeeking) {
                    seekBar.progress = e.timeChanged.toInt()
                    timeCurrent.text = formatTime(e.timeChanged)
                }
            }
            MediaPlayer.Event.Vout -> if (e.voutCount > 0) onVideoReady()
            MediaPlayer.Event.EndReached -> onEnded()
            MediaPlayer.Event.EncounteredError ->
                Toast.makeText(this, "再生できませんでした", Toast.LENGTH_LONG).show()
        }
    }

    private fun setLength(ms: Long) {
        if (ms <= 0) return
        lengthMs = ms
        seekBar.max = ms.toInt()
        timeDuration.text = formatTime(ms)
    }

    private fun onEnded() {
        items.getOrNull(index)?.let { resume.clear(it.key) }
        if (index < items.lastIndex) {
            index++
            playCurrent(resume.get(items[index].key), false)
        } else {
            finish()
        }
    }

    private fun onVideoReady() {
        val vt = runCatching { player.currentVideoTrack }.getOrNull() ?: return
        var w = vt.width
        var h = vt.height
        if (vt.sarNum > 0 && vt.sarDen > 0) w = w * vt.sarNum / vt.sarDen
        // スマホで縦に撮った動画などは回転情報を考慮する
        if (vt.orientation >= 4) w = h.also { h = w }
        if (w <= 0 || h <= 0) return
        videoW = w
        videoH = h
        if (!orientationLocked) {
            requestedOrientation =
                if (w >= h) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                else ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
        }
        player.setVideoScale(scales[scaleIndex].first)
        updatePipParams()
    }

    private fun savePosition() {
        if (!::player.isInitialized) return
        val item = items.getOrNull(index) ?: return
        val len = if (lengthMs > 0) lengthMs else player.length
        val t = player.time
        if (t < 0 || len <= 0) return
        resume.save(item.key, t, len)
    }

    private fun togglePlay() {
        if (player.isPlaying) player.pause() else player.play()
    }

    private fun seekBy(deltaMs: Long) {
        val max = if (lengthMs > 0) lengthMs else Long.MAX_VALUE
        player.setTime((player.time + deltaMs).coerceIn(0, max))
    }

    // ---------- 字幕 ----------

    private fun loadSubtitles(item: PlaylistItem) {
        val manual = addedSubtitles[item.key].orEmpty().toList()
        val path = item.path
        lifecycleScope.launch {
            val auto = if (path == null) emptyList() else withContext(Dispatchers.IO) {
                Subtitles.findFor(path).map { Subtitles.prepare(this@PlayerActivity, it) }
            }
            if (items.getOrNull(index) != item) return@launch
            (manual + auto).forEachIndexed { i, f ->
                player.addSlave(IMedia.Slave.Type.Subtitle, Uri.fromFile(f), i == 0)
            }
            if (auto.isNotEmpty()) showInfo("字幕を読み込みました")
        }
    }

    private fun addSubtitleFromUri(uri: Uri) {
        lifecycleScope.launch {
            val file = withContext(Dispatchers.IO) {
                runCatching {
                    val name = queryDisplayName(uri) ?: "subtitle.srt"
                    val bytes = contentResolver.openInputStream(uri)!!.use { it.readBytes() }
                    Subtitles.saveToCache(this@PlayerActivity, name, bytes)
                }.getOrNull()
            }
            val item = items.getOrNull(index)
            if (file == null || item == null) {
                Toast.makeText(this@PlayerActivity, "字幕ファイルを読み込めませんでした", Toast.LENGTH_LONG).show()
                return@launch
            }
            addedSubtitles.getOrPut(item.key) { mutableListOf() }.add(0, file)
            player.addSlave(IMedia.Slave.Type.Subtitle, Uri.fromFile(file), true)
            showInfo("字幕を読み込みました")
        }
    }

    private fun trackLabel(t: MediaPlayer.TrackDescription, current: Int): String {
        val name = if (t.id == -1) "オフ" else t.name ?: "トラック ${t.id}"
        return (if (t.id == current) "✓  " else "      ") + name
    }

    private fun showSubtitleMenu() {
        val tracks = player.spuTracks ?: emptyArray()
        val current = player.spuTrack
        val labels = tracks.map { trackLabel(it, current) } +
            "＋  字幕ファイルを追加…" +
            "⏱  タイミング調整…（現在 ${formatDelay(player.spuDelay)}）"
        MaterialAlertDialogBuilder(this)
            .setTitle("字幕")
            .setItems(labels.toTypedArray()) { _, which ->
                when {
                    which < tracks.size -> player.setSpuTrack(tracks[which].id)
                    which == tracks.size -> pickSubtitle.launch(arrayOf("*/*"))
                    else -> showDelayDialog(subtitle = true)
                }
            }
            .show()
    }

    private fun showAudioMenu() {
        val tracks = player.audioTracks ?: emptyArray()
        val current = player.audioTrack
        val labels = tracks.map { trackLabel(it, current) } +
            "⏱  タイミング調整…（現在 ${formatDelay(player.audioDelay)}）"
        MaterialAlertDialogBuilder(this)
            .setTitle("音声トラック")
            .setItems(labels.toTypedArray()) { _, which ->
                if (which < tracks.size) player.setAudioTrack(tracks[which].id)
                else showDelayDialog(subtitle = false)
            }
            .show()
    }

    private fun formatDelay(us: Long) = "%+.1f 秒".format(us / 1_000_000.0)

    /** 字幕・音声のずれを 0.1 秒単位で調整するダイアログ */
    private fun showDelayDialog(subtitle: Boolean) {
        val dp = resources.displayMetrics.density
        fun current() = if (subtitle) player.spuDelay else player.audioDelay
        fun apply(us: Long) {
            if (subtitle) player.setSpuDelay(us) else player.setAudioDelay(us)
        }

        val value = TextView(this).apply {
            textSize = 28f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setPadding(0, 0, 0, (12 * dp).toInt())
        }
        fun refresh() {
            value.text = formatDelay(current())
        }

        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        listOf(-1000L, -100L, 100L, 1000L).forEach { ms ->
            row.addView(
                MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                    text = (if (ms > 0) "+" else "−") + "%.1f".format(abs(ms) / 1000.0)
                    setPadding(0, 0, 0, 0)
                    setOnClickListener {
                        apply(current() + ms * 1000)
                        refresh()
                    }
                },
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginStart = (3 * dp).toInt()
                    marginEnd = (3 * dp).toInt()
                }
            )
        }
        val hint = TextView(this).apply {
            text = if (subtitle) "＋：字幕を遅らせる　−：字幕を早める" else "＋：音声を遅らせる　−：音声を早める"
            textSize = 13f
            gravity = Gravity.CENTER
            setTextColor(ContextCompat.getColor(this@PlayerActivity, R.color.text_muted))
            setPadding(0, (8 * dp).toInt(), 0, 0)
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((20 * dp).toInt(), (16 * dp).toInt(), (20 * dp).toInt(), 0)
            addView(value)
            addView(row)
            addView(hint)
        }
        refresh()
        MaterialAlertDialogBuilder(this)
            .setTitle(if (subtitle) "字幕のタイミング" else "音声のタイミング")
            .setView(box)
            .setPositiveButton("閉じる", null)
            .setNeutralButton("リセット") { _, _ -> apply(0) }
            .show()
    }

    // ---------- 速度・画面サイズ・向き ----------

    private fun showSpeedMenu() {
        val labels = speeds.map { (if (it == rate) "✓  " else "      ") + "${it}x" }
        MaterialAlertDialogBuilder(this)
            .setTitle("再生速度")
            .setItems(labels.toTypedArray()) { _, which ->
                rate = speeds[which]
                player.rate = rate
                speedButton.text = "${rate}x"
            }
            .show()
    }

    private fun cycleScale() {
        scaleIndex = (scaleIndex + 1) % scales.size
        player.setVideoScale(scales[scaleIndex].first)
        showInfo(scales[scaleIndex].second)
    }

    private fun toggleOrientation() {
        orientationLocked = true
        val isLandscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        requestedOrientation =
            if (isLandscape) ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
            else ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
    }

    // ---------- コントロールの表示 ----------

    private fun setupControls() {
        fun View.onTap(action: () -> Unit) = setOnClickListener {
            action()
            scheduleHide()
        }
        findViewById<View>(R.id.backButton).setOnClickListener { finish() }
        findViewById<View>(R.id.pipButton).apply {
            visibility = if (hasPip) View.VISIBLE else View.GONE
            setOnClickListener { enterPip() }
        }
        playButton.onTap { togglePlay() }
        prevButton.onTap { if (player.time > 3000) player.setTime(0) else playIndex(index - 1) }
        nextButton.onTap { playIndex(index + 1) }
        findViewById<View>(R.id.lockButton).setOnClickListener { setLocked(true) }
        unlockButton.setOnClickListener { setLocked(false) }
        findViewById<View>(R.id.subtitleButton).onTap { showSubtitleMenu() }
        findViewById<View>(R.id.audioButton).onTap { showAudioMenu() }
        speedButton.onTap { showSpeedMenu() }
        findViewById<View>(R.id.aspectButton).onTap { cycleScale() }
        findViewById<View>(R.id.rotateButton).onTap { toggleOrientation() }

        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) timeCurrent.text = formatTime(progress.toLong())
            }

            override fun onStartTrackingTouch(sb: SeekBar) {
                userSeeking = true
                handler.removeCallbacks(hideControlsTask)
            }

            override fun onStopTrackingTouch(sb: SeekBar) {
                player.setTime(sb.progress.toLong())
                userSeeking = false
                scheduleHide()
            }
        })
    }

    private fun setupInsets() {
        val topPad = topBar.paddingTop
        val sidePad = topBar.paddingLeft
        ViewCompat.setOnApplyWindowInsetsListener(topBar) { v, insets ->
            val cut = insets.getInsets(WindowInsetsCompat.Type.displayCutout())
            v.updatePadding(left = sidePad + cut.left, top = topPad + cut.top, right = sidePad + cut.right)
            insets
        }
        val bottomPad = bottomBar.paddingBottom
        val bottomSide = bottomBar.paddingLeft
        ViewCompat.setOnApplyWindowInsetsListener(bottomBar) { v, insets ->
            val cut = insets.getInsets(WindowInsetsCompat.Type.displayCutout())
            v.updatePadding(left = bottomSide + cut.left, right = bottomSide + cut.right, bottom = bottomPad + cut.bottom)
            insets
        }
    }

    private fun View.fadeIn() {
        animate().cancel()
        if (visibility != View.VISIBLE) {
            alpha = 0f
            visibility = View.VISIBLE
        }
        animate().alpha(1f).setDuration(150).start()
    }

    private fun View.fadeOut() {
        animate().cancel()
        animate().alpha(0f).setDuration(200).withEndAction { visibility = View.GONE }.start()
    }

    private fun showControls(autoHide: Boolean = true) {
        if (locked || inPip) return
        topBar.fadeIn()
        bottomBar.fadeIn()
        controlsVisible = true
        handler.removeCallbacks(hideControlsTask)
        if (autoHide) scheduleHide()
    }

    private fun hideControls() {
        handler.removeCallbacks(hideControlsTask)
        topBar.fadeOut()
        bottomBar.fadeOut()
        controlsVisible = false
        hideSystemUi()
    }

    private fun scheduleHide() {
        handler.removeCallbacks(hideControlsTask)
        if (controlsVisible && !userSeeking && player.isPlaying) handler.postDelayed(hideControlsTask, 4000)
    }

    private fun setLocked(lock: Boolean) {
        locked = lock
        if (lock) {
            hideControls()
            showInfo("画面をロックしました")
            showUnlockBriefly()
        } else {
            handler.removeCallbacks(hideUnlockTask)
            unlockButton.visibility = View.GONE
            showControls()
        }
    }

    private fun showUnlockBriefly() {
        unlockButton.visibility = View.VISIBLE
        handler.removeCallbacks(hideUnlockTask)
        handler.postDelayed(hideUnlockTask, 2500)
    }

    private fun hideSystemUi() {
        WindowCompat.getInsetsController(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
    }

    private fun showInfo(text: String, autoHide: Boolean = true) {
        gestureInfo.text = text
        gestureInfo.visibility = View.VISIBLE
        handler.removeCallbacks(hideInfo)
        if (autoHide) handler.postDelayed(hideInfo, 1000)
    }

    // ---------- ピクチャーインピクチャー ----------

    private fun pipParams(): PictureInPictureParams {
        val builder = PictureInPictureParams.Builder()
        if (videoW > 0 && videoH > 0) {
            val ratio = videoW.toFloat() / videoH
            val r = when {
                ratio > 2.39f -> Rational(239, 100)
                ratio < 1 / 2.39f -> Rational(100, 239)
                else -> Rational(videoW, videoH)
            }
            builder.setAspectRatio(r)
        }
        if (Build.VERSION.SDK_INT >= 31) builder.setAutoEnterEnabled(player.isPlaying)
        return builder.build()
    }

    private fun updatePipParams() {
        if (hasPip) runCatching { setPictureInPictureParams(pipParams()) }
    }

    private fun enterPip() {
        if (hasPip) runCatching { enterPictureInPictureMode(pipParams()) }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        // Android 12 以降は setAutoEnterEnabled で自動的に PiP になる
        if (Build.VERSION.SDK_INT < 31 && player.isPlaying) enterPip()
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        inPip = isInPictureInPictureMode
        if (inPip) {
            hideControls()
            unlockButton.visibility = View.GONE
            gestureInfo.visibility = View.GONE
        }
    }

    // ---------- ジェスチャー ----------
    // タップ: コントロールの表示/非表示
    // 左半分の上下スワイプ: 明るさ / 右半分の上下スワイプ: 音量 / 左右スワイプ: シーク
    // ダブルタップ: 左=10秒戻る、右=10秒進む、中央=再生/一時停止

    @SuppressLint("ClickableViewAccessibility")
    private fun setupGestures() {
        val edge = 48 * resources.displayMetrics.density
        val detector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent) = true

            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                if (controlsVisible) hideControls() else showControls()
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                val w = touchLayer.width
                when {
                    e.x < w / 3f -> { seekBy(-10_000); showInfo("⏪  10秒") }
                    e.x > w * 2 / 3f -> { seekBy(10_000); showInfo("10秒  ⏩") }
                    else -> togglePlay()
                }
                return true
            }

            override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
                if (e1 == null) return false
                val dx = e2.x - e1.x
                val dy = e2.y - e1.y
                val w = touchLayer.width.toFloat()
                val h = touchLayer.height.toFloat()
                if (gesture == Gesture.NONE) {
                    gesture = when {
                        // 画面端はシステムのジェスチャーに譲る
                        e1.y < edge || e1.y > h - edge -> Gesture.IGNORE
                        abs(dy) > abs(dx) -> if (e1.x < w / 2f) Gesture.BRIGHTNESS else Gesture.VOLUME
                        else -> Gesture.SEEK
                    }
                    gestureStartValue = when (gesture) {
                        Gesture.BRIGHTNESS -> currentBrightness()
                        Gesture.VOLUME -> audioManager.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat()
                        else -> 0f
                    }
                    seekStartPos = player.time.coerceAtLeast(0)
                    seekTarget = seekStartPos
                }
                when (gesture) {
                    Gesture.BRIGHTNESS -> {
                        val v = (gestureStartValue - dy / h).coerceIn(0.01f, 1f)
                        window.attributes = window.attributes.apply { screenBrightness = v }
                        showInfo("☀  明るさ ${(v * 100).roundToInt()}%", autoHide = false)
                    }
                    Gesture.VOLUME -> {
                        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                        val v = (gestureStartValue - dy / h * max).roundToInt().coerceIn(0, max)
                        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, v, 0)
                        showInfo("🔊  音量 ${v * 100 / max}%", autoHide = false)
                    }
                    Gesture.SEEK -> if (lengthMs > 0) {
                        val delta = (dx / w * 90_000).toLong()
                        seekTarget = (seekStartPos + delta).coerceIn(0, lengthMs)
                        val diff = (seekTarget - seekStartPos) / 1000
                        val sign = if (diff >= 0) "+" else ""
                        showInfo("${formatTime(seekTarget)} / ${formatTime(lengthMs)}\n($sign${diff}秒)", autoHide = false)
                    }
                    else -> {}
                }
                return true
            }
        })

        touchLayer.setOnTouchListener { _, e ->
            if (locked) {
                if (e.actionMasked == MotionEvent.ACTION_UP) showUnlockBriefly()
                return@setOnTouchListener true
            }
            detector.onTouchEvent(e)
            val action = e.actionMasked
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                val was = gesture
                gesture = Gesture.NONE
                if (was == Gesture.SEEK && action == MotionEvent.ACTION_UP) player.setTime(seekTarget)
                if (was != Gesture.NONE && was != Gesture.IGNORE) {
                    handler.removeCallbacks(hideInfo)
                    handler.postDelayed(hideInfo, 600)
                }
            }
            true
        }
    }

    private fun currentBrightness(): Float {
        val b = window.attributes.screenBrightness
        if (b >= 0) return b
        return try {
            Settings.System.getInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS) / 255f
        } catch (_: Exception) {
            0.5f
        }
    }
}
