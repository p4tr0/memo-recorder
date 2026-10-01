package com.ingeniumtc.voicememo.data

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TagHuesTest {
    @Test
    fun `picks stay in range and away from the previous hue, including across 0`() {
        val random = Random(42)
        for (previous in listOf(0, 10, 180, 350, 359)) {
            repeat(500) {
                val hue = TagHues.pick(previous, random)
                assertTrue(hue in 0 until 360)
                assertTrue("$previous then $hue", TagHues.distance(previous, hue) >= TagHues.MIN_DISTANCE)
            }
        }
    }

    @Test
    fun `every allowed hue can come up, so colors are actually random`() {
        val random = Random(7)
        val seen = (1..20_000).map { TagHues.pick(100, random) }.toSet()
        assertEquals(360 - (2 * TagHues.MIN_DISTANCE - 1), seen.size)
    }

    @Test
    fun `distance is the short way round`() {
        assertEquals(20, TagHues.distance(350, 10))
        assertEquals(180, TagHues.distance(0, 180))
    }
}
