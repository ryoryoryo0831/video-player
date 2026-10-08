package com.ryose.videoplayer

import android.graphics.Color
import android.media.AudioManager
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.videolan.libvlc.MediaPlayer

/** 音楽の再生画面（ジャケット・曲名・再生操作） */
class AudioPlayerActivity : AppCompatActivity() {

    private lateinit var art: ImageView
    private lateinit var artCard: MaterialCardView
    private lateinit var titleView: TextView
    private lateinit var subtitleView: TextView
    private lateinit var seekBar: SeekBar
    private lateinit var timeCurrent: TextView
    private lateinit var timeDuration: TextView
    private lateinit var playButton: ImageButton
    private lateinit var nextButton: ImageButton
    private lateinit var shuffleButton: ImageButton
    private lateinit var repeatButton: ImageButton
    private lateinit var timerButton: ImageButton
    private lateinit var speedButton: TextView

    private var svc: PlaybackService? = null
    private var pendingLoad: Triple<List<PlaylistItem>, Int, Boolean>? = null
    private var userSeeking = false
    private var lengthMs = 0L

    private val listener = object : PlaybackService.Listener {
        override fun onPlayerEvent(e: MediaPlayer.Event) {
            when (e.type) {
                MediaPlayer.Event.Playing, MediaPlayer.Event.Paused, MediaPlayer.Event.Stopped -> updatePlayButton()
                MediaPlayer.Event.LengthChanged -> setLength(e.lengthChanged)
                MediaPlayer.Event.TimeChanged -> {
                    if (lengthMs <= 0) svc?.let { setLength(it.lengthMs) }
                    if (!userSeeking) {
                        seekBar.progress = e.timeChanged.toInt()
                        timeCurrent.text = formatTime(e.timeChanged)
                    }
                }
            }
        }

        override fun onItemChanged() = updateItem()
        override fun onModesChanged() = updateModes()
        override fun onPlaybackStopped() = finish()
    }

    private val dialogHandler by lazy { VlcDialogHandler(this) { svc?.currentItem?.uri } }

    private val connection = PlaybackConnection(this, autoCreate = true, onConnected = ::onServiceReady, onDisconnected = { svc = null })

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_audio_player)
        volumeControlStream = AudioManager.STREAM_MUSIC

        art = findViewById(R.id.art)
        artCard = findViewById(R.id.artCard)
        titleView = findViewById(R.id.title)
        subtitleView = findViewById(R.id.subtitle)
        seekBar = findViewById(R.id.seekBar)
        timeCurrent = findViewById(R.id.timeCurrent)
        timeDuration = findViewById(R.id.timeDuration)
        playButton = findViewById(R.id.playButton)
        nextButton = findViewById(R.id.nextButton)
        shuffleButton = findViewById(R.id.shuffleButton)
        repeatButton = findViewById(R.id.repeatButton)
        timerButton = findViewById(R.id.timerButton)
        speedButton = findViewById(R.id.speedButton)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.root)) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.updatePadding(left = bars.left, top = bars.top, right = bars.right, bottom = bars.bottom)
            insets
        }

        // ジャケットは画面に収まる最大の正方形にする
        val container = findViewById<FrameLayout>(R.id.artContainer)
        container.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
            val size = minOf(v.width - v.paddingLeft - v.paddingRight, v.height - v.paddingTop - v.paddingBottom)
            if (size > 0 && artCard.layoutParams.width != size) {
                artCard.layoutParams = FrameLayout.LayoutParams(size, size, Gravity.CENTER)
            }
        }

        findViewById<View>(R.id.closeButton).setOnClickListener { finish() }
        findViewById<View>(R.id.moreButton).setOnClickListener { showMoreMenu() }
        playButton.setOnClickListener { svc?.togglePlay() }
        findViewById<View>(R.id.prevButton).setOnClickListener { svc?.previous() }
        nextButton.setOnClickListener { svc?.next() }
        shuffleButton.setOnClickListener { svc?.toggleShuffle() }
        repeatButton.setOnClickListener { svc?.cycleRepeat() }
        speedButton.setOnClickListener { svc?.let { s -> PlayerDialogs.showSpeed(this, s) { updateModes() } } }
        timerButton.setOnClickListener { svc?.let { PlayerDialogs.showSleepTimer(this, it) } }
        findViewById<View>(R.id.eqButton).setOnClickListener { svc?.let { PlayerDialogs.showEqualizer(this, it) } }
        findViewById<View>(R.id.queueButton).setOnClickListener { svc?.let { PlayerDialogs.showQueue(this, it) } }

        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) timeCurrent.text = formatTime(progress.toLong())
            }

            override fun onStartTrackingTouch(sb: SeekBar) {
                userSeeking = true
            }

            override fun onStopTrackingTouch(sb: SeekBar) {
                svc?.seekTo(sb.progress.toLong())
                userSeeking = false
            }
        })

        if (savedInstanceState == null) takeLoadFrom(intent)
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        takeLoadFrom(intent)
        svc?.let { onServiceReady(it) }
    }

    /** 一覧などから開かれた場合は、再生するリストを受け取っておく（通知から開かれた場合は何もしない） */
    private fun takeLoadFrom(intent: android.content.Intent) {
        if (intent.getBooleanExtra(PlaybackService.EXTRA_FROM_SESSION, false)) return
        pendingLoad = playlistFromIntent(intent)
    }

    override fun onStart() {
        super.onStart()
        connection.bind()
    }

    override fun onResume() {
        super.onResume()
        svc?.let { it.setDialogCallbacks(dialogHandler) }
    }

    override fun onPause() {
        super.onPause()
        // 別の画面が前に出たら、そちらがダイアログを担当する
        svc?.let { it.setDialogCallbacks(null) }
    }

    override fun onStop() {
        super.onStop()
        svc?.removeListener(listener)
        connection.unbind()
        svc = null
    }

    private fun onServiceReady(s: PlaybackService) {
        svc = s
        s.addListener(listener)
        // ネットワーク再生でログインや証明書の確認を求められたときにダイアログを出す
        s.setDialogCallbacks(dialogHandler)
        pendingLoad?.let { (items, index, shuffle) ->
            pendingLoad = null
            s.load(items, index, shuffle)
        }
        if (s.currentItem == null) {
            finish()
            return
        }
        refreshAll()
    }

    private fun refreshAll() {
        val s = svc ?: return
        updateItem()
        lengthMs = 0
        setLength(s.lengthMs)
        if (!userSeeking) {
            val t = s.player.time.coerceAtLeast(0)
            seekBar.progress = t.toInt()
            timeCurrent.text = formatTime(t)
        }
        updatePlayButton()
        updateModes()
    }

    private fun setLength(ms: Long) {
        if (ms <= 0 || ms == lengthMs) return
        lengthMs = ms
        seekBar.max = ms.toInt()
        timeDuration.text = formatTime(ms)
    }

    private fun updateItem() {
        val s = svc ?: return
        val item = s.currentItem ?: return
        titleView.text = s.displayTitle()
        subtitleView.text = s.displaySubtitle()
        val bitmap = s.meta?.art
        if (bitmap != null) {
            art.setPadding(0, 0, 0, 0)
            art.alpha = 1f
            art.scaleType = ImageView.ScaleType.CENTER_CROP
            art.setImageBitmap(bitmap)
        } else {
            val pad = (artCard.layoutParams.width / 4).coerceAtLeast(0)
            art.setPadding(pad, pad, pad, pad)
            art.alpha = 0.4f
            art.scaleType = ImageView.ScaleType.FIT_CENTER
            art.setImageResource(if (item.isAudio) R.drawable.ic_music_note else R.drawable.ic_movie)
        }
        lengthMs = 0
        setLength(s.lengthMs)
        updateModes()
    }

    private fun updatePlayButton() {
        val playing = svc?.isPlaying == true
        playButton.setImageResource(if (playing) R.drawable.ic_pause else R.drawable.ic_play)
    }

    private fun tint(button: ImageButton, on: Boolean) {
        val normal = com.google.android.material.color.MaterialColors.getColor(
            button, com.google.android.material.R.attr.colorOnSurface,
        )
        button.setColorFilter(if (on) ContextCompat.getColor(this, R.color.accent) else normal)
        button.alpha = if (on) 1f else 0.6f
    }

    private fun updateModes() {
        val s = svc ?: return
        tint(shuffleButton, s.shuffle)
        repeatButton.setImageResource(if (s.repeat == PlaybackService.Repeat.ONE) R.drawable.ic_repeat_one else R.drawable.ic_repeat)
        tint(repeatButton, s.repeat != PlaybackService.Repeat.OFF)
        tint(timerButton, s.sleepAt > 0 || s.sleepAtEnd)
        nextButton.alpha = if (s.hasNext()) 1f else 0.4f
        speedButton.text = formatRate(s.rate)
    }

    private fun showMoreMenu() {
        val s = svc ?: return
        val item = s.currentItem ?: return
        val chapters = s.chapters()
        val actions = mutableListOf<Pair<String, () -> Unit>>(
            "プレイリストに追加" to { PlaylistDialogs.addToPlaylist(this, listOf(item)) },
            (PlayerDialogs.sleepLabel(s)?.let { "スリープタイマー（$it）" } ?: "スリープタイマー") to {
                PlayerDialogs.showSleepTimer(this, s)
            },
            (s.abLabel()?.let { "A-Bリピート（$it）：次へ進む" } ?: "A-Bリピート（区間をくり返す）") to {
                Toast.makeText(this, s.abStep(), Toast.LENGTH_SHORT).show()
            },
        )
        if (chapters.isNotEmpty()) {
            actions += "チャプター（${chapters.size}）" to {
                val labels = chapters.mapIndexed { i, c ->
                    "${formatTime(c.timeOffset)}  " + (c.name?.takeIf { it.isNotBlank() } ?: "チャプター ${i + 1}")
                }
                MaterialAlertDialogBuilder(this)
                    .setTitle("チャプター")
                    .setItems(labels.toTypedArray()) { _, which -> s.player.setChapter(which) }
                    .show()
            }
        }
        actions += (s.renderer?.let { "キャスト中：${it.displayName ?: it.name}" } ?: "キャスト（テレビ・スピーカーで再生）") to {
            PlayerDialogs.showCast(this, s)
        }
        actions += "設定" to { startActivity(android.content.Intent(this, SettingsActivity::class.java)) }
        actions += "再生を終了" to { s.stopPlayback() }
        MaterialAlertDialogBuilder(this)
            .setTitle(s.displayTitle())
            .setItems(actions.map { it.first }.toTypedArray()) { _, which -> actions[which].second() }
            .show()
    }
}
