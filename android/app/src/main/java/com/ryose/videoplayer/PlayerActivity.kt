package com.ryose.videoplayer

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.drawable.Icon
import android.view.ScaleGestureDetector
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
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.ImageView
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
import kotlinx.coroutines.flow.MutableStateFlow
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
        POPUP("自由な小窓で再生（大きさ・場所を自由に変えられる）"),
        PIP("小窓で再生（ピクチャーインピクチャー）"),
        AUDIO("音声だけ再生"),
        PAUSE("一時停止"),
    }

    private lateinit var videoLayout: VLCVideoLayout
    private lateinit var touchLayer: View
    private lateinit var topBar: View
    private lateinit var bottomBar: View
    private lateinit var centerControls: View
    private lateinit var titleView: TextView
    private lateinit var seekBar: SeekBar
    private lateinit var timeCurrent: TextView
    private lateinit var timeDuration: TextView
    private lateinit var playButton: ImageButton
    private lateinit var nextButton: ImageButton
    private lateinit var speedButton: TextView
    private lateinit var repeatButton: ImageButton
    private lateinit var unlockButton: ImageButton
    private lateinit var abIndicator: TextView
    private lateinit var castButton: ImageButton
    private lateinit var castInfo: TextView
    private lateinit var gestureInfo: TextView
    private lateinit var restartButton: TextView
    private lateinit var seekPreview: View
    private lateinit var seekPreviewImage: ImageView
    private lateinit var seekPreviewTime: TextView
    private lateinit var audioManager: AudioManager

    private val handler = Handler(Looper.getMainLooper())
    private val hasPip by lazy { packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE) }

    private var svc: PlaybackService? = null
    /** 一覧などから開かれたときに、サービスにつながったら再生を始めるリスト */
    private var pendingLoad: Triple<List<PlaylistItem>, Int, Boolean>? = null
    /** アプリが裏で終了させられたあとに画面が復元された場合、開き直すための Intent */
    private var restoreIntent: Intent? = null

    private var lengthMs = 0L
    private var videoW = 0
    private var videoH = 0
    private var scaleIndex = 0

    // 画面の状態
    private var controlsVisible = true
    private var locked = false
    private var userSeeking = false
    private var inPip = false
    /** 回転ボタンで固定した向き（null なら設定どおり） */
    private var manualOrientation: Int? = null

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
    private val hideRestartTask = Runnable { restartButton.visibility = View.GONE }
    private val isTv by lazy { packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK) }

    /** ピンチで拡大した倍率（1 で元どおり） */
    private var zoom = 1f
    private var pinching = false

    /** シークバーを動かしている間、映像も追いかけて動かす（間引いて移動） */
    private var lastPreviewSeek = 0L

    /** シークバーを動かしている間に出す、その位置の画像 */
    private lateinit var thumbs: SeekThumbnails
    /** 画像を出したい位置（-1 なら出さない）。画像づくりが追いつかないときは最新の位置だけ作る */
    private val previewTarget = MutableStateFlow(-1L)
    /** 画像で見せている（このときは映像は動かさず、指を離したときに移動する） */
    private var previewImages = false

    /** 長押しで 2 倍速にしている間の、元の速さ */
    private var longPressRate: Float? = null

    private val pickSubtitle =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let { addSubtitleFromUri(it) } }

    private val listener = object : PlaybackService.Listener {
        override fun onPlayerEvent(e: MediaPlayer.Event) = handleEvent(e)
        override fun onItemChanged() = updateItem()
        override fun onModesChanged() = updateModes()
        override fun onPlaybackStopped() = finish()
        override fun onRenderersChanged() = updateModes()
    }

    private val dialogHandler by lazy { VlcDialogHandler(this) { svc?.currentItem?.uri } }

    private val connection = PlaybackConnection(this, autoCreate = true, onConnected = ::onServiceReady, onDisconnected = { svc = null })

    /** 画面を離れたときの動作（設定画面・その他メニューで切り替え。初期設定は自由な小窓で再生） */
    private var leaveAction: LeaveAction
        get() = runCatching { LeaveAction.valueOf(AppSettings.leaveAction(this)) }.getOrNull()
            ?.takeIf { it != LeaveAction.PIP || hasPip } ?: LeaveAction.POPUP
        set(v) = AppSettings.setLeaveAction(this, v.name)

    private val canPopup get() = Settings.canDrawOverlays(this)

    /**
     * OS の小窓（ピクチャーインピクチャー）を使うか。
     * 自由な小窓の設定でも、まだ許可が無いときは OS の小窓で代わりに再生する
     */
    private fun usesSystemPip() = hasPip &&
        (leaveAction == LeaveAction.PIP || (leaveAction == LeaveAction.POPUP && !canPopup))

    /** 自由な小窓に再生を引き渡して、この画面を閉じるところ */
    private var handedToPopup = false

    // 設定（画面を開くたびに読み直す）
    private var gestureBrightness = true
    private var gestureVolume = true
    private var gestureSeek = true
    private var gestureDoubleTap = true
    private var gestureLongPress = true
    private var doubleTapMs = 10_000L

    private fun readSettings() {
        gestureBrightness = AppSettings.gestureBrightness(this)
        gestureVolume = AppSettings.gestureVolume(this)
        gestureSeek = AppSettings.gestureSeek(this)
        gestureDoubleTap = AppSettings.gestureDoubleTap(this)
        gestureLongPress = AppSettings.gestureLongPress(this)
        doubleTapMs = AppSettings.doubleTapMs(this)
    }

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
        @Suppress("DEPRECATION")
        run {
            // 操作パネルと一緒に出すシステムのバーは、映像の上に透明で重ねる
            window.statusBarColor = Color.TRANSPARENT
            window.navigationBarColor = Color.parseColor("#66000000")
        }
        updateSystemBars()

        videoLayout = findViewById(R.id.videoLayout)
        touchLayer = findViewById(R.id.touchLayer)
        topBar = findViewById(R.id.topBar)
        bottomBar = findViewById(R.id.bottomBar)
        centerControls = findViewById(R.id.centerControls)
        titleView = findViewById(R.id.titleView)
        seekBar = findViewById(R.id.seekBar)
        timeCurrent = findViewById(R.id.timeCurrent)
        timeDuration = findViewById(R.id.timeDuration)
        playButton = findViewById(R.id.playButton)
        nextButton = findViewById(R.id.nextButton)
        speedButton = findViewById(R.id.speedButton)
        repeatButton = findViewById(R.id.repeatButton)
        unlockButton = findViewById(R.id.unlockButton)
        abIndicator = findViewById(R.id.abIndicator)
        castButton = findViewById(R.id.castButton)
        castInfo = findViewById(R.id.castInfo)
        abIndicator.setOnClickListener {
            svc?.clearAb()
            showInfo("A-Bリピートを解除しました")
        }
        gestureInfo = findViewById(R.id.gestureInfo)
        restartButton = findViewById(R.id.restartButton)
        seekPreview = findViewById(R.id.seekPreview)
        seekPreviewImage = findViewById(R.id.seekPreviewImage)
        seekPreviewTime = findViewById(R.id.seekPreviewTime)
        thumbs = SeekThumbnails(applicationContext)
        collectSeekPreviews()
        restartButton.setOnClickListener {
            svc?.seekTo(0)
            restartButton.visibility = View.GONE
            showInfo("最初から再生")
        }
        audioManager = getSystemService(AudioManager::class.java)

        onBackPressedDispatcher.addCallback(this) {
            when {
                locked -> showUnlockBriefly()
                // テレビのリモコンでは、まず操作パネルを隠す
                isTv && controlsVisible -> hideControls()
                else -> finish()
            }
        }

        setupControls()
        setupGestures()
        setupInsets()
        if (savedInstanceState == null) takeLoadFrom(intent) else restoreIntent = intent
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
        manualOrientation = null
    }

    override fun onStart() {
        super.onStart()
        // 小窓（PiP）のまま画面が消えて戻ってきた場合は、つながったままなので表示し直すだけ
        val s = svc
        if (s != null) onServiceReady(s) else connection.bind()
    }

    private fun onServiceReady(s: PlaybackService) {
        svc = s
        s.addListener(listener)
        // ネットワーク再生でログインや証明書の確認を求められたときにダイアログを出す
        s.setDialogCallbacks(dialogHandler)
        // 再生サービスが一度終了していたら、覚えておいたリストで続きから再生し直す
        restoreIntent?.let { ri ->
            restoreIntent = null
            if (s.currentItem == null && pendingLoad == null && !ri.getBooleanExtra(PlaybackService.EXTRA_FROM_SESSION, false)) {
                pendingLoad = playlistFromIntent(ri)
            }
        }
        // 自由な小窓で再生していたら、この画面に戻す
        s.closePopup()
        if (pendingLoad != null) s.ensureEngineUpToDate()
        // 「︙ → 設定」で字幕の見た目などを変えて戻ってきたら、今の動画にもすぐ反映する
        val engineRebuilt = pendingLoad == null && s.currentItem?.isAudio == false && s.recreateEngineKeepingItem()
        s.player.attachViews(videoLayout, null, true, false)
        s.videoUiAttached = true
        // 裏に回っている間に再生サービスが OS に止められていたら、前回の動画を一時停止のまま用意し直す
        if (pendingLoad == null && s.currentItem == null) s.restoreLastSession(play = false, videoOnly = true)
        when {
            pendingLoad != null -> startPendingLoad(s)
            s.currentItem == null || s.currentItem?.isAudio == true -> {
                finish()
                return
            }
            engineRebuilt -> s.replayAfterEngineChange()
            // 裏に回っていた動画に戻ってきたとき（読み込んだままなので、字幕などの選択はそのまま）
            else -> s.restoreVideo()
        }
        refreshAll()
        askOverlayPermissionOnce()
    }

    private fun startPendingLoad(s: PlaybackService) {
        val (items, index, shuffle) = pendingLoad ?: return
        pendingLoad = null
        // 一覧から渡されたリストなら、終わったら次へ進むかどうかも一覧の指定に従う
        s.load(items, index, shuffle, advance = items !== Playlist.items || Playlist.autoAdvance)
        val start = if (AppSettings.resume(this)) ResumeStore(this).get(items[index].key) else 0L
        if (start > 0) {
            showInfo("続きから再生  ${formatTime(start)}")
            // 最初から見たいときのために、少しの間だけボタンを出す
            restartButton.visibility = View.VISIBLE
            handler.removeCallbacks(hideRestartTask)
            handler.postDelayed(hideRestartTask, 8000)
        }
    }

    override fun onResume() {
        super.onResume()
        readSettings()
        // 設定画面で向きの設定が変わっていたら反映する
        applyOrientation()
        // 許可を出して戻ってきたときなど：OS の小窓に自動で入るかどうかを今の状態に合わせる
        updatePipParams()
        svc?.let { it.setDialogCallbacks(dialogHandler) }
    }

    override fun onPause() {
        super.onPause()
        endLongPressSpeed()
        // 別の画面が前に出たら、そちらがダイアログを担当する
        svc?.let { it.setDialogCallbacks(null) }
    }

    override fun onStop() {
        super.onStop()
        // 自由な小窓に引き渡した：再生はそのまま続ける
        if (handedToPopup) {
            disconnect()
            return
        }
        val s = svc
        if (s != null) {
            when {
                // キャスト中はテレビで再生を続ける（戻るボタンで閉じても止めない）
                s.renderer != null -> {}
                // 戻るボタンで閉じた
                isFinishing -> s.stopPlayback()
                // 小窓（PiP）のまま画面が消えた：音声だけ続ける（小窓を閉じた場合は onPictureInPictureModeChanged で終了する）
                inPip -> if (s.isPlaying) s.setVideoEnabled(false) else s.park()
                // 設定が「音声だけ再生」なら映像を止めて音声だけ続ける
                leaveAction == LeaveAction.AUDIO && s.isPlaying -> s.setVideoEnabled(false)
                // 普段は一時停止して映像だけ止めておき、戻ってきたら続きから
                else -> s.park()
            }
            s.videoUiAttached = false
            s.player.detachViews()
            // 小窓のときは、閉じられたことを受け取れるようにつないだままにしておく
            if (inPip && !isFinishing) return
            s.removeListener(listener)
        }
        disconnect()
    }

    private fun disconnect() {
        svc?.removeListener(listener)
        connection.unbind()
        svc = null
    }

    override fun onDestroy() {
        super.onDestroy()
        if (pipReceiverRegistered) runCatching { unregisterReceiver(pipReceiver) }
        thumbs.release()
        disconnect()
        handler.removeCallbacksAndMessages(null)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) updateSystemBars()
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
        val t = s.player.time.coerceAtLeast(0)
        seekBar.progress = t.toInt()
        timeCurrent.text = formatTime(t)
        playButton.setImageResource(if (s.isPlaying) R.drawable.ic_pause else R.drawable.ic_play)
        videoLayout.keepScreenOn = s.isPlaying
        if (s.player.videoTracksCount > 0) onVideoReady()
    }

    /** 表示中の動画（曲の情報が後から届いたときに、シークバーを 0 に戻さないように区別する） */
    private var shownItemKey: String? = null

    private fun updateItem() {
        val s = svc ?: return
        titleView.text = s.displayTitle()
        val key = s.currentItem?.key
        if (key != shownItemKey) {
            shownItemKey = key
            lengthMs = 0
            seekBar.progress = 0
            timeDuration.text = formatTime(0)
        }
        setLength(s.lengthMs)
        updateModes()
    }

    private fun updateModes() {
        val s = svc ?: return
        nextButton.alpha = if (s.hasNext()) 1f else 0.4f
        speedButton.text = formatRate(s.rate)
        repeatButton.setImageResource(if (s.repeat == PlaybackService.Repeat.ONE) R.drawable.ic_repeat_one else R.drawable.ic_repeat)
        repeatButton.alpha = if (s.repeat == PlaybackService.Repeat.OFF) 0.5f else 1f
        repeatButton.setColorFilter(
            if (s.repeat == PlaybackService.Repeat.OFF) Color.WHITE else ContextCompat.getColor(this, R.color.accent)
        )
        val ab = s.abLabel()
        abIndicator.text = ab
        abIndicator.visibility = if (ab != null) View.VISIBLE else View.GONE
        val cast = s.renderer
        castButton.setImageResource(if (cast != null) R.drawable.ic_cast_connected else R.drawable.ic_cast)
        castInfo.visibility = if (cast != null) View.VISIBLE else View.GONE
        castInfo.text = cast?.let { "${it.displayName ?: it.name} で再生中" }
        updatePipParams()
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
        applyOrientation()
        s.player.setVideoScale(scales[scaleIndex].first)
        updatePipParams()
    }

    // ---------- 字幕・音声トラック ----------

    private fun addSubtitleFromUri(uri: Uri) {
        lifecycleScope.launch {
            val name = withContext(Dispatchers.IO) { queryDisplayName(uri) } ?: "subtitle.srt"
            if (!Subtitles.isSubtitleName(name)) {
                Toast.makeText(this@PlayerActivity, "字幕ファイル（.srt .ass .ssa .vtt .smi .sub .idx）を選んでください", Toast.LENGTH_LONG).show()
                return@launch
            }
            val file = withContext(Dispatchers.IO) {
                runCatching {
                    // 間違えて大きなファイルを選んでもメモリが足りなくならないように、上限を超えたら読まない
                    val bytes = contentResolver.openInputStream(uri)!!.use { Subtitles.readLimited(it) }
                    bytes?.let { Subtitles.saveToCache(this@PlayerActivity, uri.toString(), name, it) }
                }.getOrNull()
            }
            val s = svc
            if (file == null || s == null) {
                Toast.makeText(this@PlayerActivity, "字幕ファイルを読み込めませんでした（大きすぎるか、読めないファイルです）", Toast.LENGTH_LONG).show()
                return@launch
            }
            s.addSubtitle(file)
            showInfo("字幕を読み込みました")
        }
    }

    /** トラックの一覧（選ばれているものにチェック） */
    private fun trackItems(
        tracks: Array<MediaPlayer.TrackDescription>, current: Int, icon: Int, select: (Int) -> Unit,
    ): List<SheetItem> = tracks.map { t ->
        val name = if (t.id == -1) "オフ" else t.name ?: "トラック ${t.id}"
        val on = t.id == current
        SheetItem(if (on) R.drawable.ic_check else icon, name, if (on) "選択中" else null, active = on) { select(t.id) }
    }

    private fun showSubtitleMenu() {
        val player = svc?.player ?: return
        val tracks = player.spuTracks ?: emptyArray()
        ActionSheet.show(
            this, "字幕",
            trackItems(tracks, player.spuTrack, R.drawable.ic_subtitles) { player.setSpuTrack(it) } + listOf(
                SheetItem(R.drawable.ic_add, "字幕ファイルを追加") { pickSubtitle.launch(arrayOf("*/*")) },
                SheetItem(R.drawable.ic_timer, "タイミング調整", "現在 ${formatDelay(player.spuDelay)}") { showDelayDialog(subtitle = true) },
            ) + listOfNotNull(
                // 隣にある字幕ファイルが文字化けしたとき
                if (svc?.currentItem?.path != null) SheetItem(
                    R.drawable.ic_edit, "文字化けを直す（文字コード）",
                    Subtitles.CHARSETS.firstOrNull { it.second == svc?.subtitleCharset() }?.first ?: "自動",
                ) { showCharsetMenu() } else null,
            ),
        )
    }

    private fun showCharsetMenu() {
        val s = svc ?: return
        val current = s.subtitleCharset()
        val options: List<Pair<String, String?>> = listOf<Pair<String, String?>>("自動（おすすめ）" to null) + Subtitles.CHARSETS
        ActionSheet.show(this, "字幕の文字コード", options.map { (label, name) ->
            SheetItem(if (name == current) R.drawable.ic_check else R.drawable.ic_subtitles, label, active = name == current) {
                s.setSubtitleCharset(name)
                showInfo("字幕の文字コード：${label.substringBefore('（')}")
            }
        })
    }

    private fun showAudioMenu() {
        val player = svc?.player ?: return
        val tracks = player.audioTracks ?: emptyArray()
        ActionSheet.show(
            this, "音声トラック",
            trackItems(tracks, player.audioTrack, R.drawable.ic_audiotrack) { player.setAudioTrack(it) } +
                SheetItem(R.drawable.ic_timer, "タイミング調整", "現在 ${formatDelay(player.audioDelay)}") { showDelayDialog(subtitle = false) },
        )
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
            setTextColor(Color.LTGRAY)
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
        setZoom(1f)
        scaleIndex = (scaleIndex + 1) % scales.size
        s.player.setVideoScale(scales[scaleIndex].first)
        showInfo(scales[scaleIndex].second)
    }

    /**
     * 画面の向きを決める。
     * 回転ボタンで固定していればその向き、設定が「自動回転」ならスマホの向きに合わせて回り
     * （端末の自動回転がオフでも再生画面は回る）、「動画に合わせる」なら動画の縦横で決める
     */
    private fun applyOrientation() {
        requestedOrientation = manualOrientation ?: when {
            AppSettings.playerOrientation(this) == "video" && videoW > 0 && videoH > 0 ->
                if (videoW >= videoH) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                else ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
            AppSettings.playerOrientation(this) == "video" -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            // 回転ロック中は回さない（寝転んで見るときなど）
            AppSettings.playerOrientation(this) == "user" -> ActivityInfo.SCREEN_ORIENTATION_FULL_USER
            else -> ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR
        }
    }

    /** 回転ボタン：自動 → 横に固定 → 縦に固定 → 自動 … */
    private fun toggleOrientation() {
        manualOrientation = when (manualOrientation) {
            null -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
            else -> null
        }
        applyOrientation()
        showInfo(
            when (manualOrientation) {
                ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE -> "横向きに固定"
                ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT -> "縦向きに固定"
                else -> if (AppSettings.playerOrientation(this) == "video") "向き：動画に合わせる" else "向き：自動回転"
            }
        )
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
        val fav = FavoriteMedia.isFavorite(this, item)
        val items = buildList {
            add(SheetItem(if (fav) R.drawable.ic_star else R.drawable.ic_star_border, if (fav) "お気に入りから外す" else "お気に入りに追加", active = fav) {
                val added = FavoriteMedia.toggle(this@PlayerActivity, item)
                showInfo(if (added) "★  お気に入りに追加しました" else "お気に入りから外しました")
            })
            add(SheetItem(R.drawable.ic_playlist_add, "プレイリストに追加") { PlaylistDialogs.addToPlaylist(this@PlayerActivity, listOf(item)) })
            add(SheetItem(R.drawable.ic_queue, "再生キュー", "${s.orderPos + 1} / ${s.order.size}") { PlayerDialogs.showQueue(this@PlayerActivity, s) })
            add(SheetItem(R.drawable.ic_shuffle, "シャッフル", if (s.shuffle) "オン" else "オフ", active = s.shuffle) {
                s.toggleShuffle()
                showInfo(if (s.shuffle) "シャッフル：オン" else "シャッフル：オフ")
            })
            add(SheetItem(R.drawable.ic_jump, "時間を指定して移動") { showJumpDialog() })
            if (s.chapters().isNotEmpty()) add(SheetItem(R.drawable.ic_chapters, "チャプター", "${s.chapters().size} 個") { showChapters() })
            add(SheetItem(R.drawable.ic_ab, "A-Bリピート", s.abLabel()?.let { "$it（タップで次へ）" } ?: "区間をくり返す", active = s.abLabel() != null) {
                showInfo(s.abStep())
            })
            add(SheetItem(R.drawable.ic_timer, "スリープタイマー", PlayerDialogs.sleepLabel(s) ?: "オフ", active = PlayerDialogs.sleepLabel(s) != null) {
                PlayerDialogs.showSleepTimer(this@PlayerActivity, s)
            })
            add(SheetItem(R.drawable.ic_equalizer, "イコライザー") { PlayerDialogs.showEqualizer(this@PlayerActivity, s) })
            if (s.player.videoTracksCount > 1) add(SheetItem(R.drawable.ic_movie, "映像トラック") { showVideoTrackMenu() })
            add(SheetItem(R.drawable.ic_memory, "デコード", if (s.hwDecodingFor(item)) "ハードウェア（タップでソフトウェアに）" else "ソフトウェア（タップでハードウェアに）") {
                val hw = s.toggleHwDecoding()
                showInfo(if (hw) "ハードウェアデコードに切り替えました" else "ソフトウェアデコードに切り替えました")
            })
            add(SheetItem(R.drawable.ic_camera, "スクリーンショット") { takeScreenshot() })
            add(SheetItem(R.drawable.ic_pip, "自由な小窓で再生", "大きさ・場所を自由に変えられる小窓") { startPopup() })
            if (hasPip) add(SheetItem(R.drawable.ic_pip, "小窓で再生（ピクチャーインピクチャー）") { enterPip() })
            add(SheetItem(R.drawable.ic_pip, "画面を離れたとき", leaveAction.label) { showLeaveActionMenu() })
            add(SheetItem(R.drawable.ic_settings, "設定") { startActivity(Intent(this@PlayerActivity, SettingsActivity::class.java)) })
        }
        ActionSheet.show(this, s.displayTitle(), items)
    }

    private fun showLeaveActionMenu() {
        val options = LeaveAction.entries.filter { it != LeaveAction.PIP || hasPip }
        val current = leaveAction
        ActionSheet.show(this, "ホームボタンなどで画面を離れたとき", options.map { o ->
            val icon = when (o) {
                LeaveAction.POPUP, LeaveAction.PIP -> R.drawable.ic_pip
                LeaveAction.AUDIO -> R.drawable.ic_music_note
                LeaveAction.PAUSE -> R.drawable.ic_pause
            }
            SheetItem(if (o == current) R.drawable.ic_check else icon, o.label, active = o == current) {
                leaveAction = o
                updatePipParams()
                showInfo("画面を離れたとき：${o.label}")
                if (o == LeaveAction.POPUP && !canPopup) askOverlayPermission()
            }
        })
    }

    /** 「1:23:45」「83:10」「90」（秒）などで入力した時間へ移動する */
    private fun showJumpDialog() {
        val s = svc ?: return
        val dp = resources.displayMetrics.density
        val input = android.widget.EditText(this).apply {
            hint = "例：1:23:45 / 12:30"
            inputType = android.text.InputType.TYPE_CLASS_DATETIME or android.text.InputType.TYPE_DATETIME_VARIATION_TIME
            setText(formatTime(s.player.time))
            selectAll()
        }
        val box = android.widget.FrameLayout(this).apply {
            setPadding((20 * dp).toInt(), (8 * dp).toInt(), (20 * dp).toInt(), 0)
            addView(input)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("時間を指定して移動" + if (lengthMs > 0) "（長さ ${formatTime(lengthMs)}）" else "")
            .setView(box)
            .setPositiveButton("移動") { _, _ ->
                val ms = parseTime(input.text.toString())
                if (ms == null) showInfo("時間の形式が正しくありません")
                else {
                    s.seekTo(ms)
                    showInfo(formatTime(ms))
                }
            }
            .setNegativeButton("キャンセル", null)
            .show()
        input.requestFocus()
    }

    /** "1:23:45" → ミリ秒。数字だけなら秒 */
    private fun parseTime(text: String): Long? {
        val parts = text.trim().replace('：', ':').split(':')
        if (parts.isEmpty() || parts.size > 3) return null
        val nums = parts.map { it.trim().toLongOrNull() ?: return null }
        if (nums.any { it < 0 }) return null
        val sec = nums.fold(0L) { acc, n -> acc * 60 + n }
        return sec * 1000
    }

    private fun showVideoTrackMenu() {
        val player = svc?.player ?: return
        val tracks = player.videoTracks ?: return
        ActionSheet.show(this, "映像トラック", trackItems(tracks, player.videoTrack, R.drawable.ic_movie) { player.setVideoTrack(it) })
    }

    /** チャプターの一覧（タップでそこへ移動） */
    private fun showChapters() {
        val s = svc ?: return
        val chapters = s.chapters()
        if (chapters.isEmpty()) return
        val current = s.player.chapter
        ActionSheet.show(this, "チャプター（${chapters.size}）", chapters.mapIndexed { i, c ->
            SheetItem(
                if (i == current) R.drawable.ic_play else R.drawable.ic_chapters,
                c.name?.takeIf { it.isNotBlank() } ?: "チャプター ${i + 1}",
                formatTime(c.timeOffset),
                active = i == current,
            ) { s.player.setChapter(i) }
        })
    }

    /** いま表示している映像をそのまま画像として保存する */
    private fun takeScreenshot() {
        val surface = findSurface(videoLayout)
        if (surface == null || surface.width == 0 || surface.height == 0) {
            showInfo("スクリーンショットを撮れませんでした")
            return
        }
        val bitmap = android.graphics.Bitmap.createBitmap(surface.width, surface.height, android.graphics.Bitmap.Config.ARGB_8888)
        android.view.PixelCopy.request(surface, bitmap, { result ->
            if (result != android.view.PixelCopy.SUCCESS) {
                showInfo("スクリーンショットを撮れませんでした")
                return@request
            }
            val name = (svc?.currentItem?.title?.substringBeforeLast('.') ?: "screenshot") +
                "_" + formatTime(svc?.player?.time ?: 0).replace(':', '-')
            lifecycleScope.launch {
                val saved = withContext(Dispatchers.IO) { Screenshots.save(this@PlayerActivity, bitmap, name) }
                showInfo(if (saved != null) "📷  保存しました\n$saved" else "保存できませんでした")
            }
        }, handler)
    }

    /** VLC の映像を表示している SurfaceView を探す（字幕用の面より先にある） */
    private fun findSurface(view: View): android.view.SurfaceView? {
        if (view is android.view.SurfaceView) return view
        if (view is android.view.ViewGroup) {
            for (i in 0 until view.childCount) findSurface(view.getChildAt(i))?.let { return it }
        }
        return null
    }

    // ---------- 音量（100% を超えるブースト） ----------

    /** 端末の音量と VLC の音量を合わせた値（0〜200%） */
    private fun currentVolumePercent(): Int {
        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val sys = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) * 100 / max
        val boost = svc?.volume ?: 100
        return if (sys >= 100 && boost > 100) boost else sys
    }

    private fun applyVolumePercent(percent: Int) {
        val s = svc ?: return
        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        if (percent <= 100) {
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, (percent * max + 50) / 100, 0)
            s.setBoostVolume(100)
        } else {
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, max, 0)
            s.setBoostVolume(percent)
        }
    }

    private fun volumeText(percent: Int) = "🔊  音量 $percent%" + if (percent > 100) "（ブースト）" else ""

    /**
     * リモコン（Android TV）やキーボードでの操作と、音量ボタンでのブースト。
     * コントロールが隠れているときは、左右でシーク、決定・上下でコントロールを表示する
     */
    override fun onKeyDown(keyCode: Int, event: android.view.KeyEvent?): Boolean {
        val s0 = svc
        if (s0 != null && !locked) {
            when (keyCode) {
                android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, android.view.KeyEvent.KEYCODE_SPACE -> {
                    s0.togglePlay(); showControls(); return true
                }
                android.view.KeyEvent.KEYCODE_MEDIA_PLAY -> { s0.play(); return true }
                android.view.KeyEvent.KEYCODE_MEDIA_PAUSE -> { s0.pause(); showControls(autoHide = false); return true }
                android.view.KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> { s0.seekBy(doubleTapMs); showInfo("${doubleTapMs / 1000}秒  ⏩"); return true }
                android.view.KeyEvent.KEYCODE_MEDIA_REWIND -> { s0.seekBy(-doubleTapMs); showInfo("⏪  ${doubleTapMs / 1000}秒"); return true }
                android.view.KeyEvent.KEYCODE_MEDIA_NEXT -> { s0.next(); return true }
                android.view.KeyEvent.KEYCODE_MEDIA_PREVIOUS -> { s0.previous(); return true }
                android.view.KeyEvent.KEYCODE_DPAD_LEFT, android.view.KeyEvent.KEYCODE_DPAD_RIGHT -> if (!controlsVisible) {
                    val forward = keyCode == android.view.KeyEvent.KEYCODE_DPAD_RIGHT
                    s0.seekBy(if (forward) 10_000 else -10_000)
                    showInfo(if (forward) "10秒  ⏩" else "⏪  10秒")
                    return true
                }
                android.view.KeyEvent.KEYCODE_DPAD_CENTER, android.view.KeyEvent.KEYCODE_ENTER,
                android.view.KeyEvent.KEYCODE_DPAD_UP, android.view.KeyEvent.KEYCODE_DPAD_DOWN -> if (!controlsVisible) {
                    showControls()
                    playButton.requestFocus()
                    return true
                }
            }
            // コントロールを操作している間は隠さない
            if (controlsVisible) scheduleHide()
        }
        val s = svc
        if (s != null && AppSettings.audioBoost(this)) {
            val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            val atMax = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) >= max
            when {
                keyCode == android.view.KeyEvent.KEYCODE_VOLUME_UP && atMax -> {
                    val p = (maxOf(s.volume, 100) + 10).coerceAtMost(200)
                    s.setBoostVolume(p)
                    showInfo(volumeText(p))
                    return true
                }
                keyCode == android.view.KeyEvent.KEYCODE_VOLUME_DOWN && s.volume > 100 -> {
                    val p = (s.volume - 10).coerceAtLeast(100)
                    s.setBoostVolume(p)
                    showInfo(volumeText(p))
                    return true
                }
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    // ---------- コントロールの表示 ----------

    private fun setupControls() {
        fun View.onTap(action: () -> Unit) = setOnClickListener {
            action()
            scheduleHide()
        }
        findViewById<View>(R.id.backButton).setOnClickListener { finish() }
        // 小窓のボタン：設定に合わせて、自由な小窓か OS の小窓にする
        findViewById<View>(R.id.pipButton).setOnClickListener {
            if (leaveAction == LeaveAction.PIP && hasPip) enterPip() else startPopup()
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
        castButton.onTap { svc?.let { PlayerDialogs.showCast(this, it) } }
        findViewById<View>(R.id.rotateButton).onTap { toggleOrientation() }

        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                timeCurrent.text = formatTime(progress.toLong())
                // 指で動かしている最中でなければ（リモコンの左右キーなど）すぐに移動する
                if (!userSeeking) {
                    svc?.seekTo(progress.toLong())
                    scheduleHide()
                } else {
                    updateSeekPreview(progress)
                    if (previewImages) {
                        previewTarget.value = progress.toLong()
                    } else {
                        // 画像を作れない動画（ネットワークなど）は、映像が追いかけて見えるように時々移動する
                        val now = android.os.SystemClock.uptimeMillis()
                        if (now - lastPreviewSeek > 300) {
                            lastPreviewSeek = now
                            svc?.seekTo(progress.toLong())
                        }
                    }
                }
            }

            override fun onStartTrackingTouch(sb: SeekBar) {
                userSeeking = true
                handler.removeCallbacks(hideControlsTask)
                startSeekPreview(sb.progress)
            }

            override fun onStopTrackingTouch(sb: SeekBar) {
                seekPreview.visibility = View.GONE
                previewTarget.value = -1
                svc?.seekTo(sb.progress.toLong())
                userSeeking = false
                scheduleHide()
            }
        })
    }

    // ---------- シーク中のプレビュー ----------

    private fun startSeekPreview(progress: Int) {
        previewImages = thumbs.supports(svc?.currentItem)
        seekPreviewImage.setImageDrawable(null)
        seekPreviewImage.visibility = if (previewImages) View.VISIBLE else View.GONE
        seekPreview.visibility = View.VISIBLE
        updateSeekPreview(progress)
        if (previewImages) previewTarget.value = progress.toLong()
    }

    /** 時間を書きかえて、シークバーのつまみの真上に置く */
    private fun updateSeekPreview(progress: Int) {
        seekPreviewTime.text = formatTime(progress.toLong())
        val parent = seekPreview.parent as View
        seekPreview.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
        val w = seekPreview.measuredWidth
        val h = seekPreview.measuredHeight
        val bar = IntArray(2).also { seekBar.getLocationInWindow(it) }
        val origin = IntArray(2).also { parent.getLocationInWindow(it) }
        val track = seekBar.width - seekBar.paddingLeft - seekBar.paddingRight
        val fraction = if (seekBar.max > 0) progress.toFloat() / seekBar.max else 0f
        val thumbX = bar[0] - origin[0] + seekBar.paddingLeft + track * fraction
        val dp = resources.displayMetrics.density
        val margin = 8 * dp
        seekPreview.translationX = (thumbX - w / 2f).coerceIn(margin, maxOf(margin, parent.width - w - margin))
        seekPreview.translationY = bar[1] - origin[1] + seekBar.paddingTop - h - 12 * dp
    }

    /** 画像づくりは裏で行い、間に合わなかった途中の位置は飛ばす */
    private fun collectSeekPreviews() {
        val dp = resources.displayMetrics.density
        val maxW = (176 * dp).toInt()
        val maxH = (99 * dp).toInt()
        lifecycleScope.launch {
            previewTarget.collect { ms ->
                val item = svc?.currentItem
                if (ms < 0 || item == null || !previewImages) return@collect
                val frame = thumbs.frameAt(item, ms, maxW, maxH)
                if (!userSeeking || !previewImages) return@collect
                if (frame != null) {
                    seekPreviewImage.setImageBitmap(frame)
                } else if (!thumbs.supports(item)) {
                    // この動画は画像を作れなかった：代わりに映像そのものを動かして見せる
                    previewImages = false
                    seekPreviewImage.visibility = View.GONE
                    updateSeekPreview(seekBar.progress)
                    svc?.seekTo(seekBar.progress.toLong())
                }
            }
        }
    }

    // ---------- 長押しで 2 倍速 ----------

    private fun startLongPressSpeed() {
        val s = svc ?: return
        longPressRate = s.rate
        s.setPlaybackRate(2f)
        touchLayer.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        showInfo("2倍速  ⏩", autoHide = false)
    }

    /** 指を離したら元の速さに戻す */
    private fun endLongPressSpeed() {
        val r = longPressRate ?: return
        longPressRate = null
        svc?.setPlaybackRate(r)
        handler.removeCallbacks(hideInfo)
        handler.post(hideInfo)
    }

    private fun setupInsets() {
        val topPad = topBar.paddingTop
        val sidePad = topBar.paddingLeft
        // システムのバーやカメラの切り欠きと重ならないように、その分だけ内側に寄せる
        // （バーが隠れているときも同じ位置にしておき、表示を切り替えてもボタンが動かないようにする）
        fun safe(insets: WindowInsetsCompat) = androidx.core.graphics.Insets.max(
            insets.getInsetsIgnoringVisibility(WindowInsetsCompat.Type.systemBars()),
            insets.getInsets(WindowInsetsCompat.Type.displayCutout()),
        )
        ViewCompat.setOnApplyWindowInsetsListener(topBar) { v, insets ->
            val cut = safe(insets)
            v.updatePadding(left = sidePad + cut.left, top = topPad + cut.top, right = sidePad + cut.right)
            insets
        }
        val bottomPad = bottomBar.paddingBottom
        val bottomSide = bottomBar.paddingLeft
        ViewCompat.setOnApplyWindowInsetsListener(bottomBar) { v, insets ->
            val cut = safe(insets)
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
        centerControls.fadeIn()
        controlsVisible = true
        updateSystemBars()
        handler.removeCallbacks(hideControlsTask)
        if (autoHide) scheduleHide()
    }

    private fun toggleControls() {
        if (controlsVisible) hideControls() else showControls()
    }

    private fun hideControls() {
        handler.removeCallbacks(hideControlsTask)
        topBar.fadeOut()
        bottomBar.fadeOut()
        centerControls.fadeOut()
        controlsVisible = false
        updateSystemBars()
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

    /** 操作パネルが出ている間はシステムのバー（時計・ナビゲーション）も出し、隠れたら一緒に隠す */
    private fun updateSystemBars() {
        WindowCompat.getInsetsController(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            if (controlsVisible && !locked && !inPip) show(WindowInsetsCompat.Type.systemBars())
            else hide(WindowInsetsCompat.Type.systemBars())
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
            builder.setAutoEnterEnabled(svc?.isPlaying == true && usesSystemPip() && svc?.renderer == null)
        }
        builder.setActions(pipActions())
        return builder.build()
    }

    /** 小窓に出すボタン（戻る・再生/一時停止・進む。5 個まで出せる端末では前へ・次へも） */
    private fun pipActions(): List<RemoteAction> {
        fun action(icon: Int, title: String, code: Int) = RemoteAction(
            Icon.createWithResource(this, icon), title, title,
            PendingIntent.getBroadcast(
                this, code, Intent(ACTION_PIP).setPackage(packageName).putExtra(EXTRA_PIP, code),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            ),
        )
        val playing = svc?.isPlaying == true
        val basic = listOf(
            action(R.drawable.ic_stat_rewind, "戻る", PIP_REWIND),
            if (playing) action(R.drawable.ic_stat_pause, "一時停止", PIP_PLAY_PAUSE)
            else action(R.drawable.ic_stat_play, "再生", PIP_PLAY_PAUSE),
            action(R.drawable.ic_stat_forward, "進む", PIP_FORWARD),
        )
        if (maxNumPictureInPictureActions < 5) return basic
        return listOf(action(R.drawable.ic_stat_skip_previous, "前へ", PIP_PREVIOUS)) + basic +
            action(R.drawable.ic_stat_skip_next, "次へ", PIP_NEXT)
    }

    private val pipReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val s = svc ?: return
            when (intent.getIntExtra(EXTRA_PIP, -1)) {
                PIP_REWIND -> s.seekBy(-doubleTapMs)
                PIP_PLAY_PAUSE -> s.togglePlay()
                PIP_FORWARD -> s.seekBy(doubleTapMs)
                PIP_PREVIOUS -> s.previous()
                PIP_NEXT -> s.next()
            }
        }
    }
    private var pipReceiverRegistered = false

    private fun updatePipParams() {
        if (hasPip) runCatching { setPictureInPictureParams(pipParams()) }
    }

    private fun enterPip() {
        if (hasPip) runCatching { enterPictureInPictureMode(pipParams()) }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        val s = svc ?: return
        if (!s.isPlaying || s.renderer != null) return
        when {
            leaveAction == LeaveAction.POPUP && canPopup -> startPopup()
            // Android 12 以降は setAutoEnterEnabled で自動的に PiP になる
            Build.VERSION.SDK_INT < 31 && usesSystemPip() -> enterPip()
        }
    }

    // ---------- 自由な小窓 ----------

    /** 再生を自由な小窓に引き渡して、この画面を閉じる */
    private fun startPopup() {
        val s = svc ?: return
        if (s.renderer != null) {
            showInfo("キャスト中は小窓にできません")
            return
        }
        if (!canPopup) {
            askOverlayPermission()
            return
        }
        handedToPopup = true
        s.removeListener(listener)
        // 画面を閉じる前と同じように、いったん映像を止めてから小窓に付け替える
        s.setVideoEnabled(false)
        s.videoUiAttached = false
        s.player.detachViews()
        if (!s.showPopup()) {
            handedToPopup = false
            s.addListener(listener)
            s.player.attachViews(videoLayout, null, true, false)
            s.videoUiAttached = true
            s.restoreVideo()
            Toast.makeText(this, "小窓を表示できませんでした", Toast.LENGTH_SHORT).show()
            return
        }
        finish()
    }

    /**
     * 自由な小窓の設定なのにまだ許可が無いときは、最初の 1 回だけ先にお願いしておく
     * （ホームボタンで離れるときには確認の画面を出せないので）
     */
    private fun askOverlayPermissionOnce() {
        if (isTv || leaveAction != LeaveAction.POPUP || canPopup || AppSettings.popupPermissionAsked(this)) return
        AppSettings.setPopupPermissionAsked(this)
        askOverlayPermission()
    }

    /** 「他のアプリの上に重ねて表示」の許可をお願いする */
    private fun askOverlayPermission() {
        MaterialAlertDialogBuilder(this)
            .setTitle("自由な小窓を使うには")
            .setMessage(
                "ほかのアプリを使っている間も小窓を表示するため、「他のアプリの上に重ねて表示」の許可が必要です。\n\n" +
                    "次の画面で Orbit のスイッチをオンにして、戻ってきてください。"
            )
            .setPositiveButton("設定を開く") { _, _ ->
                runCatching {
                    startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
                }
            }
            .apply { if (hasPip) setNeutralButton("いつもの小窓を使う") { _, _ -> enterPip() } }
            .setNegativeButton("キャンセル", null)
            .show()
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        inPip = isInPictureInPictureMode
        if (inPip && !pipReceiverRegistered) {
            ContextCompat.registerReceiver(this, pipReceiver, IntentFilter(ACTION_PIP), ContextCompat.RECEIVER_NOT_EXPORTED)
            pipReceiverRegistered = true
        } else if (!inPip && pipReceiverRegistered) {
            runCatching { unregisterReceiver(pipReceiver) }
            pipReceiverRegistered = false
        }
        if (inPip) {
            setZoom(1f)
            hideControls()
            unlockButton.visibility = View.GONE
            gestureInfo.visibility = View.GONE
        } else if (lifecycle.currentState == Lifecycle.State.CREATED) {
            // 小窓が閉じられた
            svc?.stopPlayback()
            disconnect()
            finish()
        }
    }

    // ---------- ジェスチャー ----------
    // タップ: コントロールの表示/非表示
    // 左半分の上下スワイプ: 明るさ / 右半分の上下スワイプ: 音量 / 左右スワイプ: シーク
    // ダブルタップ: 左=10秒戻る、右=10秒進む、中央=再生/一時停止
    // 長押し: 押している間だけ 2 倍速

    /** ピンチで拡大（1〜4 倍）。元に戻すときは指を縮めるか、画面サイズのボタン */
    private fun setZoom(z: Float) {
        zoom = z.coerceIn(1f, 4f)
        videoLayout.scaleX = zoom
        videoLayout.scaleY = zoom
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupGestures() {
        val edge = 48 * resources.displayMetrics.density
        val scaleDetector = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                pinching = true
                gesture = Gesture.IGNORE
                return true
            }

            override fun onScale(detector: ScaleGestureDetector): Boolean {
                setZoom(zoom * detector.scaleFactor)
                showInfo(if (zoom <= 1.01f) "ズーム：元のサイズ" else "ズーム  %.1f 倍".format(zoom), autoHide = false)
                return true
            }

            override fun onScaleEnd(detector: ScaleGestureDetector) {
                if (zoom < 1.05f) setZoom(1f)
                handler.removeCallbacks(hideInfo)
                handler.postDelayed(hideInfo, 600)
            }
        })
        val detector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent) = true

            // ダブルタップを使わない設定なら、ダブルタップ待ちをせずにすぐ反応する
            override fun onSingleTapUp(e: MotionEvent): Boolean {
                if (!gestureDoubleTap) toggleControls()
                return false
            }

            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                if (gestureDoubleTap) toggleControls()
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                val s = svc ?: return true
                if (!gestureDoubleTap) return false
                val w = touchLayer.width
                val sec = doubleTapMs / 1000
                when {
                    e.x < w / 3f -> { s.seekBy(-doubleTapMs); showInfo("⏪  ${sec}秒") }
                    e.x > w * 2 / 3f -> { s.seekBy(doubleTapMs); showInfo("${sec}秒  ⏩") }
                    else -> s.togglePlay()
                }
                return true
            }

            override fun onLongPress(e: MotionEvent) {
                val s = svc ?: return
                if (!gestureLongPress || pinching || gesture != Gesture.NONE || !s.isPlaying) return
                // 指を離すまで、ほかのジェスチャーは受け付けない
                gesture = Gesture.IGNORE
                startLongPressSpeed()
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
                        abs(dy) > abs(dx) -> when {
                            e1.x < w / 2f -> if (gestureBrightness) Gesture.BRIGHTNESS else Gesture.IGNORE
                            else -> if (gestureVolume) Gesture.VOLUME else Gesture.IGNORE
                        }
                        else -> if (gestureSeek) Gesture.SEEK else Gesture.IGNORE
                    }
                    gestureStartValue = when (gesture) {
                        Gesture.BRIGHTNESS -> currentBrightness()
                        Gesture.VOLUME -> currentVolumePercent().toFloat()
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
                        // 画面の高さ 1.5 倍分で 0〜100%。ブーストがオンなら 200% まで
                        val limit = if (AppSettings.audioBoost(this@PlayerActivity)) 200 else 100
                        val v = (gestureStartValue - dy / h * 150).roundToInt().coerceIn(0, limit)
                        applyVolumePercent(v)
                        showInfo(volumeText(v), autoHide = false)
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
            scaleDetector.onTouchEvent(e)
            // 2 本指で拡大している間は、ほかのジェスチャーは受け付けない
            if (!pinching && e.pointerCount == 1) detector.onTouchEvent(e)
            val action = e.actionMasked
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) pinching = false
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                endLongPressSpeed()
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
            // 機種によって最大値が 255 とは限らない（1023 や 4095 など）ので、端末の設定値の最大を調べる
            val res = android.content.res.Resources.getSystem()
            val id = res.getIdentifier("config_screenBrightnessSettingMaximum", "integer", "android")
            val max = (if (id != 0) res.getInteger(id) else 255).takeIf { it > 0 } ?: 255
            (Settings.System.getInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS).toFloat() / max).coerceIn(0.01f, 1f)
        } catch (_: Exception) {
            0.5f
        }
    }

    private companion object {
        const val ACTION_PIP = "com.ryose.videoplayer.PIP_CONTROL"
        const val EXTRA_PIP = "control"
        const val PIP_REWIND = 1
        const val PIP_PLAY_PAUSE = 2
        const val PIP_FORWARD = 3
        const val PIP_PREVIOUS = 4
        const val PIP_NEXT = 5
    }
}
