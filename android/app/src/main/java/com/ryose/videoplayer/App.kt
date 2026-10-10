package com.ryose.videoplayer

import android.app.Application
import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Size
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.decode.DataSource
import coil.decode.ImageSource
import coil.fetch.FetchResult
import coil.fetch.Fetcher
import coil.fetch.SourceResult
import coil.key.Keyer
import coil.request.Options
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import okio.Path.Companion.toOkioPath
import java.io.File

/** 音楽ファイルに埋め込まれたジャケット画像（一覧のサムネイル用） */
data class AudioArt(val path: String)

/** 動画のサムネイル（一覧用）。uri は MediaStore のものなら Android が作ったサムネイルを使える */
data class VideoThumb(val path: String?, val uri: Uri)

class App : Application(), ImageLoaderFactory {
    override fun onCreate() {
        super.onCreate()
        AppSettings.applyTheme(this)
        // 古いデータの片付け（起動を遅くしないように裏で）
        CoroutineScope(Dispatchers.IO).launch {
            runCatching { ThumbCache.trim(this@App) }
            runCatching { ResumeStore(this@App).prune() }
        }
        // 登録したサーバーのパスワードを戻す処理（鍵保管庫）は重いので、画面で使う前に裏で済ませておく
        CoroutineScope(Dispatchers.IO).launch {
            runCatching { ServerStore(this@App).preload() }
        }
    }

    override fun newImageLoader(): ImageLoader =
        ImageLoader.Builder(this)
            .components {
                add(VideoThumbFetcher.Factory()) // 動画のサムネイル
                add(AudioArtFetcher.Factory())   // 音楽のジャケット
                add(Keyer<AudioArt> { data, _ -> "audioart:${data.path}" })
                add(Keyer<VideoThumb> { data, _ -> "thumb:${data.path ?: data.uri}" })
            }
            .crossfade(true)
            .build()
}

/**
 * 作ったサムネイルをファイルに保存しておく場所。
 * 毎回動画から画像を作ると重いので、一度作ったら次からはこのファイルを使う（元のファイルが更新されたら作り直す）
 */
object ThumbCache {
    private const val MAX_FILES = 4000
    private const val SIZE = 384

    private fun dir(context: Context) = File(context.cacheDir, "thumbs").apply { mkdirs() }

    fun fileFor(context: Context, key: String, modified: Long): File =
        File(dir(context), "${key.hashCode().toUInt().toString(16)}_${modified.toString(16)}.jpg")

    /** 作れなかったもの（アプリを閉じるまで覚えておき、表示のたびに作り直そうとしない） */
    private val failed = java.util.Collections.synchronizedSet(HashSet<String>())

    /** キャッシュにあればそれを返し、無ければ make で作って保存する */
    fun getOrCreate(context: Context, key: String, modified: Long, make: () -> Bitmap?): File? {
        val file = fileFor(context, key, modified)
        if (file.length() > 0) {
            file.setLastModified(System.currentTimeMillis())
            return file
        }
        if (file.name in failed) return null
        val bitmap = make() ?: run {
            failed += file.name
            return null
        }
        val scaled = scaleDown(bitmap)
        // 同じサムネイルを同時に作っても混ざらないよう、書き込みごとに別の一時ファイルを使う
        var tmp: File? = null
        val ok = try {
            val t = File.createTempFile("thumb", ".tmp", file.parentFile)
            tmp = t
            t.outputStream().use { scaled.compress(Bitmap.CompressFormat.JPEG, 85, it) } &&
                (t.renameTo(file) || file.length() > 0)
        } catch (_: Exception) {
            false
        } finally {
            if (scaled !== bitmap) scaled.recycle()
            bitmap.recycle()
            // 置き換えに使われなかった一時ファイルは消す
            tmp?.takeIf { it.exists() }?.delete()
        }
        return if (ok && file.length() > 0) file else null
    }

    private fun scaleDown(b: Bitmap): Bitmap {
        val longSide = maxOf(b.width, b.height)
        if (longSide <= SIZE) return b
        val ratio = SIZE.toFloat() / longSide
        return Bitmap.createScaledBitmap(b, (b.width * ratio).toInt().coerceAtLeast(1), (b.height * ratio).toInt().coerceAtLeast(1), true)
    }

    /** 増えすぎたら、しばらく使っていないものから消す */
    fun trim(context: Context) {
        val files = dir(context).listFiles() ?: return
        if (files.size <= MAX_FILES) return
        files.sortedBy { it.lastModified() }.take(files.size - MAX_FILES * 3 / 4).forEach { it.delete() }
    }

    fun source(file: File) = SourceResult(
        source = ImageSource(file.toOkioPath()),
        mimeType = "image/jpeg",
        dataSource = DataSource.DISK,
    )
}

private class VideoThumbFetcher(private val data: VideoThumb, private val options: Options) : Fetcher {
    override suspend fun fetch(): FetchResult {
        val context = options.context
        val modified = data.path?.let { File(it).lastModified() } ?: 0L
        val file = ThumbCache.getOrCreate(context, data.path ?: data.uri.toString(), modified) {
            systemThumbnail(context) ?: frameFromVideo(context)
        } ?: throw IllegalStateException("サムネイルを作れませんでした")
        return ThumbCache.source(file)
    }

    /** Android が作っておいたサムネイル（MediaStore の動画なら速い） */
    private fun systemThumbnail(context: Context): Bitmap? {
        if (Build.VERSION.SDK_INT < 29) return null
        if (data.uri.scheme != ContentResolver.SCHEME_CONTENT || data.uri.authority != MediaStore.AUTHORITY) return null
        return runCatching { context.contentResolver.loadThumbnail(data.uri, Size(512, 512), null) }.getOrNull()
    }

    /** 動画の 1 秒あたりの場面を取り出す */
    private fun frameFromVideo(context: Context): Bitmap? {
        val r = MediaMetadataRetriever()
        return try {
            if (data.path != null && File(data.path).canRead()) r.setDataSource(data.path) else r.setDataSource(context, data.uri)
            val frame = if (Build.VERSION.SDK_INT >= 27) {
                r.getScaledFrameAtTime(1_000_000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 512, 512)
            } else {
                r.getFrameAtTime(1_000_000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            }
            frame ?: r.frameAtTime
        } catch (_: Exception) {
            null
        } finally {
            runCatching { r.release() }
        }
    }

    class Factory : Fetcher.Factory<VideoThumb> {
        override fun create(data: VideoThumb, options: Options, imageLoader: ImageLoader): Fetcher =
            VideoThumbFetcher(data, options)
    }
}

private class AudioArtFetcher(private val data: AudioArt, private val options: Options) : Fetcher {
    override suspend fun fetch(): FetchResult {
        val file = ThumbCache.getOrCreate(options.context, "art:${data.path}", File(data.path).lastModified()) {
            val r = MediaMetadataRetriever()
            val bytes = try {
                r.setDataSource(data.path)
                r.embeddedPicture
            } catch (_: Exception) {
                null
            } finally {
                runCatching { r.release() }
            }
            bytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
        } ?: throw IllegalStateException("ジャケット画像がありません")
        return ThumbCache.source(file)
    }

    class Factory : Fetcher.Factory<AudioArt> {
        override fun create(data: AudioArt, options: Options, imageLoader: ImageLoader): Fetcher =
            AudioArtFetcher(data, options)
    }
}
