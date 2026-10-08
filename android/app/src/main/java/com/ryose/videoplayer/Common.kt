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
    val isAudio: Boolean get() = MediaFiles.isAudioName(path ?: title)

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
    }
}

/** 一覧画面から再生画面へ渡すプレイリスト（件数が多いと Intent に乗らないためメモリで受け渡す） */
object Playlist {
    var items: List<PlaylistItem> = emptyList()
    var shuffle = false
}

/** 再生画面を開いて再生を始める（音楽なら音楽の画面、動画なら動画の画面） */
fun Activity.playItems(items: List<PlaylistItem>, index: Int, shuffle: Boolean = false) {
    if (index !in items.indices) return
    Playlist.items = items
    Playlist.shuffle = shuffle
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
    val list = Playlist.items
    val extraIndex = intent.getIntExtra(EXTRA_INDEX, -1)
    val idx = if (extraIndex in list.indices && list[extraIndex].uri == data) extraIndex
    else list.indexOfFirst { it.uri == data }
    if (idx >= 0) return Triple(list, idx, Playlist.shuffle)
    val single = PlaylistItem(data, queryDisplayName(data) ?: data.lastPathSegment ?: "メディア", resolvePath(data))
    return Triple(listOf(single), 0, false)
}

/** 再生位置の記憶（続きから再生） */
class ResumeStore(context: Context) {
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

    /** 旧バージョンのキー（content:// の Uri）で保存された位置を、新しいキー（ファイルパス）へ移す */
    fun migrate(from: String, to: String) {
        if (from == to || !prefs.contains(from)) return
        val pos = prefs.getLong(from, 0L)
        val editor = prefs.edit().remove(from)
        if (!prefs.contains(to)) editor.putLong(to, pos)
        editor.apply()
    }
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
    if (uri.authority != MediaStore.AUTHORITY || !hasStorageAccess()) return null
    return try {
        contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.DATA), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }?.takeIf { File(it).canRead() }
    } catch (_: Exception) {
        null
    }
}
