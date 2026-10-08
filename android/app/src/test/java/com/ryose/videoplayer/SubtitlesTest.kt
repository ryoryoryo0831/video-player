package com.ryose.videoplayer

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files

class SubtitlesTest {
    private lateinit var dir: File

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("subs").toFile()
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    private fun touch(name: String) = File(dir, name).apply { writeText("1") }

    @Test
    fun 同じ名前の字幕だけを見つける() {
        val video = touch("movie.mp4")
        touch("movie.srt")
        touch("movie.ja.srt")
        touch("movie2.srt") // 別の動画の字幕
        touch("movie.txt")  // 字幕ではない
        val found = Subtitles.findFor(video.path).map { it.name }.toSet()
        assertEquals(setOf("movie.srt", "movie.ja.srt"), found)
    }

    @Test
    fun 日本語の字幕を先頭にする() {
        val video = touch("a.mkv")
        touch("a.en.srt")
        touch("a.ja.srt")
        assertEquals("a.ja.srt", Subtitles.findFor(video.path).first().name)
    }

    @Test
    fun VobSubのsubはidxがあれば除く() {
        val video = touch("b.avi")
        touch("b.idx")
        touch("b.sub")
        assertEquals(listOf("b.idx"), Subtitles.findFor(video.path).map { it.name })
    }

    @Test
    fun 字幕の拡張子を判定する() {
        assertTrue(Subtitles.isSubtitleName("x.SRT"))
        assertTrue(Subtitles.isSubtitleName("x.ass"))
        assertTrue(!Subtitles.isSubtitleName("x.mp4"))
    }

    @Test
    fun 上限までは読み上限を超えたら読まない() {
        val small = ByteArray(1000) { it.toByte() }
        assertArrayEquals(small, Subtitles.readLimited(ByteArrayInputStream(small)))
        val huge = object : java.io.InputStream() {
            override fun read() = 0
            override fun read(b: ByteArray, off: Int, len: Int) = len
        }
        assertNull(Subtitles.readLimited(huge))
    }
}
