package com.ingeniumtc.voicememo.data

/** Rules for what the user can type as a tag name. */
object TagNames {
    const val MAX_LENGTH = 30

    sealed interface Result {
        data class Valid(val name: String) : Result

        data object Blank : Result

        data object TooLong : Result
    }

    /** Trims and collapses inner whitespace, so "  Work   notes " and "Work notes" are the same tag. */
    fun validate(input: String): Result {
        val name = input.trim().replace(WHITESPACE, " ")
        return when {
            name.isEmpty() -> Result.Blank
            name.length > MAX_LENGTH -> Result.TooLong
            else -> Result.Valid(name)
        }
    }

    private val WHITESPACE = Regex("\\s+")
}
