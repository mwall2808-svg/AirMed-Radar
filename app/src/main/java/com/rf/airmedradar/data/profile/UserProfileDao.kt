package com.rf.airmedradar.data.profile

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface UserProfileDao {

    @Insert
    suspend fun insert(profile: UserProfile): Long

    @Update
    suspend fun update(profile: UserProfile)

    /** Reactive — emits a fresh value whenever any row in `user_profiles` changes. */
    @Query("SELECT * FROM user_profiles WHERE isActive = 1 LIMIT 1")
    fun observeActiveProfile(): Flow<UserProfile?>

    @Query("SELECT * FROM user_profiles ORDER BY profileName ASC")
    fun observeAllProfiles(): Flow<List<UserProfile>>

    @Query("UPDATE user_profiles SET isActive = 0")
    suspend fun deactivateAll()

    @Query("UPDATE user_profiles SET isActive = 1 WHERE id = :profileId")
    suspend fun activate(profileId: Long)

    /** Deactivates every profile then activates exactly [profileId], as a single unit. */
    @Transaction
    suspend fun setActiveProfile(profileId: Long) {
        deactivateAll()
        activate(profileId)
    }
}
