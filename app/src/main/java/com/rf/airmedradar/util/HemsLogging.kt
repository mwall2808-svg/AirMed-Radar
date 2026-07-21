package com.rf.airmedradar.util

/**
 * Single Logcat tag shared across the Phase 9.9 trajectory gate and Phase 9.10 ETA math (both
 * in AirMedTrackingService) and the ViewModel's own lifecycle logging — filtering Logcat on this
 * one tag surfaces the whole tracking pipeline as a single interleaved trace instead of hunting
 * across per-class tags. Message bodies are further bracket-prefixed by category (e.g.
 * "[TRAJECTORY_GATE]", "[ETA_CALC]", "[TARGET_LOCK]", "[MISSION]", "[LIFECYCLE]") so a specific
 * stage can still be isolated with Android Studio's Logcat message filter alongside this tag.
 */
const val HEMS_LOG_TAG = "HEMS_TRACKING_ENGINE"
