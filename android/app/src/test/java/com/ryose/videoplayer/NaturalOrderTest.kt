package com.ryose.videoplayer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NaturalOrderTest {
    private fun sorted(vararg names: String) = names.sortedWith(NaturalOrder)

    @Test
    fun 数字は数値として比べる() {
        assertEquals(listOf("第2話", "第10話", "第100話"), sorted("第10話", "第100話", "第2話"))
    }

    @Test
    fun 先頭の0は無視する() {
        assertEquals(listOf("ep01", "ep2", "ep003", "ep10"), sorted("ep10", "ep003", "ep2", "ep01"))
    }

    @Test
    fun 大文字小文字は区別しない() {
        assertEquals(0, NaturalOrder.compare("Movie", "movie"))
        assertTrue(NaturalOrder.compare("apple", "Banana") < 0)
    }

    @Test
    fun 短いほうが先() {
        assertTrue(NaturalOrder.compare("abc", "abc1") < 0)
        assertTrue(NaturalOrder.compare("a", "a") == 0)
    }

    @Test
    fun 桁の多い数字でもあふれない() {
        assertTrue(NaturalOrder.compare("x99999999999999999999", "x100000000000000000000") < 0)
    }
}
