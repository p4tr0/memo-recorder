package com.ingeniumtc.voicememo.data

import java.text.Normalizer
import java.util.Locale

/** Rules for what the user can type as a tag name. */
object TagNames {
    const val MAX_LENGTH = 30

    sealed interface Result {
        data class Valid(val name: String) : Result

        data object Blank : Result

        data object TooLong : Result

        /** Another tag already has this name, ignoring case. Only from renaming: adding reuses the existing tag. */
        data object Taken : Result
    }

    /**
     * Trims and collapses inner whitespace, so "  Work   notes " and "Work notes" are the same tag. NFC
     * normalization makes an "é" typed as one character or as e plus an accent the same name.
     */
    fun validate(input: String): Result {
        val name = Normalizer.normalize(input, Normalizer.Form.NFC).trim().replace(WHITESPACE, " ")
        return when {
            name.isEmpty() -> Result.Blank
            name.length > MAX_LENGTH -> Result.TooLong
            else -> Result.Valid(name)
        }
    }

    /** What uniqueness is decided on: case-insensitive for every script, not just ASCII. */
    fun key(validName: String): String = validName.lowercase(Locale.ROOT)

    private val WHITESPACE = Regex("\\s+")
}
