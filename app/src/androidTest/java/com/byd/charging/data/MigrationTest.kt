package com.byd.charging.data

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Overuje, ze migrace databaze zachovaji uzivatelska data.
 *
 * Duvod: databaze je nasazena na telefonu autora a naplnena realnymi zaznamy.
 * Kazda nova verze schematu musi projit timto testem drive, nez se aplikace nainstaluje.
 */
@RunWith(AndroidJUnit4::class)
class MigrationTest {

    private companion object {
        const val TEST_DB = "migration-test"
    }

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        ChargingDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory()
    )

    /**
     * Migrace v5 -> v6 prida sloupce se stavem baterie a zachova stavajici zaznam.
     * Nove sloupce dostanou -1.0, tedy "nezadano" - u starych zaznamu se nic nedomysli.
     */
    @Test
    fun migrate5To6_zachova_data_a_doplni_soc_jako_nezadano() {
        helper.createDatabase(TEST_DB, 5).use { db ->
            db.execSQL(
                """
                INSERT INTO charging_sessions
                    (id, date, startTime, endTime, powerKw, startMeterKwh, chargedKwh,
                     mainMeterKwh, note, chargingType, pricePerKwh, locationName,
                     latitude, longitude, mainMeterEndKwh, garageMeterStartKwh,
                     garageMeterEndKwh, odometer)
                VALUES
                    (1, '2026-09-01', '18:00', '22:00', 3.7, 0.0, 12.5,
                     45678.0, 'nocni proud', 'HOME_GRID', 2.85, '',
                     0.0, 0.0, 0.0, 0.0, 0.0, 31456.0)
                """.trimIndent()
            )
        }

        val db = helper.runMigrationsAndValidate(TEST_DB, 6, true, *ChargingDatabase.ALL_MIGRATIONS)

        db.query("SELECT date, chargedKwh, odometer, socStart, socEnd FROM charging_sessions WHERE id = 1")
            .use { cursor ->
                assertTrue("Puvodni zaznam se ztratil", cursor.moveToFirst())
                assertEquals("2026-09-01", cursor.getString(0))
                assertEquals(12.5, cursor.getDouble(1), 1e-9)
                assertEquals(31456.0, cursor.getDouble(2), 1e-9)
                assertEquals(-1.0, cursor.getDouble(3), 1e-9)
                assertEquals(-1.0, cursor.getDouble(4), 1e-9)
            }
    }

    /**
     * Cela cesta z nejstarsiho exportovaneho schematu az na aktualni verzi projde
     * a puvodni zaznam prezije. Schema v1 neni v repozitari (export zacal az u v2),
     * proto test startuje na v2.
     */
    @Test
    fun migrate2To6_zachova_data() {
        helper.createDatabase(TEST_DB, 2).use { db ->
            db.execSQL(
                """
                INSERT INTO charging_sessions
                    (id, date, startTime, endTime, powerKw, startMeterKwh, chargedKwh,
                     mainMeterKwh, note, chargingType, pricePerKwh, locationName)
                VALUES
                    (1, '2024-03-15', '20:00', '23:30', 3.7, 100.0, 11.0, 12345.0,
                     'prvni', 'HOME_FVE', 0.0, '')
                """.trimIndent()
            )
        }

        val db = helper.runMigrationsAndValidate(TEST_DB, 6, true, *ChargingDatabase.ALL_MIGRATIONS)

        db.query("SELECT note, chargingType, odometer, socStart FROM charging_sessions WHERE id = 1")
            .use { cursor ->
                assertTrue("Zaznam z v2 se ztratil", cursor.moveToFirst())
                assertEquals("prvni", cursor.getString(0))
                assertEquals("HOME_FVE", cursor.getString(1))
                assertEquals(0.0, cursor.getDouble(2), 1e-9)
                assertEquals(-1.0, cursor.getDouble(3), 1e-9)
            }
    }

    /** Po migraci musi jit databaze normalne otevrit pres Room a cist z ni. */
    @Test
    fun po_migraci_lze_databazi_otevrit_pres_room() {
        helper.createDatabase(TEST_DB, 5).close()
        helper.runMigrationsAndValidate(TEST_DB, 6, true, *ChargingDatabase.ALL_MIGRATIONS)

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val room = Room.databaseBuilder(context, ChargingDatabase::class.java, TEST_DB)
            .addMigrations(*ChargingDatabase.ALL_MIGRATIONS)
            .build()
        room.openHelper.writableDatabase
        room.close()
    }
}
