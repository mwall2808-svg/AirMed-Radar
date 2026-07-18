package com.rf.airmedradar.data

/** Matches "Street A and Street B" / "Route X and Route Y" style separators. */
private val INTERSECTION_SEPARATOR_REGEX = Regex("""\s+(?:&|and)\s+""", RegexOption.IGNORE_CASE)

/** Full state names are unambiguous; bare 2-letter codes are not ("in" is common English). */
private val STATE_NAME_REGEX = Regex("""\b(indiana|ohio|kentucky)\b""", RegexOption.IGNORE_CASE)

/** A 2-letter code only counts as a state hint when it's comma-prefixed, e.g. ", IN". */
private val STATE_ABBREVIATION_REGEX = Regex(""",\s*(IN|OH|KY)\b""")

private const val DEFAULT_REGION_SUFFIX = ", Indiana"

/**
 * Cleans up free-text search input before it's handed to [Geocoder][android.location.Geocoder]:
 * normalizes intersection syntax and appends a default regional context so a bare
 * intersection ("Main St & 5th Ave") resolves locally instead of matching an
 * identically-named intersection in another state.
 */
object AddressQueryNormalizer {
    fun normalize(rawQuery: String): String {
        val trimmed = rawQuery.trim()
        if (trimmed.isEmpty()) return trimmed
        val withNormalizedIntersection = trimmed.replace(INTERSECTION_SEPARATOR_REGEX, " & ")
        return appendRegionIfMissing(withNormalizedIntersection)
    }

    private fun appendRegionIfMissing(query: String): String {
        val hasStateHint = STATE_NAME_REGEX.containsMatchIn(query) ||
            STATE_ABBREVIATION_REGEX.containsMatchIn(query)
        return if (hasStateHint) query else "$query$DEFAULT_REGION_SUFFIX"
    }
}
