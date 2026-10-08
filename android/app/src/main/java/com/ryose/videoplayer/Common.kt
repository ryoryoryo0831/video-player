package com.ryose.videoplayer

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.core.content.ContextCompat
import org.json.JSONObject
import java.io.File

data class Video(
    val uri: Uri,
    val path: String?,
    val title: String,
    val durationMs: Long,
    val size: Long,
    val folder: String,
    val dateAdded: Long,
) {
    fun toItem() = PlaylistItem(uri, title, path, durationMs)
}

/**
 * 再生する1件分。path があればファイルとして直接開き（字幕の自動読み込みもできる）、
 * 無ければ uri（他アプリから渡されたもの等）をファイルディスクリプタ経由で開く。
 */
data class PlaylistItem(
    val uri: Uri,
    val title: String,
    val path: String? = null,
    val durationMs: Long = 0,
) {
    /** 続きから再生・履歴・プレイリストで同じ動画を見分けるためのキー */
    val key: String get() = path ?: uri.toString()

    /** 音楽ファイルかどうか（拡張子で判断） */
    val isAudio: Boolean
        get() = MediaFiles.isAudioName(path ?: (if (isNetwork) uri.lastPathSegment else null) ?: title)

    /** ネットワーク上のもの（URL・NAS・DLNA など） */
    val isNetwork: Boolean get() = path == null && uri.scheme !in LOCAL_SCHEMES

    fun toJson(): JSONObject = JSONObject()
        .put("uri", uri.toString())
        .put("title", title)
        .put("path", path ?: JSONObject.NULL)
        .put("duration", durationMs)

    companion object {
        fun fromJson(o: JSONObject) = PlaylistItem(
            uri = Uri.parse(o.getString("uri")),
            title = o.optString("title", "動画"),
            path = if (o.isNull("path")) null else o.getString("path"),
            durationMs = o.optLong("duration"),
        )

        fun fromFile(file: File, durationMs: Long = 0) =
            PlaylistItem(Uri.fromFile(file), file.name, file.path, durationMs)

        private val LOCAL_SCHEMES = setOf("content", "file", "android.resource", null)
    }
}

/**
 * 一覧画面から再生画面へ渡すプレイリスト（件数が多いと Intent に乗らないためメモリで受け渡す）。
 * アプリが裏で終了させられても再生画面を復元できるように、ファイルにも書いておく
 */
object Playlist {
    var items: List<PlaylistItem> = emptyList()
    var shuffle = false

    private fun file(context: Context) = File(context.applicationContext.filesDir, "queue.json")

    fun set(context: Context, list: List<PlaylistItem>, shuffled: Boolean) {
        items = list
        shuffle = shuffled
        val app = context.applicationContext
        Thread {
            runCatching {
                val arr = org.json.JSONArray()
                list.forEach { arr.put(it.toJson()) }
                val text = JSONObject().put("shuffle", shuffled).put("items", arr).toString()
                val tmp = File(file(app).path + ".tmp")
                tmp.writeText(text)
                if (!tmp.renameTo(file(app))) {
                    file(app).delete()
                    tmp.renameTo(file(app))
                }
            }
        }.start()
    }

    /** メモリから消えていたら（アプリが一度終了していたら）ファイルから読み直す */
    fun restoreIfNeeded(context: Context) {
        if (items.isNotEmpty()) return
        runCatching {
            val o = JSONObject(file(context).readText())
            val arr = o.getJSONArray("items")
            items = (0 until arr.length()).map { PlaylistItem.fromJson(arr.getJSONObject(it)) }
            shuffle = o.optBoolean("shuffle")
        }
    }
}

/** 再生画面を開いて再生を始める（音楽なら音楽の画面、動画なら動画の画面） */
fun Activity.playItems(items: List<PlaylistItem>, index: Int, shuffle: Boolean = false) {
    if (index !in items.indices) return
    Playlist.set(this, items, shuffle)
    val cls = if (items[index].isAudio) AudioPlayerActivity::class.java else PlayerActivity::class.java
    startActivity(
        Intent(this, cls)
            .setData(items[index].uri)
            .putExtra(EXTRA_INDEX, index)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    )
}

const val EXTRA_INDEX = "index"

/**
 * 再生画面に渡された Intent から、再生するリストと開始位置を決める。
 * 一覧画面から来た場合は Playlist に入っているリストを使い、他のアプリから開かれた場合は単体で再生する。
 */
fun Context.playlistFromIntent(intent: Intent): Triple<List<PlaylistItem>, Int, Boolean>? {
    val data = intent.data ?: return null
    Playlist.restoreIfNeeded(this)
    val list = Playlist.items
    val extraIndex = intent.getIntExtra(EXTRA_INDEX, -1)
    val idx = if (extraIndex in list.indices && list[extraIndex].uri == data) extraIndex
    else list.indexOfFirst { it.uri == data }
    if (idx >= 0) return Triple(list, idx, Playlist.shuffle)
    val single = PlaylistItem(data, queryDisplayName(data) ?: data.lastPathSegment ?: "メディア", resolvePath(data))
    return Triple(listOf(single), 0, false)
}

/** 再生位置の記憶（続きから再生） */
class ResumeStore(private val context: Context) {
    private val prefs = context.getSharedPreferences("resume", Context.MODE_PRIVATE)

    fun get(key: String): Long = prefs.getLong(key, 0L)

    fun save(key: String, positionMs: Long, durationMs: Long) {
        if (durationMs <= 0) return
        // 冒頭や最後まで見た場合は記憶しない
        if (positionMs < 3_000 || positionMs > durationMs - 5_000) clear(key)
        else prefs.edit().putLong(key, positionMs).apply()
    }

    fun clear(key: String) {
        prefs.edit().remove(key).apply()
    }

    /** SDカードが抜かれているだけの場合は消さないように、そのストレージ自体が読めるか確かめる */
    private fun volumeAvailable(path: String): Boolean {
        val parts = path.split('/').filter { it.isNotEmpty() }
        // /storage/emulated/0/... は 3 階層、/storage/XXXX-XXXX/... は 2 階層目までがストレージ
        val depth = if (parts.getOrNull(1) == "emulated") 3 else 2
        if (parts.size <= depth) return false
        return File("/" + parts.take(depth).joinToString("/")).listFiles() != null
    }

    /** 消えたファイルの再生位置を片付ける（1 日 1 回まで。裏で呼ぶ） */
    fun prune() {
        val meta = context.getSharedPreferences("resume_meta", Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        if (now - meta.getLong("pruned_at", 0) < 24 * 60 * 60 * 1000L) return
        val gone = prefs.all.keys.filter { it.startsWith("/") && !File(it).exists() && volumeAvailable(it) }
        if (gone.isNotEmpty()) {
            val editor = prefs.edit()
            gone.forEach { editor.remove(it) }
            editor.apply()
        }
        meta.edit().putLong("pruned_at", now).apply()
    }

    /** 旧バージョンのキー（content:// の Uri）で保存された位置を、新しいキー（ファイルパス）へ移す */
    fun migrate(from: String, to: String) {
        if (from == to || !prefs.contains(from)) return
        val pos = prefs.getLong(from, 0L)
        val editor = prefs.edit().remove(from)
        if (!prefs.contains(to)) editor.putLong(to, pos)
        editor.apply()
    }
}

/**
 * 動画・音楽の一覧（MediaStore）を読めるか。
 * 「すべてのファイルへのアクセス」が無くても、「音楽とオーディオ」「写真と動画」の許可があれば読める（Android TV など向け）
 */
fun Context.hasMediaAccess(audio: Boolean): Boolean {
    if (hasStorageAccess()) return true
    fun granted(p: String) = ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED
    return when {
        Build.VERSION.SDK_INT >= 33 && audio -> granted(Manifest.permission.READ_MEDIA_AUDIO)
        Build.VERSION.SDK_INT >= 33 -> granted(Manifest.permission.READ_MEDIA_VIDEO) ||
            (Build.VERSION.SDK_INT >= 34 && granted(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED))
        else -> granted(Manifest.permission.READ_EXTERNAL_STORAGE)
    }
}

/** 動画・音楽の一覧を読むために求める許可 */
fun mediaPermissions(): Array<String> = when {
    Build.VERSION.SDK_INT >= 34 -> arrayOf(
        Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.READ_MEDIA_AUDIO,
        Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
    )
    Build.VERSION.SDK_INT >= 33 -> arrayOf(Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.READ_MEDIA_AUDIO)
    else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
}

/** 端末内のファイルを読めるか（Android 11 以降は「すべてのファイルへのアクセス」） */
fun Context.hasStorageAccess(): Boolean =
    if (Build.VERSION.SDK_INT >= 30) Environment.isExternalStorageManager()
    else ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) ==
        PackageManager.PERMISSION_GRANTED

fun formatTime(ms: Long): String {
    val total = (ms.coerceAtLeast(0) / 1000)
    val h = total / 3600
    val m = total % 3600 / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

fun Context.queryDisplayName(uri: Uri): String? = try {
    contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
        if (c.moveToFirst()) c.getString(0) else null
    }
} catch (_: Exception) {
    null
}

/** content:// や file:// の Uri から実際のファイルパスを探す（見つからなければ null） */
@Suppress("DEPRECATION")
fun Context.resolvePath(uri: Uri): String? {
    if (uri.scheme == "file") return uri.path
    if (uri.authority != MediaStore.AUTHORITY || !(hasMediaAccess(false) || hasMediaAccess(true))) return null
    return try {
        contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.DATA), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }?.takeIf { File(it).canRead() }
    } catch (_: Exception) {
        null
    }
}
