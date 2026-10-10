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

    /** [text] は書き込み用のスレッドで作る（長いリストの JSON づくりで画面を止めないように） */
    fun save(file: File, text: () -> String) {
        executor.execute {
            runCatching {
                val tmp = File(file.path + ".tmp")
                tmp.writeText(text())
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

    data class Saved(
        val items: List<PlaylistItem>,
        val index: Int,
        val shuffle: Boolean,
        /** 再生する順番（並べ替えた・「次に再生」に入れた順番も残す） */
        val order: List<Int>?,
        val advance: Boolean,
        /** 終了ボタンや最後まで再生して、わざと終わらせた */
        val stopped: Boolean,
    ) {
        val current: PlaylistItem? get() = items.getOrNull(index)

        /**
         * 前回の続きとして読み込み直してよいか。[videoOnly]・[audioOnly] で、前回が動画（音楽）だったときだけに限る。
         * わざと終わらせた再生は、[evenIfStopped]（ボタンで再生を頼まれたとき）でなければ戻さない
         */
        fun restorable(videoOnly: Boolean = false, audioOnly: Boolean = false, evenIfStopped: Boolean = false): Boolean {
            val cur = current ?: return false
            if (stopped && !evenIfStopped) return false
            if (videoOnly && cur.isAudio) return false
            if (audioOnly && !cur.isAudio) return false
            return true
        }
    }

    private fun file(context: Context) = File(context.applicationContext.filesDir, "last_session.json")
    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences("last_session", Context.MODE_PRIVATE)

    fun save(context: Context, items: List<PlaylistItem>, index: Int, shuffle: Boolean, order: List<Int>, advance: Boolean) {
        if (index !in items.indices) return
        prefs(context).edit().putBoolean("stopped", false).apply()
        FileSaver.save(file(context)) {
            val arr = JSONArray()
            items.forEach { arr.put(it.toJson()) }
            val ord = JSONArray()
            order.forEach { ord.put(it) }
            JSONObject().put("index", index).put("shuffle", shuffle).put("advance", advance)
                .put("order", ord).put("items", arr).toString()
        }
    }

    /** わざと再生を終わらせた（画面に戻ったときに、勝手に元に戻さない） */
    fun markStopped(context: Context) = prefs(context).edit().putBoolean("stopped", true).apply()

    /** ファイルから読む（時間がかかることがあるので、できるだけ裏のスレッドで呼ぶ） */
    fun load(context: Context): Saved? = runCatching {
        val o = JSONObject(file(context).readText())
        val arr = o.getJSONArray("items")
        val items = (0 until arr.length()).map { PlaylistItem.fromJson(arr.getJSONObject(it)) }
        val order = o.optJSONArray("order")?.let { a -> (0 until a.length()).map { a.getInt(it) } }
            ?.takeIf { it.size == items.size && it.sorted() == items.indices.toList() }
        Saved(
            items, o.getInt("index"), o.optBoolean("shuffle"), order, o.optBoolean("advance", true),
            prefs(context).getBoolean("stopped", false),
        )
    }.getOrNull()?.takeIf { it.current != null }
}
