package com.ryose.videoplayer

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.storage.StorageManager
import android.provider.MediaStore
import java.io.File

/** 端末内の動画ファイルの検索やフォルダの一覧 */
object MediaFiles {
    val VIDEO_EXT = setOf(
        "mp4", "m4v", "mkv", "webm", "avi", "mov", "wmv", "flv", "f4v", "mpg", "mpeg", "m2v",
        "ts", "m2ts", "mts", "3gp", "3g2", "ogv", "vob", "rm", "rmvb", "asf", "divx", "xvid",
    )

    val AUDIO_EXT = setOf(
        "mp3", "m4a", "m4b", "aac", "flac", "wav", "ogg", "oga", "opus", "wma", "ape", "aif", "aiff",
        "mka", "wv", "tta", "dsf", "dff", "ac3", "dts", "amr", "mpc",
    )

    fun isVideo(f: File) = f.isFile && f.extension.lowercase() in VIDEO_EXT

    fun isAudio(f: File) = f.isFile && f.extension.lowercase() in AUDIO_EXT

    fun isMedia(f: File) = isVideo(f) || isAudio(f)

    fun isAudioName(name: String) = name.substringAfterLast('.', "").lowercase() in AUDIO_EXT

    private fun visibleChildren(dir: File): List<File> =
        dir.listFiles()?.filter { !it.name.startsWith(".") } ?: emptyList()

    data class Root(val dir: File, val name: String)

    /** 内部ストレージ・SDカードなど */
    fun roots(context: Context): List<Root> {
        val sm = context.getSystemService(StorageManager::class.java)
        if (Build.VERSION.SDK_INT >= 30) {
            return sm.storageVolumes
                .filter { it.state == Environment.MEDIA_MOUNTED }
                .mapNotNull { v -> v.directory?.let { Root(it, v.getDescription(context)) } }
        }
        return context.getExternalFilesDirs(null).filterNotNull().mapIndexed { i, f ->
            Root(File(f.path.substringBefore("/Android/data")), if (i == 0) "内部ストレージ" else "SDカード")
        }
    }

    data class Folder(val dir: File, val folderCount: Int, val videoCount: Int, val audioCount: Int)
    data class Listing(val folders: List<Folder>, val media: List<PlaylistItem>)

    fun list(context: Context, dir: File): Listing {
        val children = visibleChildren(dir)
        val folders = children.filter { it.isDirectory }
            .sortedWith(compareBy(NaturalOrder) { it.name })
            .map { d ->
                val sub = visibleChildren(d)
                Folder(d, sub.count { it.isDirectory }, sub.count { isVideo(it) }, sub.count { isAudio(it) })
            }
        return Listing(folders, mediaIn(context, dir, children))
    }

    /** フォルダ直下の動画・音楽（名前順） */
    fun mediaIn(context: Context, dir: File, children: List<File> = visibleChildren(dir)): List<PlaylistItem> {
        val durations = durationsIn(context, dir, MediaStore.Video.Media.EXTERNAL_CONTENT_URI) +
            durationsIn(context, dir, MediaStore.Audio.Media.EXTERNAL_CONTENT_URI)
        return children.filter { isMedia(it) }
            .sortedWith(compareBy(NaturalOrder) { it.name })
            .map { PlaylistItem.fromFile(it, durations[it.path] ?: 0) }
    }

    /** MediaStore に登録されているファイルなら長さが分かるので取ってくる */
    @Suppress("DEPRECATION")
    private fun durationsIn(context: Context, dir: File, collection: Uri): Map<String, Long> = try {
        context.contentResolver.query(
            collection,
            arrayOf(MediaStore.MediaColumns.DATA, MediaStore.MediaColumns.DURATION),
            // このフォルダの直下だけ（サブフォルダの中は含めない）。% と _ はそのままの文字として扱う
            "${MediaStore.MediaColumns.DATA} LIKE ? ESCAPE '\\' AND ${MediaStore.MediaColumns.DATA} NOT LIKE ? ESCAPE '\\'",
            arrayOf("${escapeLike(dir.path)}/%", "${escapeLike(dir.path)}/%/%"),
            null,
        )?.use { c ->
            buildMap {
                while (c.moveToNext()) {
                    val path = c.getString(0) ?: continue
                    put(path, c.getLong(1))
                }
            }
        } ?: emptyMap()
    } catch (_: Exception) {
        emptyMap()
    }

    private fun escapeLike(s: String) = s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")

    /** 音楽ライブラリの1曲 */
    data class Song(
        val item: PlaylistItem,
        val title: String,
        val artist: String,
        val album: String,
        val albumId: Long,
        val track: Int,
    )

    /** 端末内のすべての曲（MediaStore から。着信音などは除く） */
    @Suppress("DEPRECATION")
    fun queryAllSongs(context: Context): List<Song> {
        val collection: Uri =
            if (Build.VERSION.SDK_INT >= 29) MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
            else MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.DATA,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.ALBUM_ID,
            MediaStore.Audio.Media.TRACK,
            MediaStore.Audio.Media.DURATION,
        )
        val result = mutableListOf<Song>()
        try {
            context.contentResolver.query(
                collection, projection, "${MediaStore.Audio.Media.IS_MUSIC} != 0", null, null,
            )?.use { c ->
                val idCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val dataCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA)
                val titleCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                val artistCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
                val albumCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
                val albumIdCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
                val trackCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TRACK)
                val durCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
                while (c.moveToNext()) {
                    val path = c.getString(dataCol)
                    val title = c.getString(titleCol)?.takeIf { it.isNotBlank() }
                        ?: path?.let { File(it).nameWithoutExtension } ?: "(名前なし)"
                    val artist = c.getString(artistCol)?.takeIf { it.isNotBlank() && it != "<unknown>" } ?: "不明なアーティスト"
                    val album = c.getString(albumCol)?.takeIf { it.isNotBlank() } ?: "不明なアルバム"
                    val uri = ContentUris.withAppendedId(collection, c.getLong(idCol))
                    result += Song(
                        item = PlaylistItem(uri, title, path, c.getLong(durCol)),
                        title = title,
                        artist = artist,
                        album = album,
                        albumId = c.getLong(albumIdCol),
                        // TRACK は「ディスク番号×1000＋曲番号」の形式
                        track = c.getInt(trackCol) % 1000,
                    )
                }
            }
        } catch (_: Exception) {
        }
        return result
    }

    /** 端末内のすべての動画（MediaStore から） */
    @Suppress("DEPRECATION")
    fun queryAllVideos(context: Context): List<Video> {
        val collection: Uri =
            if (Build.VERSION.SDK_INT >= 29) MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
            else MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DATA,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.DURATION,
            MediaStore.Video.Media.SIZE,
            MediaStore.Video.Media.BUCKET_DISPLAY_NAME,
            MediaStore.Video.Media.DATE_ADDED,
        )
        val result = mutableListOf<Video>()
        try {
            context.contentResolver.query(collection, projection, null, null, null)?.use { c ->
                val idCol = c.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
                val dataCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.DATA)
                val nameCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
                val durCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
                val sizeCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
                val folderCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.BUCKET_DISPLAY_NAME)
                val dateCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_ADDED)
                while (c.moveToNext()) {
                    result += Video(
                        uri = ContentUris.withAppendedId(collection, c.getLong(idCol)),
                        path = c.getString(dataCol),
                        title = c.getString(nameCol) ?: "(名前なし)",
                        durationMs = c.getLong(durCol),
                        size = c.getLong(sizeCol),
                        folder = c.getString(folderCol) ?: "",
                        dateAdded = c.getLong(dateCol),
                    )
                }
            }
        } catch (_: Exception) {
        }
        return result
    }
}

/** 「2話」が「10話」より前に来るように、数字を数値として比べる並び順（数千件を並べ替えても重くならないよう、文字を順に見て比べる） */
object NaturalOrder : Comparator<String> {
    override fun compare(a: String, b: String): Int {
        var i = 0
        var j = 0
        while (i < a.length && j < b.length) {
            val ca = a[i]
            val cb = b[j]
            if (ca.isAsciiDigit() && cb.isAsciiDigit()) {
                // 数字の並びどうし：先頭の 0 を飛ばして、桁数→各桁の順に比べる
                var si = i
                while (si < a.length && a[si] == '0') si++
                var sj = j
                while (sj < b.length && b[sj] == '0') sj++
                var ei = si
                while (ei < a.length && a[ei].isAsciiDigit()) ei++
                var ej = sj
                while (ej < b.length && b[ej].isAsciiDigit()) ej++
                val lenDiff = (ei - si) - (ej - sj)
                if (lenDiff != 0) return lenDiff
                for (k in 0 until ei - si) {
                    val d = a[si + k] - b[sj + k]
                    if (d != 0) return d
                }
                i = ei
                j = ej
            } else {
                val d = ca.lowercaseChar() - cb.lowercaseChar()
                if (d != 0) return d
                i++
                j++
            }
        }
        return (a.length - i) - (b.length - j)
    }

    private fun Char.isAsciiDigit() = this in '0'..'9'
}
