package com.byd.charging.util

import com.byd.charging.data.ChargingSession
import com.byd.charging.data.ChargingType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Overuje kolecko export zalohy -> import zalohy a to, ze se cizi CSV nenacte
 * jako data. Na tomhle stoji obnova dat po pripadnem problemu, takze to musi
 * byt hlidane testem, ne jen vyzkousene rukou.
 */
class CsvRoundTripTest {

    private fun session(
        date: String = "2026-09-01",
        startTime: String = "18:00",
        endTime: String = "22:30",
        chargedKwh: Double = 12.5,
        odometer: Double = 31_456.0,
        socStart: Double = ChargingSession.SOC_UNSET,
        socEnd: Double = ChargingSession.SOC_UNSET,
        type: ChargingType = ChargingType.HOME_GRID,
        note: String = "",
        locationName: String = ""
    ) = ChargingSession(
        date = date,
        startTime = startTime,
        endTime = endTime,
        powerKw = 3.7,
        startMeterKwh = 0.0,
        chargedKwh = chargedKwh,
        mainMeterKwh = 45_678.0,
        note = note,
        chargingType = type.name,
        pricePerKwh = 2.85,
        locationName = locationName,
        odometer = odometer,
        socStart = socStart,
        socEnd = socEnd
    )

    private fun roundTrip(sessions: List<ChargingSession>): List<ChargingSession> =
        CsvImporter.parseCsvText(String(CsvExporter.buildCsv(sessions), Charsets.UTF_8))

    @Test
    fun `zaloha se po importu vrati se stejnymi hodnotami`() {
        val original = session(socStart = 22.0, socEnd = 100.0, note = "nocni proud")
        val restored = roundTrip(listOf(original)).single()

        assertEquals(original.date, restored.date)
        assertEquals(original.startTime, restored.startTime)
        assertEquals(original.endTime, restored.endTime)
        assertEquals(original.chargedKwh, restored.chargedKwh, 1e-9)
        assertEquals(original.pricePerKwh, restored.pricePerKwh, 1e-9)
        assertEquals(original.odometer, restored.odometer, 1e-9)
        assertEquals(original.socStart, restored.socStart, 1e-9)
        assertEquals(original.socEnd, restored.socEnd, 1e-9)
        assertEquals(original.chargingType, restored.chargingType)
        assertEquals(original.note, restored.note)
    }

    @Test
    fun `nezadany stav baterie zustane nezadany i po obnove`() {
        val restored = roundTrip(listOf(session())).single()
        assertEquals(ChargingSession.SOC_UNSET, restored.socStart, 1e-9)
        assertEquals(ChargingSession.SOC_UNSET, restored.socEnd, 1e-9)
        assertTrue(!restored.hasSocStart)
        assertTrue(!restored.hasSocEnd)
    }

    @Test
    fun `strednik a uvozovky v poznamce zalohu nerozbiji`() {
        val original = session(note = "nabito; \"rychlo\" u Lidlu", locationName = "Brno; Kralovo Pole")
        val restored = roundTrip(listOf(original)).single()
        assertEquals(original.note, restored.note)
        assertEquals(original.locationName, restored.locationName)
    }

    @Test
    fun `obnova zachova vsechny zaznamy i jejich typy`() {
        val originals = listOf(
            session(date = "2026-01-10", type = ChargingType.HOME_FVE),
            session(date = "2026-02-10", type = ChargingType.PUBLIC),
            session(date = "2026-03-10", type = ChargingType.GASOLINE, chargedKwh = 31.4)
        )
        val restored = roundTrip(originals)
        assertEquals(3, restored.size)
        assertEquals(originals.map { it.chargingType }, restored.map { it.chargingType })
    }

    @Test
    fun `CSV se statistikami se jako zaloha nenacte`() {
        // Obe CSV chodi emailem a jmenuji se podobne - zamena nesmi vytvorit zaznamy
        val statsCsv = String(
            StatsReport.buildCsv(
                listOf(
                    session(date = "2026-01-10", odometer = 10_000.0),
                    session(date = "2026-07-10", odometer = 20_000.0)
                ),
                18.0
            ),
            Charsets.UTF_8
        )
        assertTrue(CsvImporter.parseCsvText(statsCsv).isEmpty())
    }

    @Test
    fun `zaloha do souboru je platny ZIP a da se z nej obnovit`() {
        val originals = listOf(
            session(date = "2026-01-10", socStart = 30.0, socEnd = 90.0, note = "prvni"),
            session(date = "2026-02-10", type = ChargingType.GASOLINE, chargedKwh = 31.4)
        )

        val zipBytes = java.io.ByteArrayOutputStream().also { out ->
            CsvExporter.writeBackupZip(originals, out)
        }.toByteArray()

        // Rozbalit stejne, jako to dela import
        val csv = java.util.zip.ZipInputStream(zipBytes.inputStream()).use { zis ->
            var entry = zis.nextEntry
            var found: String? = null
            while (entry != null && found == null) {
                if (entry.name.endsWith(".csv", ignoreCase = true)) {
                    found = String(zis.readBytes(), Charsets.UTF_8)
                }
                entry = zis.nextEntry
            }
            found
        }

        assertTrue("V ZIP nebylo nalezeno zadne CSV", csv != null)
        val restored = CsvImporter.parseCsvText(csv!!)
        assertEquals(2, restored.size)
        assertEquals("prvni", restored[0].note)
        assertEquals(90.0, restored[0].socEnd, 1e-9)
        assertEquals(ChargingType.GASOLINE.name, restored[1].chargingType)
    }

    @Test
    fun `nazev souboru zalohy obsahuje datum a priponu zip`() {
        val name = CsvExporter.backupFileName()
        assertTrue(name, name.startsWith("byd_logger_zaloha_"))
        assertTrue(name, name.endsWith(".zip"))
        assertTrue(name, Regex("""\d{4}-\d{2}-\d{2}""").containsMatchIn(name))
    }

    @Test
    fun `prazdny nebo cizi soubor nevytvori zadne zaznamy`() {
        assertTrue(CsvImporter.parseCsvText("").isEmpty())
        assertTrue(CsvImporter.parseCsvText("jen hlavicka").isEmpty())
        assertTrue(CsvImporter.parseCsvText("a;b;c\n1;2;3").isEmpty())
    }
}
