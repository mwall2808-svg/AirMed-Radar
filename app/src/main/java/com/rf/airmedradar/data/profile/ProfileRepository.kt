package com.rf.airmedradar.data.profile

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * Thin wrapper over [UserProfileDao] — the single source of truth for which region the
 * tracking Service, weather monitor, and map camera are all scoped to. Both
 * [com.rf.airmedradar.service.AirMedTrackingService] and
 * [com.rf.airmedradar.viewmodel.AirMedRadarViewModel] hold their own instance of this backed
 * by the same [AppDatabase] singleton, so a profile switch from either side propagates to both
 * purely through Room's Flow invalidation — no manual event bus needed.
 */
class ProfileRepository(private val dao: UserProfileDao) {

    /** The one active region. `distinctUntilChanged` avoids spurious re-emits from unrelated
     *  writes to the table (Room's Flow invalidation is table-, not row-scoped). */
    val activeProfile: Flow<UserProfile?> = dao.observeActiveProfile().distinctUntilChanged()

    val allProfiles: Flow<List<UserProfile>> = dao.observeAllProfiles()

    suspend fun setActiveProfile(profileId: Long) {
        dao.setActiveProfile(profileId)
    }
}
