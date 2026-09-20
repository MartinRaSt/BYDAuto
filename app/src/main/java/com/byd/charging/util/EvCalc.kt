package com.byd.charging.util

import com.byd.charging.data.ChargingSession
import com.byd.charging.data.ChargingType

/**
 * Vypocty odvozene ze stavu baterie (SOC), tachometru a nabitych kWh.
 *
 * Zasada: kazda funkce vraci null, pokud pro vypocet chybi vstup. Zadne nahradni
 * ani odhadnute hodnoty se nevraci jako by byly zmerene - volajici musi null osetrit
 * a pole v UI skryt.
 */
object EvCalc {

    /**
     * Minimalni pokles baterie mezi dvema nabijenimi (v procentnich bodech),
     * aby mel interval vypovidaci hodnotu o spotrebe. Kratke presuny s poklesem
     * o par procent maji prilis velkou relativni chybu odectu.
     */
    private const val MIN_DRAIN_PERCENT = 15.0

    /** Ucinnost nad touto mezi znamena chybu zadani, ne realne mereni. */
    const val MAX_PLAUSIBLE_EFFICIENCY = 1.05

    /** Ucinnost pod touto mezi znamena chybu zadani (nebo extremni mraz). */
    const val MIN_PLAUSIBLE_EFFICIENCY = 0.5

    /**
     * Meze pravdepodobne spotreby baterie v kWh/100 km.
     *
     * Usek mimo tohle rozmezi nepopisuje jizdu na baterii: pod spodni mezi jela
     * vetsinu cesty spalovaci jednotka (typicky dlouha cesta nebo dlouha pauza
     * mezi nabijenimi), nad horni mezi jde o chybny odecet tachometru nebo SOC.
     * Takove useky by zkreslily medianovou spotrebu i odhad dojezdu.
     */
    private const val MIN_PLAUSIBLE_CONSUMPTION = 8.0
    private const val MAX_PLAUSIBLE_CONSUMPTION = 60.0

    // ---------------------------------------------------------------
    // Prevody mezi procenty baterie a kWh
    // ---------------------------------------------------------------

    /** Rozdil SOC v procentnich bodech -> kWh; null pokud kapacita neni kladna. */
    fun socDeltaToKwh(deltaPercent: Double, batteryCapacityKwh: Double): Double? {
        if (batteryCapacityKwh <= 0.0) return null
        return deltaPercent / 100.0 * batteryCapacityKwh
    }

    /** kWh -> rozdil SOC v procentnich bodech; null pokud kapacita neni kladna. */
    fun kwhToSocDelta(kwh: Double, batteryCapacityKwh: Double): Double? {
        if (batteryCapacityKwh <= 0.0) return null
        return kwh / batteryCapacityKwh * 100.0
    }

    // ---------------------------------------------------------------
    // Jeden zaznam
    // ---------------------------------------------------------------

    /**
     * Energie, ktera realne pribyla v baterii (z rozdilu SOC a kapacity).
     * Null pokud nejsou zadany oba stavy baterie nebo kapacita.
     */
    fun batteryGainKwh(session: ChargingSession, batteryCapacityKwh: Double): Double? {
        val delta = session.socDelta ?: return null
        return socDeltaToKwh(delta, batteryCapacityKwh)
    }

    /**
     * Ucinnost nabijeni = kWh, ktere pribyly v baterii / kWh odebrane ze zasuvky.
     * Rozdil jsou ztraty v nabijecce, kabelu a temperovani baterie.
     * Null pro benzin, pro zaznam bez SOC nebo bez nabitych kWh.
     */
    fun efficiency(session: ChargingSession, batteryCapacityKwh: Double): Double? {
        if (session.type == ChargingType.GASOLINE) return null
        if (session.chargedKwh <= 0.0) return null
        val gain = batteryGainKwh(session, batteryCapacityKwh) ?: return null
        return gain / session.chargedKwh
    }

    /** True pokud ucinnost vysla mimo fyzikalne rozumne rozmezi (typicky preklep). */
    fun isEfficiencySuspicious(efficiency: Double): Boolean =
        efficiency > MAX_PLAUSIBLE_EFFICIENCY || efficiency < MIN_PLAUSIBLE_EFFICIENCY

    /**
     * Realny prumerny vykon behem nabijeni = nabite kWh / doba trvani.
     * Null pro benzin nebo pro nulovou dobu trvani.
     */
    fun realPowerKw(session: ChargingSession): Double? {
        if (session.type == ChargingType.GASOLINE) return null
        if (session.chargedKwh <= 0.0) return null
        val minutes = DateUtil.calculateDuration(session.startTime, session.endTime)
        if (minutes <= 0) return null
        return session.chargedKwh / (minutes / 60.0)
    }

    // ---------------------------------------------------------------
    // Souhrny pres vice zaznamu
    // ---------------------------------------------------------------

    /**
     * Usek mezi dvema po sobe jdoucimi zaznamy s vyplnenym tachometrem.
     * Vzdalenost se pripisuje pozdejsimu zaznamu - podle nej se usek radi do obdobi.
     */
    data class Leg(
        val previous: ChargingSession,
        val session: ChargingSession,
        val distanceKm: Double
    )

    /**
     * Rozdeli historii na useky mezi po sobe jdoucimi odecty tachometru.
     *
     * Soucet useku je spravna vzdalenost i pro podmnozinu zaznamu (jedna sezona,
     * jeden mesic). Rozpeti tachometru (max - min) by u sezony sbirane pres vice let
     * zahrnulo i kilometry najete mezi temi lety.
     *
     * Zaporne nebo nulove useky se preskakuji - vznikaji prehozenym poradim zadani
     * nebo chybejicim odectem, ne skutecnou jizdou.
     */
    fun legs(sessions: List<ChargingSession>): List<Leg> {
        val ordered = sessions
            .filter { it.odometer > 0.0 }
            .sortedWith(compareBy({ it.date }, { it.startTime }))

        val result = mutableListOf<Leg>()
        for (i in 1 until ordered.size) {
            val distance = ordered[i].odometer - ordered[i - 1].odometer
            if (distance > 0.0) result += Leg(ordered[i - 1], ordered[i], distance)
        }
        return result
    }

    /** Souhrn za zvolene obdobi. Vzdalenost je souctem useku mezi odecty tachometru. */
    data class Totals(
        val distanceKm: Double,
        val electricKwh: Double,
        val gasolineLiters: Double,
        val electricCost: Double,
        val gasolineCost: Double
    ) {
        val totalCost: Double get() = electricCost + gasolineCost

        /** Elektrina na 100 km pres vsechny ujete km (i ty na benzin). */
        val electricKwhPer100Km: Double? get() = per100(electricKwh)

        /** Benzin na 100 km pres vsechny ujete km (i ty na elektrinu). */
        val gasolineLitersPer100Km: Double? get() = per100(gasolineLiters)

        val electricCostPer100Km: Double? get() = per100(electricCost)
        val gasolineCostPer100Km: Double? get() = per100(gasolineCost)
        val totalCostPer100Km: Double? get() = per100(totalCost)

        private fun per100(value: Double): Double? =
            if (distanceKm > 0.0) value / distanceKm * 100.0 else null
    }

    /**
     * Souhrn pres zadane zaznamy. Vzdalenost se bere jako rozpeti tachometru
     * (max - min) pres zaznamy, ktere maji tachometr vyplneny.
     * Null pokud nelze urcit zadnou vzdalenost.
     */
    fun totals(sessions: List<ChargingSession>): Totals? {
        if (sessions.isEmpty()) return null

        val distance = legs(sessions).sumOf { it.distanceKm }

        val electric = sessions.filter { it.type != ChargingType.GASOLINE }
        val gasoline = sessions.filter { it.type == ChargingType.GASOLINE }

        return Totals(
            distanceKm = distance,
            electricKwh = electric.sumOf { it.chargedKwh },
            gasolineLiters = gasoline.sumOf { it.chargedKwh },
            electricCost = electric.sumOf { it.totalCost },
            gasolineCost = gasoline.sumOf { it.totalCost }
        )
    }

    /**
     * Jeden usek jizdy mezi dvema nabijenimi.
     * [endDate] je datum nabijeni, kterym usek koncil - podle nej se usek radi do sezony.
     */
    data class DrainSample(
        val distanceKm: Double,
        val drainedKwh: Double,
        val startDate: String,
        val endDate: String
    ) {
        val kwhPer100Km: Double get() = drainedKwh / distanceKm * 100.0
    }

    /**
     * Useky mezi dvema nabijenimi, kde je znamy koncovy SOC predchoziho nabijeni
     * i pocatecni SOC nasledujiciho a mezi nimi se netankoval benzin.
     * Takovy usek popisuje jizdu na baterii, takze z nej lze urcit realnou
     * spotrebu elektriny a dojezd.
     */
    fun drainSamples(sessions: List<ChargingSession>, batteryCapacityKwh: Double): List<DrainSample> {
        if (batteryCapacityKwh <= 0.0) return emptyList()

        val ordered = sessions.sortedWith(compareBy({ it.date }, { it.startTime }))
        val samples = mutableListOf<DrainSample>()

        var prev: ChargingSession? = null
        for (s in ordered) {
            // Tankovani benzinu usek prerusi - ujete km pak nejsou jen z baterie
            if (s.type == ChargingType.GASOLINE) {
                prev = null
                continue
            }

            val p = prev
            if (p != null && s.hasSocStart && s.odometer > 0.0) {
                val drainedPercent = p.socEnd - s.socStart
                val distance = s.odometer - p.odometer
                if (drainedPercent >= MIN_DRAIN_PERCENT && distance > 0.0) {
                    val drainedKwh = socDeltaToKwh(drainedPercent, batteryCapacityKwh)
                    if (drainedKwh != null && drainedKwh > 0.0) {
                        val consumption = drainedKwh / distance * 100.0
                        if (consumption in MIN_PLAUSIBLE_CONSUMPTION..MAX_PLAUSIBLE_CONSUMPTION) {
                            samples += DrainSample(distance, drainedKwh, p.date, s.date)
                        }
                    }
                }
            }

            prev = if (s.hasSocEnd && s.odometer > 0.0) s else null
        }
        return samples
    }

    /**
     * Realna spotreba baterie v kWh/100 km - median pres useky jizdy na baterii.
     * Median, ne prumer: jedna extremni jizda (dalnice, mraz) nema prevalcovat zbytek.
     * Null pokud neni dost useku.
     */
    fun batteryConsumptionPer100Km(
        sessions: List<ChargingSession>,
        batteryCapacityKwh: Double,
        minSamples: Int = 2
    ): Double? {
        val samples = drainSamples(sessions, batteryCapacityKwh)
        if (samples.size < minSamples) return null
        return median(samples.map { it.kwhPer100Km })
    }

    /**
     * Odhad dojezdu na plnou baterii z realne spotreby baterie.
     * Null pokud neni dost dat.
     */
    fun electricRangeKm(
        sessions: List<ChargingSession>,
        batteryCapacityKwh: Double,
        minSamples: Int = 2
    ): Double? {
        if (batteryCapacityKwh <= 0.0) return null
        val consumption = batteryConsumptionPer100Km(sessions, batteryCapacityKwh, minSamples)
            ?: return null
        if (consumption <= 0.0) return null
        return batteryCapacityKwh / consumption * 100.0
    }

    /**
     * Kolik km ujedes na energii nabitou v tomto zaznamu.
     * Null pokud zaznam nema SOC nebo neni znama realna spotreba.
     */
    fun sessionRangeKm(
        session: ChargingSession,
        allSessions: List<ChargingSession>,
        batteryCapacityKwh: Double
    ): Double? {
        val gain = batteryGainKwh(session, batteryCapacityKwh) ?: return null
        if (gain <= 0.0) return null
        val consumption = batteryConsumptionPer100Km(allSessions, batteryCapacityKwh) ?: return null
        if (consumption <= 0.0) return null
        return gain / consumption * 100.0
    }

    /** Prumerna ucinnost nabijeni pres zaznamy, ktere ji umoznuji spocitat. */
    fun averageEfficiency(sessions: List<ChargingSession>, batteryCapacityKwh: Double): Double? {
        val values = sessions.mapNotNull { efficiency(it, batteryCapacityKwh) }
            .filter { !isEfficiencySuspicious(it) }
        if (values.isEmpty()) return null
        return median(values)
    }

    // ---------------------------------------------------------------
    // Benzin
    // ---------------------------------------------------------------

    /** Spotreba benzinu; kterakoliv polozka muze byt null, kdyz na ni nejsou data. */
    data class GasolineConsumption(
        /** Spotreba od predposledniho tankovani k poslednimu, l/100 km. */
        val lastPer100Km: Double?,
        /** Prumer pres celou evidovanou historii tankovani, l/100 km. */
        val averagePer100Km: Double?,
        /** Prumerna cena za litr pres vsechna tankovani. */
        val averagePricePerLiter: Double?,
        val fillUpCount: Int
    )

    /**
     * Spotreba benzinu metodou plne nadrze.
     *
     * Predpoklad: pri kazdem tankovani se natankuje do plna. Vzdalenost mezi
     * dvema tankovanimi pak odpovida palivu natankovanemu pri tom druhem z nich,
     * proto se litry scitaji az od druheho zaznamu - prvni naplnil nadrz pro
     * cestu, ktera se teprve pojede.
     *
     * Tankovani bez vyplneneho tachometru se preskakuji - bez nej nelze urcit
     * vzdalenost a zaznam by rozhodil poradi.
     */
    fun gasolineConsumption(sessions: List<ChargingSession>): GasolineConsumption? {
        val fillUps = sessions
            .filter { it.type == ChargingType.GASOLINE }
            .filter { it.odometer > 0.0 }
            .sortedBy { it.odometer }

        if (fillUps.isEmpty()) return null

        val averagePrice = fillUps
            .filter { it.pricePerKwh > 0.0 }
            .map { it.pricePerKwh }
            .takeIf { it.isNotEmpty() }
            ?.average()

        if (fillUps.size < 2) {
            return GasolineConsumption(null, null, averagePrice, fillUps.size)
        }

        val last = fillUps.last()
        val previous = fillUps[fillUps.size - 2]
        val lastDistance = last.odometer - previous.odometer
        val lastPer100 = if (lastDistance > 0.0 && last.chargedKwh > 0.0) {
            last.chargedKwh / lastDistance * 100.0
        } else {
            null
        }

        val totalDistance = last.odometer - fillUps.first().odometer
        val totalLiters = fillUps.drop(1).sumOf { it.chargedKwh }
        val averagePer100 = if (totalDistance > 0.0 && totalLiters > 0.0) {
            totalLiters / totalDistance * 100.0
        } else {
            null
        }

        return GasolineConsumption(lastPer100, averagePer100, averagePrice, fillUps.size)
    }

    /** Prumerna cena za kWh pres nabijeni, kde je cena vyplnena. */
    fun averagePricePerKwh(sessions: List<ChargingSession>): Double? =
        sessions
            .filter { it.type != ChargingType.GASOLINE && it.pricePerKwh > 0.0 }
            .map { it.pricePerKwh }
            .takeIf { it.isNotEmpty() }
            ?.average()

    // ---------------------------------------------------------------
    // Souhrny po obdobich (mesic, rok, sezona)
    // ---------------------------------------------------------------

    /** Souhrn za jedno obdobi. [key] je klic obdobi, napr. "2026-01", "2026" nebo "WINTER". */
    data class PeriodStats(
        val key: String,
        val sessionCount: Int,
        val totals: Totals,
        /** Realna spotreba baterie v kWh/100 km; null pri malo datech. */
        val batteryConsumptionPer100Km: Double?,
        /** Medianova ucinnost nabijeni (0-1); null pri malo datech. */
        val averageEfficiency: Double?,
        /** Odhad dojezdu na plnou baterii v km; null pri malo datech. */
        val electricRangeKm: Double?
    )

    /**
     * Rozdeli zaznamy na obdobi podle [keyOf] a spocita souhrn za kazde z nich.
     *
     * Vzdalenost se bere z useku mezi odecty tachometru pres CELOU historii,
     * ale zapocita se jen tehdy, kdyz oba konce useku padnou do tehoz obdobi.
     * Usek, ktery obdobi prekrocil, nepopisuje jizdu v zadnem z nich - typicky
     * je to delsi mezera v zaznamech. Stejne pravidlo plati pro useky jizdy
     * na baterii.
     *
     * [keyOf] dostava datum ve tvaru yyyy-MM-dd a vraci klic obdobi, nebo null
     * pro zaznam, ktery do zadneho obdobi nepatri.
     */
    fun statsByPeriod(
        sessions: List<ChargingSession>,
        batteryCapacityKwh: Double,
        minDrainSamples: Int = 2,
        keyOf: (String) -> String?
    ): Map<String, PeriodStats> {
        if (sessions.isEmpty()) return emptyMap()

        val distanceByKey = legs(sessions)
            .mapNotNull { leg ->
                val from = keyOf(leg.previous.date)
                val to = keyOf(leg.session.date)
                if (from != null && from == to) to to leg.distanceKm else null
            }
            .groupBy({ it.first }, { it.second })
            .mapValues { (_, distances) -> distances.sum() }

        val drainsByKey = drainSamples(sessions, batteryCapacityKwh)
            .mapNotNull { sample ->
                val from = keyOf(sample.startDate)
                val to = keyOf(sample.endDate)
                if (from != null && from == to) to to sample else null
            }
            .groupBy({ it.first }, { it.second })

        return sessions
            .groupBy { keyOf(it.date) }
            .mapNotNull { (key, list) ->
                if (key == null || list.isEmpty()) return@mapNotNull null

                val electric = list.filter { it.type != ChargingType.GASOLINE }
                val gasoline = list.filter { it.type == ChargingType.GASOLINE }

                val totals = Totals(
                    distanceKm = distanceByKey[key] ?: 0.0,
                    electricKwh = electric.sumOf { it.chargedKwh },
                    gasolineLiters = gasoline.sumOf { it.chargedKwh },
                    electricCost = electric.sumOf { it.totalCost },
                    gasolineCost = gasoline.sumOf { it.totalCost }
                )

                val drains = drainsByKey[key].orEmpty()
                val consumption =
                    if (drains.size >= minDrainSamples) median(drains.map { it.kwhPer100Km }) else null

                key to PeriodStats(
                    key = key,
                    sessionCount = list.size,
                    totals = totals,
                    batteryConsumptionPer100Km = consumption,
                    averageEfficiency = averageEfficiency(list, batteryCapacityKwh),
                    electricRangeKm = if (consumption != null && consumption > 0.0 && batteryCapacityKwh > 0.0) {
                        batteryCapacityKwh / consumption * 100.0
                    } else {
                        null
                    }
                )
            }
            .toMap()
    }

    /** Souhrny po mesicich, klic "yyyy-MM", serazene vzestupne. */
    fun statsByMonth(sessions: List<ChargingSession>, batteryCapacityKwh: Double): List<PeriodStats> =
        statsByPeriod(sessions, batteryCapacityKwh) { date -> date.take(7).takeIf { it.length == 7 } }
            .values.sortedBy { it.key }

    /** Souhrny po letech, klic "yyyy", serazene vzestupne. */
    fun statsByYear(sessions: List<ChargingSession>, batteryCapacityKwh: Double): List<PeriodStats> =
        statsByPeriod(sessions, batteryCapacityKwh) { date -> date.take(4).takeIf { it.length == 4 } }
            .values.sortedBy { it.key }

    /**
     * Mesice daneho roku, ze kterych existuji zaznamy, od nejnovejsiho.
     * Vraci dvoumistne cislo mesice ("01".."12").
     */
    fun availableMonths(sessions: List<ChargingSession>, year: String): List<String> =
        sessions.map { it.date }
            .filter { it.startsWith(year) && it.length >= 7 }
            .map { it.substring(5, 7) }
            .distinct()
            .sortedDescending()

    /** Roky, ze kterych existuji zaznamy, od nejnovejsiho. */
    fun availableYears(sessions: List<ChargingSession>): List<String> =
        sessions.map { it.date.take(4) }
            .filter { it.length == 4 }
            .distinct()
            .sortedDescending()

    // ---------------------------------------------------------------
    // Obdobi (sezony)
    // ---------------------------------------------------------------

    /**
     * Meteorologicke sezony: jaro 3-5, leto 6-8, podzim 9-11, zima 12-2.
     * Meteorologicke, ne astronomicke - deli rok na cele mesice, takze zaznamy
     * nepadaji do dvou sezon podle dne v mesici.
     */
    enum class Season {
        SPRING, SUMMER, AUTUMN, WINTER;

        companion object {
            /** Sezona pro mesic 1-12; null pro neplatny mesic. */
            fun fromMonth(month: Int): Season? = when (month) {
                3, 4, 5 -> SPRING
                6, 7, 8 -> SUMMER
                9, 10, 11 -> AUTUMN
                12, 1, 2 -> WINTER
                else -> null
            }

            /** Sezona pro datum ve tvaru yyyy-MM-dd; null pri neplatnem datu. */
            fun fromIsoDate(isoDate: String): Season? {
                val month = isoDate.trim().drop(5).take(2).toIntOrNull() ?: return null
                return fromMonth(month)
            }

            /** Poradi pro zobrazeni - rok zacina jarem. */
            val ordered: List<Season> = listOf(SPRING, SUMMER, AUTUMN, WINTER)
        }
    }

    /** Souhrn za jednu sezonu napric vsemi roky. */
    data class SeasonStats(
        val season: Season,
        val sessionCount: Int,
        val totals: Totals,
        /** Realna spotreba baterie v kWh/100 km; null pri malo datech. */
        val batteryConsumptionPer100Km: Double?,
        /** Medianova ucinnost nabijeni (0-1); null pri malo datech. */
        val averageEfficiency: Double?,
        /** Odhad dojezdu na plnou baterii v km; null pri malo datech. */
        val electricRangeKm: Double?
    )

    /**
     * Souhrn po sezonach napric vsemi roky - porovnani zima/leto u hybridu.
     *
     * Vzdalenost se pocita z useku mezi odecty tachometru a pripisuje se sezone
     * pozdejsiho zaznamu, takze scitani sezony pres vice let nezahrne kilometry
     * najete mimo ni. Energie a naklady se pripisuji sezone daneho zaznamu.
     *
     * Vraci jen sezony, pro ktere existuji nejake zaznamy, v poradi jaro-zima.
     */
    fun seasonalStats(
        sessions: List<ChargingSession>,
        batteryCapacityKwh: Double,
        minDrainSamples: Int = 2
    ): List<SeasonStats> {
        val byKey = statsByPeriod(sessions, batteryCapacityKwh, minDrainSamples) { date ->
            Season.fromIsoDate(date)?.name
        }

        return Season.ordered.mapNotNull { season ->
            val stats = byKey[season.name] ?: return@mapNotNull null
            SeasonStats(
                season = season,
                sessionCount = stats.sessionCount,
                totals = stats.totals,
                batteryConsumptionPer100Km = stats.batteryConsumptionPer100Km,
                averageEfficiency = stats.averageEfficiency,
                electricRangeKm = stats.electricRangeKm
            )
        }
    }

    // ---------------------------------------------------------------

    /** Median seznamu; predpoklada neprazdny vstup. */
    private fun median(values: List<Double>): Double {
        val sorted = values.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[mid]
        else (sorted[mid - 1] + sorted[mid]) / 2.0
    }
}
