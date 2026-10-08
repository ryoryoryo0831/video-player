package com.ryose.videoplayer

import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Bundle
import android.os.Parcelable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.videolan.libvlc.Media
import org.videolan.libvlc.interfaces.IMedia
import org.videolan.libvlc.util.MediaBrowser
import java.io.File

/**
 * 「フォルダ」タブ：端末のストレージと、ネットワーク（NAS・DLNA サーバー・登録したサーバー）をたどる
 */
class FoldersFragment : BaseListFragment() {

    /** 今いる場所。端末内のフォルダか、ネットワーク上の場所 */
    private sealed class Loc {
        abstract val key: String

        data class Local(val dir: File) : Loc() {
            override val key get() = "L|${dir.path}"
        }

        data class Net(val uri: Uri, val title: String) : Loc() {
            override val key get() = "N|$uri|$title"
        }

        companion object {
            fun parse(s: String): Loc? = when {
                s.startsWith("L|") -> Local(File(s.removePrefix("L|")))
                s.startsWith("N|") -> s.removePrefix("N|").split("|", limit = 2)
                    .takeIf { it.size == 2 }?.let { Net(Uri.parse(it[0]), it[1]) }
                else -> null
            }
        }
    }

    /** たどってきた場所（空ならトップ） */
    private val stack = ArrayList<Loc>()
    private val current get() = stack.lastOrNull()
    @Volatile
    private var roots: List<MediaFiles.Root> = emptyList()
    /** 場所ごとのスクロール位置（戻ったときに元の位置に戻すため） */
    private val scrollStates = mutableMapOf<String, Parcelable?>()

    // ネットワーク
    private var svc: PlaybackService? = null
    private var browser: MediaBrowser? = null
    /** 自動で見つかったネットワーク上のサーバー（uri → 行） */
    private val discovered = linkedMapOf<String, Row.Folder>()
    /** ネットワーク上のフォルダの中身 */
    private val netRows = mutableListOf<Row>()
    private var multicastLock: WifiManager.MulticastLock? = null
    private lateinit var connection: PlaybackConnection
    private val dialogHandler by lazy { VlcDialogHandler(requireActivity()) { (current as? Loc.Net)?.uri } }
    private val renderNetTask = Runnable { renderNet(finished = false) }
    /** サーバーが応答しないまま待ち続けないように、しばらく何も届かなければあきらめる */
    private val netTimeoutTask = Runnable {
        if (current is Loc.Net && netRows.isEmpty() && browser != null && isAdded) {
            stopBrowser()
            showRows(
                emptyList(),
                "サーバーから応答がありません。\n\nサーバーの電源やネットワークを確認して、もう一度開いてください。",
                R.drawable.ic_lan,
            )
        }
    }

    private val backCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() = goUp()
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        savedInstanceState?.getStringArrayList(KEY_STACK)?.mapNotNullTo(stack) { Loc.parse(it) }
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, backCallback)
        // ネットワークの閲覧には VLC のエンジンが必要なので、再生サービスにつなぐ
        connection = PlaybackConnection(requireContext(), autoCreate = true, onConnected = { s ->
            svc = s
            if (isResumed) {
                s.setDialogCallbacks(dialogHandler)
                // 端末内のフォルダは onResume で読み込み済み。ネットワークの閲覧・検出はエンジンが必要なのでここで始める
                if (current !is Loc.Local) load()
            }
        }, onDisconnected = { svc = null })
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putStringArrayList(KEY_STACK, ArrayList(stack.map { it.key }))
    }

    override fun onStart() {
        super.onStart()
        connection.bind()
    }

    override fun onStop() {
        super.onStop()
        connection.unbind()
        svc = null
    }

    override fun onResume() {
        super.onResume()
        svc?.let { it.setDialogCallbacks(dialogHandler) }
        load()
    }

    override fun onPause() {
        super.onPause()
        // 他のタブを見ているときは「戻る」でフォルダを上がらない
        backCallback.isEnabled = false
        stopBrowser()
        svc?.let { it.setDialogCallbacks(null) }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        list.removeCallbacks(renderNetTask)
    }

    // 中に入っているときは、上のバーに場所を表示する
    override fun subtitle(): String? = if (stack.isEmpty()) "ストレージとネットワーク" else null

    /** 場所の表示名（ストレージの一番上なら「内部共有ストレージ」など） */
    private fun labelOf(loc: Loc): String = when (loc) {
        is Loc.Local -> roots.find { it.dir.path == loc.dir.path }?.name ?: loc.dir.name
        is Loc.Net -> loc.title
    }

    /** 「トップ › 内部共有ストレージ › Movies」のような場所の表示。タップでその階層へ戻る */
    private fun renderPath() {
        val view = view ?: return
        val scroll = view.findViewById<HorizontalScrollView>(R.id.pathScroll)
        val bar = view.findViewById<LinearLayout>(R.id.pathBar)
        bar.removeAllViews()
        if (stack.isEmpty()) {
            scroll.visibility = View.GONE
            return
        }
        scroll.visibility = View.VISIBLE
        val ctx = requireContext()
        val pad = (8 * resources.displayMetrics.density).toInt()
        fun segment(text: String, level: Int, isLast: Boolean) {
            if (level > 0) {
                bar.addView(TextView(ctx).apply {
                    this.text = "›"
                    setTextColor(ContextCompat.getColor(ctx, R.color.text_muted))
                    textSize = 16f
                })
            }
            bar.addView(TextView(ctx).apply {
                this.text = text
                textSize = 14f
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                maxWidth = (240 * resources.displayMetrics.density).toInt()
                gravity = Gravity.CENTER_VERTICAL
                setPadding(pad, 0, pad, 0)
                setTextColor(if (isLast) android.graphics.Color.WHITE else ContextCompat.getColor(ctx, R.color.accent))
                if (!isLast) {
                    setBackgroundResource(android.R.drawable.list_selector_background)
                    setOnClickListener { popTo(level) }
                }
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.MATCH_PARENT))
        }
        segment("トップ", 0, false)
        stack.forEachIndexed { i, loc -> segment(labelOf(loc), i + 1, i == stack.lastIndex) }
        // 一番奥（今いる場所）が見えるように右端までスクロール
        scroll.post { scroll.fullScroll(View.FOCUS_RIGHT) }
    }

    /** 場所の表示をタップしたとき：その階層まで戻る */
    private fun popTo(level: Int) {
        if (level >= stack.size) return
        while (stack.size > level) stack.removeAt(stack.lastIndex)
        load(restoreScroll = scrollStates[current?.key ?: ROOT_KEY])
    }

    // ---------- 読み込み ----------

    override fun onFilesChanged() = load(list.layoutManager?.onSaveInstanceState())

    private fun load(restoreScroll: Parcelable? = null) {
        backCallback.isEnabled = isResumed && stack.isNotEmpty()
        stopBrowser()
        renderPath()
        when (val loc = current) {
            null -> loadTop(restoreScroll)
            is Loc.Local -> loadLocal(loc.dir, restoreScroll)
            is Loc.Net -> loadNet(loc)
        }
    }

    /** トップ：ストレージ・登録したサーバー・見つかったサーバー */
    private fun loadTop(restoreScroll: Parcelable?) {
        val ctx = requireContext()
        viewLifecycleOwner.lifecycleScope.launch {
            roots = withContext(Dispatchers.IO) { MediaFiles.roots(ctx) }
            if (current != null) return@launch
            renderTop()
            renderPath()
            restoreScroll?.let { list.layoutManager?.onRestoreInstanceState(it) }
        }
        startDiscovery()
    }

    override fun onGrantClick() {
        (activity as? MainActivity)?.requestStorageAccess()
    }

    override val needPermissionText: Int get() = R.string.need_permission

    private fun renderTop() {
        if (current != null || !isAdded) return
        val ctx = requireContext()
        val rows = mutableListOf<Row>()
        rows += Row.Header("ストレージ")
        if (ctx.hasStorageAccess()) {
            roots.forEach { rows += Row.Folder(it.name, it.dir.path, R.drawable.ic_storage, it.dir, isStorage = true) }
        } else {
            rows += Row.Folder("端末内のファイルを見る", "タップして「すべてのファイルへのアクセス」を許可", R.drawable.ic_storage, id = ACTION_GRANT)
        }
        rows += Row.Header("ネットワーク")
        ServerStore(ctx).all().forEach { rows += Row.Folder(it.name, it.label, R.drawable.ic_server, id = "server:${it.id}") }
        rows += discovered.values
        rows += Row.Folder("サーバーを追加", "NAS（SMB）・FTP・SFTP・NFS", R.drawable.ic_add, id = ACTION_ADD_SERVER)
        rows += Row.Folder("URLを開く", "http・https・rtsp などのストリーミング", R.drawable.ic_link, id = ACTION_OPEN_URL)
        if (discovered.isEmpty()) {
            rows += Row.Folder("同じネットワークの機器を探しています…", "DLNA サーバーや共有フォルダが見つかるとここに出ます", R.drawable.ic_lan)
        }
        showRows(rows, "")
    }

    private fun loadLocal(dir: File, restoreScroll: Parcelable?) {
        val ctx = requireContext()
        if (!ctx.hasStorageAccess()) {
            showNeedPermission()
            return
        }
        viewLifecycleOwner.lifecycleScope.launch {
            val rows = withContext(Dispatchers.IO) {
                if (roots.isEmpty()) roots = MediaFiles.roots(ctx)
                val listing = MediaFiles.list(ctx, dir)
                listing.folders.map { Row.Folder(it.dir.name, folderInfo(it), dir = it.dir) } +
                    listing.media.map { Row.Media(it, "") }
            }
            if ((current as? Loc.Local)?.dir != dir) return@launch
            renderPath()
            showRows(rows, "このフォルダには動画や音楽がありません。", R.drawable.ic_folder)
            restoreScroll?.let { list.layoutManager?.onRestoreInstanceState(it) }
        }
    }

    private fun folderInfo(f: MediaFiles.Folder): String {
        val parts = mutableListOf<String>()
        if (f.folderCount > 0) parts += "${f.folderCount} フォルダ"
        if (f.videoCount > 0) parts += "${f.videoCount} 本の動画"
        if (f.audioCount > 0) parts += "${f.audioCount} 曲"
        return if (parts.isEmpty()) "空" else parts.joinToString(" · ")
    }

    // ---------- ネットワーク ----------

    /** 同じネットワーク上の DLNA サーバーや共有フォルダを自動で探す */
    private fun startDiscovery() {
        val s = svc ?: return
        acquireMulticast()
        browser = MediaBrowser(s.libVLC, object : MediaBrowser.EventListener {
            override fun onMediaAdded(index: Int, media: IMedia) {
                val uri = media.uri ?: return
                val title = media.getMeta(IMedia.Meta.Title)?.takeIf { it.isNotBlank() } ?: uri.host ?: uri.toString()
                discovered[uri.toString()] = Row.Folder(title, kindLabel(uri), R.drawable.ic_lan, id = "net:$uri")
                renderTop()
            }

            override fun onMediaRemoved(index: Int, media: IMedia) {
                media.uri?.let { discovered.remove(it.toString()) }
                renderTop()
            }

            override fun onBrowseEnd() {}
        }).also { it.discoverNetworkShares() }
    }

    private fun kindLabel(uri: Uri) = when (uri.scheme?.lowercase()) {
        "upnp" -> "DLNA / UPnP メディアサーバー"
        "smb" -> "共有フォルダ（SMB）"
        "ftp", "ftps" -> "FTP"
        "sftp" -> "SFTP"
        "nfs" -> "NFS"
        else -> uri.scheme.orEmpty()
    }

    /** ネットワーク上のフォルダの中身を読み込む */
    private fun loadNet(loc: Loc.Net) {
        val s = svc
        if (s == null) {
            // サービスにつながったら onConnected から読み込み直す
            loading.visibility = View.VISIBLE
            return
        }
        netRows.clear()
        adapter.rows = emptyList()
        emptyView.visibility = View.GONE
        list.visibility = View.VISIBLE
        loading.visibility = View.VISIBLE
        updateSubtitle()
        list.removeCallbacks(netTimeoutTask)
        list.postDelayed(netTimeoutTask, NET_TIMEOUT_MS)
        browser = MediaBrowser(s.libVLC, object : MediaBrowser.EventListener {
            override fun onMediaAdded(index: Int, media: IMedia) {
                val uri = media.uri ?: return
                val name = media.getMeta(IMedia.Meta.Title)?.takeIf { it.isNotBlank() }
                    ?: Uri.decode(uri.lastPathSegment ?: uri.toString())
                netRows += if (media.type == IMedia.Type.Directory) {
                    Row.Folder(name, "", R.drawable.ic_folder, id = "net:$uri")
                } else {
                    Row.Media(PlaylistItem(uri, name, durationMs = media.duration.coerceAtLeast(0)), "")
                }
                // まとめて表示を更新する
                list.removeCallbacks(renderNetTask)
                list.postDelayed(renderNetTask, 150)
            }

            override fun onMediaRemoved(index: Int, media: IMedia) {}

            override fun onBrowseEnd() {
                list.removeCallbacks(netTimeoutTask)
                list.removeCallbacks(renderNetTask)
                renderNet(finished = true)
            }
        }).also { b ->
            val media = Media(s.libVLC, loc.uri)
            ServerStore(requireContext()).optionsFor(loc.uri).forEach { media.addOption(it) }
            b.browse(media, MediaBrowser.Flag.Interact or MediaBrowser.Flag.NoSlavesAutodetect)
            media.release()
        }
    }

    private fun renderNet(finished: Boolean) {
        if (current !is Loc.Net || !isAdded) return
        // フォルダを先に、それぞれ名前順
        val sorted = netRows.filterIsInstance<Row.Folder>().sortedWith(compareBy(NaturalOrder) { it.name }) +
            netRows.filterIsInstance<Row.Media>().sortedWith(compareBy(NaturalOrder) { it.item.title })
        if (sorted.isEmpty() && !finished) return
        showRows(sorted, "何も見つかりませんでした。\n\n空のフォルダか、接続できなかった可能性があります。", R.drawable.ic_lan)
        loading.visibility = if (finished) View.GONE else View.VISIBLE
    }

    private fun stopBrowser() {
        list.removeCallbacks(renderNetTask)
        list.removeCallbacks(netTimeoutTask)
        browser?.release()
        browser = null
        releaseMulticast()
    }

    /** 機器の自動検出（mDNS・SSDP）には Wi-Fi のマルチキャスト受信を有効にする必要がある */
    private fun acquireMulticast() {
        if (multicastLock?.isHeld == true) return
        multicastLock = runCatching {
            requireContext().applicationContext.getSystemService(WifiManager::class.java)
                .createMulticastLock("VideoPlayer").apply {
                    setReferenceCounted(false)
                    acquire()
                }
        }.getOrNull()
    }

    private fun releaseMulticast() {
        runCatching { multicastLock?.release() }
        multicastLock = null
    }

    // ---------- 移動 ----------

    private fun push(loc: Loc) {
        scrollStates[current?.key ?: ROOT_KEY] = list.layoutManager?.onSaveInstanceState()
        stack += loc
        list.scrollToPosition(0)
        load()
    }

    private fun goUp() {
        if (stack.isEmpty()) return
        val leaving = stack.removeAt(stack.lastIndex)
        // 端末内のフォルダは、親フォルダへ（ストレージの一番上ならトップへ）
        if (leaving is Loc.Local && stack.isEmpty() && roots.none { it.dir.path == leaving.dir.path }) {
            leaving.dir.parentFile?.let { stack += Loc.Local(it) }
        }
        load(restoreScroll = scrollStates.remove(current?.key ?: ROOT_KEY))
    }

    override fun onRowClick(position: Int) {
        when (val row = adapter.rows.getOrNull(position)) {
            is Row.Folder -> {
                val id = row.id
                when {
                    row.dir != null -> push(Loc.Local(row.dir))
                    id == ACTION_GRANT -> (activity as? MainActivity)?.requestStorageAccess()
                    id == ACTION_ADD_SERVER -> NetworkDialogs.editServer(requireActivity(), null) { load() }
                    id == ACTION_OPEN_URL -> NetworkDialogs.openUrl(requireActivity())
                    id != null && id.startsWith("server:") -> ServerStore(requireContext()).get(id.removePrefix("server:"))
                        ?.let { push(Loc.Net(it.uri, it.name)) }
                    id != null && id.startsWith("net:") -> push(Loc.Net(Uri.parse(id.removePrefix("net:")), row.name))
                }
            }
            is Row.Media -> playMediaAt(position)
            else -> {}
        }
    }

    override fun extraActions(row: Row): List<Pair<String, () -> Unit>> {
        if (row !is Row.Folder) return emptyList()
        val id = row.id
        val dir = row.dir
        return when {
            dir != null && !row.isStorage -> listOf(
                "このフォルダを再生" to { withFolderMedia(dir) { requireActivity().playItems(it, 0) } },
                "シャッフル再生" to { withFolderMedia(dir) { requireActivity().playItems(it, it.indices.random(), shuffle = true) } },
                "プレイリストに追加" to { withFolderMedia(dir) { PlaylistDialogs.addToPlaylist(requireContext(), it) } },
            )
            id != null && id.startsWith("server:") -> {
                val store = ServerStore(requireContext())
                val server = store.get(id.removePrefix("server:")) ?: return emptyList()
                listOf(
                    "編集" to { NetworkDialogs.editServer(requireActivity(), server) { load() } },
                    "削除" to {
                        PlaylistDialogs.confirm(requireContext(), "「${server.name}」の登録を削除しますか？", "削除") {
                            store.remove(server.id)
                            load()
                        }
                    },
                )
            }
            // 見つかった共有フォルダを、ログイン情報付きで登録する
            id != null && id.startsWith("net:") && current == null -> {
                val uri = Uri.parse(id.removePrefix("net:"))
                if (uri.scheme?.lowercase() in Server.PROTOCOLS.keys) {
                    listOf("ログイン情報を設定して登録" to { NetworkDialogs.editServer(requireActivity(), null, uri) { load() } })
                } else emptyList()
            }
            else -> emptyList()
        }
    }

    /** フォルダ直下の動画・音楽を読み込んでから処理する（空なら知らせる） */
    private fun withFolderMedia(dir: File, action: (List<PlaylistItem>) -> Unit) {
        val ctx = requireContext()
        viewLifecycleOwner.lifecycleScope.launch {
            val media = withContext(Dispatchers.IO) { MediaFiles.mediaIn(ctx, dir) }
            if (media.isEmpty()) {
                Toast.makeText(ctx, "このフォルダの直下には動画や音楽がありません", Toast.LENGTH_SHORT).show()
            } else {
                action(media)
            }
        }
    }

    private companion object {
        const val KEY_STACK = "stack"
        const val ROOT_KEY = "<root>"
        const val ACTION_GRANT = "action:grant"
        const val ACTION_ADD_SERVER = "action:add_server"
        const val ACTION_OPEN_URL = "action:open_url"
        const val NET_TIMEOUT_MS = 20_000L
    }
}
