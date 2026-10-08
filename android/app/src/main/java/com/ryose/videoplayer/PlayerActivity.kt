package com.ryose.videoplayer

import android.annotation.SuppressLint
import android.app.PictureInPictureParams
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Rational
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.updatePadding
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import kotlin.math.abs
import kotlin.math.roundToInt

@OptIn(UnstableApi::class)
class PlayerActivity : AppCompatActivity() {

    private enum class Gesture { NONE, IGNORE, BRIGHTNESS, VOLUME, SEEK }

    private lateinit var player: ExoPlayer
    private lateinit var playerView: PlayerView
    private lateinit var topBar: View
    private lateinit var titleView: TextView
    private lateinit var gestureInfo: TextView
    private lateinit var resume: ResumeStore
    private lateinit var audioManager: AudioManager

    private val handler = Handler(Looper.getMainLooper())
    private var orientationLocked = false
    private var lastMediaId: String? = null
    private val hasPip by lazy { packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE) }

    private var gesture = Gesture.NONE
    private var gestureStartValue = 0f
    private var seekStartPos = 0L
    private var seekTarget = 0L

    private val saveTask = object : Runnable {
        override fun run() {
            savePosition()
            handler.postDelayed(this, 2000)
        }
    }
    private val hideInfo = Runnable { gestureInfo.visibility = View.GONE }

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

        playerView = findViewById(R.id.playerView)
        topBar = findViewById(R.id.topBar)
        titleView = findViewById(R.id.titleView)
        gestureInfo = findViewById(R.id.gestureInfo)
        resume = ResumeStore(this)
        audioManager = getSystemService(AudioManager::class.java)

        player = ExoPlayer.Builder(this)
            .setSeekBackIncrementMs(10_000)
            .setSeekForwardIncrementMs(10_000)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .build(),
                /* handleAudioFocus = */ true,
            )
            .setHandleAudioBecomingNoisy(true) // イヤホンが抜けたら一時停止
            .build()
        player.addListener(playerListener)

        playerView.player = player
        playerView.setShowSubtitleButton(true)
        playerView.setShowNextButton(true)
        playerView.setShowPreviousButton(true)
        playerView.setControllerVisibilityListener(PlayerView.ControllerVisibilityListener { visibility ->
            topBar.visibility = visibility
        })

        val basePad = topBar.paddingTop
        ViewCompat.setOnApplyWindowInsetsListener(topBar) { v, insets ->
            val cut = insets.getInsets(WindowInsetsCompat.Type.displayCutout())
            v.updatePadding(left = basePad + cut.left, top = basePad + cut.top, right = basePad + cut.right)
            insets
        }

        findViewById<ImageButton>(R.id.backButton).setOnClickListener { finish() }
        findViewById<ImageButton>(R.id.rotateButton).setOnClickListener { toggleOrientation() }
        findViewById<ImageButton>(R.id.pipButton).apply {
            visibility = if (hasPip) View.VISIBLE else View.GONE
            setOnClickListener { enterPip() }
        }

        setupGestures()
        loadFromIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        savePosition()
        orientationLocked = false
        loadFromIntent(intent)
    }

    private fun loadFromIntent(intent: Intent) {
        val data = intent.data ?: run { finish(); return }
        val list = Playlist.items
        val idx = list.indexOfFirst { it.uri == data }
        val items: List<PlaylistItem>
        val start: Int
        if (idx >= 0) {
            items = list
            start = idx
        } else {
            // 他のアプリから開かれた場合は単体再生
            items = listOf(PlaylistItem(data, queryDisplayName(data) ?: data.lastPathSegment ?: "動画"))
            start = 0
        }

        val mediaItems = items.map {
            MediaItem.Builder()
                .setUri(it.uri)
                .setMediaId(it.uri.toString())
                .setMediaMetadata(MediaMetadata.Builder().setTitle(it.title).build())
                .build()
        }
        val startId = items[start].uri.toString()
        val startPos = resume.get(startId)
        lastMediaId = startId
        player.setMediaItems(mediaItems, start, startPos)
        player.prepare()
        player.play()
        updateTitle()
        if (startPos > 0) showInfo("続きから再生  ${formatTime(startPos)}")
    }

    private val playerListener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            updateTitle()
            if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) {
                // 前の動画は最後まで見たので続き位置を消す
                lastMediaId?.let { resume.clear(it) }
            }
            if (mediaItem != null &&
                (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO || reason == Player.MEDIA_ITEM_TRANSITION_REASON_SEEK)
            ) {
                val pos = resume.get(mediaItem.mediaId)
                if (pos > 0) player.seekTo(pos)
            }
            lastMediaId = mediaItem?.mediaId
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            playerView.keepScreenOn = isPlaying
            handler.removeCallbacks(saveTask)
            if (isPlaying) handler.post(saveTask) else savePosition()
            updatePipParams()
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_ENDED) {
                player.currentMediaItem?.let { resume.clear(it.mediaId) }
            }
        }

        override fun onVideoSizeChanged(videoSize: VideoSize) {
            if (videoSize.width == 0 || videoSize.height == 0) return
            if (!orientationLocked) {
                val landscape = videoSize.width * videoSize.pixelWidthHeightRatio >= videoSize.height
                requestedOrientation =
                    if (landscape) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                    else ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
            }
            updatePipParams()
        }

        override fun onPlayerError(error: PlaybackException) {
            Toast.makeText(this@PlayerActivity, "再生できませんでした（${error.errorCodeName}）", Toast.LENGTH_LONG).show()
        }
    }

    private fun updateTitle() {
        titleView.text = player.currentMediaItem?.mediaMetadata?.title ?: ""
    }

    private fun savePosition() {
        if (!::player.isInitialized) return
        val id = player.currentMediaItem?.mediaId ?: return
        resume.save(id, player.currentPosition, player.duration)
    }

    private fun toggleOrientation() {
        orientationLocked = true
        val isLandscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        requestedOrientation =
            if (isLandscape) ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
            else ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
    }

    // ---------- 全画面 ----------

    private fun hideSystemUi() {
        WindowCompat.getInsetsController(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemUi()
    }

    // ---------- ピクチャーインピクチャー ----------

    private fun pipParams(): PictureInPictureParams {
        val builder = PictureInPictureParams.Builder()
        val vs = player.videoSize
        if (vs.width > 0 && vs.height > 0) {
            val ratio = vs.width.toFloat() / vs.height
            val r = when {
                ratio > 2.39f -> Rational(239, 100)
                ratio < 1 / 2.39f -> Rational(100, 239)
                else -> Rational(vs.width, vs.height)
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
        playerView.useController = !isInPictureInPictureMode
        if (isInPictureInPictureMode) {
            topBar.visibility = View.GONE
            gestureInfo.visibility = View.GONE
        }
    }

    // ---------- ジェスチャー ----------
    // 左半分の上下スワイプ: 明るさ / 右半分の上下スワイプ: 音量 / 左右スワイプ: シーク
    // ダブルタップ: 左=10秒戻る、右=10秒進む、中央=再生/一時停止

    @SuppressLint("ClickableViewAccessibility")
    private fun setupGestures() {
        val edge = 48 * resources.displayMetrics.density
        val detector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent) = true

            override fun onDoubleTap(e: MotionEvent): Boolean {
                val w = playerView.width
                when {
                    e.x < w / 3f -> { player.seekBack(); showInfo("⏪  10秒") }
                    e.x > w * 2 / 3f -> { player.seekForward(); showInfo("10秒  ⏩") }
                    else -> if (player.isPlaying) player.pause() else player.play()
                }
                return true
            }

            override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
                if (e1 == null) return false
                val dx = e2.x - e1.x
                val dy = e2.y - e1.y
                if (gesture == Gesture.NONE) {
                    gesture = when {
                        // 画面端はシステムのジェスチャーに譲る
                        e1.y < edge || e1.y > playerView.height - edge -> Gesture.IGNORE
                        abs(dy) > abs(dx) -> if (e1.x < playerView.width / 2f) Gesture.BRIGHTNESS else Gesture.VOLUME
                        else -> Gesture.SEEK
                    }
                    gestureStartValue = when (gesture) {
                        Gesture.BRIGHTNESS -> currentBrightness()
                        Gesture.VOLUME -> audioManager.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat()
                        else -> 0f
                    }
                    seekStartPos = player.currentPosition
                    seekTarget = seekStartPos
                }
                val h = playerView.height.toFloat()
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
                    Gesture.SEEK -> {
                        val dur = player.duration
                        if (dur > 0) {
                            val delta = (dx / playerView.width * 90_000).toLong()
                            seekTarget = (seekStartPos + delta).coerceIn(0, dur)
                            val diff = (seekTarget - seekStartPos) / 1000
                            val sign = if (diff >= 0) "+" else ""
                            showInfo("${formatTime(seekTarget)} / ${formatTime(dur)}\n($sign${diff}秒)", autoHide = false)
                        }
                    }
                    else -> {}
                }
                return true
            }
        })

        playerView.setOnTouchListener { _, e ->
            detector.onTouchEvent(e)
            val action = e.actionMasked
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                val was = gesture
                gesture = Gesture.NONE
                if (was == Gesture.SEEK && action == MotionEvent.ACTION_UP) player.seekTo(seekTarget)
                if (was != Gesture.NONE && was != Gesture.IGNORE) {
                    handler.removeCallbacks(hideInfo)
                    handler.postDelayed(hideInfo, 600)
                    return@setOnTouchListener true // スワイプ後にコントローラーが開閉しないようにする
                }
                return@setOnTouchListener false
            }
            gesture != Gesture.NONE && gesture != Gesture.IGNORE
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

    private fun showInfo(text: String, autoHide: Boolean = true) {
        gestureInfo.text = text
        gestureInfo.visibility = View.VISIBLE
        handler.removeCallbacks(hideInfo)
        if (autoHide) handler.postDelayed(hideInfo, 900)
    }

    // ---------- ライフサイクル ----------

    override fun onStop() {
        super.onStop()
        savePosition()
        player.pause()
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        player.removeListener(playerListener)
        player.release()
    }
}
