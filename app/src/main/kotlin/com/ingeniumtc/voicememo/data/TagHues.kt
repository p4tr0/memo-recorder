package com.ingeniumtc.voicememo.data

import kotlin.math.abs
import kotlin.random.Random

/** Tag colors are stored as a hue; the theme decides lightness, so tags stay pastel in light and dark. */
object TagHues {
    /** How far round the color wheel a new tag's hue must be from the previous tag's. */
    const val MIN_DISTANCE = 75

    /** A random hue at least [MIN_DISTANCE] from [previous] (the most recently created tag's), if there is one. */
    fun pick(previous: Int?, random: Random = Random.Default): Int {
        if (previous == null) return random.nextInt(FULL_CIRCLE)
        // The allowed hues form one arc of the circle; pick uniformly within it.
        val arc = FULL_CIRCLE - 2 * MIN_DISTANCE + 1
        return (previous + MIN_DISTANCE + random.nextInt(arc)) % FULL_CIRCLE
    }

    /** Shortest distance round the circle, 0 until 180. */
    fun distance(a: Int, b: Int): Int = abs(a - b).let { minOf(it, FULL_CIRCLE - it) }

    private const val FULL_CIRCLE = 360
}
