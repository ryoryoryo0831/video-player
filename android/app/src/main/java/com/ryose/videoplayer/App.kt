package com.ryose.videoplayer

import android.app.Application
import android.media.MediaMetadataRetriever
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.decode.DataSource
import coil.decode.ImageSource
import coil.decode.VideoFrameDecoder
import coil.fetch.FetchResult
import coil.fetch.Fetcher
import coil.fetch.SourceResult
import coil.key.Keyer
import coil.request.Options
import okio.Buffer

/** 音楽ファイルに埋め込まれたジャケット画像（一覧のサムネイル用） */
data class AudioArt(val path: String)

class App : Application(), ImageLoaderFactory {
    override fun newImageLoader(): ImageLoader =
        ImageLoader.Builder(this)
            .components {
                add(VideoFrameDecoder.Factory()) // 動画のサムネイル
                add(AudioArtFetcher.Factory())   // 音楽のジャケット
                add(Keyer<AudioArt> { data, _ -> "audioart:${data.path}" })
            }
            .crossfade(true)
            .build()
}

private class AudioArtFetcher(private val data: AudioArt, private val options: Options) : Fetcher {
    override suspend fun fetch(): FetchResult {
        val r = MediaMetadataRetriever()
        val bytes = try {
            r.setDataSource(data.path)
            r.embeddedPicture
        } finally {
            runCatching { r.release() }
        } ?: throw IllegalStateException("ジャケット画像がありません")
        return SourceResult(
            source = ImageSource(Buffer().write(bytes), options.context),
            mimeType = null,
            dataSource = DataSource.DISK,
        )
    }

    class Factory : Fetcher.Factory<AudioArt> {
        override fun create(data: AudioArt, options: Options, imageLoader: ImageLoader): Fetcher =
            AudioArtFetcher(data, options)
    }
}
