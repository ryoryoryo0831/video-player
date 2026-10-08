package com.ryose.videoplayer

import android.annotation.SuppressLint
import android.app.PictureInPictureParams
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.util.VLCVideoLayout
import kotlin.math.abs
import kotlin.math.roundToInt

/** 動画の再生画面。再生そのものは PlaybackService が担当し、この画面は表示と操作を受け持つ */
class PlayerActivity : AppCompatActivity() {

    private enum class Gesture { NONE, IGNORE, BRIGHTNESS, VOLUME, SEEK }

    /** ホームボタンなどで動画の画面を離れたときの動作 */
    private enum class LeaveAction(val label: String) {
        PIP("小窓で再生（ピクチャーインピクチャー）"),
        AUDIO("音声だけ再生"),
        PAUSE("一時停止"),
    }

    private lateinit var videoLayout: VLCVideoLayout
    private lateinit var touchLayer: View
    private lateinit var topBar: View
    private lateinit var bottomBar: View
    private lateinit var titleView: TextView
    private lateinit var seekBar: SeekBar
    private lateinit var timeCurrent: TextView
    private lateinit var timeDuration: TextView
    private lateinit var playButton: ImageButton
    private lateinit var nextButton: ImageButton
    private lateinit var speedButton: TextView
    private lateinit var repeatButton: ImageButton
    private lateinit var unlockButton: ImageButton
    private lateinit var gestureInfo: TextView
    private lateinit var audioManager: AudioManager

    private val handler = Handler(Looper.getMainLooper())
    private val hasPip by lazy { packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE) }

    private var svc: PlaybackService? = null
    /** 一覧などから開かれたときに、サービスにつながったら再生を始めるリスト */
    private var pendingLoad: Triple<List<PlaylistItem>, Int, Boolean>? = null

    private var lengthMs = 0L
    private var videoW = 0
    private var videoH = 0
    private var scaleIndex = 0

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

    private val hideInfo = Runnable { gestureInfo.visibility = View.GONE }
    private val hideControlsTask = Runnable { hideControls() }
    private val hideUnlockTask = Runnable { unlockButton.visibility = View.GONE }

    private val pickSubtitle =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let { addSubtitleFromUri(it) } }

    private val listener = object : PlaybackService.Listener {
        override fun onPlayerEvent(e: MediaPlayer.Event) = handleEvent(e)
        override fun onItemChanged() = updateItem()
        override fun onModesChanged() = updateModes()
        override fun onPlaybackStopped() = finish()
    }

    private val dialogHandler by lazy { VlcDialogHandler(this) { svc?.currentItem?.uri } }

    private val connection = PlaybackConnection(this, autoCreate = true, onConnected = ::onServiceReady, onDisconnected = { svc = null })

    /** 画面を離れたときの動作（その他メニューで切り替え。初期設定は小窓で再生） */
    private var leaveAction: LeaveAction
        get() = getSharedPreferences("player", MODE_PRIVATE).getString("leave_action", null)
            ?.let { runCatching { LeaveAction.valueOf(it) }.getOrNull() }
            ?: if (hasPip) LeaveAction.PIP else LeaveAction.PAUSE
        set(v) = getSharedPreferences("player", MODE_PRIVATE).edit().putString("leave_action", v.name).apply()

    // ---------- ライフサイクル ----------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_player)
        volumeControlStream = AudioManager.STREAM_MUSIC

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
        nextButton = findViewById(R.id.nextButton)
        speedButton = findViewById(R.id.speedButton)
        repeatButton = findViewById(R.id.repeatButton)
        unlockButton = findViewById(R.id.unlockButton)
        gestureInfo = findViewById(R.id.gestureInfo)
        audioManager = getSystemService(AudioManager::class.java)

        onBackPressedDispatcher.addCallback(this) {
            if (locked) showUnlockBriefly() else finish()
        }

        setupControls()
        setupGestures()
        setupInsets()
        if (savedInstanceState == null) takeLoadFrom(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        takeLoadFrom(intent)
        // 別の動画が選ばれた場合、表示中ならすぐ切り替える（裏にいた場合は onStart でつながってから）
        val s = svc
        if (s != null && pendingLoad != null) startPendingLoad(s)
    }

    /** 一覧などから開かれた場合は、再生するリストを受け取っておく（通知から開かれた場合は何もしない） */
    private fun takeLoadFrom(intent: Intent) {
        if (intent.getBooleanExtra(PlaybackService.EXTRA_FROM_SESSION, false)) return
        pendingLoad = playlistFromIntent(intent)
        orientationLocked = false
    }

    override fun onStart() {
        super.onStart()
        connection.bind()
    }

    private fun onServiceReady(s: PlaybackService) {
        svc = s
        s.addListener(listener)
        // ネットワーク再生でログインや証明書の確認を求められたときにダイアログを出す
        org.videolan.libvlc.Dialog.setCallbacks(s.libVLC, dialogHandler)
        s.player.attachViews(videoLayout, null, true, false)
        s.videoUiAttached = true
        when {
            pendingLoad != null -> startPendingLoad(s)
            s.currentItem == null || s.currentItem?.isAudio == true -> {
                finish()
                return
            }
            // 裏に回っていた動画に戻ってきたとき
            s.parkedAt != null -> s.resumeParked(paused = true)
            s.videoTrackDisabled -> s.setVideoEnabled(true)
        }
        refreshAll()
    }

    private fun startPendingLoad(s: PlaybackService) {
        val (items, index, shuffle) = pendingLoad ?: return
        pendingLoad = null
        s.load(items, index, shuffle)
        val start = ResumeStore(this).get(items[index].key)
        if (start > 0) showInfo("続きから再生  ${formatTime(start)}")
    }

    override fun onResume() {
        super.onResume()
        svc?.let { org.videolan.libvlc.Dialog.setCallbacks(it.libVLC, dialogHandler) }
    }

    override fun onPause() {
        super.onPause()
        // 別の画面が前に出たら、そちらがダイアログを担当する
        svc?.let { org.videolan.libvlc.Dialog.setCallbacks(it.libVLC, null) }
    }

    override fun onStop() {
        super.onStop()
        val s = svc
        if (s != null) {
            when {
                // 戻るボタンで閉じた・ピクチャーインピクチャーの小窓を閉じた
                isFinishing || inPip -> s.stopPlayback()
                // 設定が「音声だけ再生」なら映像を止めて音声だけ続ける
                leaveAction == LeaveAction.AUDIO && s.isPlaying -> s.setVideoEnabled(false)
                // 普段は止めて位置を覚えておき、戻ってきたら続きから
                else -> s.park()
            }
            s.videoUiAttached = false
            s.player.detachViews()
            s.removeListener(listener)
        }
        connection.unbind()
        svc = null
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemUi()
    }

    // ---------- 表示の更新 ----------

    private fun handleEvent(e: MediaPlayer.Event) {
        when (e.type) {
            MediaPlayer.Event.Playing -> {
                playButton.setImageResource(R.drawable.ic_pause)
                videoLayout.keepScreenOn = true
                scheduleHide()
                updatePipParams()
            }
            MediaPlayer.Event.Paused -> {
                playButton.setImageResource(R.drawable.ic_play)
                videoLayout.keepScreenOn = false
                showControls(autoHide = false)
                updatePipParams()
            }
            MediaPlayer.Event.Stopped -> {
                playButton.setImageResource(R.drawable.ic_play)
                videoLayout.keepScreenOn = false
            }
            MediaPlayer.Event.LengthChanged -> setLength(e.lengthChanged)
            MediaPlayer.Event.TimeChanged -> {
                if (lengthMs <= 0) svc?.let { setLength(it.lengthMs) }
                if (!userSeeking) {
                    seekBar.progress = e.timeChanged.toInt()
                    timeCurrent.text = formatTime(e.timeChanged)
                }
            }
            MediaPlayer.Event.Vout -> if (e.voutCount > 0) onVideoReady()
        }
    }

    private fun refreshAll() {
        val s = svc ?: return
        updateItem()
        val t = (s.parkedAt ?: s.player.time).coerceAtLeast(0)
        seekBar.progress = t.toInt()
        timeCurrent.text = formatTime(t)
        playButton.setImageResource(if (s.isPlaying) R.drawable.ic_pause else R.drawable.ic_play)
        videoLayout.keepScreenOn = s.isPlaying
        if (s.player.videoTracksCount > 0) onVideoReady()
    }

    private fun updateItem() {
        val s = svc ?: return
        titleView.text = s.displayTitle()
        lengthMs = 0
        seekBar.progress = 0
        timeDuration.text = formatTime(0)
        setLength(s.lengthMs)
        updateModes()
    }

    private fun updateModes() {
        val s = svc ?: return
        nextButton.alpha = if (s.hasNext()) 1f else 0.4f
        speedButton.text = "${s.rate}x"
        repeatButton.setImageResource(if (s.repeat == PlaybackService.Repeat.ONE) R.drawable.ic_repeat_one else R.drawable.ic_repeat)
        repeatButton.alpha = if (s.repeat == PlaybackService.Repeat.OFF) 0.5f else 1f
        repeatButton.setColorFilter(
            if (s.repeat == PlaybackService.Repeat.OFF) Color.WHITE else ContextCompat.getColor(this, R.color.accent)
        )
    }

    private fun setLength(ms: Long) {
        if (ms <= 0 || ms == lengthMs) return
        lengthMs = ms
        seekBar.max = ms.toInt()
        timeDuration.text = formatTime(ms)
    }

    private fun onVideoReady() {
        val s = svc ?: return
        val vt = runCatching { s.player.currentVideoTrack }.getOrNull() ?: return
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
        s.player.setVideoScale(scales[scaleIndex].first)
        updatePipParams()
    }

    // ---------- 字幕・音声トラック ----------

    private fun addSubtitleFromUri(uri: Uri) {
        lifecycleScope.launch {
            val file = withContext(Dispatchers.IO) {
                runCatching {
                    val name = queryDisplayName(uri) ?: "subtitle.srt"
                    val bytes = contentResolver.openInputStream(uri)!!.use { it.readBytes() }
                    Subtitles.saveToCache(this@PlayerActivity, name, bytes)
                }.getOrNull()
            }
            val s = svc
            if (file == null || s == null) {
                Toast.makeText(this@PlayerActivity, "字幕ファイルを読み込めませんでした", Toast.LENGTH_LONG).show()
                return@launch
            }
            s.addSubtitle(file)
            showInfo("字幕を読み込みました")
        }
    }

    private fun trackLabel(t: MediaPlayer.TrackDescription, current: Int): String {
        val name = if (t.id == -1) "オフ" else t.name ?: "トラック ${t.id}"
        return (if (t.id == current) "✓  " else "      ") + name
    }

    private fun showSubtitleMenu() {
        val player = svc?.player ?: return
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
        val player = svc?.player ?: return
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
        val player = svc?.player ?: return
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

    // ---------- 画面サイズ・向き・その他メニュー ----------

    private fun cycleScale() {
        val s = svc ?: return
        scaleIndex = (scaleIndex + 1) % scales.size
        s.player.setVideoScale(scales[scaleIndex].first)
        showInfo(scales[scaleIndex].second)
    }

    private fun toggleOrientation() {
        orientationLocked = true
        val isLandscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        requestedOrientation =
            if (isLandscape) ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
            else ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
    }

    private fun cycleRepeat() {
        val s = svc ?: return
        s.cycleRepeat()
        showInfo(
            when (s.repeat) {
                PlaybackService.Repeat.OFF -> "リピート：オフ"
                PlaybackService.Repeat.ALL -> "リピート：すべて"
                PlaybackService.Repeat.ONE -> "リピート：1本"
            }
        )
    }

    /** 右上の「︙」メニュー */
    private fun showMoreMenu() {
        val s = svc ?: return
        val item = s.currentItem ?: return
        val actions = listOf<Pair<String, () -> Unit>>(
            (if (s.shuffle) "シャッフルをオフにする" else "シャッフルをオンにする") to {
                s.toggleShuffle()
                showInfo(if (s.shuffle) "シャッフル：オン" else "シャッフル：オフ")
            },
            "プレイリストに追加" to { PlaylistDialogs.addToPlaylist(this, listOf(item)) },
            "再生キュー" to { PlayerDialogs.showQueue(this, s) },
            "イコライザー" to { PlayerDialogs.showEqualizer(this, s) },
            (PlayerDialogs.sleepLabel(s)?.let { "スリープタイマー（$it）" } ?: "スリープタイマー") to {
                PlayerDialogs.showSleepTimer(this, s)
            },
            "画面を離れたとき：${leaveAction.label}" to { showLeaveActionMenu() },
        )
        MaterialAlertDialogBuilder(this)
            .setItems(actions.map { it.first }.toTypedArray()) { _, which -> actions[which].second() }
            .show()
    }

    private fun showLeaveActionMenu() {
        val options = LeaveAction.entries.filter { it != LeaveAction.PIP || hasPip }
        val current = leaveAction
        MaterialAlertDialogBuilder(this)
            .setTitle("ホームボタンなどで画面を離れたとき")
            .setItems(options.map { (if (it == current) "✓  " else "      ") + it.label }.toTypedArray()) { _, which ->
                leaveAction = options[which]
                updatePipParams()
                showInfo("画面を離れたとき：${options[which].label}")
            }
            .show()
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
        playButton.onTap { svc?.togglePlay() }
        findViewById<View>(R.id.prevButton).onTap { svc?.previous() }
        nextButton.onTap { svc?.next() }
        repeatButton.onTap { cycleRepeat() }
        findViewById<View>(R.id.moreButton).onTap { showMoreMenu() }
        findViewById<View>(R.id.lockButton).setOnClickListener { setLocked(true) }
        unlockButton.setOnClickListener { setLocked(false) }
        findViewById<View>(R.id.subtitleButton).onTap { showSubtitleMenu() }
        findViewById<View>(R.id.audioButton).onTap { showAudioMenu() }
        speedButton.onTap { svc?.let { PlayerDialogs.showSpeed(this, it) { updateModes() } } }
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
                svc?.seekTo(sb.progress.toLong())
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
        if (controlsVisible && !userSeeking && svc?.isPlaying == true) handler.postDelayed(hideControlsTask, 4000)
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
        if (Build.VERSION.SDK_INT >= 31) {
            builder.setAutoEnterEnabled(svc?.isPlaying == true && leaveAction == LeaveAction.PIP)
        }
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
        if (Build.VERSION.SDK_INT < 31 && svc?.isPlaying == true && leaveAction == LeaveAction.PIP) enterPip()
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        inPip = isInPictureInPictureMode
        if (inPip) {
            hideControls()
            unlockButton.visibility = View.GONE
            gestureInfo.visibility = View.GONE
        } else if (lifecycle.currentState == Lifecycle.State.CREATED) {
            // 小窓が閉じられた
            finish()
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
                val s = svc ?: return true
                val w = touchLayer.width
                when {
                    e.x < w / 3f -> { s.seekBy(-10_000); showInfo("⏪  10秒") }
                    e.x > w * 2 / 3f -> { s.seekBy(10_000); showInfo("10秒  ⏩") }
                    else -> s.togglePlay()
                }
                return true
            }

            override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
                val s = svc ?: return false
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
                    seekStartPos = s.player.time.coerceAtLeast(0)
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
                if (was == Gesture.SEEK && action == MotionEvent.ACTION_UP) svc?.seekTo(seekTarget)
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
