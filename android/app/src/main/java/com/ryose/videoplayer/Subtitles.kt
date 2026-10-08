package com.ryose.videoplayer

import android.content.Context
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/**
 * 外部字幕ファイルの検索と、文字コードの変換。
 * 日本語の字幕は Shift_JIS で作られていることが多く、そのままだと文字化けするので UTF-8 に変換してから渡す。
 */
object Subtitles {
    private val TEXT_EXT = setOf("srt", "ass", "ssa", "vtt", "smi", "sami")
    private val ALL_EXT = TEXT_EXT + setOf("sub", "idx")
    private const val MAX_TEXT_SIZE = 10L * 1024 * 1024

    /** 動画と同じフォルダにある、ファイル名が同じ字幕（例: movie.srt, movie.ja.srt）を探す。日本語らしいものを先頭に */
    fun findFor(videoPath: String): List<File> {
        val video = File(videoPath)
        val dir = video.parentFile ?: return emptyList()
        val base = video.nameWithoutExtension.lowercase()
        val found = dir.listFiles { f ->
            f.isFile && f.extension.lowercase() in ALL_EXT && f.name.lowercase().startsWith(base)
        } ?: return emptyList()
        // VobSub は .idx を読めば .sub も一緒に読まれるので .sub 単体は除く
        val idxBases = found.filter { it.extension.equals("idx", true) }.map { it.nameWithoutExtension }
        return found
            .filterNot { it.extension.equals("sub", true) && it.nameWithoutExtension in idxBases }
            .sortedWith(compareByDescending<File> { languageScore(it.name) }.thenBy { it.name.length })
    }

    private fun languageScore(name: String): Int {
        val n = name.lowercase()
        return when {
            listOf(".ja.", ".jp.", ".jpn.", "japanese", "日本語").any { it in n } -> 2
            listOf(".en.", ".eng.", "english").any { it in n } -> 0
            else -> 1
        }
    }

    /** VLC に渡せる字幕ファイルを返す。文字コードの変換が必要ならキャッシュに UTF-8 版を作る */
    fun prepare(context: Context, file: File): File {
        if (file.extension.lowercase() !in TEXT_EXT || file.length() > MAX_TEXT_SIZE) return file
        return try {
            val converted = toUtf8IfNeeded(file.readBytes()) ?: return file
            writeCache(context, file.name, converted)
        } catch (_: Exception) {
            file
        }
    }

    /** 「字幕ファイルを追加」で選ばれたファイルの中身をキャッシュに保存して返す */
    fun saveToCache(context: Context, name: String, bytes: ByteArray): File {
        val ext = name.substringAfterLast('.', "").lowercase()
        val data = if (ext in TEXT_EXT) toUtf8IfNeeded(bytes) ?: bytes else bytes
        return writeCache(context, name, data)
    }

    private fun writeCache(context: Context, name: String, data: ByteArray): File {
        val dir = File(context.cacheDir, "subtitles").apply { mkdirs() }
        val safeName = name.replace(Regex("[\\\\/:*?\"<>|]"), "_")
        return File(dir, safeName).apply { writeBytes(data) }
    }

    /** UTF-8 でなければ Shift_JIS（Windows-31J）として読み直して UTF-8 にする。変換不要なら null */
    private fun toUtf8IfNeeded(bytes: ByteArray): ByteArray? {
        if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) return null
        if (bytes.size >= 2 && (bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte() ||
                bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte())
        ) return null
        if (isValidUtf8(bytes)) return null
        val sjis = runCatching { Charset.forName("windows-31j") }.getOrElse { Charset.forName("Shift_JIS") }
        return String(bytes, sjis).toByteArray(Charsets.UTF_8)
    }

    private fun isValidUtf8(bytes: ByteArray): Boolean = try {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
        true
    } catch (_: CharacterCodingException) {
        false
    }
}
