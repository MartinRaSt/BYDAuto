package com.byd.charging.util

import com.byd.charging.data.ChargingSession
import com.byd.charging.data.ChargingType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Testy CSV se statistikami. Textovy prehled potrebuje Context (retezce z resources),
 * proto se tady testuje jen strojove citelna podoba - cisla jsou v obou stejna,
 * berou se z tehoz volani EvCalc.
 */
class StatsReportTest {

    private val capacity = 18.0

    private fun session(
        date: String,
        odometer: Double = 0.0,
        chargedKwh: Double = 0.0,
        pricePerKwh: Double = 0.0,
        type: ChargingType = ChargingType.HOME_GRID,
        socStart: Double = ChargingSession.SOC_UNSET,
        socEnd: Double = ChargingSession.SOC_UNSET
    ) = ChargingSession(
        date = date,
        startTime = "08:00",
        endTime = "10:00",
        powerKw = 3.7,
        startMeterKwh = 0.0,
        chargedKwh = chargedKwh,
        mainMeterKwh = 0.0,
        chargingType = type.name,
        pricePerKwh = pricePerKwh,
        odometer = odometer,
        socStart = socStart,
        socEnd = socEnd
    )

    private fun csvLines(sessions: List<ChargingSession>): List<String> =
        String(StatsReport.buildCsv(sessions, capacity), Charsets.UTF_8)
            .removePrefix("﻿")
            .lines()
            .filter { it.isNotBlank() }

    @Test
    fun `CSV ma hlavicku a radek s celkovym souhrnem`() {
        val lines = csvLines(
            listOf(
                session("2026-01-10", odometer = 10_000.0, chargedKwh = 10.0, pricePerKwh = 5.0),
                session("2026-01-20", odometer = 10_500.0, chargedKwh = 10.0, pricePerKwh = 5.0)
            )
        )
        assertTrue(lines[0].startsWith("Sekce;Obdobi;Zaznamu"))

        val total = lines.first { it.startsWith("CELKEM") }.split(";")
        assertEquals("2", total[2])        // pocet zaznamu
        assertEquals("500", total[3])      // najeto km
        assertEquals("20", total[4])       // elektrina kWh
    }

    @Test
    fun `CSV obsahuje radek pro kazdou sezonu a kazdy mesic`() {
        val lines = csvLines(
            listOf(
                session("2026-01-10", odometer = 10_000.0, chargedKwh = 10.0),
                session("2026-02-10", odometer = 10_300.0, chargedKwh = 10.0),
                session("2026-07-10", odometer = 20_000.0, chargedKwh = 8.0)
            )
        )
        val seasons = lines.filter { it.startsWith("SEZONA") }
        assertEquals(2, seasons.size)
        assertTrue(seasons.any { it.contains("WINTER") })
        assertTrue(seasons.any { it.contains("SUMMER") })

        val months = lines.filter { it.startsWith("MESIC") }
        assertEquals(listOf("2026-01", "2026-02", "2026-07"), months.map { it.split(";")[1] })
    }

    @Test
    fun `chybejici hodnota je pomlcka, ne nula`() {
        // Bez tachometru nelze spocitat nic na 100 km ani dojezd
        val lines = csvLines(listOf(session("2026-01-10", chargedKwh = 10.0)))
        val total = lines.first { it.startsWith("CELKEM") }.split(";")
        assertEquals("-", total[8])    // elektrina na 100 km
        assertEquals("-", total[10])   // spotreba baterie
        assertEquals("-", total[11])   // dojezd
    }

    @Test
    fun `do mesice se pocitaji jen useky cele uvnitr nej`() {
        val lines = csvLines(
            listOf(
                session("2025-01-10", odometer = 10_000.0),
                session("2025-01-20", odometer = 10_400.0),
                session("2025-08-10", odometer = 30_000.0),
                session("2026-01-10", odometer = 30_500.0)
            )
        )
        // Usek 10. 1. -> 20. 1. je cely v lednu 2025
        val jan2025 = lines.first { it.startsWith("MESIC;2025-01") }.split(";")
        assertEquals("400", jan2025[3])

        // Usek ze srpna 2025 do ledna 2026 prekracuje mesic i rok, takze
        // nepatri ani jednomu - jinak by leden 2026 vykazal kilometry najete
        // za pul roku a spotreba by vysla nesmyslne nizka
        val jan2026 = lines.first { it.startsWith("MESIC;2026-01") }.split(";")
        assertEquals("0", jan2026[3])
    }

    @Test
    fun `benzin se v CSV drzi oddelene od elektriny`() {
        val lines = csvLines(
            listOf(
                session("2026-01-10", odometer = 10_000.0, chargedKwh = 10.0, pricePerKwh = 5.0),
                session(
                    "2026-01-20", odometer = 10_500.0, chargedKwh = 30.0, pricePerKwh = 38.0,
                    type = ChargingType.GASOLINE
                )
            )
        )
        val total = lines.first { it.startsWith("CELKEM") }.split(";")
        assertEquals("10", total[4])     // elektrina kWh
        assertEquals("30", total[5])     // benzin l
        assertEquals("50", total[6])     // cena elektrina
        assertEquals("1140", total[7])   // cena benzin
    }
}
