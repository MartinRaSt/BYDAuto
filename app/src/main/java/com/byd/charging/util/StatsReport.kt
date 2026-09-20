package com.byd.charging.util

import android.content.Context
import com.byd.charging.R
import com.byd.charging.data.ChargingSession
import com.byd.charging.util.EvCalc.Season
import java.io.ByteArrayOutputStream
import java.io.OutputStreamWriter

/**
 * Sestavi textovy prehled statistik pro email a strojove citelne CSV se stejnymi cisly.
 *
 * Obe podoby berou hodnoty z tehoz zdroje (EvCalc), takze se nemohou rozejit.
 * Nedostupna hodnota se vypise jako pomlcka, nikdy jako nula nebo odhad.
 */
object StatsReport {

    private const val SEP = ";"
    private const val MISSING = "-"

    /** Nazev sekce v CSV pro celkovy souhrn. */
    private const val SECTION_TOTAL = "CELKEM"
    private const val SECTION_SEASON = "SEZONA"
    private const val SECTION_MONTH = "MESIC"

    // ---------------------------------------------------------------
    // Textovy prehled do tela emailu
    // ---------------------------------------------------------------

    fun buildText(context: Context, sessions: List<ChargingSession>, batteryCapacityKwh: Double): String {
        val sb = StringBuilder()

        sb.appendLine(context.getString(R.string.report_title))
        sb.appendLine()

        val dates = sessions.map { it.date }.sorted()
        if (dates.isNotEmpty()) {
            sb.appendLine(
                context.getString(
                    R.string.report_period,
                    DateUtil.toDisplayDate(dates.first()),
                    DateUtil.toDisplayDate(dates.last())
                )
            )
        }
        sb.appendLine(context.getString(R.string.report_record_count, sessions.size))
        sb.appendLine(
            context.getString(R.string.report_battery_capacity, NumberUtil.format(batteryCapacityKwh))
        )
        sb.appendLine()

        // --- Celkem ---
        sb.appendLine(context.getString(R.string.report_section_total))
        appendBlock(
            context, sb,
            totals = EvCalc.totals(sessions),
            batteryConsumption = EvCalc.batteryConsumptionPer100Km(sessions, batteryCapacityKwh),
            rangeKm = EvCalc.electricRangeKm(sessions, batteryCapacityKwh),
            efficiency = EvCalc.averageEfficiency(sessions, batteryCapacityKwh)
        )
        sb.appendLine()

        // --- Podle sezony ---
        val seasons = EvCalc.seasonalStats(sessions, batteryCapacityKwh)
        if (seasons.isNotEmpty()) {
            sb.appendLine(context.getString(R.string.report_section_seasons))
            sb.appendLine()
            seasons.forEach { stats ->
                sb.appendLine(
                    context.getString(
                        R.string.report_season_header,
                        context.getString(seasonLabelRes(stats.season)),
                        stats.sessionCount
                    )
                )
                appendBlock(
                    context, sb,
                    totals = stats.totals,
                    batteryConsumption = stats.batteryConsumptionPer100Km,
                    rangeKm = stats.electricRangeKm,
                    efficiency = stats.averageEfficiency
                )
                sb.appendLine()
            }
        }

        sb.appendLine(context.getString(R.string.report_footer))
        return sb.toString()
    }

    /** Jeden blok cisel - pouziva se pro celek i pro kazdou sezonu. */
    private fun appendBlock(
        context: Context,
        sb: StringBuilder,
        totals: EvCalc.Totals?,
        batteryConsumption: Double?,
        rangeKm: Double?,
        efficiency: Double?
    ) {
        fun line(labelRes: Int, value: String) {
            sb.appendLine("  " + context.getString(labelRes) + ": " + value)
        }

        if (totals == null) {
            sb.appendLine("  " + context.getString(R.string.report_no_data))
            return
        }

        if (totals.distanceKm > 0.0) {
            line(R.string.stats_distance, context.getString(R.string.fmt_km, round0(totals.distanceKm)))
        }
        if (totals.electricKwh > 0.0) {
            line(R.string.stats_total_kwh, context.getString(R.string.stats_total_kwh_val, round1(totals.electricKwh)))
        }
        if (totals.gasolineLiters > 0.0) {
            line(R.string.stats_total_gas, context.getString(R.string.stats_total_gas_val, round1(totals.gasolineLiters)))
        }
        if (totals.totalCost > 0.0) {
            line(R.string.stats_total_cost, context.getString(R.string.stats_total_cost_val, NumberUtil.formatCost(totals.totalCost)))
        }

        totals.electricKwhPer100Km?.takeIf { it > 0.0 }?.let {
            line(R.string.stats_electric_consumption, context.getString(R.string.fmt_kwh_per_100km, round1(it)))
        }
        totals.gasolineLitersPer100Km?.takeIf { it > 0.0 }?.let {
            line(R.string.stats_avg_consumption, context.getString(R.string.fmt_consumption, round1(it)))
        }
        batteryConsumption?.let {
            line(R.string.stats_battery_consumption, context.getString(R.string.fmt_kwh_per_100km, round1(it)))
        }
        rangeKm?.let {
            line(R.string.stats_electric_range, context.getString(R.string.fmt_km, round0(it)))
        }
        efficiency?.let {
            line(R.string.stats_avg_efficiency, context.getString(R.string.fmt_percent, round0(it * 100)))
        }
        totals.totalCostPer100Km?.takeIf { it > 0.0 }?.let {
            line(
                R.string.stats_cost_per_100km,
                context.getString(
                    R.string.fmt_cost_split,
                    NumberUtil.formatCost(it),
                    NumberUtil.formatCost(totals.electricCostPer100Km ?: 0.0),
                    NumberUtil.formatCost(totals.gasolineCostPer100Km ?: 0.0)
                )
            )
        }
    }

    // ---------------------------------------------------------------
    // CSV se stejnymi cisly pro dalsi zpracovani
    // ---------------------------------------------------------------

    fun buildCsv(sessions: List<ChargingSession>, batteryCapacityKwh: Double): ByteArray {
        val out = ByteArrayOutputStream()
        // BOM - spravne otevreni v ceskem Excelu
        out.write(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()))

        OutputStreamWriter(out, Charsets.UTF_8).use { writer ->
            writer.appendLine(
                listOf(
                    "Sekce", "Obdobi", "Zaznamu", "Najeto (km)",
                    "Elektrina (kWh)", "Benzin (l)",
                    "Cena elektrina (Kc)", "Cena benzin (Kc)",
                    "Elektrina na 100 km (kWh)", "Benzin na 100 km (l)",
                    "Spotreba baterie (kWh/100 km)", "Dojezd na plnou baterii (km)",
                    "Ucinnost nabijeni (%)", "Naklady na 100 km (Kc)"
                ).joinToString(SEP)
            )

            // Celkem
            writer.appendLine(
                csvRow(
                    section = SECTION_TOTAL,
                    period = "",
                    count = sessions.size,
                    totals = EvCalc.totals(sessions),
                    batteryConsumption = EvCalc.batteryConsumptionPer100Km(sessions, batteryCapacityKwh),
                    rangeKm = EvCalc.electricRangeKm(sessions, batteryCapacityKwh),
                    efficiency = EvCalc.averageEfficiency(sessions, batteryCapacityKwh)
                )
            )

            // Sezony
            EvCalc.seasonalStats(sessions, batteryCapacityKwh).forEach { s ->
                writer.appendLine(
                    csvRow(
                        section = SECTION_SEASON,
                        period = seasonCsvName(s.season),
                        count = s.sessionCount,
                        totals = s.totals,
                        batteryConsumption = s.batteryConsumptionPer100Km,
                        rangeKm = s.electricRangeKm,
                        efficiency = s.averageEfficiency
                    )
                )
            }

            // Mesice - stejny vypocet jako zalozky Elektrina a Benzin,
            // aby emailovy prehled a aplikace ukazovaly tataz cisla
            EvCalc.statsByMonth(sessions, batteryCapacityKwh).forEach { st ->
                writer.appendLine(
                    csvRow(
                        section = SECTION_MONTH,
                        period = st.key,
                        count = st.sessionCount,
                        totals = st.totals,
                        batteryConsumption = st.batteryConsumptionPer100Km,
                        rangeKm = st.electricRangeKm,
                        efficiency = st.averageEfficiency
                    )
                )
            }
        }
        return out.toByteArray()
    }

    private fun csvRow(
        section: String,
        period: String,
        count: Int,
        totals: EvCalc.Totals?,
        batteryConsumption: Double?,
        rangeKm: Double?,
        efficiency: Double?
    ): String = listOf(
        section,
        period,
        count.toString(),
        num(totals?.distanceKm),
        num(totals?.electricKwh),
        num(totals?.gasolineLiters),
        num(totals?.electricCost),
        num(totals?.gasolineCost),
        num(totals?.electricKwhPer100Km),
        num(totals?.gasolineLitersPer100Km),
        num(batteryConsumption),
        num(rangeKm),
        num(efficiency?.let { it * 100 }),
        num(totals?.totalCostPer100Km)
    ).joinToString(SEP)

    /** Chybejici hodnota jde do CSV jako pomlcka, ne jako nula. */
    private fun num(value: Double?): String =
        if (value == null) MISSING else NumberUtil.format(Math.round(value * 100) / 100.0)

    // ---------------------------------------------------------------

    private fun seasonLabelRes(season: Season): Int = when (season) {
        Season.SPRING -> R.string.season_spring
        Season.SUMMER -> R.string.season_summer
        Season.AUTUMN -> R.string.season_autumn
        Season.WINTER -> R.string.season_winter
    }

    /** Nazev sezony v CSV - jazykove nezavisly, aby sel soubor zpracovat strojove. */
    private fun seasonCsvName(season: Season): String = season.name

    private fun round0(value: Double): String = NumberUtil.format(Math.round(value).toDouble())
    private fun round1(value: Double): String = NumberUtil.format(Math.round(value * 10) / 10.0)
}
