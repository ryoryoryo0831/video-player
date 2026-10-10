package com.ryose.videoplayer

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors

/**
 * ファイルへの書き込みを 1 本のスレッドで順番に行う。
 * 続けて保存しても書きかけのファイル同士がぶつからないように、いったん別名で書いてから置き換える
 */
object FileSaver {
    private val executor = Executors.newSingleThreadExecutor()

    fun save(file: File, text: String) {
        executor.execute {
            runCatching {
                val tmp = File(file.path + ".tmp")
                tmp.writeText(text)
                if (!tmp.renameTo(file)) {
                    file.delete()
                    tmp.renameTo(file)
                }
            }
        }
    }
}

/**
 * 前回の再生（再生キューと、その中のどれを再生していたか）。
 * 再生サービスが OS に止められたあとや、イヤホンの再生ボタンで、前回の続きから再生し直すために覚えておく
 */
object LastSession {

    data class Saved(val items: List<PlaylistItem>, val index: Int, val shuffle: Boolean) {
        val current: PlaylistItem? get() = items.getOrNull(index)
    }

    private fun file(context: Context) = File(context.applicationContext.filesDir, "last_session.json")

    fun save(context: Context, items: List<PlaylistItem>, index: Int, shuffle: Boolean) {
        if (index !in items.indices) return
        val arr = JSONArray()
        items.forEach { arr.put(it.toJson()) }
        val text = JSONObject().put("index", index).put("shuffle", shuffle).put("items", arr).toString()
        FileSaver.save(file(context), text)
    }

    fun load(context: Context): Saved? = runCatching {
        val o = JSONObject(file(context).readText())
        val arr = o.getJSONArray("items")
        val items = (0 until arr.length()).map { PlaylistItem.fromJson(arr.getJSONObject(it)) }
        Saved(items, o.getInt("index"), o.optBoolean("shuffle"))
    }.getOrNull()?.takeIf { it.current != null }
}
