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
import androidx.core.view.MenuProvider
import androidx.lifecycle.Lifecycle
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
    /** 読み込み終わったネットワークのフォルダの中身（場所ごと）。戻ってきたときに待たずに表示する */
    private val netCache = mutableMapOf<String, List<Row>>()
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
                "サーバーから応答がありません。\n\nサーバーの電源やネットワークを確認して、もう一度お試しください。",
                R.drawable.ic_lan,
                actionLabel = "再試行",
                action = ::reloadNet,
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
        requireActivity().addMenuProvider(menuProvider, viewLifecycleOwner, Lifecycle.State.RESUMED)
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
        // 再生から戻ってきたときなどは、スクロール位置を保ったまま読み込み直す
        // （ネットワークのフォルダは覚えておいた中身を出すので、待たされない）
        load(list.layoutManager?.onSaveInstanceState())
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

    // ---------- お気に入り ----------

    /** 上のバーの星：今いる場所をお気に入りに追加・削除する */
    private val menuProvider = object : MenuProvider {
        override fun onCreateMenu(menu: android.view.Menu, menuInflater: android.view.MenuInflater) {
            menuInflater.inflate(R.menu.folders_menu, menu)
        }

        override fun onPrepareMenu(menu: android.view.Menu) {
            val item = menu.findItem(R.id.action_favorite) ?: return
            val loc = current
            item.isVisible = loc != null
            val fav = loc != null && FavoriteFolders(requireContext()).contains(uriOf(loc))
            item.setIcon(if (fav) R.drawable.ic_star else R.drawable.ic_star_border)
            item.title = if (fav) "お気に入りから外す" else "お気に入りに追加"
        }

        override fun onMenuItemSelected(menuItem: android.view.MenuItem): Boolean {
            if (menuItem.itemId != R.id.action_favorite) return false
            current?.let { toggleFavorite(uriOf(it), labelOf(it)) }
            return true
        }
    }

    private fun uriOf(loc: Loc): Uri = when (loc) {
        is Loc.Local -> Uri.fromFile(loc.dir)
        is Loc.Net -> loc.uri
    }

    private fun toggleFavorite(uri: Uri, title: String) {
        val added = FavoriteFolders(requireContext()).toggle(uri, title)
        Toast.makeText(
            requireContext(), if (added) "「$title」をお気に入りに追加しました" else "「$title」をお気に入りから外しました", Toast.LENGTH_SHORT,
        ).show()
        requireActivity().invalidateOptionsMenu()
        if (current == null) renderTop()
    }

    private fun favoriteItem(uri: Uri, title: String): SheetItem {
        val fav = FavoriteFolders(requireContext()).contains(uri)
        return SheetItem(
            if (fav) R.drawable.ic_star else R.drawable.ic_star_border,
            if (fav) "お気に入りから外す" else "お気に入りに追加",
            active = fav,
        ) { toggleFavorite(uri, title) }
    }

    /** お気に入りを開く。端末内のフォルダは、ストレージの一番上からの道順も積んでおく（戻るで一つ上へ） */
    private fun openFavorite(entry: FavoriteFolders.Entry) {
        if (entry.isLocal) {
            val dir = File(entry.uri.path ?: return)
            val root = roots.firstOrNull { dir.path == it.dir.path || dir.path.startsWith(it.dir.path + "/") }
            scrollStates[ROOT_KEY] = list.layoutManager?.onSaveInstanceState()
            stack.clear()
            if (root != null) {
                var d: File? = dir
                val chain = mutableListOf<File>()
                while (d != null && d.path != root.dir.path) {
                    chain += d
                    d = d.parentFile
                }
                stack += Loc.Local(root.dir)
                chain.asReversed().forEach { stack += Loc.Local(it) }
            } else {
                stack += Loc.Local(dir)
            }
            list.scrollToPosition(0)
            load()
        } else {
            push(Loc.Net(entry.uri, entry.title))
        }
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
                textSize = 15f
                minHeight = (48 * resources.displayMetrics.density).toInt()
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                maxWidth = (240 * resources.displayMetrics.density).toInt()
                gravity = Gravity.CENTER_VERTICAL
                setPadding(pad, 0, pad, 0)
                setTextColor(ContextCompat.getColor(ctx, if (isLast) R.color.text_primary else R.color.accent))
                if (isLast) paint.isFakeBoldText = true
                if (!isLast) {
                    setBackgroundResource(android.R.drawable.list_selector_background)
                    setOnClickListener { popTo(level) }
                } else if (current is Loc.Net) {
                    // 今いるネットワークのフォルダの名前をタップすると、読み込み直す
                    setBackgroundResource(android.R.drawable.list_selector_background)
                    setOnClickListener { reloadNet() }
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
        activity?.invalidateOptionsMenu()
        stopBrowser()
        renderPath()
        when (val loc = current) {
            null -> loadTop(restoreScroll)
            is Loc.Local -> loadLocal(loc.dir, restoreScroll)
            is Loc.Net -> loadNet(loc, restoreScroll)
        }
    }

    private fun reloadNet() {
        val loc = current as? Loc.Net ?: return
        netCache.remove(loc.key)
        load()
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
        val favorites = FavoriteFolders(ctx).all()
        if (favorites.isNotEmpty()) {
            rows += Row.Header("お気に入り")
            favorites.forEach { f ->
                val info = if (f.isLocal) f.uri.path.orEmpty() else "${kindLabel(f.uri)} · ${f.uri.host.orEmpty()}${f.uri.path.orEmpty()}"
                rows += Row.Folder(f.title, info, R.drawable.ic_star, id = "fav:${f.uri}")
            }
        }
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
            // 押せる項目ではないので、押せない行にして、探している最中だと分かるようにくるくるを出す
            rows += Row.Folder(
                "同じネットワークの機器を探しています…", "DLNA サーバーや共有フォルダが見つかるとここに出ます", R.drawable.ic_lan,
                id = ROW_SEARCHING, busy = true,
            )
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
    private fun loadNet(loc: Loc.Net, restoreScroll: Parcelable? = null) {
        netCache[loc.key]?.let { cached ->
            showNetRows(cached)
            restoreScroll?.let { list.layoutManager?.onRestoreInstanceState(it) }
            return
        }
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
        showNetRows(sorted)
        loading.visibility = if (finished) View.GONE else View.VISIBLE
        // 最後まで読めた中身は覚えておく（空のときは接続の失敗かもしれないので覚えない）
        if (finished && sorted.isNotEmpty()) current?.let { netCache[it.key] = sorted }
    }

    private fun showNetRows(rows: List<Row>) {
        showRows(
            rows, "何も見つかりませんでした。\n\n空のフォルダか、接続できなかった可能性があります。", R.drawable.ic_lan,
            actionLabel = "再試行",
            action = ::reloadNet,
        )
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
                    id != null && id.startsWith("fav:") -> FavoriteFolders(requireContext()).all()
                        .find { it.uri.toString() == id.removePrefix("fav:") }?.let { openFavorite(it) }
                }
            }
            is Row.Media -> playMediaAt(position)
            else -> {}
        }
    }

    // メニューがある行（フォルダ・お気に入り・サーバー・ネットワークの場所）に︙ボタンを出す
    override fun hasExtraActions(row: Row): Boolean {
        if (row !is Row.Folder) return false
        val id = row.id
        return row.dir != null || (id != null && (id.startsWith("fav:") || id.startsWith("server:") || id.startsWith("net:")))
    }

    override fun extraActions(row: Row): List<SheetItem> {
        if (row !is Row.Folder) return emptyList()
        val id = row.id
        val dir = row.dir
        return when {
            dir != null -> buildList {
                if (!row.isStorage) {
                    add(SheetItem(R.drawable.ic_play, "このフォルダを再生") { withFolderMedia(dir) { requireActivity().playItems(it, 0) } })
                    add(SheetItem(R.drawable.ic_shuffle, "シャッフル再生") {
                        withFolderMedia(dir) { requireActivity().playItems(it, it.indices.random(), shuffle = true) }
                    })
                    add(SheetItem(R.drawable.ic_playlist_add, "プレイリストに追加") {
                        withFolderMedia(dir) { PlaylistDialogs.addToPlaylist(requireContext(), it) }
                    })
                }
                add(favoriteItem(Uri.fromFile(dir), row.name))
            }
            id != null && id.startsWith("fav:") -> {
                val uri = Uri.parse(id.removePrefix("fav:"))
                listOf(
                    SheetItem(R.drawable.ic_edit, "表示名を変更") {
                        PlaylistDialogs.promptName(requireContext(), "表示名を変更", row.name) { name ->
                            FavoriteFolders(requireContext()).rename(uri, name)
                            renderTop()
                        }
                    },
                    SheetItem(R.drawable.ic_star_border, "お気に入りから外す") { toggleFavorite(uri, row.name) },
                )
            }
            id != null && id.startsWith("server:") -> {
                val store = ServerStore(requireContext())
                val server = store.get(id.removePrefix("server:")) ?: return emptyList()
                listOf(
                    favoriteItem(server.uri, server.name),
                    SheetItem(R.drawable.ic_edit, "編集") { NetworkDialogs.editServer(requireActivity(), server) { load() } },
                    SheetItem(R.drawable.ic_delete, "削除") {
                        PlaylistDialogs.confirm(requireContext(), "「${server.name}」の登録を削除しますか？", "削除") {
                            store.remove(server.id)
                            load()
                        }
                    },
                )
            }
            id != null && id.startsWith("net:") -> buildList {
                val uri = Uri.parse(id.removePrefix("net:"))
                add(favoriteItem(uri, row.name))
                // 見つかった共有フォルダを、ログイン情報付きで登録する
                if (current == null && uri.scheme?.lowercase() in Server.PROTOCOLS.keys) {
                    add(SheetItem(R.drawable.ic_login, "ログイン情報を設定して登録") {
                        NetworkDialogs.editServer(requireActivity(), null, uri) { load() }
                    })
                }
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
        const val ROW_SEARCHING = "info:searching"
        const val NET_TIMEOUT_MS = 20_000L
    }
}
