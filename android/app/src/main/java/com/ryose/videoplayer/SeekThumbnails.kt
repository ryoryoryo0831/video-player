package com.ryose.videoplayer

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.os.Build
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.Executors

/**
 * シークバーを動かしている間に出す、その位置の小さな画像を作る。
 * 端末内のファイルだけ（ネットワーク上の動画は画像を作れないので、映像そのものを動かして見せる）
 */
class SeekThumbnails(private val context: Context) {

    /** 画像づくりは 1 本のスレッドで順番に行う（同じファイルを同時に読まないように） */
    private val executor = Executors.newSingleThreadExecutor()
    private val worker = executor.asCoroutineDispatcher()

    private var retriever: MediaMetadataRetriever? = null
    private var sourceKey: String? = null
    /** 今のファイルは画像を作れなかった（端末が対応していない形式など） */
    private var failedKey: String? = null
    /** 今のファイルで画像を作れたことがあるか・続けて作れなかった回数 */
    private var anyFrame = false
    private var misses = 0

    /** 画像を作れそうな動画か */
    fun supports(item: PlaylistItem?): Boolean =
        item != null && !item.isNetwork && !item.isAudio && item.key != failedKey

    /** [ms] の位置の画像（作れなければ null） */
    suspend fun frameAt(item: PlaylistItem, ms: Long, maxW: Int, maxH: Int): Bitmap? = withContext(worker) {
        if (!supports(item)) return@withContext null
        if (item.key != sourceKey) {
            releaseNow()
            sourceKey = item.key
            anyFrame = false
            misses = 0
        }
        val r = retriever ?: open(item)?.also { retriever = it }
        if (r == null) {
            failedKey = item.key
            return@withContext null
        }
        val us = ms * 1000
        val frame = runCatching {
            if (Build.VERSION.SDK_INT >= 27) {
                r.getScaledFrameAtTime(us, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, maxW, maxH)
            } else {
                r.getFrameAtTime(us, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)?.let { scaleDown(it, maxW, maxH) }
            }
        }.getOrNull()
        if (frame != null) {
            anyFrame = true
            misses = 0
        } else if (!anyFrame && ++misses >= 2) {
            // ファイルは開けても画像を取り出せない形式（端末のデコーダが対応していないなど）
            failedKey = item.key
        }
        frame
    }

    /** 画面を閉じるときに後片付けする */
    fun release() {
        CoroutineScope(worker).launch {
            releaseNow()
            executor.shutdown()
        }
    }

    private fun open(item: PlaylistItem): MediaMetadataRetriever? {
        val r = MediaMetadataRetriever()
        return try {
            val path = item.path
            if (path != null && File(path).canRead()) r.setDataSource(path) else r.setDataSource(context, item.uri)
            r
        } catch (_: Exception) {
            runCatching { r.release() }
            null
        }
    }

    private fun releaseNow() {
        runCatching { retriever?.release() }
        retriever = null
        sourceKey = null
    }

    private fun scaleDown(b: Bitmap, maxW: Int, maxH: Int): Bitmap {
        val ratio = minOf(maxW.toFloat() / b.width, maxH.toFloat() / b.height, 1f)
        if (ratio >= 1f) return b
        val scaled = Bitmap.createScaledBitmap(b, (b.width * ratio).toInt().coerceAtLeast(1), (b.height * ratio).toInt().coerceAtLeast(1), true)
        if (scaled != b) b.recycle()
        return scaled
    }
}
