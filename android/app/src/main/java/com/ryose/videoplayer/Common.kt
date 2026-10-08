package com.ryose.videoplayer

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.core.content.ContextCompat

data class Video(
    val uri: Uri,
    val path: String?,
    val title: String,
    val durationMs: Long,
    val size: Long,
    val folder: String,
    val dateAdded: Long,
)

/**
 * 再生する1件分。path があればファイルとして直接開き（字幕の自動読み込みもできる）、
 * 無ければ uri（他アプリから渡されたもの等）をファイルディスクリプタ経由で開く。
 */
data class PlaylistItem(val uri: Uri, val title: String, val path: String? = null) {
    /** 続きから再生の記録に使うキー */
    val key: String get() = uri.toString()
}

/** 一覧画面から再生画面へ渡すプレイリスト（件数が多いと Intent に乗らないためメモリで受け渡す） */
object Playlist {
    var items: List<PlaylistItem> = emptyList()
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
        }?.takeIf { java.io.File(it).canRead() }
    } catch (_: Exception) {
        null
    }
}
