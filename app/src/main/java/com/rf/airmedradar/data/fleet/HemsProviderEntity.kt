package com.rf.airmedradar.data.fleet

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A persisted HEMS operator record: either a factory-baseline row seeded on first launch (see
 * [HemsFleetDatabase]'s creation callback) or one the dispatcher entered by hand.
 * [tailNumbers] is stored as a single comma-separated TEXT column via [Converters] — Room has
 * no native list column type, and a join table is unwarranted for a flat, rarely-queried list
 * this small.
 */
@Entity(
    tableName = "hems_providers",
    indices = [Index(value = ["providerName"], unique = true)],
)
data class HemsProviderEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val providerName: String,
    val tailNumbers: List<String>,
    val isCustom: Boolean,
)
