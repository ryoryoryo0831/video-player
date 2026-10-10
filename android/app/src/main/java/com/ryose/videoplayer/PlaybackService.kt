package com.ryose.videoplayer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaMetadataRetriever
import android.net.wifi.WifiManager
import android.net.Uri
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.videolan.libvlc.Dialog
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.RendererDiscoverer
import org.videolan.libvlc.RendererItem
import org.videolan.libvlc.interfaces.IMedia
import java.io.File
import java.io.FileNotFoundException

/**
 * 再生を担当するサービス。画面（動画・音楽）はこのサービスにつないで操作する。
 * 画面を閉じても、画面を消しても再生を続け、通知・ロック画面・イヤホンのボタンから操作できる。
 */
class PlaybackService : Service() {

    enum class Repeat { OFF, ALL, ONE }

    /** 画面側が受け取る通知。必要なものだけ実装すればよい */
    interface Listener {
        fun onPlayerEvent(e: MediaPlayer.Event) {}
        /** 再生する曲・動画が変わった（タイトル・ジャケットなども） */
        fun onItemChanged() {}
        /** シャッフル・リピート・スリープタイマーなどが変わった */
        fun onModesChanged() {}
        /** 再生が終わった・止められた */
        fun onPlaybackStopped() {}
        /** キャスト先（Chromecast など）の一覧が変わった */
        fun onRenderersChanged() {}
    }

    /** 曲の情報（音楽ファイルに埋め込まれたタイトル・アーティスト・ジャケット） */
    data class TrackMeta(val title: String?, val artist: String?, val album: String?, val art: Bitmap?)

    inner class LocalBinder : Binder() {
        val service: PlaybackService get() = this@PlaybackService
    }

    private val binder = LocalBinder()
    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val listeners = mutableListOf<Listener>()

    lateinit var libVLC: LibVLC
        private set
    lateinit var player: MediaPlayer
        private set
    private lateinit var session: MediaSessionCompat
    private lateinit var resume: ResumeStore
    private lateinit var history: HistoryStore
    private lateinit var audioManager: AudioManager

    var items: List<PlaylistItem> = emptyList()
        private set
    var index = 0
        private set
    /** 再生する順番（items のインデックス）。シャッフル中は並びが変わる */
    var order: List<Int> = emptyList()
        private set
    var orderPos = 0
        private set
    var shuffle = false
        private set
    /** 動画が終わったら次の動画へ進むか（「動画」タブの一覧から再生したときは設定しだい） */
    var autoAdvance = true
        private set
    var repeat = Repeat.OFF
        private set
    var lengthMs = 0L
        private set
    var rate = 1f
        private set
    var meta: TrackMeta? = null
        private set
    /** スリープタイマーで止める時刻（0 なら未設定） */
    var sleepAt = 0L
        private set
    /** 今の曲・動画が終わったら止める */
    var sleepAtEnd = false
        private set
    /** 動画を裏で音声だけ再生している・一時停止している（映像を止めている）状態 */
    var videoTrackDisabled = false
        private set
    /** 画面が無い間に始まった動画（:no-video で開いたので、映像を戻すには開き直しが必要） */
    private var startedWithoutVideo = false
    /** 動画の画面が表示されているか（表示されていない間に始まる動画は音声だけ再生する） */
    var videoUiAttached = false

    /** 自由な小窓（ポップアップ再生） */
    private var popup: PopupPlayer? = null
    val isPopupShowing: Boolean get() = popup?.isShowing == true

    /** 今の動画を自由な小窓で表示する（許可が無いなどで出せなければ false） */
    fun showPopup(): Boolean {
        val item = currentItem ?: return false
        if (item.isAudio || renderer != null) return false
        val p = popup ?: PopupPlayer(this).also { popup = it }
        return p.show()
    }

    /** 自由な小窓を閉じる（再生は続ける） */
    fun closePopup() {
        popup?.dismiss()
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        popup?.onScreenChanged()
    }

    /** 動画の映像を止めて音声だけ再生する／映像を戻す */
    fun setVideoEnabled(enabled: Boolean) {
        if (enabled == !videoTrackDisabled) return
        player.setVideoTrackEnabled(enabled)
        videoTrackDisabled = !enabled
    }

    private var openFd: ParcelFileDescriptor? = null
    /** 「字幕ファイルを追加」で読み込んだ字幕（動画ごと） */
    private val addedSubtitles = mutableMapOf<String, MutableList<File>>()
    private var foreground = false
    /** 通知を出しているか（一時停止中は通知だけ残してサービスの常駐をやめる） */
    private var notificationShown = false
    private var pausedByFocus = false
    private var hasFocus = false
    /** 今の曲・動画で再生エラーが起きて、次へ進む処理を済ませた */
    private var errorHandled = false
    /** 続けて再生できなかった数（全部だめなときに延々と次へ進まないように） */
    private var errorStreak = 0
    private var wifiLock: WifiManager.WifiLock? = null

    val currentItem: PlaylistItem? get() = items.getOrNull(index)
    val isPlaying: Boolean get() = player.isPlaying

    // ---------- サービスのライフサイクル ----------

    override fun onCreate() {
        super.onCreate()
        resume = ResumeStore(this)
        history = HistoryStore(this)
        audioManager = getSystemService(AudioManager::class.java)
        repeat = runCatching { Repeat.valueOf(prefs().getString("repeat", null) ?: "OFF") }.getOrDefault(Repeat.OFF)

        createEngine()

        session = MediaSessionCompat(this, "VideoPlayer").apply {
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() = play()
                override fun onPause() = pause()
                override fun onSkipToNext() = next()
                override fun onSkipToPrevious() = previous()
                override fun onSeekTo(pos: Long) = seekTo(pos)
                override fun onStop() = stopPlayback()
            })
            // アプリが止まったあとでも、イヤホンや Bluetooth の再生ボタンで前回の続きを再生できるように
            setMediaButtonReceiver(
                PendingIntent.getBroadcast(
                    this@PlaybackService, 0,
                    Intent(Intent.ACTION_MEDIA_BUTTON).setClass(this@PlaybackService, MediaButtonReceiver::class.java),
                    PendingIntent.FLAG_IMMUTABLE,
                )
            )
        }

        ContextCompat.registerReceiver(
            this, noisyReceiver, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        // 再生サービスが一度止められていたら（通知・イヤホンのボタンから起こされた）、前回の続きを用意する
        val resumes = action == ACTION_PLAY_PAUSE || action == ACTION_PLAY
        val restored = currentItem == null && action in RESUMING_ACTIONS && restoreLastSession(play = resumes)
        when (action) {
            // 前回の続きを読み込み直したときは、もう再生が始まっている
            ACTION_PLAY_PAUSE -> if (!restored) togglePlay()
            ACTION_PLAY -> if (!restored) play()
            ACTION_NEXT -> next()
            ACTION_PREVIOUS -> previous()
            ACTION_STOP -> stopPlayback()
            ACTION_ENQUEUE, ACTION_PLAY_NEXT -> {
                val list = intent?.getStringExtra(EXTRA_ITEMS)?.let { json ->
                    runCatching {
                        val arr = org.json.JSONArray(json)
                        (0 until arr.length()).map { PlaylistItem.fromJson(arr.getJSONObject(it)) }
                    }.getOrNull()
                }.orEmpty()
                enqueue(list, next = action == ACTION_PLAY_NEXT)
                if (list.isNotEmpty() && currentItem != null) {
                    Toast.makeText(
                        this, if (action == ACTION_PLAY_NEXT) "次に再生します" else "再生キューに追加しました", Toast.LENGTH_SHORT,
                    ).show()
                }
            }
            // 一時停止中の通知をスワイプで消した：画面で見ていなければ終了する
            ACTION_DISMISS -> if (!videoUiAttached && !player.isPlaying) stopPlayback()
        }
        if (intent?.getBooleanExtra(EXTRA_FOREGROUND, false) == true) {
            // startForegroundService で起こされたときは、必ず常駐を始める（始めないとアプリが落ちる）
            startForegroundIfNeeded()
            if (currentItem == null) stopPlayback()
        } else if (currentItem == null && listeners.isEmpty()) {
            // 何も再生するものが無い：起こされただけなので終わる
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        // 最近使ったアプリから消されたとき、再生中でなければ終了する
        if (!player.isPlaying) stopPlayback()
    }

    override fun onDestroy() {
        hasQueue = false
        popup?.dismiss()
        savePosition()
        // OS に止められた場合も、押しても何も起きない通知を残さない
        NotificationManagerCompat.from(this).cancel(NOTIFICATION_ID)
        stopRendererDiscovery()
        renderer?.release()
        renderer = null
        handler.removeCallbacksAndMessages(null)
        scope.cancel()
        runCatching { unregisterReceiver(noisyReceiver) }
        abandonFocus()
        releaseWifiLock()
        session.release()
        player.setEventListener(null)
        player.release()
        libVLC.release()
        closeFd()
        super.onDestroy()
    }

    // ---------- VLC のエンジン ----------

    /** エンジンを作ったときのオプション（字幕の見た目などの設定が変わったら作り直す） */
    private var engineOptions: List<String> = emptyList()
    private var dialogCallbacks: Dialog.Callbacks? = null

    private fun createEngine() {
        val options = AppSettings.vlcOptions(this)
        libVLC = try {
            LibVLC(this, ArrayList(options))
        } catch (_: Exception) {
            // 万一オプションが受け付けられなかった場合は、基本のオプションだけで作る
            LibVLC(this, ArrayList(AppSettings.BASE_VLC_OPTIONS))
        }
        engineOptions = options
        player = MediaPlayer(libVLC)
        player.setEventListener(object : MediaPlayer.EventListener {
            override fun onEvent(event: MediaPlayer.Event) = onPlayerEvent(event)
        })
        applyEqualizer()
        dialogCallbacks?.let { Dialog.setCallbacks(libVLC, it) }
    }

    /**
     * 設定（字幕の見た目など）が変わっていたら、エンジンを作り直す。
     * 新しく再生を始める直前、動画の画面につなぐ前に呼ぶ。
     */
    fun ensureEngineUpToDate() {
        if (videoUiAttached || !engineOutdated()) return
        rebuildEngine()
    }

    fun engineOutdated() = AppSettings.vlcOptions(this) != engineOptions

    private fun rebuildEngine() {
        savePosition()
        stopRendererDiscovery()
        renderer?.release()
        renderer = null
        player.setEventListener(null)
        player.stop()
        player.release()
        libVLC.release()
        createEngine()
    }

    /** エンジンを作り直したあとに、同じ動画を同じ位置から開き直すための記録（位置・再生中だったか） */
    private var pendingReplay: Pair<Long, Boolean>? = null

    /**
     * 再生中の動画はそのままに、設定（字幕の見た目など）が変わっていたらエンジンを作り直す。
     * 動画の画面につなぐ前に呼び、つないだあとで [replayAfterEngineChange] を呼ぶ。作り直したら true
     */
    fun recreateEngineKeepingItem(): Boolean {
        if (currentItem == null || videoUiAttached || !engineOutdated()) return false
        pendingReplay = player.time.coerceAtLeast(0) to player.isPlaying
        rebuildEngine()
        return true
    }

    fun replayAfterEngineChange() {
        val (t, playing) = pendingReplay ?: return
        pendingReplay = null
        playCurrent(t, paused = !playing)
    }

    /** VLC からの質問（ログイン・証明書の確認など）を表示する画面を登録する */
    fun setDialogCallbacks(callbacks: Dialog.Callbacks?) {
        dialogCallbacks = callbacks
        Dialog.setCallbacks(libVLC, callbacks)
    }

    fun addListener(l: Listener) {
        if (l !in listeners) listeners += l
    }

    fun removeListener(l: Listener) {
        listeners -= l
    }

    private fun dispatch(action: (Listener) -> Unit) = listeners.toList().forEach(action)

    private fun prefs() = getSharedPreferences("player", MODE_PRIVATE)

    // ---------- 読み込みと再生 ----------

    /** 新しいプレイリストで再生を始める（[paused] なら一時停止した状態で用意だけする） */
    fun load(
        newItems: List<PlaylistItem>, startIndex: Int, shuffled: Boolean,
        paused: Boolean = false, advance: Boolean = true,
    ) {
        if (startIndex !in newItems.indices) return
        savePosition()
        autoAdvance = advance
        sleepAtEnd = false
        errorStreak = 0
        items = newItems
        index = startIndex
        shuffle = shuffled
        hasQueue = true
        buildOrder()
        ensureEngineUpToDate()
        // 画面から切り離されても動き続けるように「開始済み」のサービスにしておく
        startService(Intent(this, PlaybackService::class.java))
        playCurrent(startPositionOf(newItems[startIndex]), paused)
        // 画面が表示されている今のうちに常駐を始める（再生開始を待つと、その前に画面を離れた場合に始められない）
        startForegroundIfNeeded()
        if (paused) scheduleIdleStop()
        dispatch { it.onModesChanged() }
    }

    /**
     * 前回の再生キューを読み込み直す（再生サービスが OS に止められたあとなど）。
     * [videoOnly]・[audioOnly] で、前回が動画（音楽）だったときだけに限る。読み込めたら true
     */
    fun restoreLastSession(play: Boolean, videoOnly: Boolean = false, audioOnly: Boolean = false): Boolean {
        if (currentItem != null) return true
        val saved = LastSession.load(this) ?: return false
        val cur = saved.current ?: return false
        if (videoOnly && cur.isAudio) return false
        if (audioOnly && !cur.isAudio) return false
        load(saved.items, saved.index, saved.shuffle, paused = !play)
        return currentItem != null
    }

    private fun playCurrent(startMs: Long, paused: Boolean) {
        val item = currentItem ?: return
        closeFd()
        lengthMs = 0
        meta = null
        videoTrackDisabled = false
        startedWithoutVideo = false
        errorHandled = false
        abA = -1
        abB = -1

        val media = try {
            val path = item.path
            when {
                path != null && File(path).canRead() -> Media(libVLC, path)
                item.isNetwork -> Media(libVLC, item.uri).also { m ->
                    // 登録したサーバーなら、ログイン情報を渡す
                    ServerStore(this).optionsFor(item.uri).forEach { m.addOption(it) }
                }
                else -> {
                    val pfd = contentResolver.openFileDescriptor(item.uri, "r") ?: throw FileNotFoundException()
                    openFd = pfd
                    Media(libVLC, pfd.fileDescriptor)
                }
            }
        } catch (_: Exception) {
            onPlaybackError("ファイルを開けませんでした：${item.title}")
            return
        }
        media.setHWDecoderEnabled(hwDecodingFor(item), false)
        if (item.isNetwork) {
            media.addOption(":network-caching=${AppSettings.networkCachingMs(this)}")
            // NAS などでは隣の字幕ファイルをアプリが探せないので、VLC に探してもらう
            if (!item.isAudio) media.addOption(":sub-autodetect-file")
        }
        if (startMs > 0) media.addOption(":start-time=${startMs / 1000.0}")
        if (paused) media.addOption(":start-paused")
        if (!item.isAudio && !videoUiAttached && renderer == null) {
            media.addOption(":no-video")
            videoTrackDisabled = true
            startedWithoutVideo = true
        }
        player.setMedia(media)
        media.release()
        player.play()

        // 履歴は動画だけ（音楽を聴くと動画の履歴が押し出されてしまうので）
        if (!item.isAudio) scope.launch(Dispatchers.IO) { history.add(item) }
        LastSession.save(this, items, index, shuffle)
        loadSubtitles(item)
        loadMeta(item)
        updateSession()
        dispatch { it.onItemChanged() }
    }

    private fun closeFd() {
        runCatching { openFd?.close() }
        openFd = null
    }

    fun togglePlay() {
        if (player.isPlaying) pause() else play()
    }

    fun play() {
        if (currentItem != null) player.play()
    }

    fun pause() {
        player.pause()
    }

    fun seekTo(ms: Long) {
        val max = if (lengthMs > 0) lengthMs else Long.MAX_VALUE
        player.setTime(ms.coerceIn(0, max))
        updateSession()
    }

    fun seekBy(deltaMs: Long) = seekTo(player.time.coerceAtLeast(0) + deltaMs)

    fun setPlaybackRate(r: Float) {
        rate = r
        player.rate = r
        updateSession()
    }

    /**
     * 動画画面が裏に回ったとき：一時停止して映像を止めておく。
     * 読み込んだままにしておくので、戻ってきたときに選んだ字幕・音声トラックやずれの調整がそのまま残る
     */
    fun park() {
        if (currentItem == null) return
        // 電話などで一時停止していた場合も、画面を離れたら勝手に再開しない
        pausedByFocus = false
        if (player.isPlaying) player.pause()
        savePosition()
        setVideoEnabled(false)
        updateSession()
    }

    /** 動画の画面に戻ってきたとき：止めていた映像を戻す */
    fun restoreVideo() {
        if (currentItem == null || currentItem?.isAudio == true) return
        if (startedWithoutVideo) {
            // 映像なしで開いた動画は、同じ位置から開き直して映像を出す
            val t = player.time.coerceAtLeast(0)
            playCurrent(t, paused = !player.isPlaying)
            return
        }
        if (!videoTrackDisabled) return
        setVideoEnabled(true)
        // 一時停止中でも今の場面が表示されるように、同じ位置へ移動し直す
        if (!player.isPlaying) player.setTime(player.time.coerceAtLeast(0))
    }

    /** 再生を終了して通知も消す */
    fun stopPlayback() {
        savePosition()
        player.stop()
        if (renderer != null) {
            player.setRenderer(null)
            renderer?.release()
            renderer = null
        }
        items = emptyList()
        order = emptyList()
        index = 0
        hasQueue = false
        lengthMs = 0
        meta = null
        cancelSleepTimer()
        abandonFocus()
        releaseWifiLock()
        handler.removeCallbacks(saveTask)
        handler.removeCallbacks(idleStopTask)
        session.isActive = false
        lastMetaKey = null
        lastNotifiedPlaying = null
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        NotificationManagerCompat.from(this).cancel(NOTIFICATION_ID)
        foreground = false
        notificationShown = false
        dispatch { it.onPlaybackStopped() }
        stopSelf()
    }

    // ---------- 再生順（シャッフル・リピート） ----------

    private fun buildOrder() {
        order = if (shuffle) listOf(index) + (items.indices - index).shuffled() else items.indices.toList()
        orderPos = order.indexOf(index).coerceAtLeast(0)
    }

    fun playAt(pos: Int) {
        if (pos !in order.indices) return
        savePosition()
        orderPos = pos
        index = order[pos]
        playCurrent(startPositionOf(items[index]), false)
    }

    // ---------- 再生キューの編集 ----------

    /** 再生キューに加える（[next] なら今の曲・動画の次に、そうでなければ最後に） */
    fun enqueue(added: List<PlaylistItem>, next: Boolean) {
        if (added.isEmpty() || currentItem == null) return
        val start = items.size
        items = items + added
        val newIdx = added.indices.map { start + it }
        order = if (next) order.take(orderPos + 1) + newIdx + order.drop(orderPos + 1) else order + newIdx
        queueChanged()
    }

    /** 再生キューから外す（今再生しているものは外せない） */
    fun removeFromQueue(pos: Int) {
        if (pos !in order.indices || pos == orderPos) return
        val removed = order[pos]
        items = items.filterIndexed { i, _ -> i != removed }
        order = order.filterIndexed { p, _ -> p != pos }.map { if (it > removed) it - 1 else it }
        if (index > removed) index--
        queueChanged()
    }

    /** 再生キューの順番を入れ替える */
    fun moveInQueue(from: Int, to: Int) {
        if (from !in order.indices || to !in order.indices || from == to) return
        order = order.toMutableList().apply { add(to, removeAt(from)) }
        queueChanged()
    }

    private fun queueChanged() {
        orderPos = order.indexOf(index).coerceAtLeast(0)
        LastSession.save(this, items, index, shuffle)
        dispatch { it.onModesChanged() }
    }

    fun hasNext() = orderPos < order.lastIndex || (repeat == Repeat.ALL && items.size > 1)

    fun next() {
        when {
            orderPos < order.lastIndex -> playAt(orderPos + 1)
            repeat == Repeat.ALL && items.isNotEmpty() -> {
                // 最後まで来たら最初から（シャッフル中は並べ直す。今の曲がまた最初に来て 2 回続かないように）
                if (shuffle) order = items.indices.shuffled().let { o ->
                    if (o.size > 1 && o.first() == index) o.drop(1) + o.first() else o
                }
                playAt(0)
            }
        }
    }

    fun previous() {
        when {
            player.time > 3000 || (orderPos == 0 && repeat != Repeat.ALL) -> seekTo(0)
            orderPos > 0 -> playAt(orderPos - 1)
            else -> playAt(order.lastIndex)
        }
    }

    fun toggleShuffle() {
        shuffle = !shuffle
        buildOrder()
        dispatch { it.onModesChanged() }
    }

    fun cycleRepeat() {
        repeat = Repeat.entries[(repeat.ordinal + 1) % Repeat.entries.size]
        prefs().edit().putString("repeat", repeat.name).apply()
        dispatch { it.onModesChanged() }
    }

    /**
     * 再生したものが「プレイリスト」（.m3u の URL など）だった場合、中身の曲・動画に置き換えて再生する。
     * 置き換えた場合は true
     */
    private fun expandSubItems(): Boolean {
        if (lengthMs > 0) return false
        val media = player.media ?: return false
        val list = media.subItems()
        try {
            val n = list.count
            if (n == 0) return false
            val subs = (0 until n).map { i ->
                val m = list.getMediaAt(i)
                val uri = m.uri
                val title = m.getMeta(IMedia.Meta.Title)?.takeIf { it.isNotBlank() }
                    ?: uri.lastPathSegment ?: uri.toString()
                m.release()
                PlaylistItem(uri, title)
            }
            items = items.take(index) + subs + items.drop(index + 1)
            buildOrder()
            playCurrent(0, false)
            dispatch { it.onModesChanged() }
            return true
        } finally {
            list.release()
            media.release()
        }
    }

    private fun onEnded() {
        // 再生エラーで次へ進む処理を済ませている
        if (errorHandled) return
        if (expandSubItems()) return
        currentItem?.let { resume.clear(it.key) }
        if (sleepAtEnd) {
            // スリープタイマー「この曲の終わりまで」
            sleepAtEnd = false
            dispatch { it.onModesChanged() }
            stopPlayback()
            return
        }
        val item = currentItem
        when {
            repeat == Repeat.ONE -> playCurrent(0, false)
            hasNext() && (autoAdvance || item?.isAudio == true) -> next()
            else -> stopPlayback()
        }
    }

    // ---------- キャスト（Chromecast など） ----------

    /** いまキャストしている先（null ならこの端末で再生） */
    var renderer: RendererItem? = null
        private set
    /** 見つかったキャスト先（名前 → 機器） */
    val renderers = linkedMapOf<String, RendererItem>()
    private val rendererDiscoverers = mutableListOf<RendererDiscoverer>()
    private var castMulticast: WifiManager.MulticastLock? = null

    /** 同じネットワークのキャスト先を探し始める（キャストのメニューを開いている間） */
    fun startRendererDiscovery() {
        if (rendererDiscoverers.isNotEmpty()) return
        castMulticast = runCatching {
            applicationContext.getSystemService(WifiManager::class.java)
                .createMulticastLock("VideoPlayerCast").apply {
                    setReferenceCounted(false)
                    acquire()
                }
        }.getOrNull()
        val descriptions = runCatching { RendererDiscoverer.list(libVLC) }.getOrNull() ?: return
        descriptions.forEach { d ->
            val rd = RendererDiscoverer(libVLC, d.name)
            rd.setEventListener(object : RendererDiscoverer.EventListener {
                override fun onEvent(event: RendererDiscoverer.Event) {
                    val item = event.item ?: return
                    when (event.type) {
                        RendererDiscoverer.Event.ItemAdded -> if (!renderers.containsKey(item.name)) {
                            // イベントが終わると解放されるので、自分でも保持しておく
                            item.retain()
                            renderers[item.name] = item
                            dispatch { it.onRenderersChanged() }
                        }
                        RendererDiscoverer.Event.ItemDeleted -> {
                            renderers.remove(item.name)?.release()
                            dispatch { it.onRenderersChanged() }
                        }
                    }
                }
            })
            if (rd.start()) rendererDiscoverers += rd else rd.release()
        }
    }

    fun stopRendererDiscovery() {
        rendererDiscoverers.forEach {
            it.stop()
            it.release()
        }
        rendererDiscoverers.clear()
        renderers.values.forEach { it.release() }
        renderers.clear()
        runCatching { castMulticast?.release() }
        castMulticast = null
    }

    /** キャスト先を切り替える（null でこの端末に戻す）。再生中なら同じ位置から再生し直す */
    fun castTo(item: RendererItem?) {
        if (item == renderer) return
        val active = currentItem != null
        val position = player.time.coerceAtLeast(0)
        savePosition()
        renderer?.release()
        renderer = item?.also { it.retain() }
        player.setRenderer(item)
        if (active) {
            playCurrent(position, false)
        }
        dispatch { it.onModesChanged() }
    }

    // ---------- デコード（動画ごとの切り替え） ----------

    /** 動画ごとに「ハードウェア／ソフトウェア」を切り替えたもの（アプリを閉じるまで覚えておく） */
    private val hwOverrides = mutableMapOf<String, Boolean>()

    fun hwDecodingFor(item: PlaylistItem): Boolean = hwOverrides[item.key] ?: AppSettings.hwDecoding(this)

    /** 今の動画のデコード方法を切り替えて、同じ位置から開き直す */
    fun toggleHwDecoding(): Boolean {
        val item = currentItem ?: return false
        val hw = !hwDecodingFor(item)
        hwOverrides[item.key] = hw
        val t = player.time.coerceAtLeast(0)
        val wasPlaying = player.isPlaying
        playCurrent(t, paused = !wasPlaying)
        return hw
    }

    // ---------- A-B リピート ----------

    var abA = -1L
        private set
    var abB = -1L
        private set

    /** 1回目で A、2回目で B を今の位置に設定し、3回目で解除する。結果を文章で返す */
    fun abStep(): String {
        val t = player.time.coerceAtLeast(0)
        val msg = when {
            abA < 0 -> {
                abA = t
                "A-Bリピート：A を ${formatTime(t)} に設定（もう一度で B）"
            }
            abB < 0 && t > abA + 500 -> {
                abB = t
                player.setTime(abA)
                "A-Bリピート：${formatTime(abA)} 〜 ${formatTime(t)} をくり返します"
            }
            abB < 0 -> "B は A（${formatTime(abA)}）より後の位置で設定してください"
            else -> {
                clearAb()
                return "A-Bリピートを解除しました"
            }
        }
        dispatch { it.onModesChanged() }
        return msg
    }

    fun clearAb() {
        abA = -1
        abB = -1
        dispatch { it.onModesChanged() }
    }

    fun abLabel(): String? = when {
        abA >= 0 && abB > abA -> "A-B"
        abA >= 0 -> "A-"
        else -> null
    }

    // ---------- 音量（100% を超えるブースト） ----------

    /** VLC 側の音量（100 が標準、最大 200） */
    var volume = 100
        private set

    fun setBoostVolume(v: Int) {
        volume = v.coerceIn(0, if (AppSettings.audioBoost(this)) 200 else 100)
        player.setVolume(volume)
    }

    // ---------- チャプター ----------

    fun chapters(): Array<MediaPlayer.Chapter> = runCatching { player.getChapters(-1) }.getOrNull() ?: emptyArray()

    // ---------- スリープタイマー ----------

    private val sleepTask = Runnable {
        sleepAt = 0
        pause()
        dispatch { it.onModesChanged() }
    }

    fun setSleepTimer(minutes: Int) {
        cancelSleepTimer()
        sleepAt = System.currentTimeMillis() + minutes * 60_000L
        handler.postDelayed(sleepTask, minutes * 60_000L)
        dispatch { it.onModesChanged() }
    }

    fun setSleepAtEnd() {
        cancelSleepTimer()
        sleepAtEnd = true
        dispatch { it.onModesChanged() }
    }

    fun cancelSleepTimer() {
        handler.removeCallbacks(sleepTask)
        sleepAt = 0
        sleepAtEnd = false
        dispatch { it.onModesChanged() }
    }

    // ---------- イコライザー ----------

    fun applyEqualizer() {
        val s = EqualizerPrefs.load(this)
        if (!s.enabled) {
            player.setEqualizer(null)
            return
        }
        val eq = MediaPlayer.Equalizer.create()
        eq.setPreAmp(s.preamp)
        s.bands.forEachIndexed { i, v -> eq.setAmp(i, v) }
        player.setEqualizer(eq)
    }

    // ---------- プレイヤーのイベント ----------

    private val saveTask = object : Runnable {
        override fun run() {
            savePosition()
            handler.postDelayed(this, 5000)
        }
    }

    private fun onPlayerEvent(e: MediaPlayer.Event) {
        when (e.type) {
            MediaPlayer.Event.Playing -> {
                if (!requestFocus()) {
                    // 通話中など、ほかのアプリが音を使っていて譲ってもらえなかった
                    player.pause()
                    Toast.makeText(this, "ほかのアプリが音声を使用中のため、再生できません", Toast.LENGTH_SHORT).show()
                    return
                }
                errorStreak = 0
                if (player.rate != rate) player.rate = rate
                if (player.volume != volume) player.setVolume(volume)
                handler.removeCallbacks(saveTask)
                handler.post(saveTask)
                if (currentItem?.isNetwork == true) acquireWifiLock()
                handler.removeCallbacks(idleStopTask)
                updateSession()
                startForegroundIfNeeded()
            }
            MediaPlayer.Event.Paused -> {
                handler.removeCallbacks(saveTask)
                savePosition()
                releaseWifiLock()
                updateSession()
                // 一時停止してもしばらくは常駐を続ける（すぐやめると OS に止められて、通知や電話のあとの再開が効かなくなる）
                scheduleIdleStop()
            }
            MediaPlayer.Event.Stopped -> {
                handler.removeCallbacks(saveTask)
                releaseWifiLock()
                updateSession()
            }
            MediaPlayer.Event.LengthChanged -> setLength(e.lengthChanged)
            MediaPlayer.Event.TimeChanged -> {
                if (lengthMs <= 0) setLength(player.length)
                // A-B リピート：B を過ぎたら A に戻る
                if (abA >= 0 && abB > abA && e.timeChanged >= abB) player.setTime(abA)
            }
            MediaPlayer.Event.EndReached -> {
                // 画面側にも伝えてから次へ進む
                dispatch { it.onPlayerEvent(e) }
                onEnded()
                return
            }
            MediaPlayer.Event.EncounteredError -> {
                dispatch { it.onPlayerEvent(e) }
                onPlaybackError("再生できませんでした：${currentItem?.title.orEmpty()}")
                return
            }
        }
        dispatch { it.onPlayerEvent(e) }
    }

    /** 開けない・再生できないファイルは飛ばして次へ進む（次が無い、または全部だめなら終了） */
    private fun onPlaybackError(message: String) {
        if (errorHandled) return
        errorHandled = true
        errorStreak++
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        handler.post {
            if (hasNext() && errorStreak < items.size) next() else stopPlayback()
        }
    }

    private fun setLength(ms: Long) {
        if (ms <= 0 || ms == lengthMs) return
        lengthMs = ms
        // 履歴に長さを記録しておく（一覧で視聴位置のバーを出すため）
        currentItem?.let { item -> scope.launch(Dispatchers.IO) { history.updateDuration(item.key, ms) } }
        updateSession()
    }

    /** 続きから再生する位置（設定でオフなら最初から） */
    private fun startPositionOf(item: PlaylistItem): Long =
        if (AppSettings.resume(this)) resume.get(item.key) else 0L

    fun savePosition() {
        val item = currentItem ?: return
        val len = if (lengthMs > 0) lengthMs else player.length
        val t = player.time
        if (t < 0 || len <= 0) return
        // 普通の長さの曲は続きから再生しない（10分以上の音声＝ラジオや朗読などだけ）
        if (item.isAudio && len < 10 * 60_000L) return
        resume.save(item.key, t, len)
    }

    // ---------- 字幕 ----------

    /** 字幕の文字コードを手で選んだもの（動画ごと。アプリを閉じるまで覚えておく） */
    private val subtitleCharsets = mutableMapOf<String, String>()

    fun subtitleCharset(): String? = currentItem?.let { subtitleCharsets[it.key] }

    /** 隣の字幕ファイルの文字コードを選び直す（null で自動）。同じ位置から開き直して字幕を読み直す */
    fun setSubtitleCharset(name: String?) {
        val item = currentItem ?: return
        if (name == null) subtitleCharsets.remove(item.key) else subtitleCharsets[item.key] = name
        playCurrent(player.time.coerceAtLeast(0), paused = !player.isPlaying)
    }

    private fun loadSubtitles(item: PlaylistItem) {
        if (item.isAudio) return
        val manual = addedSubtitles[item.key].orEmpty().toList()
        val path = item.path
        val charset = subtitleCharsets[item.key]
        scope.launch {
            val auto = if (path == null) emptyList() else withContext(Dispatchers.IO) {
                Subtitles.findFor(path).map { Subtitles.prepare(this@PlaybackService, it, charset) }
            }
            if (currentItem != item) return@launch
            (manual + auto).forEachIndexed { i, f ->
                player.addSlave(IMedia.Slave.Type.Subtitle, Uri.fromFile(f), i == 0)
            }
        }
    }

    fun addSubtitle(file: File) {
        val item = currentItem ?: return
        addedSubtitles.getOrPut(item.key) { mutableListOf() }.add(0, file)
        player.addSlave(IMedia.Slave.Type.Subtitle, Uri.fromFile(file), true)
    }

    // ---------- 曲の情報・ジャケット ----------

    private fun loadMeta(item: PlaylistItem) {
        // ネットワーク上のファイルは読み込みに時間がかかるので調べない
        if (item.isNetwork) return
        scope.launch {
            val m = withContext(Dispatchers.IO) { readMeta(item) }
            if (currentItem != item) return@launch
            meta = m
            updateSession()
            dispatch { it.onItemChanged() }
        }
    }

    private fun readMeta(item: PlaylistItem): TrackMeta {
        val r = MediaMetadataRetriever()
        return try {
            if (item.path != null) r.setDataSource(item.path) else r.setDataSource(this, item.uri)
            val art = if (item.isAudio) r.embeddedPicture?.let { decodeArt(it) } else null
            TrackMeta(
                title = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)?.takeIf { item.isAudio && it.isNotBlank() },
                artist = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)?.takeIf { it.isNotBlank() },
                album = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)?.takeIf { it.isNotBlank() },
                art = art,
            )
        } catch (_: Exception) {
            TrackMeta(null, null, null, null)
        } finally {
            runCatching { r.release() }
        }
    }

    /** ジャケット画像を 512px 程度に縮小して読み込む */
    private fun decodeArt(bytes: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (bounds.outWidth / sample > 512 || bounds.outHeight / sample > 512) sample *= 2
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    /** 画面に表示するタイトル（曲なら埋め込みのタイトルを優先） */
    fun displayTitle(): String = meta?.title ?: currentItem?.title.orEmpty()

    fun displaySubtitle(): String = listOfNotNull(meta?.artist, meta?.album).joinToString(" · ")

    // ---------- 音声フォーカス・イヤホン ----------

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
                    AudioManager.AUDIOFOCUS_LOSS -> {
                        hasFocus = false
                        pause()
                    }
                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                        // 通話中にうっかり再生しても、もう一度フォーカスを求めて断られるようにする
                        hasFocus = false
                        if (player.isPlaying) {
                            pausedByFocus = true
                            pause()
                        }
                    }
                    AudioManager.AUDIOFOCUS_GAIN -> {
                        hasFocus = true
                        if (pausedByFocus) {
                            pausedByFocus = false
                            play()
                        }
                    }
                }
            }
            .build()
    }

    /** 音声フォーカスを求める。もらえなかったら false */
    private fun requestFocus(): Boolean {
        if (!hasFocus) {
            hasFocus = audioManager.requestAudioFocus(focusRequest) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        }
        return hasFocus
    }

    private fun abandonFocus() {
        audioManager.abandonAudioFocusRequest(focusRequest)
        hasFocus = false
    }

    // ---------- Wi-Fi（画面が消えていてもネットワーク再生が途切れないように） ----------

    private fun acquireWifiLock() {
        val lock = wifiLock ?: runCatching {
            @Suppress("DEPRECATION")
            applicationContext.getSystemService(WifiManager::class.java)
                .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "VideoPlayerStream")
                .apply { setReferenceCounted(false) }
        }.getOrNull()?.also { wifiLock = it } ?: return
        runCatching { if (!lock.isHeld) lock.acquire() }
    }

    private fun releaseWifiLock() {
        runCatching { wifiLock?.takeIf { it.isHeld }?.release() }
    }

    /** イヤホンが抜けたら一時停止 */
    private val noisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) pause()
        }
    }

    // ---------- 通知・ロック画面（MediaSession） ----------

    private fun updateSession() {
        val item = currentItem ?: return
        val playing = player.isPlaying
        val state = PlaybackStateCompat.Builder()
            .setActions(
                PlaybackStateCompat.ACTION_PLAY or PlaybackStateCompat.ACTION_PAUSE or
                    PlaybackStateCompat.ACTION_PLAY_PAUSE or PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                    PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or PlaybackStateCompat.ACTION_SEEK_TO or
                    PlaybackStateCompat.ACTION_STOP
            )
            .setState(
                if (playing) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED,
                player.time.coerceAtLeast(0),
                if (playing) rate else 0f,
            )
            .build()
        session.setPlaybackState(state)
        // 曲の情報や通知は、変わったときだけ作り直す（シークのたびにジャケット画像ごと作り直すと重い）
        val metaKey = listOf(item.key, meta?.title, meta?.artist, meta?.album, lengthMs, System.identityHashCode(meta?.art))
        val metaChanged = metaKey != lastMetaKey
        if (metaChanged) {
            lastMetaKey = metaKey
            session.setMetadata(
                MediaMetadataCompat.Builder()
                    .putString(MediaMetadataCompat.METADATA_KEY_TITLE, meta?.title ?: item.title)
                    .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, meta?.artist.orEmpty())
                    .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, meta?.album.orEmpty())
                    .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, lengthMs)
                    .putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, meta?.art)
                    .build()
            )
        }
        session.isActive = true
        if (notificationShown && (metaChanged || playing != lastNotifiedPlaying)) notifyNotification()
    }

    /** 最後に MediaSession に渡した曲の情報・通知に出した再生状態（変わったときだけ作り直すため） */
    private var lastMetaKey: List<Any?>? = null
    private var lastNotifiedPlaying: Boolean? = null

    private fun startForegroundIfNeeded() {
        if (foreground) {
            notifyNotification()
            return
        }
        try {
            ServiceCompat.startForeground(
                this, NOTIFICATION_ID, buildNotification(),
                if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK else 0,
            )
            foreground = true
            notificationShown = true
            lastNotifiedPlaying = player.isPlaying
        } catch (_: Exception) {
            // バックグラウンドからの開始が制限された場合など。通知だけ更新して再生は続ける
            notifyNotification()
        }
    }

    /**
     * 一時停止のまま長い間たったら終わる（前回の続きはファイルに覚えてあるので、
     * 通知・イヤホンのボタンや画面からまた再生できる）。画面で見ている間は待ち続ける
     */
    private val idleStopTask = object : Runnable {
        override fun run() {
            if (player.isPlaying) return
            // 画面で見ている間や、電話で一時停止している間は待ち続ける
            if (videoUiAttached || listeners.isNotEmpty() || pausedByFocus) {
                handler.postDelayed(this, IDLE_STOP_MS)
                return
            }
            stopPlayback()
        }
    }

    private fun scheduleIdleStop() {
        handler.removeCallbacks(idleStopTask)
        handler.postDelayed(idleStopTask, IDLE_STOP_MS)
    }

    private fun notifyNotification() {
        if (currentItem == null) return
        try {
            NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, buildNotification())
            notificationShown = true
            lastNotifiedPlaying = player.isPlaying
        } catch (_: SecurityException) {
        }
    }

    private fun serviceIntent(action: String, code: Int) = PendingIntent.getService(
        this, code, Intent(this, PlaybackService::class.java).setAction(action),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    /** 今の曲・動画の画面を開く Intent */
    fun screenIntent(): Intent {
        val cls = if (currentItem?.isAudio == true) AudioPlayerActivity::class.java else PlayerActivity::class.java
        return Intent(this, cls).putExtra(EXTRA_FROM_SESSION, true)
    }

    private fun buildNotification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "再生中", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "再生中の曲や動画の操作"
                    setShowBadge(false)
                }
            )
        }
        val playing = player.isPlaying
        val open = PendingIntent.getActivity(
            this, 0, screenIntent().addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_music_note)
            .setContentTitle(displayTitle())
            .setContentText(displaySubtitle())
            .setLargeIcon(meta?.art)
            .setContentIntent(open)
            .setDeleteIntent(serviceIntent(ACTION_DISMISS, 5))
            // 再生中だけ消せないようにする（一時停止中はスワイプで消すと終了）
            .setOngoing(playing)
            .setSilent(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .addAction(R.drawable.ic_stat_skip_previous, "前へ", serviceIntent(ACTION_PREVIOUS, 1))
            .addAction(
                if (playing) R.drawable.ic_stat_pause else R.drawable.ic_stat_play,
                if (playing) "一時停止" else "再生",
                serviceIntent(ACTION_PLAY_PAUSE, 2),
            )
            .addAction(R.drawable.ic_stat_skip_next, "次へ", serviceIntent(ACTION_NEXT, 3))
            .addAction(R.drawable.ic_stat_close, "終了", serviceIntent(ACTION_STOP, 4))
            .setStyle(
                androidx.media.app.NotificationCompat.MediaStyle()
                    .setMediaSession(session.sessionToken)
                    .setShowActionsInCompactView(0, 1, 2)
            )
            .build()
    }

    companion object {
        const val EXTRA_FROM_SESSION = "from_session"
        /** startForegroundService で起こしたことを表す（すぐに常駐を始める必要がある） */
        const val EXTRA_FOREGROUND = "foreground"
        /** 一時停止のまま、この時間がたったら終わる */
        private const val IDLE_STOP_MS = 30 * 60_000L
        private const val CHANNEL_ID = "playback"
        private const val NOTIFICATION_ID = 1
        const val ACTION_ENQUEUE = "com.ryose.videoplayer.ENQUEUE"
        const val ACTION_PLAY_NEXT = "com.ryose.videoplayer.PLAY_NEXT"
        const val EXTRA_ITEMS = "items"

        /** 再生キューがあるか（一覧の「次に再生」「キューに追加」を出すかどうかに使う） */
        @Volatile
        var hasQueue = false
            private set

        /** 一覧の画面から、再生中のキューに加える */
        fun enqueueFrom(context: Context, added: List<PlaylistItem>, next: Boolean) {
            val arr = org.json.JSONArray()
            added.forEach { arr.put(it.toJson()) }
            context.startService(
                Intent(context, PlaybackService::class.java)
                    .setAction(if (next) ACTION_PLAY_NEXT else ACTION_ENQUEUE)
                    .putExtra(EXTRA_ITEMS, arr.toString())
            )
        }

        const val ACTION_PLAY_PAUSE = "com.ryose.videoplayer.PLAY_PAUSE"
        const val ACTION_PLAY = "com.ryose.videoplayer.PLAY"
        const val ACTION_NEXT = "com.ryose.videoplayer.NEXT"
        const val ACTION_PREVIOUS = "com.ryose.videoplayer.PREVIOUS"
        private const val ACTION_STOP = "com.ryose.videoplayer.STOP"
        private const val ACTION_DISMISS = "com.ryose.videoplayer.DISMISS"
        private val RESUMING_ACTIONS = setOf(ACTION_PLAY_PAUSE, ACTION_PLAY, ACTION_NEXT, ACTION_PREVIOUS)
    }
}
