package com.rf.airmedradar.data.fleet

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [HemsProviderEntity::class], version = 1, exportSchema = false)
@TypeConverters(Converters::class)
abstract class HemsFleetDatabase : RoomDatabase() {

    abstract fun hemsProviderDao(): HemsProviderDao

    companion object {
        @Volatile
        private var instance: HemsFleetDatabase? = null

        fun getInstance(context: Context): HemsFleetDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    HemsFleetDatabase::class.java,
                    "hems_fleet.db",
                ).addCallback(SeedFactoryBaselineCallback).build().also { instance = it }
            }
    }
}

/**
 * Seeds the national parent-network watchlist on first-ever DB creation, via raw SQL against
 * the callback's own [SupportSQLiteDatabase] handle rather than the Dao — the Dao/database
 * instance doesn't exist yet at this point in construction, so going through it here would
 * recursively re-enter [HemsFleetDatabase.getInstance]'s synchronized block. Every seeded row
 * has `isCustom = false`, distinguishing it from anything the dispatcher later enters by hand.
 */
private object SeedFactoryBaselineCallback : RoomDatabase.Callback() {
    override fun onCreate(db: SupportSQLiteDatabase) {
        super.onCreate(db)
        insertProvider(db, "UC Air Care", listOf("N145UC", "N84UC", "N874BU"))
        insertProvider(db, "StatFlight", listOf("N401PH", "N402PH", "N403PH", "N320PH"))
        insertProvider(db, "IU Health LifeLine", listOf("N192LL", "N194LL", "N273NE", "N193LL"))
        insertProvider(db, "Air Evac Lifeteam", listOf("N166AE", "N101AE", "N490AE"))
    }

    private fun insertProvider(db: SupportSQLiteDatabase, providerName: String, tailNumbers: List<String>) {
        val values = ContentValues().apply {
            put("providerName", providerName)
            put("tailNumbers", tailNumbers.joinToString(","))
            put("isCustom", 0)
        }
        db.insert("hems_providers", SQLiteDatabase.CONFLICT_REPLACE, values)
    }
}
