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

    fun isVideo(f: File) = f.isFile && f.extension.lowercase() in VIDEO_EXT

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

    data class Folder(val dir: File, val folderCount: Int, val videoCount: Int)
    data class Listing(val folders: List<Folder>, val videos: List<PlaylistItem>)

    fun list(context: Context, dir: File): Listing {
        val children = visibleChildren(dir)
        val folders = children.filter { it.isDirectory }
            .sortedWith(compareBy(NaturalOrder) { it.name })
            .map { d ->
                val sub = visibleChildren(d)
                Folder(d, sub.count { it.isDirectory }, sub.count { isVideo(it) })
            }
        return Listing(folders, videosIn(context, dir, children))
    }

    /** フォルダ直下の動画（名前順） */
    fun videosIn(context: Context, dir: File, children: List<File> = visibleChildren(dir)): List<PlaylistItem> {
        val durations = durationsIn(context, dir)
        return children.filter { isVideo(it) }
            .sortedWith(compareBy(NaturalOrder) { it.name })
            .map { PlaylistItem.fromFile(it, durations[it.path] ?: 0) }
    }

    /** MediaStore に登録されている動画なら長さが分かるので取ってくる */
    @Suppress("DEPRECATION")
    private fun durationsIn(context: Context, dir: File): Map<String, Long> = try {
        context.contentResolver.query(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Video.Media.DATA, MediaStore.Video.Media.DURATION),
            "${MediaStore.Video.Media.DATA} LIKE ?",
            arrayOf("${dir.path}/%"),
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

/** 「2話」が「10話」より前に来るように、数字を数値として比べる並び順 */
object NaturalOrder : Comparator<String> {
    private val chunk = Regex("[0-9]+|[^0-9]+")

    override fun compare(a: String, b: String): Int {
        val x = chunk.findAll(a.lowercase()).map { it.value }.toList()
        val y = chunk.findAll(b.lowercase()).map { it.value }.toList()
        for (i in 0 until minOf(x.size, y.size)) {
            val p = x[i]
            val q = y[i]
            val c = if (p[0] in '0'..'9' && q[0] in '0'..'9') {
                val pn = p.trimStart('0')
                val qn = q.trimStart('0')
                if (pn.length != qn.length) pn.length - qn.length else pn.compareTo(qn)
            } else {
                p.compareTo(q)
            }
            if (c != 0) return c
        }
        return x.size - y.size
    }
}
