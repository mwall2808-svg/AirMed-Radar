package com.rf.airmedradar.data.fleet

/**
 * Sanitizes a dispatcher-typed tail-number field ("n123med, n456 med") into a clean list
 * ("N123MED", "N456MED"): forces uppercase, strips all whitespace (including any typed inside
 * a single tail number), splits on comma boundaries, and drops any resulting blank entries —
 * a trailing comma or doubled comma shouldn't produce an empty tail number.
 */
fun parseTailNumbers(raw: String): List<String> =
    raw.uppercase()
        .replace(" ", "")
        .split(",")
        .filter { it.isNotBlank() }
