package com.ryose.videoplayer

import android.content.Context
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
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
    /** 「字幕ファイルを追加」で読み込む上限（VobSub の .sub は大きいことがあるので少し余裕を持たせる） */
    private const val MAX_ADD_SIZE = 64L * 1024 * 1024
    /** キャッシュに作った字幕を消すまでの日数 */
    private const val CACHE_DAYS = 14

    /** 手で選べる文字コード（表示名 → 文字コード名） */
    val CHARSETS = listOf(
        "UTF-8" to "UTF-8",
        "Shift_JIS（日本語）" to "windows-31j",
        "EUC-JP（日本語）" to "EUC-JP",
        "GB18030（中国語・簡体字）" to "GB18030",
        "Big5（中国語・繁体字）" to "Big5",
        "EUC-KR（韓国語）" to "EUC-KR",
        "Windows-1252（英語・欧米の言語）" to "windows-1252",
    )

    fun isSubtitleName(name: String) = name.substringAfterLast('.', "").lowercase() in ALL_EXT

    /** 上限までしか読まない。上限を超えるファイルなら null */
    fun readLimited(input: InputStream): ByteArray? {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            total += n
            if (total > MAX_ADD_SIZE) return null
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    /** 動画と同じフォルダにある、ファイル名が同じ字幕（例: movie.srt, movie.ja.srt）を探す。日本語らしいものを先頭に */
    fun findFor(videoPath: String): List<File> {
        val video = File(videoPath)
        val dir = video.parentFile ?: return emptyList()
        val base = video.nameWithoutExtension.lowercase()
        val found = dir.listFiles { f ->
            if (!f.isFile || f.extension.lowercase() !in ALL_EXT) return@listFiles false
            // movie.srt / movie.ja.srt は対象。movie2.srt のように別の動画の字幕は対象外
            val stem = f.nameWithoutExtension.lowercase()
            stem == base || stem.startsWith("$base.")
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

    /**
     * VLC に渡せる字幕ファイルを返す。文字コードの変換が必要ならキャッシュに UTF-8 版を作る。
     * [charset] を指定したら（文字化けしたので手で選んだときは）、推測せずにその文字コードとして読む
     */
    fun prepare(context: Context, file: File, charset: String? = null): File {
        if (file.extension.lowercase() !in TEXT_EXT || file.length() > MAX_TEXT_SIZE) return file
        return try {
            val bytes = file.readBytes()
            val converted = if (charset != null) String(bytes, Charset.forName(charset)).toByteArray(Charsets.UTF_8)
            else toUtf8IfNeeded(bytes) ?: return file
            writeCache(context, file.path, file.name, converted)
        } catch (_: Exception) {
            file
        }
    }

    /** 「字幕ファイルを追加」で選ばれたファイルの中身をキャッシュに保存して返す（source は元の場所。同じ名前の別の字幕と区別する） */
    fun saveToCache(context: Context, source: String, name: String, bytes: ByteArray): File {
        val ext = name.substringAfterLast('.', "").lowercase()
        val data = if (ext in TEXT_EXT) toUtf8IfNeeded(bytes) ?: bytes else bytes
        return writeCache(context, source, name, data)
    }

    /**
     * 字幕をキャッシュに書き出す。元の場所ごとにフォルダを分けるので、別の動画の同じ名前の字幕（movie.srt など）で上書きされない。
     * VobSub（.idx と .sub）は同じフォルダに同じ名前で並んでいる必要があるので、ファイル名はそのまま使う
     */
    private fun writeCache(context: Context, source: String, name: String, data: ByteArray): File {
        val root = File(context.cacheDir, "subtitles")
        cleanOldCache(root)
        val key = source.hashCode().toUInt().toString(16)
        val dir = File(root, key).apply { mkdirs() }
        val safeName = name.replace(Regex("[\\\\/:*?\"<>|]"), "_")
        return File(dir, safeName).apply { writeBytes(data) }
    }

    /** しばらく使っていないキャッシュの字幕を消す */
    private fun cleanOldCache(root: File) {
        val limit = System.currentTimeMillis() - CACHE_DAYS * 24L * 60 * 60 * 1000
        root.walkBottomUp().forEach { f ->
            if (f == root) return@forEach
            if (f.isFile && f.lastModified() < limit) f.delete()
            else if (f.isDirectory && f.list()?.isEmpty() == true) f.delete()
        }
    }

    /** UTF-8 でなければ Shift_JIS（Windows-31J）として読み直して UTF-8 にする。変換不要なら null */
    private fun toUtf8IfNeeded(bytes: ByteArray): ByteArray? {
        if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) return null
        if (bytes.size >= 2 && (bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte() ||
                bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte())
        ) return null
        if (isValidUtf8(bytes)) return null
        return String(bytes, detectCharset(bytes)).toByteArray(Charsets.UTF_8)
    }

    /**
     * UTF-8 ではない字幕の文字コードを推測する。
     * 日本語（Shift_JIS・EUC-JP）を優先し、日本語らしくなければ欧米の字幕でよく使われる Windows-1252 とみなす
     */
    private fun detectCharset(bytes: ByteArray): Charset {
        val sjis = runCatching { Charset.forName("windows-31j") }.getOrElse { Charset.forName("Shift_JIS") }
        for (cs in listOfNotNull(sjis, runCatching { Charset.forName("EUC-JP") }.getOrNull())) {
            val text = strictDecode(bytes, cs) ?: continue
            if (looksJapanese(text)) return cs
        }
        // ほとんどが半角英数字なら、欧米の言語（アクセント付きの文字などだけが 0x80 以上）
        val high = bytes.count { it < 0 }
        if (high * 10 < bytes.size) runCatching { Charset.forName("windows-1252") }.getOrNull()?.let { return it }
        return sjis
    }

    private fun strictDecode(bytes: ByteArray, cs: Charset): String? = try {
        cs.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    } catch (_: CharacterCodingException) {
        null
    }

    /** ひらがな・カタカナが一定以上含まれていれば日本語とみなす */
    private fun looksJapanese(text: String): Boolean {
        val kana = text.count { it in '\u3040'..'\u30FF' }
        return kana >= 10 || (text.isNotEmpty() && kana * 50 >= text.length)
    }

    private fun isValidUtf8(bytes: ByteArray): Boolean = strictDecode(bytes, Charsets.UTF_8) != null
}
