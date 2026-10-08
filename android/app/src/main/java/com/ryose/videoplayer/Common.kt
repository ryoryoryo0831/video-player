package com.ryose.videoplayer

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns

data class Video(
    val uri: Uri,
    val title: String,
    val durationMs: Long,
    val size: Long,
    val folder: String,
    val dateAdded: Long,
)

data class PlaylistItem(val uri: Uri, val title: String)

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
