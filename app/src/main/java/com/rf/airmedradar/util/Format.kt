package com.rf.airmedradar.util

/** Renders a countdown as mm:ss; negative (unknown) durations render as an em-dash placeholder. */
fun formatEtaSeconds(totalSeconds: Long): String {
    if (totalSeconds < 0) return "—:—"
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%02d:%02d".format(minutes, seconds)
}
