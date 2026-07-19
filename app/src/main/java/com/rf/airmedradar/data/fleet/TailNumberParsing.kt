package com.rf.airmedradar.data.fleet

/**
 * Sanitizes a dispatcher-typed tail-number field ("n123med,\tn456 med") into a clean list
 * ("N123MED", "N456MED"): forces uppercase, strips all whitespace — spaces, tabs, and any
 * other whitespace character, not just literal spaces, including any typed inside a single
 * tail number — splits on comma boundaries, and drops any resulting blank entries so a
 * trailing comma, doubled comma, or blank line never produces an empty tail number.
 */
fun parseTailNumbers(raw: String): List<String> =
    raw.uppercase()
        .filterNot { it.isWhitespace() }
        .split(",")
        .filter { it.isNotBlank() }
