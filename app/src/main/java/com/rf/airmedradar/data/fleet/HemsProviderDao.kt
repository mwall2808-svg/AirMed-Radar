package com.rf.airmedradar.data.fleet

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface HemsProviderDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertProvider(provider: HemsProviderEntity)

    /** Reactive — emits a fresh list whenever any row in `hems_providers` changes. */
    @Query("SELECT * FROM hems_providers ORDER BY providerName ASC")
    fun getAllProviders(): Flow<List<HemsProviderEntity>>

    /**
     * A direct, blocking lookup (not `suspend`, not a [Flow]) — call it from a background
     * dispatcher, never the main thread. Room returns an empty `List` here, never null, when
     * [name] has no matching row.
     */
    @Query("SELECT tailNumbers FROM hems_providers WHERE providerName = :name LIMIT 1")
    fun getTailNumbersForProvider(name: String): List<String>
}
