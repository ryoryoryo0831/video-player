package com.ryose.videoplayer

import android.app.Activity
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.text.InputType
import android.text.TextUtils
import android.view.View
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import org.json.JSONArray
import org.json.JSONObject
import org.videolan.libvlc.Dialog
import java.util.UUID

/** 登録したサーバー（NAS など）。パスワードはアプリ内にだけ保存する */
data class Server(
    val id: String,
    val name: String,
    val scheme: String,
    val host: String,
    val port: Int?,
    val path: String,
    val user: String,
    val password: String,
    val domain: String,
) {
    val uri: Uri
        get() = Uri.Builder()
            .scheme(scheme)
            .encodedAuthority(hostForUri + (port?.let { ":$it" } ?: ""))
            .path(path.ifEmpty { "/" })
            .build()

    /** IPv6 アドレス（fe80::1 など）は URL では [ ] で囲む */
    private val hostForUri: String
        get() = if (':' in host && !host.startsWith("[")) "[$host]" else host

    /** 画面に出す説明（パスワードは出さない） */
    val label: String get() = "${PROTOCOLS[scheme] ?: scheme} · $host${port?.let { ":$it" } ?: ""}${path.takeIf { it != "/" }.orEmpty()}"

    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("name", name).put("scheme", scheme).put("host", host)
        .put("port", port ?: JSONObject.NULL).put("path", path)
        .put("user", user).put("password", password).put("domain", domain)

    companion object {
        /** 登録できる種類 */
        val PROTOCOLS = linkedMapOf(
            "smb" to "SMB（Windows共有・NAS）",
            "ftp" to "FTP",
            "ftps" to "FTPS",
            "sftp" to "SFTP",
            "nfs" to "NFS",
        )

        fun fromJson(o: JSONObject) = Server(
            id = o.getString("id"),
            name = o.optString("name"),
            scheme = o.getString("scheme"),
            host = o.getString("host"),
            port = if (o.isNull("port")) null else o.optInt("port"),
            path = o.optString("path", "/"),
            user = o.optString("user"),
            password = o.optString("password"),
            domain = o.optString("domain"),
        )
    }
}

class ServerStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("servers", Context.MODE_PRIVATE)

    /**
     * パスワードは暗号化して保存し、読み出すときに元に戻す。
     * 元に戻す処理は重いので、一度読んだらアプリを閉じるまで覚えておく（再生のたびに全部を戻さないように）
     */
    fun all(): List<Server> = cache ?: synchronized(lock) {
        // 裏での先読み（preload）と同時に呼ばれても、読み込みは 1 回だけにする
        cache ?: try {
            val arr = JSONArray(prefs.getString("list", "[]"))
            val stored = (0 until arr.length()).map { Server.fromJson(arr.getJSONObject(it)) }
            val list = stored.map { it.copy(password = Secrets.decrypt(it.password)) }
            // 以前のバージョンで暗号化せずに保存したパスワードがあれば、暗号化して保存し直す
            if (stored.any { it.password.isNotEmpty() && !Secrets.isEncrypted(it.password) }) save(list)
            cache = list
            list
        } catch (_: Exception) {
            emptyList()
        }
    }

    /** アプリの起動時に裏のスレッドで呼び、パスワードを戻しておく（画面のスレッドで重い処理をしないように） */
    fun preload() {
        all()
    }

    private companion object {
        @Volatile var cache: List<Server>? = null
        val lock = Any()
    }

    private fun save(list: List<Server>) = synchronized(lock) {
        cache = list
        val arr = JSONArray()
        list.forEach { s ->
            // 暗号化できない（鍵保管庫が使えない）場合は、パスワードを保存しない
            val enc = runCatching { Secrets.encrypt(s.password) }.getOrDefault("")
            arr.put(s.copy(password = enc).toJson())
        }
        prefs.edit().putString("list", arr.toString()).apply()
    }

    fun get(id: String) = all().find { it.id == id }

    fun put(server: Server) = synchronized(lock) {
        val list = all()
        save(if (list.any { it.id == server.id }) list.map { if (it.id == server.id) server else it } else list + server)
    }

    fun remove(id: String) = synchronized(lock) { save(all().filterNot { it.id == id }) }

    /** その Uri と同じサーバー（種類・ホスト・ポートが一致）でログイン情報があるもの */
    fun find(uri: Uri): Server? {
        val scheme = uri.scheme ?: return null
        val host = uri.host ?: return null
        return all().firstOrNull {
            it.scheme.equals(scheme, true) && it.host.equals(host, true) &&
                (it.port == null || uri.port == -1 || it.port == uri.port) && it.user.isNotEmpty()
        }
    }

    /** ログインダイアログで「保存」が選ばれたとき：同じサーバーがあれば上書き、なければ追加 */
    fun saveCredentials(uri: Uri, user: String, password: String) {
        val scheme = uri.scheme ?: return
        val host = uri.host ?: return
        val existing = all().firstOrNull { it.scheme.equals(scheme, true) && it.host.equals(host, true) }
        put(
            existing?.copy(user = user, password = password)
                ?: Server(
                    id = UUID.randomUUID().toString(), name = host, scheme = scheme, host = host,
                    port = uri.port.takeIf { it > 0 }, path = "/", user = user, password = password, domain = "",
                )
        )
    }

    /** VLC に渡すログイン情報のオプション */
    fun optionsFor(uri: Uri): List<String> {
        val s = find(uri) ?: return emptyList()
        val prefix = when (s.scheme.lowercase()) {
            "smb" -> "smb"
            "ftp", "ftps", "ftpes" -> "ftp"
            "sftp" -> "sftp"
            "http", "https" -> "http"
            else -> return emptyList()
        }
        return buildList {
            add(":$prefix-user=${s.user}")
            add(":$prefix-pwd=${s.password}")
            if (prefix == "smb" && s.domain.isNotEmpty()) add(":smb-domain=${s.domain}")
        }
    }
}

/** 最近開いた URL（最大 20 件） */
object UrlHistory {
    fun load(context: Context): List<String> = try {
        val arr = JSONArray(context.getSharedPreferences("urls", Context.MODE_PRIVATE).getString("list", "[]"))
        (0 until arr.length()).map { arr.getString(it) }
    } catch (_: Exception) {
        emptyList()
    }

    fun add(context: Context, url: String) {
        val list = (listOf(url) + load(context).filterNot { it == url }).take(20)
        context.getSharedPreferences("urls", Context.MODE_PRIVATE).edit()
            .putString("list", JSONArray(list).toString()).apply()
    }

    fun clear(context: Context) {
        context.getSharedPreferences("urls", Context.MODE_PRIVATE).edit().remove("list").apply()
    }
}

object NetworkDialogs {

    private val URL_SCHEMES = listOf("http://", "https://", "rtsp://", "rtmp://", "mms://", "udp://", "rtp://", "smb://", "ftp://", "sftp://")

    private fun Context.dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun field(context: Context, hint: String, value: String = "", type: Int = InputType.TYPE_CLASS_TEXT): Pair<TextInputLayout, TextInputEditText> {
        val layout = TextInputLayout(context).apply { this.hint = hint }
        val edit = TextInputEditText(layout.context).apply {
            setText(value)
            inputType = type
            setSingleLine()
        }
        layout.addView(edit)
        return layout to edit
    }

    /** 「URLを開く」：入力欄と最近開いた URL */
    fun openUrl(activity: Activity) {
        val (inputLayout, input) = field(activity, "URL（http://、rtsp:// など）", type = InputType.TYPE_TEXT_VARIATION_URI)
        // クリップボードに URL があれば入れておく
        val clip = runCatching {
            activity.getSystemService(ClipboardManager::class.java).primaryClip?.getItemAt(0)?.text?.toString()?.trim()
        }.getOrNull()
        if (clip != null && URL_SCHEMES.any { clip.startsWith(it, ignoreCase = true) }) {
            input.setText(clip)
            input.setSelection(clip.length)
        }

        val box = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(activity.dp(20), activity.dp(8), activity.dp(20), 0)
            addView(inputLayout)
        }
        var dialog: AlertDialog? = null
        fun play(url: String) {
            dialog?.dismiss()
            playUrl(activity, url)
        }

        val recent = UrlHistory.load(activity)
        if (recent.isNotEmpty()) {
            box.addView(TextView(activity).apply {
                text = "最近開いた URL"
                setTextColor(ContextCompat.getColor(activity, R.color.text_muted))
                textSize = 13f
                setPadding(0, activity.dp(16), 0, activity.dp(4))
            })
            recent.take(8).forEach { url ->
                box.addView(TextView(activity).apply {
                    text = url
                    setTextColor(ContextCompat.getColor(activity, R.color.accent))
                    textSize = 14f
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.MIDDLE
                    setPadding(0, activity.dp(8), 0, activity.dp(8))
                    setOnClickListener { play(url) }
                })
            }
        }

        dialog = MaterialAlertDialogBuilder(activity)
            .setTitle("URLを開く")
            .setView(android.widget.ScrollView(activity).apply { addView(box) })
            .setPositiveButton("再生") { _, _ ->
                val text = input.text?.toString()?.trim().orEmpty()
                if (text.isNotEmpty()) playUrl(activity, text)
            }
            .setNegativeButton("キャンセル", null)
            .apply { if (recent.isNotEmpty()) setNeutralButton("履歴を消去") { _, _ -> UrlHistory.clear(activity) } }
            .show()
        input.requestFocus()
    }

    fun playUrl(activity: Activity, raw: String) {
        val url = if ("://" in raw) raw else "https://$raw"
        val uri = Uri.parse(url)
        if (uri.host.isNullOrEmpty() && uri.scheme !in listOf("udp", "rtp")) {
            Toast.makeText(activity, "URLの形式が正しくありません", Toast.LENGTH_SHORT).show()
            return
        }
        UrlHistory.add(activity, url)
        val title = uri.lastPathSegment?.takeIf { it.isNotBlank() } ?: uri.host ?: url
        activity.playItems(listOf(PlaylistItem(uri, title)), 0)
    }

    /** サーバーの登録・編集 */
    fun editServer(activity: Activity, existing: Server?, prefill: Uri? = null, onSaved: () -> Unit) {
        var scheme = existing?.scheme ?: prefill?.scheme?.lowercase()?.takeIf { it in Server.PROTOCOLS } ?: "smb"
        val (nameL, name) = field(activity, "表示名（省略可）", existing?.name.orEmpty())
        val (hostL, host) = field(activity, "ホスト名またはIPアドレス", existing?.host ?: prefill?.host.orEmpty(), InputType.TYPE_TEXT_VARIATION_URI)
        val (portL, port) = field(activity, "ポート（省略可）", existing?.port?.toString().orEmpty(), InputType.TYPE_CLASS_NUMBER)
        val (pathL, path) = field(activity, "フォルダ（省略可。例：/movies）", existing?.path?.takeIf { it != "/" }.orEmpty())
        val (userL, user) = field(activity, "ユーザー名（省略可）", existing?.user.orEmpty())
        val (passL, pass) = field(
            activity, "パスワード（省略可）", existing?.password.orEmpty(),
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD,
        )
        passL.endIconMode = TextInputLayout.END_ICON_PASSWORD_TOGGLE
        val (domainL, domain) = field(activity, "ドメイン（SMBのみ・省略可）", existing?.domain.orEmpty())

        val typeButton = MaterialButton(activity, null, com.google.android.material.R.attr.materialButtonOutlinedStyle)
        fun refreshType() {
            typeButton.text = "種類：${Server.PROTOCOLS[scheme]}"
            domainL.visibility = if (scheme == "smb") View.VISIBLE else View.GONE
            val noLogin = scheme == "nfs"
            userL.visibility = if (noLogin) View.GONE else View.VISIBLE
            passL.visibility = if (noLogin) View.GONE else View.VISIBLE
        }
        typeButton.setOnClickListener {
            val keys = Server.PROTOCOLS.keys.toList()
            MaterialAlertDialogBuilder(activity)
                .setTitle("種類")
                .setItems(keys.map { Server.PROTOCOLS[it] }.toTypedArray()) { _, i ->
                    scheme = keys[i]
                    refreshType()
                }
                .show()
        }
        refreshType()

        val box = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(activity.dp(20), activity.dp(8), activity.dp(20), 0)
            addView(typeButton)
            listOf(hostL, pathL, userL, passL, domainL, portL, nameL).forEach { addView(it) }
        }

        val dialog = MaterialAlertDialogBuilder(activity)
            .setTitle(if (existing == null) "サーバーを追加" else "サーバーを編集")
            .setView(android.widget.ScrollView(activity).apply { addView(box) })
            .setPositiveButton("保存", null)
            .setNegativeButton("キャンセル", null)
            .show()
        // 入力が足りないときに閉じないよう、保存ボタンは自分で処理する
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val h = host.text?.toString()?.trim().orEmpty()
                .removePrefix("$scheme://").substringBefore('/')
            if (h.isEmpty()) {
                hostL.error = "入力してください"
                return@setOnClickListener
            }
            val p = path.text?.toString()?.trim().orEmpty()
            ServerStore(activity).put(
                Server(
                    id = existing?.id ?: UUID.randomUUID().toString(),
                    name = name.text?.toString()?.trim().orEmpty().ifEmpty { h },
                    scheme = scheme,
                    host = h,
                    port = port.text?.toString()?.toIntOrNull(),
                    path = if (p.isEmpty()) "/" else if (p.startsWith("/")) p else "/$p",
                    user = user.text?.toString()?.trim().orEmpty(),
                    password = pass.text?.toString().orEmpty(),
                    domain = domain.text?.toString()?.trim().orEmpty(),
                )
            )
            dialog.dismiss()
            onSaved()
        }
    }
}

/**
 * VLC が「ログインが必要」「この証明書を信用しますか」などを聞いてきたときに、画面にダイアログを出す。
 * contextUri は「今どこに接続しようとしているか」（ログイン情報を保存するときに使う）。
 */
class VlcDialogHandler(
    private val activity: Activity,
    private val contextUri: () -> Uri?,
) : Dialog.Callbacks {

    private fun Context.dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onDisplay(dialog: Dialog.ErrorMessage) {
        val text = listOfNotNull(dialog.title, dialog.text).joinToString("\n")
        Toast.makeText(activity, text, Toast.LENGTH_LONG).show()
    }

    override fun onDisplay(dialog: Dialog.LoginDialog) {
        val user = EditText(activity).apply {
            hint = "ユーザー名"
            setText(dialog.defaultUsername.orEmpty())
            setSingleLine()
        }
        val pass = EditText(activity).apply {
            hint = "パスワード"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setSingleLine()
        }
        val save = CheckBox(activity).apply {
            text = "このサーバーのログイン情報を保存"
            isChecked = true
        }
        val box = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(activity.dp(20), activity.dp(8), activity.dp(20), 0)
            addView(user)
            addView(pass)
            addView(save)
        }
        val shown = MaterialAlertDialogBuilder(activity)
            .setTitle(dialog.title ?: "ログイン")
            .setMessage(dialog.text)
            .setView(box)
            .setPositiveButton("ログイン") { _, _ ->
                val u = user.text.toString().trim()
                val p = pass.text.toString()
                dialog.postLogin(u, p, save.isChecked)
                if (save.isChecked) contextUri()?.let { ServerStore(activity).saveCredentials(it, u, p) }
            }
            .setNegativeButton("キャンセル") { _, _ -> dialog.dismiss() }
            .setOnCancelListener { dialog.dismiss() }
            .show()
        dialog.context = shown
    }

    override fun onDisplay(dialog: Dialog.QuestionDialog) {
        val builder = MaterialAlertDialogBuilder(activity)
            .setTitle(dialog.title)
            .setMessage(dialog.text)
            .setOnCancelListener { dialog.dismiss() }
        dialog.action1Text?.let { builder.setPositiveButton(it) { _, _ -> dialog.postAction(1) } }
        dialog.action2Text?.let { builder.setNeutralButton(it) { _, _ -> dialog.postAction(2) } }
        builder.setNegativeButton(dialog.cancelText ?: "キャンセル") { _, _ -> dialog.dismiss() }
        dialog.context = builder.show()
    }

    override fun onDisplay(dialog: Dialog.ProgressDialog) {}

    override fun onCanceled(dialog: Dialog?) {
        (dialog?.context as? AlertDialog)?.dismiss()
    }

    override fun onProgressUpdate(dialog: Dialog.ProgressDialog) {}
}
