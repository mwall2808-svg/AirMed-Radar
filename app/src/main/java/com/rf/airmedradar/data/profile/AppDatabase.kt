package com.rf.airmedradar.data.profile

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [UserProfile::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {

    abstract fun userProfileDao(): UserProfileDao

    companion object {
        @Volatile
        private var instance: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "airmedradar.db",
                ).addCallback(SeedDefaultProfilesCallback).build().also { instance = it }
            }
    }
}

/**
 * Seeds the two starting dispatch regions on first-ever DB creation, via raw SQL against the
 * callback's own [SupportSQLiteDatabase] handle rather than the Dao — the Dao/[AppDatabase]
 * instance doesn't exist yet at this point in construction, so going through it here would
 * recursively re-enter [AppDatabase.getInstance]'s synchronized block.
 */
private object SeedDefaultProfilesCallback : RoomDatabase.Callback() {
    override fun onCreate(db: SupportSQLiteDatabase) {
        super.onCreate(db)
        db.execSQL(
            """
            INSERT INTO user_profiles (profileName, centerLat, centerLon, radiusNM, metarStationId, isActive)
            VALUES ('Greater Cincinnati (KCVG)', 39.0, -84.9, 75, 'KCVG', 1)
            """.trimIndent(),
        )
        db.execSQL(
            """
            INSERT INTO user_profiles (profileName, centerLat, centerLon, radiusNM, metarStationId, isActive)
            VALUES ('Houston (KHOU)', 29.65, -95.28, 75, 'KHOU', 0)
            """.trimIndent(),
        )
    }
}
