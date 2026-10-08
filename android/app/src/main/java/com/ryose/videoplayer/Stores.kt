package com.ryose.videoplayer

import android.content.Context
import org.json.JSONArray
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors

/**
 * 再生履歴（新しい順、最大 200 件）。
 * 読み込みは最初の 1 回だけで、あとはメモリ上の一覧を使う。再生サービスと画面の両方から変更されるので、同時に書き換えないようにしている
 */
class HistoryStore(context: Context) {
    data class Entry(val item: PlaylistItem, val playedAt: Long)

    private val prefs = context.applicationContext.getSharedPreferences("history", Context.MODE_PRIVATE)

    fun all(): List<Entry> = synchronized(lock) {
        cache ?: read().also { cache = it }
    }

    private fun read(): List<Entry> = try {
        val arr = JSONArray(prefs.getString(KEY, "[]"))
        (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            Entry(PlaylistItem.fromJson(o), o.optLong("playedAt"))
        }
    } catch (_: Exception) {
        emptyList()
    }

    private fun save(list: List<Entry>) {
        cache = list
        val arr = JSONArray()
        list.forEach { arr.put(it.item.toJson().put("playedAt", it.playedAt)) }
        prefs.edit().putString(KEY, arr.toString()).apply()
    }

    fun add(item: PlaylistItem) = synchronized(lock) {
        val old = all()
        // 長さが分かっていない場合は、以前の記録の長さを引き継ぐ
        val duration = item.durationMs.takeIf { it > 0 } ?: old.find { it.item.key == item.key }?.item?.durationMs ?: 0
        val entry = Entry(item.copy(durationMs = duration), System.currentTimeMillis())
        save((listOf(entry) + old.filterNot { it.item.key == item.key }).take(MAX))
    }

    fun updateDuration(key: String, durationMs: Long) = synchronized(lock) {
        val list = all()
        if (list.none { it.item.key == key && it.item.durationMs != durationMs }) return@synchronized
        save(list.map { if (it.item.key == key) it.copy(item = it.item.copy(durationMs = durationMs)) else it })
    }

    fun remove(key: String) = synchronized(lock) { save(all().filterNot { it.item.key == key }) }

    fun clear() = synchronized(lock) { save(emptyList()) }

    private companion object {
        const val KEY = "entries"
        const val MAX = 200
        val lock = Any()
        var cache: List<Entry>? = null
    }
}

data class SavedPlaylist(val id: String, val name: String, val items: List<PlaylistItem>)

/**
 * プレイリスト（アプリ内の playlists.json に保存）。
 * 読み込みは最初の 1 回だけで、保存はメモリ上の一覧を更新してから裏でファイルに書く（画面が固まらないように）
 */
class PlaylistStore(context: Context) {
    private val file = File(context.applicationContext.filesDir, "playlists.json")

    fun all(): List<SavedPlaylist> = synchronized(lock) {
        cache ?: read().also { cache = it }
    }

    private fun read(): List<SavedPlaylist> = try {
        if (!file.exists()) emptyList() else {
            val arr = JSONArray(file.readText())
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                val items = o.getJSONArray("items")
                SavedPlaylist(
                    id = o.getString("id"),
                    name = o.getString("name"),
                    items = (0 until items.length()).map { PlaylistItem.fromJson(items.getJSONObject(it)) },
                )
            }
        }
    } catch (_: Exception) {
        // 読めなかったファイルは、次に保存したときに上書きして消してしまわないよう別名で残しておく
        runCatching { file.renameTo(File(filesDirOf(file), "playlists.broken-${System.currentTimeMillis()}.json")) }
        emptyList()
    }

    private fun filesDirOf(f: File) = f.parentFile ?: f

    fun get(id: String) = all().find { it.id == id }

    private fun save(list: List<SavedPlaylist>) {
        cache = list
        val arr = JSONArray()
        list.forEach { p ->
            val items = JSONArray()
            p.items.forEach { items.put(it.toJson()) }
            arr.put(org.json.JSONObject().put("id", p.id).put("name", p.name).put("items", items))
        }
        val text = arr.toString()
        writer.execute {
            // 書き込み途中で壊れないよう、一時ファイルに書いてから置き換える
            val tmp = File(file.path + ".tmp")
            tmp.writeText(text)
            if (!tmp.renameTo(file)) {
                file.delete()
                tmp.renameTo(file)
            }
        }
    }

    private fun update(id: String, change: (SavedPlaylist) -> SavedPlaylist) = synchronized(lock) {
        save(all().map { if (it.id == id) change(it) else it })
    }

    fun create(name: String): SavedPlaylist = synchronized(lock) {
        val p = SavedPlaylist(UUID.randomUUID().toString(), name, emptyList())
        save(all() + p)
        p
    }

    fun rename(id: String, name: String) = update(id) { it.copy(name = name) }

    fun delete(id: String) = synchronized(lock) { save(all().filterNot { it.id == id }) }

    /** 重複を除いて追加し、追加できた件数を返す */
    fun addItems(id: String, items: List<PlaylistItem>): Int {
        var added = 0
        update(id) { p ->
            val keys = p.items.map { it.key }.toMutableSet()
            val new = items.filter { keys.add(it.key) }
            added = new.size
            p.copy(items = p.items + new)
        }
        return added
    }

    fun setItems(id: String, items: List<PlaylistItem>) = update(id) { it.copy(items = items) }

    private companion object {
        val lock = Any()
        var cache: List<SavedPlaylist>? = null
        /** ファイルへの書き込みは 1 本ずつ順番に */
        val writer = Executors.newSingleThreadExecutor()
    }
}
