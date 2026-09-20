package com.byd.charging.data

import android.content.Context
import android.util.Log
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [ChargingSession::class],
    version = DbSchema.VERSION,
    exportSchema = true          // generuje schemas/6.json pro sledování schématu ve VCS
)
abstract class ChargingDatabase : RoomDatabase() {

    abstract fun chargingDao(): ChargingDao

    companion object {
        private const val TAG = "ChargingDatabase"
        private const val DB_NAME = "charging_database"

        @Volatile
        private var INSTANCE: ChargingDatabase? = null

        /**
         * Migrace v1 → v2: přidány sloupce pro typ nabíjení, cenu a místo.
         */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE charging_sessions ADD COLUMN chargingType TEXT NOT NULL DEFAULT 'HOME_GRID'"
                )
                db.execSQL(
                    "ALTER TABLE charging_sessions ADD COLUMN pricePerKwh REAL NOT NULL DEFAULT 0.0"
                )
                db.execSQL(
                    "ALTER TABLE charging_sessions ADD COLUMN locationName TEXT NOT NULL DEFAULT ''"
                )
                Log.i(TAG, "Migration 1→2 completed")
            }
        }

        /**
         * Migrace v2 → v3: přidány GPS souřadnice.
         */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE charging_sessions ADD COLUMN latitude REAL NOT NULL DEFAULT 0.0"
                )
                db.execSQL(
                    "ALTER TABLE charging_sessions ADD COLUMN longitude REAL NOT NULL DEFAULT 0.0"
                )
                Log.i(TAG, "Migration 2→3 completed")
            }
        }

        /**
         * Migrace v3 → v4: přidány rozšířené stavy elektroměrů (hlavní konec, garáž start/konec).
         */
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE charging_sessions ADD COLUMN mainMeterEndKwh REAL NOT NULL DEFAULT 0.0")
                db.execSQL("ALTER TABLE charging_sessions ADD COLUMN garageMeterStartKwh REAL NOT NULL DEFAULT 0.0")
                db.execSQL("ALTER TABLE charging_sessions ADD COLUMN garageMeterEndKwh REAL NOT NULL DEFAULT 0.0")
                Log.i(TAG, "Migration 3→4 completed")
            }
        }

        /**
         * Migrace v4 → v5: přidán sloupec pro tachometr (odometer).
         */
        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE charging_sessions ADD COLUMN odometer REAL NOT NULL DEFAULT 0.0")
                Log.i(TAG, "Migration 4→5 completed")
            }
        }

        /**
         * Migrace v5 -> v6: pridany stavy baterie v procentech (socStart, socEnd).
         * Default -1.0 znamena "nezadano" - odlisuje se tim od skutecnych 0 %.
         * Stavajici zaznamy tak zustanou bez SOC a nedopocitava se u nich nic nasilim.
         */
        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE charging_sessions ADD COLUMN socStart REAL NOT NULL DEFAULT -1.0")
                db.execSQL("ALTER TABLE charging_sessions ADD COLUMN socEnd REAL NOT NULL DEFAULT -1.0")
                Log.i(TAG, "Migration 5->6 completed")
            }
        }

        /** Vsechny migrace v poradi - pouziva je i migracni test. */
        val ALL_MIGRATIONS = arrayOf(
            MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6
        )

        fun getDatabase(context: Context): ChargingDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    ChargingDatabase::class.java,
                    DB_NAME
                )
                    .addMigrations(*ALL_MIGRATIONS)
                    // POZOR: fallbackToDestructiveMigration() ZDE ZÁMĚRNĚ CHYBÍ.
                    // Každá nová verze DB MUSÍ mít explicitní Migration.
                    // Tiché smazání uživatelských dat je nepřijatelné.
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
