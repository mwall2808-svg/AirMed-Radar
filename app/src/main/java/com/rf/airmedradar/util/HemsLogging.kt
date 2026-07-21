package com.rf.airmedradar.util

/**
 * Single Logcat tag shared across the Phase 9.9 trajectory gate (AirMedTrackingService), Phase
 * 9.10 ETA math (AirMedTrackingService), and the Phase 9.11 launch simulator
 * (MockHemsController/AirMedRadarViewModel) — filtering Logcat on this one tag surfaces the
 * whole tracking pipeline as a single interleaved trace instead of hunting across per-class tags.
 * Message bodies are further bracket-prefixed by category (e.g. "[TRAJECTORY_GATE]",
 * "[ETA_CALC]", "[SIM_STATE]", "[SIM_TICK]", "[TARGET_LOCK]") so a specific stage can still be
 * isolated with Android Studio's Logcat message filter alongside this tag.
 */
const val HEMS_LOG_TAG = "HEMS_TRACKING_ENGINE"
