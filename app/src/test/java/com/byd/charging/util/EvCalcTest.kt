package com.byd.charging.util

import com.byd.charging.data.ChargingSession
import com.byd.charging.data.ChargingType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Testy vypoctu odvozenych ze stavu baterie, tachometru a nabitych kWh.
 * Kapacita baterie 18 kWh odpovida vychozimu nastaveni aplikace.
 */
class EvCalcTest {

    private val capacity = 18.0
    private val eps = 1e-9

    private fun session(
        date: String = "2026-01-01",
        startTime: String = "08:00",
        endTime: String = "10:00",
        chargedKwh: Double = 0.0,
        odometer: Double = 0.0,
        socStart: Double = ChargingSession.SOC_UNSET,
        socEnd: Double = ChargingSession.SOC_UNSET,
        type: ChargingType = ChargingType.HOME_GRID,
        pricePerKwh: Double = 0.0
    ) = ChargingSession(
        date = date,
        startTime = startTime,
        endTime = endTime,
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

    // --- Prevody procenta <-> kWh ---

    @Test
    fun `socDeltaToKwh prepocte procenta na kWh`() {
        assertEquals(9.0, EvCalc.socDeltaToKwh(50.0, capacity)!!, eps)
        assertEquals(18.0, EvCalc.socDeltaToKwh(100.0, capacity)!!, eps)
    }

    @Test
    fun `kwhToSocDelta je inverzni k socDeltaToKwh`() {
        val kwh = EvCalc.socDeltaToKwh(37.0, capacity)!!
        assertEquals(37.0, EvCalc.kwhToSocDelta(kwh, capacity)!!, 1e-9)
    }

    @Test
    fun `nulova nebo zaporna kapacita vraci null`() {
        assertNull(EvCalc.socDeltaToKwh(50.0, 0.0))
        assertNull(EvCalc.kwhToSocDelta(5.0, -1.0))
    }

    // --- Jeden zaznam ---

    @Test
    fun `batteryGainKwh vraci null bez zadaneho SOC`() {
        assertNull(EvCalc.batteryGainKwh(session(chargedKwh = 10.0), capacity))
        assertNull(EvCalc.batteryGainKwh(session(chargedKwh = 10.0, socStart = 20.0), capacity))
    }

    @Test
    fun `batteryGainKwh pocita z rozdilu SOC`() {
        val s = session(socStart = 20.0, socEnd = 70.0)
        assertEquals(9.0, EvCalc.batteryGainKwh(s, capacity)!!, eps)
    }

    @Test
    fun `efficiency je podil energie v baterii a energie ze zasuvky`() {
        // 50 % z 18 kWh = 9 kWh do baterie, ze zasuvky odebrano 10 kWh
        val s = session(chargedKwh = 10.0, socStart = 20.0, socEnd = 70.0)
        assertEquals(0.9, EvCalc.efficiency(s, capacity)!!, 1e-9)
    }

    @Test
    fun `efficiency vraci null pro benzin i bez nabitych kWh`() {
        val gas = session(chargedKwh = 30.0, socStart = 20.0, socEnd = 70.0, type = ChargingType.GASOLINE)
        assertNull(EvCalc.efficiency(gas, capacity))
        assertNull(EvCalc.efficiency(session(socStart = 20.0, socEnd = 70.0), capacity))
    }

    @Test
    fun `isEfficiencySuspicious oznaci hodnoty mimo rozsah`() {
        assertTrue(EvCalc.isEfficiencySuspicious(1.3))
        assertTrue(EvCalc.isEfficiencySuspicious(0.2))
        assertTrue(!EvCalc.isEfficiencySuspicious(0.88))
    }

    @Test
    fun `realPowerKw pocita z doby trvani`() {
        val s = session(startTime = "08:00", endTime = "10:00", chargedKwh = 7.4)
        assertEquals(3.7, EvCalc.realPowerKw(s)!!, 1e-9)
    }

    @Test
    fun `realPowerKw vraci null pri nulove dobe trvani`() {
        assertNull(EvCalc.realPowerKw(session(startTime = "08:00", endTime = "08:00", chargedKwh = 7.4)))
    }

    // --- Souhrny ---

    @Test
    fun `totals pocita vzdalenost jako soucet useku`() {
        val sessions = listOf(
            session(date = "2026-01-01", odometer = 10_000.0, chargedKwh = 10.0, pricePerKwh = 5.0),
            session(date = "2026-01-10", odometer = 10_500.0, chargedKwh = 10.0, pricePerKwh = 5.0)
        )
        val t = EvCalc.totals(sessions)!!
        assertEquals(500.0, t.distanceKm, eps)
        assertEquals(20.0, t.electricKwh, eps)
        assertEquals(100.0, t.electricCost, eps)
        assertEquals(4.0, t.electricKwhPer100Km!!, eps)
        assertEquals(20.0, t.electricCostPer100Km!!, eps)
    }

    @Test
    fun `totals bez tachometru nedava spotrebu na 100 km`() {
        val t = EvCalc.totals(listOf(session(chargedKwh = 10.0)))!!
        assertEquals(0.0, t.distanceKm, eps)
        assertNull(t.electricKwhPer100Km)
    }

    @Test
    fun `totals oddeluje benzin od elektriny`() {
        val sessions = listOf(
            session(date = "2026-01-01", odometer = 10_000.0, chargedKwh = 10.0, pricePerKwh = 5.0),
            session(
                date = "2026-01-05", odometer = 10_200.0, chargedKwh = 30.0, pricePerKwh = 38.0,
                type = ChargingType.GASOLINE
            ),
            session(date = "2026-01-10", odometer = 10_400.0, chargedKwh = 10.0, pricePerKwh = 5.0)
        )
        val t = EvCalc.totals(sessions)!!
        assertEquals(20.0, t.electricKwh, eps)
        assertEquals(30.0, t.gasolineLiters, eps)
        assertEquals(100.0, t.electricCost, eps)
        assertEquals(1140.0, t.gasolineCost, eps)
    }

    // --- Useky jizdy na baterii ---

    @Test
    fun `drainSamples najde usek mezi dvema nabijenimi`() {
        val sessions = listOf(
            session(date = "2026-01-01", odometer = 10_000.0, socStart = 20.0, socEnd = 100.0),
            session(date = "2026-01-05", odometer = 10_060.0, socStart = 20.0, socEnd = 100.0)
        )
        val samples = EvCalc.drainSamples(sessions, capacity)
        assertEquals(1, samples.size)
        assertEquals(60.0, samples[0].distanceKm, eps)
        // Pokles 100 % -> 20 % je 80 % z 18 kWh = 14,4 kWh
        assertEquals(14.4, samples[0].drainedKwh, 1e-9)
        assertEquals(24.0, samples[0].kwhPer100Km, 1e-9)
    }

    @Test
    fun `tankovani benzinu usek prerusi`() {
        val sessions = listOf(
            session(date = "2026-01-01", odometer = 10_000.0, socStart = 20.0, socEnd = 100.0),
            session(date = "2026-01-03", odometer = 10_030.0, chargedKwh = 30.0, type = ChargingType.GASOLINE),
            session(date = "2026-01-05", odometer = 10_060.0, socStart = 20.0, socEnd = 100.0)
        )
        assertTrue(EvCalc.drainSamples(sessions, capacity).isEmpty())
    }

    @Test
    fun `maly pokles baterie se jako usek nepocita`() {
        val sessions = listOf(
            session(date = "2026-01-01", odometer = 10_000.0, socStart = 80.0, socEnd = 100.0),
            // Pokles jen o 10 procentnich bodu - pod prahem
            session(date = "2026-01-05", odometer = 10_020.0, socStart = 90.0, socEnd = 100.0)
        )
        assertTrue(EvCalc.drainSamples(sessions, capacity).isEmpty())
    }

    @Test
    fun `dlouha mezera mezi nabijenimi se jako usek nepocita`() {
        // Pul roku a 9 880 km na 14,4 kWh z baterie je 0,15 kWh/100 km - vetsinu
        // te cesty jel spalovaci motor, usek tedy nepopisuje jizdu na baterii
        val sessions = listOf(
            session(date = "2026-01-10", odometer = 10_000.0, socStart = 20.0, socEnd = 100.0),
            session(date = "2026-07-01", odometer = 19_880.0, socStart = 20.0, socEnd = 100.0)
        )
        assertTrue(EvCalc.drainSamples(sessions, capacity).isEmpty())
    }

    @Test
    fun `nesmyslne vysoka spotreba se jako usek nepocita`() {
        // 14,4 kWh na 10 km je 144 kWh/100 km - chybny odecet tachometru
        val sessions = listOf(
            session(date = "2026-01-01", odometer = 10_000.0, socStart = 20.0, socEnd = 100.0),
            session(date = "2026-01-05", odometer = 10_010.0, socStart = 20.0, socEnd = 100.0)
        )
        assertTrue(EvCalc.drainSamples(sessions, capacity).isEmpty())
    }

    @Test
    fun `chybejici tachometr usek vyradi`() {
        val sessions = listOf(
            session(date = "2026-01-01", odometer = 0.0, socStart = 20.0, socEnd = 100.0),
            session(date = "2026-01-05", odometer = 10_060.0, socStart = 20.0, socEnd = 100.0)
        )
        assertTrue(EvCalc.drainSamples(sessions, capacity).isEmpty())
    }

    @Test
    fun `batteryConsumptionPer100Km vraci median pres useky`() {
        // Tri useky: 24, 20 a 30 kWh/100 km -> median 24
        val sessions = listOf(
            session(date = "2026-01-01", odometer = 10_000.0, socStart = 20.0, socEnd = 100.0),
            session(date = "2026-01-05", odometer = 10_060.0, socStart = 20.0, socEnd = 100.0),
            session(date = "2026-01-10", odometer = 10_132.0, socStart = 20.0, socEnd = 100.0),
            session(date = "2026-01-15", odometer = 10_180.0, socStart = 20.0, socEnd = 100.0)
        )
        val samples = EvCalc.drainSamples(sessions, capacity)
        assertEquals(3, samples.size)
        assertEquals(24.0, EvCalc.batteryConsumptionPer100Km(sessions, capacity)!!, 1e-9)
    }

    @Test
    fun `batteryConsumptionPer100Km vraci null pri malo datech`() {
        val sessions = listOf(
            session(date = "2026-01-01", odometer = 10_000.0, socStart = 20.0, socEnd = 100.0),
            session(date = "2026-01-05", odometer = 10_060.0, socStart = 20.0, socEnd = 100.0)
        )
        // Jen jeden usek, vychozi minimum jsou dva
        assertNull(EvCalc.batteryConsumptionPer100Km(sessions, capacity))
    }

    @Test
    fun `electricRangeKm odpovida kapacite delene spotrebou`() {
        val sessions = listOf(
            session(date = "2026-01-01", odometer = 10_000.0, socStart = 20.0, socEnd = 100.0),
            session(date = "2026-01-05", odometer = 10_060.0, socStart = 20.0, socEnd = 100.0),
            session(date = "2026-01-10", odometer = 10_120.0, socStart = 20.0, socEnd = 100.0)
        )
        // Spotreba 24 kWh/100 km, kapacita 18 kWh -> 75 km
        assertEquals(75.0, EvCalc.electricRangeKm(sessions, capacity)!!, 1e-9)
    }

    @Test
    fun `averageEfficiency ignoruje nesmyslne hodnoty`() {
        val sessions = listOf(
            session(chargedKwh = 10.0, socStart = 20.0, socEnd = 70.0),   // 0,90
            session(chargedKwh = 10.0, socStart = 20.0, socEnd = 68.0),   // 0,864
            // Preklep: 1 kWh ze zasuvky a 50 % prirustek baterie -> 9,0 (vyrazeno)
            session(chargedKwh = 1.0, socStart = 20.0, socEnd = 70.0)
        )
        val avg = EvCalc.averageEfficiency(sessions, capacity)!!
        assertEquals(0.882, avg, 1e-9)
    }

    @Test
    fun `averageEfficiency vraci null bez pouzitelnych zaznamu`() {
        assertNull(EvCalc.averageEfficiency(listOf(session(chargedKwh = 10.0)), capacity))
    }

    // --- Useky tachometru ---

    @Test
    fun `legs pocita vzdalenost mezi po sobe jdoucimi odecty`() {
        val sessions = listOf(
            session(date = "2026-01-01", odometer = 10_000.0),
            session(date = "2026-01-05", odometer = 10_150.0),
            session(date = "2026-01-10", odometer = 10_400.0)
        )
        val legs = EvCalc.legs(sessions)
        assertEquals(2, legs.size)
        assertEquals(150.0, legs[0].distanceKm, eps)
        assertEquals(250.0, legs[1].distanceKm, eps)
        // Usek se pripisuje pozdejsimu zaznamu
        assertEquals("2026-01-05", legs[0].session.date)
    }

    @Test
    fun `legs preskoci zaznamy bez tachometru i zaporne useky`() {
        val sessions = listOf(
            session(date = "2026-01-01", odometer = 10_000.0),
            session(date = "2026-01-03", odometer = 0.0),
            session(date = "2026-01-05", odometer = 9_900.0),
            session(date = "2026-01-07", odometer = 10_200.0)
        )
        val legs = EvCalc.legs(sessions)
        // 10000 -> 9900 je zaporny (chybny odecet) a vypadne; zustane 9900 -> 10200
        assertEquals(1, legs.size)
        assertEquals(300.0, legs[0].distanceKm, eps)
    }

    @Test
    fun `vzdalenost stejne sezony ve dvou letech nezahrne kilometry mezi nimi`() {
        val sessions = listOf(
            // Zima 2025: 10 000 -> 10 500
            session(date = "2025-01-10", odometer = 10_000.0),
            session(date = "2025-02-10", odometer = 10_500.0),
            // Leto 2025: najeto hodne
            session(date = "2025-07-10", odometer = 30_000.0),
            // Zima 2026: 30 200 -> 30 600
            session(date = "2026-01-10", odometer = 30_200.0),
            session(date = "2026-02-10", odometer = 30_600.0)
        )
        val winter = EvCalc.seasonalStats(sessions, capacity).first { it.season == EvCalc.Season.WINTER }
        // Rozpeti tachometru zimnich zaznamu by dalo 20 600 km; spravne je 500 + 400
        assertEquals(900.0, winter.totals.distanceKm, eps)
    }

    // --- Spotreba benzinu ---

    private fun fill(date: String, odometer: Double, liters: Double, price: Double = 0.0) =
        session(date = date, odometer = odometer, chargedKwh = liters,
                pricePerKwh = price, type = ChargingType.GASOLINE)

    @Test
    fun `gasolineConsumption pocita metodou plne nadrze`() {
        val sessions = listOf(
            fill("2026-01-01", 10_000.0, 40.0),
            fill("2026-02-01", 10_500.0, 30.0),
            fill("2026-03-01", 11_000.0, 35.0)
        )
        val c = EvCalc.gasolineConsumption(sessions)!!

        // Posledni usek: 35 l na 500 km
        assertEquals(7.0, c.lastPer100Km!!, 1e-9)
        // Prumer: litry od druheho tankovani (30 + 35) na 1000 km
        assertEquals(6.5, c.averagePer100Km!!, 1e-9)
        assertEquals(3, c.fillUpCount)
    }

    @Test
    fun `prvni tankovani se do prumeru nepocita`() {
        // Prvni tankovani naplnilo nadrz pro cestu, ktera se teprve pojede
        val sessions = listOf(
            fill("2026-01-01", 10_000.0, 99.0),
            fill("2026-02-01", 10_500.0, 30.0)
        )
        val c = EvCalc.gasolineConsumption(sessions)!!
        assertEquals(6.0, c.averagePer100Km!!, 1e-9)
    }

    @Test
    fun `jedine tankovani nedava spotrebu`() {
        val c = EvCalc.gasolineConsumption(listOf(fill("2026-01-01", 10_000.0, 40.0)))!!
        assertNull(c.lastPer100Km)
        assertNull(c.averagePer100Km)
        assertEquals(1, c.fillUpCount)
    }

    @Test
    fun `tankovani bez tachometru se preskoci`() {
        val sessions = listOf(
            fill("2026-01-01", 10_000.0, 40.0),
            fill("2026-02-01", 0.0, 30.0),      // chybi odecet tachometru
            fill("2026-03-01", 11_000.0, 35.0)
        )
        val c = EvCalc.gasolineConsumption(sessions)!!
        assertEquals(2, c.fillUpCount)
        // 35 l na 1000 km
        assertEquals(3.5, c.averagePer100Km!!, 1e-9)
    }

    @Test
    fun `gasolineConsumption ignoruje nabijeni elektrinou`() {
        val sessions = listOf(
            session(date = "2026-01-01", odometer = 10_000.0, chargedKwh = 12.0),
            fill("2026-01-05", 10_200.0, 40.0),
            fill("2026-02-01", 10_700.0, 30.0)
        )
        val c = EvCalc.gasolineConsumption(sessions)!!
        assertEquals(2, c.fillUpCount)
        assertEquals(6.0, c.averagePer100Km!!, 1e-9)
    }

    @Test
    fun `gasolineConsumption vraci null bez tankovani`() {
        assertNull(EvCalc.gasolineConsumption(listOf(session(odometer = 10_000.0))))
    }

    @Test
    fun `prumerna cena za litr a za kWh bere jen vyplnene ceny`() {
        val sessions = listOf(
            fill("2026-01-01", 10_000.0, 40.0, price = 38.0),
            fill("2026-02-01", 10_500.0, 30.0, price = 36.0),
            fill("2026-03-01", 11_000.0, 30.0, price = 0.0),   // cena nezadana
            session(date = "2026-01-10", chargedKwh = 10.0, pricePerKwh = 3.0),
            session(date = "2026-01-20", chargedKwh = 10.0, pricePerKwh = 5.0)
        )
        assertEquals(37.0, EvCalc.gasolineConsumption(sessions)!!.averagePricePerLiter!!, 1e-9)
        assertEquals(4.0, EvCalc.averagePricePerKwh(sessions)!!, 1e-9)
    }

    @Test
    fun `averagePricePerKwh vraci null kdyz zadna cena neni vyplnena`() {
        assertNull(EvCalc.averagePricePerKwh(listOf(session(chargedKwh = 10.0))))
    }

    // --- Sezony ---

    @Test
    fun `Season fromMonth radi mesice do meteorologickych sezon`() {
        assertEquals(EvCalc.Season.WINTER, EvCalc.Season.fromMonth(12))
        assertEquals(EvCalc.Season.WINTER, EvCalc.Season.fromMonth(1))
        assertEquals(EvCalc.Season.WINTER, EvCalc.Season.fromMonth(2))
        assertEquals(EvCalc.Season.SPRING, EvCalc.Season.fromMonth(3))
        assertEquals(EvCalc.Season.SUMMER, EvCalc.Season.fromMonth(7))
        assertEquals(EvCalc.Season.AUTUMN, EvCalc.Season.fromMonth(11))
        assertNull(EvCalc.Season.fromMonth(13))
        assertNull(EvCalc.Season.fromMonth(0))
    }

    @Test
    fun `Season fromIsoDate cte mesic z data`() {
        assertEquals(EvCalc.Season.SUMMER, EvCalc.Season.fromIsoDate("2026-08-15"))
        assertEquals(EvCalc.Season.WINTER, EvCalc.Season.fromIsoDate("2026-12-31"))
        assertNull(EvCalc.Season.fromIsoDate("nesmysl"))
    }

    @Test
    fun `seasonalStats oddeli sezony a vrati je v poradi od jara`() {
        val sessions = listOf(
            session(date = "2026-01-10", odometer = 10_000.0, chargedKwh = 10.0),
            session(date = "2026-01-20", odometer = 10_300.0, chargedKwh = 12.0),
            session(date = "2026-07-10", odometer = 20_000.0, chargedKwh = 8.0),
            session(date = "2026-07-20", odometer = 20_400.0, chargedKwh = 9.0)
        )
        val stats = EvCalc.seasonalStats(sessions, capacity)
        assertEquals(listOf(EvCalc.Season.SUMMER, EvCalc.Season.WINTER), stats.map { it.season })

        val winter = stats.first { it.season == EvCalc.Season.WINTER }
        assertEquals(2, winter.sessionCount)
        assertEquals(22.0, winter.totals.electricKwh, eps)
        assertEquals(300.0, winter.totals.distanceKm, eps)

        val summer = stats.first { it.season == EvCalc.Season.SUMMER }
        assertEquals(17.0, summer.totals.electricKwh, eps)
        assertEquals(400.0, summer.totals.distanceKm, eps)
    }

    @Test
    fun `seasonalStats spocita spotrebu baterie jen kde je dost useku`() {
        val sessions = listOf(
            // Zima: tri nabijeni, tedy dva useky jizdy na baterii
            session(date = "2026-01-01", odometer = 10_000.0, socStart = 20.0, socEnd = 100.0),
            session(date = "2026-01-05", odometer = 10_060.0, socStart = 20.0, socEnd = 100.0),
            session(date = "2026-01-10", odometer = 10_120.0, socStart = 20.0, socEnd = 100.0),
            // Leto: jen dve nabijeni, tedy jediny usek - pod minimem
            session(date = "2026-07-01", odometer = 20_000.0, socStart = 20.0, socEnd = 100.0),
            session(date = "2026-07-05", odometer = 20_090.0, socStart = 20.0, socEnd = 100.0)
        )
        val stats = EvCalc.seasonalStats(sessions, capacity)

        val winter = stats.first { it.season == EvCalc.Season.WINTER }
        assertEquals(24.0, winter.batteryConsumptionPer100Km!!, 1e-9)
        assertEquals(75.0, winter.electricRangeKm!!, 1e-9)

        val summer = stats.first { it.season == EvCalc.Season.SUMMER }
        assertNull(summer.batteryConsumptionPer100Km)
        assertNull(summer.electricRangeKm)
    }

    @Test
    fun `seasonalStats vraci prazdny seznam bez zaznamu`() {
        assertTrue(EvCalc.seasonalStats(emptyList(), capacity).isEmpty())
    }

    // --- Obdobi: mesice a roky ---

    @Test
    fun `statsByMonth deli zaznamy po mesicich a radi vzestupne`() {
        val sessions = listOf(
            session(date = "2026-01-05", odometer = 10_000.0, chargedKwh = 10.0),
            session(date = "2026-01-20", odometer = 10_300.0, chargedKwh = 12.0),
            session(date = "2026-02-10", odometer = 10_800.0, chargedKwh = 8.0)
        )
        val months = EvCalc.statsByMonth(sessions, capacity)
        assertEquals(listOf("2026-01", "2026-02"), months.map { it.key })

        val january = months.first { it.key == "2026-01" }
        assertEquals(2, january.sessionCount)
        assertEquals(22.0, january.totals.electricKwh, eps)
        // Jen usek uvnitr ledna; prechod 20. 1. -> 10. 2. se nepocita
        assertEquals(300.0, january.totals.distanceKm, eps)

        val february = months.first { it.key == "2026-02" }
        assertEquals(8.0, february.totals.electricKwh, eps)
        assertEquals(0.0, february.totals.distanceKm, eps)
    }

    @Test
    fun `usek prekracujici mesic se nezapocita do zadneho`() {
        val sessions = listOf(
            session(date = "2026-01-28", odometer = 10_000.0),
            session(date = "2026-02-03", odometer = 10_900.0),
            session(date = "2026-02-20", odometer = 11_100.0)
        )
        val months = EvCalc.statsByMonth(sessions, capacity)
        assertEquals(0.0, months.first { it.key == "2026-01" }.totals.distanceKm, eps)
        // Jen usek 3. 2. -> 20. 2., prechod pres konec ledna vypada
        assertEquals(200.0, months.first { it.key == "2026-02" }.totals.distanceKm, eps)
    }

    @Test
    fun `statsByYear seskupi zaznamy po letech`() {
        val sessions = listOf(
            session(date = "2025-06-01", odometer = 10_000.0, chargedKwh = 10.0),
            session(date = "2025-08-01", odometer = 10_500.0, chargedKwh = 10.0),
            session(date = "2026-03-01", odometer = 20_000.0, chargedKwh = 5.0),
            session(date = "2026-05-01", odometer = 20_400.0, chargedKwh = 5.0)
        )
        val years = EvCalc.statsByYear(sessions, capacity)
        assertEquals(listOf("2025", "2026"), years.map { it.key })

        val y2025 = years.first { it.key == "2025" }
        assertEquals(500.0, y2025.totals.distanceKm, eps)
        assertEquals(20.0, y2025.totals.electricKwh, eps)
        assertEquals(4.0, y2025.totals.electricKwhPer100Km!!, eps)

        // Prechod z roku 2025 do 2026 (9 500 km) se nezapocita nikam
        val y2026 = years.first { it.key == "2026" }
        assertEquals(400.0, y2026.totals.distanceKm, eps)
        assertEquals(10.0, y2026.totals.electricKwh, eps)
    }

    @Test
    fun `statsByPeriod oddeluje benzin od elektriny`() {
        val sessions = listOf(
            session(date = "2026-01-05", odometer = 10_000.0, chargedKwh = 10.0, pricePerKwh = 5.0),
            session(
                date = "2026-01-20", odometer = 10_500.0, chargedKwh = 30.0, pricePerKwh = 38.0,
                type = ChargingType.GASOLINE
            )
        )
        val january = EvCalc.statsByMonth(sessions, capacity).single()
        assertEquals(10.0, january.totals.electricKwh, eps)
        assertEquals(30.0, january.totals.gasolineLiters, eps)
        assertEquals(500.0, january.totals.distanceKm, eps)
        assertEquals(6.0, january.totals.gasolineLitersPer100Km!!, eps)
    }

    @Test
    fun `availableYears vraci roky od nejnovejsiho bez duplicit`() {
        val sessions = listOf(
            session(date = "2025-01-05"),
            session(date = "2026-03-05"),
            session(date = "2025-08-05"),
            session(date = "2024-12-31")
        )
        assertEquals(listOf("2026", "2025", "2024"), EvCalc.availableYears(sessions))
    }

    @Test
    fun `availableMonths vraci mesice zvoleneho roku od nejnovejsiho`() {
        val sessions = listOf(
            session(date = "2026-03-05"),
            session(date = "2026-01-20"),
            session(date = "2026-03-25"),
            session(date = "2025-07-01")
        )
        assertEquals(listOf("03", "01"), EvCalc.availableMonths(sessions, "2026"))
        assertEquals(listOf("07"), EvCalc.availableMonths(sessions, "2025"))
        assertTrue(EvCalc.availableMonths(sessions, "2024").isEmpty())
    }

    @Test
    fun `statsByPeriod umi klicovat i po konkretnim mesici`() {
        val sessions = listOf(
            session(date = "2026-03-05", odometer = 10_000.0, chargedKwh = 10.0),
            session(date = "2026-03-25", odometer = 10_400.0, chargedKwh = 6.0),
            session(date = "2026-04-05", odometer = 10_900.0, chargedKwh = 9.0)
        )
        val byMonth = EvCalc.statsByPeriod(sessions, capacity) { it.take(7) }
        val march = byMonth["2026-03"]!!
        assertEquals(2, march.sessionCount)
        assertEquals(400.0, march.totals.distanceKm, eps)
        assertEquals(16.0, march.totals.electricKwh, eps)
        assertEquals(4.0, march.totals.electricKwhPer100Km!!, eps)
    }

    @Test
    fun `statsByPeriod bez zaznamu vraci prazdnou mapu`() {
        assertTrue(EvCalc.statsByMonth(emptyList(), capacity).isEmpty())
        assertTrue(EvCalc.statsByYear(emptyList(), capacity).isEmpty())
        assertTrue(EvCalc.availableYears(emptyList()).isEmpty())
    }
}
