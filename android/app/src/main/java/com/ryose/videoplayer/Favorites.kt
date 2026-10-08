package com.ryose.videoplayer

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject

/**
 * お気に入りのフォルダ（VLC の「お気に入り」と同じ）。
 * 端末内のフォルダは file:// 、ネットワーク上の場所は smb:// などの Uri で覚え、フォルダタブの一番上に並べる
 */
class FavoriteFolders(context: Context) {
    data class Entry(val uri: Uri, val title: String) {
        val isLocal get() = uri.scheme == "file"
    }

    private val prefs = context.applicationContext.getSharedPreferences("favorites", Context.MODE_PRIVATE)

    fun all(): List<Entry> = try {
        val arr = JSONArray(prefs.getString(KEY, "[]"))
        (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            Entry(Uri.parse(o.getString("uri")), o.optString("title"))
        }
    } catch (_: Exception) {
        emptyList()
    }

    private fun save(list: List<Entry>) {
        val arr = JSONArray()
        list.forEach { arr.put(JSONObject().put("uri", it.uri.toString()).put("title", it.title)) }
        prefs.edit().putString(KEY, arr.toString()).apply()
    }

    fun contains(uri: Uri) = all().any { it.uri == uri }

    fun add(uri: Uri, title: String) {
        if (contains(uri)) return
        save(all() + Entry(uri, title))
    }

    fun remove(uri: Uri) = save(all().filterNot { it.uri == uri })

    fun rename(uri: Uri, title: String) = save(all().map { if (it.uri == uri) it.copy(title = title) else it })

    /** 追加・削除を切り替える。追加したら true */
    fun toggle(uri: Uri, title: String): Boolean {
        return if (contains(uri)) {
            remove(uri)
            false
        } else {
            add(uri, title)
            true
        }
    }

    private companion object {
        const val KEY = "folders"
    }
}

/**
 * お気に入りの動画・曲。プレイリストタブの一番上の「お気に入り」プレイリストとして保存する
 * （並べ替えや削除はプレイリストと同じ画面でできる）
 */
object FavoriteMedia {
    const val PLAYLIST_ID = "favorites"
    const val NAME = "お気に入り"

    fun isFavorite(context: Context, item: PlaylistItem): Boolean =
        PlaylistStore(context).get(PLAYLIST_ID)?.items?.any { it.key == item.key } == true

    /** 追加・削除を切り替える。追加したら true */
    fun toggle(context: Context, item: PlaylistItem): Boolean {
        val store = PlaylistStore(context)
        store.ensure(PLAYLIST_ID, NAME)
        val list = store.get(PLAYLIST_ID)?.items.orEmpty()
        return if (list.any { it.key == item.key }) {
            store.setItems(PLAYLIST_ID, list.filterNot { it.key == item.key })
            false
        } else {
            store.addItems(PLAYLIST_ID, listOf(item))
            true
        }
    }
}
