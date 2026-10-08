package com.ryose.videoplayer

import org.junit.Assert.assertEquals
import org.junit.Test

class PopupGeometryTest {

    @Test
    fun 縦横の比率は動画に合わせて極端なものは抑える() {
        assertEquals(9f / 16f, PopupGeometry.aspect(1920, 1080), 0.001f)
        assertEquals(16f / 9f, PopupGeometry.aspect(1080, 1920), 0.001f)
        // 映像の大きさが分からないときは 16:9
        assertEquals(9f / 16f, PopupGeometry.aspect(0, 0), 0.001f)
        // 極端に横長・縦長な動画は 2.39:1 までに抑える
        assertEquals(1 / 2.39f, PopupGeometry.aspect(4000, 100), 0.001f)
        assertEquals(2.39f, PopupGeometry.aspect(100, 4000), 0.001f)
    }

    @Test
    fun 大きさは長い方の辺で決める() {
        assertEquals(320 to 180, PopupGeometry.windowSize(320, 9f / 16f))
        assertEquals(180 to 320, PopupGeometry.windowSize(320, 16f / 9f))
    }

    @Test
    fun 大きさは画面に収まる() {
        val a = 9f / 16f
        // 縦向きの画面（幅 1080）では、横長の小窓は画面の幅まで
        assertEquals(1080, PopupGeometry.clampSize(5000, 300, 1080, 2400, a))
        // 小さすぎるときは最小に
        assertEquals(300, PopupGeometry.clampSize(10, 300, 1080, 2400, a))
        // 横向きの画面（高さ 1080）では、縦長の小窓は画面の高さまで
        assertEquals(1080, PopupGeometry.clampSize(5000, 300, 2400, 1080, 16f / 9f))
    }

    @Test
    fun 端に寄せても一部は見えている() {
        // 左の端に寄せても 40 は見えている
        assertEquals(-260 to 100, PopupGeometry.clampPosition(-1000, 100, 300, 200, 1080, 2400, 40))
        // 右下の端
        assertEquals(1040 to 2360, PopupGeometry.clampPosition(5000, 5000, 300, 200, 1080, 2400, 40))
        // 画面の中ならそのまま
        assertEquals(500 to 600, PopupGeometry.clampPosition(500, 600, 300, 200, 1080, 2400, 40))
    }

    @Test
    fun ボタンの数は幅で変わる() {
        assertEquals(5, PopupGeometry.buttonCount(240f))
        assertEquals(3, PopupGeometry.buttonCount(180f))
        assertEquals(1, PopupGeometry.buttonCount(132f))
        // 一番小さいときは真ん中のボタンを出さない（ダブルタップで再生・一時停止）
        assertEquals(0, PopupGeometry.buttonCount(96f))
    }
}
