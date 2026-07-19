package com.rf.airmedradar.data.profile

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A saved dispatch region: where the map centers, how far out ADS-B traffic is pulled, and
 * which METAR station backs the weather-minimums banner. Exactly one row has [isActive] = true
 * at any time — [UserProfileDao.setActiveProfile] enforces that atomically.
 */
@Entity(tableName = "user_profiles")
data class UserProfile(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val profileName: String,
    val centerLat: Double,
    val centerLon: Double,
    val radiusNM: Int,
    val metarStationId: String,
    val isActive: Boolean,
)
