package com.rf.airmedradar.data.fleet

import androidx.room.TypeConverter

/** Stores [HemsProviderEntity.tailNumbers] as a single comma-separated TEXT column. */
class Converters {
    @TypeConverter
    fun fromTailNumberList(tailNumbers: List<String>): String = tailNumbers.joinToString(",")

    @TypeConverter
    fun toTailNumberList(value: String): List<String> =
        if (value.isBlank()) emptyList() else value.split(",").map { it.trim() }
}
