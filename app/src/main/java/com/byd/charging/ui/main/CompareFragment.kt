package com.byd.charging.ui.main

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Spinner
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import com.byd.charging.R
import com.byd.charging.data.ChargingSession
import com.byd.charging.data.ChargingType
import com.byd.charging.databinding.FragmentCompareBinding
import com.byd.charging.databinding.ViewCompareRowBinding
import com.byd.charging.ui.charts.BarChartHelper
import com.byd.charging.ui.settings.SettingsActivity
import com.byd.charging.util.EvCalc
import com.byd.charging.util.NumberUtil
import com.byd.charging.util.ZoomHelper

/**
 * Porovnani dvou velicin proti sobe. Rezim se voli spinnerem:
 *
 *  - dve obdobi proti sobe (leden 2026 proti lednu 2025)
 *  - elektrina proti benzinu za zvolene obdobi
 *  - typy nabijeni mezi sebou
 *  - rok proti roku pres celou historii
 *
 * Hodnota, kterou nelze z dat spocitat, se vypise jako pomlcka - nikdy jako nula.
 */
class CompareFragment : Fragment() {

    private companion object {
        const val MODE_PERIODS = 0
        const val MODE_ENERGY = 1
        const val MODE_CHARGING_TYPES = 2
        const val MODE_YEARS = 3

        const val MISSING = "-"

        /** Prvni polozka spinneru roku znamena celou historii. */
        const val YEAR_ALL_INDEX = 0

        /** Prvni polozka spinneru mesicu znamena cely rok. */
        const val MONTH_ALL_INDEX = 0
    }

    private var _binding: FragmentCompareBinding? = null
    private val binding get() = _binding!!
    private val viewModel: MainViewModel by activityViewModels()

    private var years: List<String> = emptyList()
    private var monthsA: List<String> = emptyList()
    private var monthsB: List<String> = emptyList()

    /** Blokuje prekresleni, dokud se plni spinnery. */
    private var updatingSpinners = false

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentCompareBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        ZoomHelper.setupPinchToZoom(binding.root)
        setupModeSpinner()

        listOf(
            binding.spinnerYearA, binding.spinnerMonthA,
            binding.spinnerYearB, binding.spinnerMonthB
        ).forEach { spinner ->
            spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                    if (updatingSpinners) return
                    // Zmena roku meni nabidku mesicu, proto se obe strany prestavi
                    refreshMonthSpinners(viewModel.allSessions.value.orEmpty())
                    render()
                }

                override fun onNothingSelected(p: AdapterView<*>?) = Unit
            }
        }

        viewModel.allSessions.observe(viewLifecycleOwner) { sessions ->
            setupYearSpinners(sessions.orEmpty())
            refreshMonthSpinners(sessions.orEmpty())
            render()
        }
    }

    // ---------------------------------------------------------------
    // Spinnery
    // ---------------------------------------------------------------

    private fun setupModeSpinner() {
        val modes = listOf(
            getString(R.string.compare_mode_periods),
            getString(R.string.compare_mode_energy),
            getString(R.string.compare_mode_types),
            getString(R.string.compare_mode_years)
        )
        binding.spinnerMode.adapter = simpleAdapter(modes)
        binding.spinnerMode.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                applyModeVisibility(pos)
                render()
            }

            override fun onNothingSelected(p: AdapterView<*>?) = Unit
        }
        applyModeVisibility(MODE_PERIODS)
    }

    /** Rezim urcuje, kolik vyberu obdobi ma smysl ukazovat. */
    private fun applyModeVisibility(mode: Int) {
        val needsA = mode != MODE_YEARS
        val needsB = mode == MODE_PERIODS

        binding.layoutPeriodA.visibility = if (needsA) View.VISIBLE else View.GONE
        binding.layoutPeriodB.visibility = if (needsB) View.VISIBLE else View.GONE
        binding.tvLabelA.text = getString(
            if (needsB) R.string.compare_period_a else R.string.energy_period
        )
    }

    private fun setupYearSpinners(sessions: List<ChargingSession>) {
        val available = EvCalc.availableYears(sessions)
        if (available == years && binding.spinnerYearA.adapter != null) return

        years = available
        val labels = mutableListOf(getString(R.string.period_all))
        labels += available

        updatingSpinners = true
        binding.spinnerYearA.adapter = simpleAdapter(labels)
        binding.spinnerYearB.adapter = simpleAdapter(labels)

        // Vychozi porovnani: nejnovejsi rok proti predchozimu, kdyz jsou aspon dva
        if (available.size >= 2) {
            binding.spinnerYearA.setSelection(1)
            binding.spinnerYearB.setSelection(2)
        }
        updatingSpinners = false
    }

    private fun refreshMonthSpinners(sessions: List<ChargingSession>) {
        updatingSpinners = true
        monthsA = fillMonthSpinner(binding.spinnerMonthA, binding.spinnerYearA, sessions, monthsA)
        monthsB = fillMonthSpinner(binding.spinnerMonthB, binding.spinnerYearB, sessions, monthsB)
        updatingSpinners = false
    }

    /**
     * Naplni spinner mesicu podle roku zvoleneho v [yearSpinner].
     * Pri volbe "cele obdobi" nema vyber mesice smysl, spinner se skryje.
     */
    private fun fillMonthSpinner(
        monthSpinner: Spinner,
        yearSpinner: Spinner,
        sessions: List<ChargingSession>,
        previous: List<String>
    ): List<String> {
        val year = selectedYear(yearSpinner)
        if (year == null) {
            monthSpinner.visibility = View.INVISIBLE
            return emptyList()
        }

        val months = EvCalc.availableMonths(sessions, year)
        monthSpinner.visibility = View.VISIBLE
        if (months == previous && monthSpinner.adapter != null) return months

        val labels = mutableListOf(getString(R.string.period_whole_year))
        labels += months.map { monthLabel(year, it) }
        monthSpinner.adapter = simpleAdapter(labels)
        return months
    }

    private fun simpleAdapter(items: List<String>): ArrayAdapter<String> =
        ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, items)
            .also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }

    /** Rok zvoleny ve spinneru, nebo null pro celou historii. */
    private fun selectedYear(spinner: Spinner): String? {
        val pos = spinner.selectedItemPosition
        if (pos <= YEAR_ALL_INDEX) return null
        return years.getOrNull(pos - 1)
    }

    /**
     * Predpona data pro zvolene obdobi: null = cela historie,
     * "2026" = cely rok, "2026-03" = konkretni mesic.
     */
    private fun selectedPrefix(yearSpinner: Spinner, monthSpinner: Spinner, months: List<String>): String? {
        val year = selectedYear(yearSpinner) ?: return null
        val pos = monthSpinner.selectedItemPosition
        if (pos <= MONTH_ALL_INDEX) return year
        val month = months.getOrNull(pos - 1) ?: return year
        return "$year-$month"
    }

    private fun monthLabel(year: String, month: String): String = "$month/$year"

    private fun periodLabel(prefix: String?): String = when {
        prefix == null -> getString(R.string.period_all)
        prefix.length == 4 -> prefix
        else -> monthLabel(prefix.take(4), prefix.substring(5, 7))
    }

    // ---------------------------------------------------------------
    // Vykresleni
    // ---------------------------------------------------------------

    private fun render() {
        if (_binding == null) return

        val sessions = viewModel.allSessions.value.orEmpty()
        binding.containerHeader.removeAllViews()
        binding.containerResult.removeAllViews()
        binding.compareChart.clear()
        binding.compareChart.visibility = View.GONE
        binding.tvCompareChartLabel.visibility = View.GONE

        if (sessions.isEmpty()) {
            binding.tvCompareEmpty.visibility = View.VISIBLE
            return
        }
        binding.tvCompareEmpty.visibility = View.GONE

        val capacity = SettingsActivity.getBatteryCapacity(requireContext())
        when (binding.spinnerMode.selectedItemPosition) {
            MODE_PERIODS -> renderPeriods(sessions, capacity)
            MODE_ENERGY -> renderEnergy(sessions, capacity)
            MODE_CHARGING_TYPES -> renderChargingTypes(sessions, capacity)
            MODE_YEARS -> renderYears(sessions, capacity)
        }

        if (binding.containerResult.childCount == 0) {
            binding.tvCompareEmpty.visibility = View.VISIBLE
        }
    }

    /** Dve obdobi vedle sebe vcetne rozdilu. */
    private fun renderPeriods(sessions: List<ChargingSession>, capacity: Double) {
        val prefixA = selectedPrefix(binding.spinnerYearA, binding.spinnerMonthA, monthsA)
        val prefixB = selectedPrefix(binding.spinnerYearB, binding.spinnerMonthB, monthsB)

        val a = statsForPrefix(sessions, capacity, prefixA)
        val b = statsForPrefix(sessions, capacity, prefixB)

        addHeader(periodLabel(prefixA), periodLabel(prefixB), getString(R.string.compare_diff))

        // Graf jen pro naklady na 100 km: ostatni metriky maji ruzne jednotky
        // a do jednoho grafu je michat nelze
        drawChart(
            listOf(
                BarChartHelper.Bar(periodLabel(prefixA), a?.totals?.totalCostPer100Km),
                BarChartHelper.Bar(periodLabel(prefixB), b?.totals?.totalCostPer100Km)
            ),
            R.color.type_grid,
            getString(R.string.stats_cost_per_100km),
            decimals = 0
        )

        metrics(capacity).forEach { metric ->
            val va = a?.let { metric.value(it) }
            val vb = b?.let { metric.value(it) }
            addRow(
                metric.label,
                format(va, metric.unit),
                format(vb, metric.unit),
                formatDiff(va, vb, metric.unit)
            )
        }
    }

    /** Elektrina proti benzinu za zvolene obdobi. */
    private fun renderEnergy(sessions: List<ChargingSession>, capacity: Double) {
        val prefix = selectedPrefix(binding.spinnerYearA, binding.spinnerMonthA, monthsA)
        val stats = statsForPrefix(sessions, capacity, prefix) ?: return
        val totals = stats.totals

        addHeader(
            getString(R.string.energy_electric),
            getString(R.string.energy_gasoline),
            getString(R.string.compare_diff)
        )

        val electricPer100 = totals.electricCostPer100Km
        val gasolinePer100 = totals.gasolineCostPer100Km

        drawChart(
            listOf(
                BarChartHelper.Bar(getString(R.string.energy_electric), electricPer100),
                BarChartHelper.Bar(getString(R.string.energy_gasoline), gasolinePer100)
            ),
            R.color.type_public,
            getString(R.string.stats_cost_per_100km),
            decimals = 0
        )

        addRow(
            getString(R.string.stats_cost_per_100km),
            format(electricPer100, Unit.CZK),
            format(gasolinePer100, Unit.CZK),
            formatDiff(electricPer100, gasolinePer100, Unit.CZK)
        )
        addRow(
            getString(R.string.stats_total_cost),
            format(totals.electricCost.takeIf { it > 0.0 }, Unit.CZK),
            format(totals.gasolineCost.takeIf { it > 0.0 }, Unit.CZK),
            MISSING
        )
        addRow(
            getString(R.string.compare_amount),
            format(totals.electricKwh.takeIf { it > 0.0 }, Unit.KWH),
            format(totals.gasolineLiters.takeIf { it > 0.0 }, Unit.LITER),
            MISSING
        )

        // Uspora na 100 km: o kolik je elektrina levnejsi nez benzin
        if (electricPer100 != null && gasolinePer100 != null && gasolinePer100 > 0.0) {
            val saving = gasolinePer100 - electricPer100
            addRow(
                getString(R.string.compare_saving),
                format(saving, Unit.CZK),
                MISSING,
                MISSING
            )
        }
    }

    /** Typy nabijeni mezi sebou: kolik kWh, za kolik a s jakou ucinnosti. */
    private fun renderChargingTypes(sessions: List<ChargingSession>, capacity: Double) {
        val prefix = selectedPrefix(binding.spinnerYearA, binding.spinnerMonthA, monthsA)
        val inPeriod = sessions.filter { prefix == null || it.date.startsWith(prefix) }
        val electric = inPeriod.filter { it.type != ChargingType.GASOLINE }
        if (electric.isEmpty()) return

        val totalKwh = electric.sumOf { it.chargedKwh }

        addHeader(
            getString(R.string.compare_col_amount),
            getString(R.string.compare_col_price),
            getString(R.string.compare_col_share)
        )

        val grouped = electric.groupBy { it.chargingType }
            .toList()
            .sortedByDescending { (_, list) -> list.sumOf { it.chargedKwh } }

        drawChart(
            grouped.map { (typeName, list) ->
                BarChartHelper.Bar(
                    getString(ChargingType.fromString(typeName).labelRes()),
                    list.sumOf { it.chargedKwh }
                )
            },
            R.color.type_fve,
            getString(R.string.stats_total_kwh)
        )

        grouped
            .forEach { (typeName, list) ->
                val type = ChargingType.fromString(typeName)
                val kwh = list.sumOf { it.chargedKwh }
                val price = EvCalc.averagePricePerKwh(list)
                val share = if (totalKwh > 0.0) kwh / totalKwh * 100.0 else null
                addRow(
                    getString(type.labelRes()),
                    format(kwh.takeIf { it > 0.0 }, Unit.KWH),
                    format(price, Unit.CZK),
                    format(share, Unit.PERCENT)
                )
            }

        // Ucinnost nabijeni se da spocitat jen tam, kde je zadany stav baterie
        val efficiencyRows = electric.groupBy { it.chargingType }
            .mapNotNull { (typeName, list) ->
                EvCalc.averageEfficiency(list, capacity)?.let { typeName to it }
            }
        if (efficiencyRows.isNotEmpty()) {
            addHeader(getString(R.string.label_efficiency), "", "")
            efficiencyRows.forEach { (typeName, efficiency) ->
                addRow(
                    getString(ChargingType.fromString(typeName).labelRes()),
                    format(efficiency * 100, Unit.PERCENT),
                    "",
                    ""
                )
            }
        }
    }

    /** Vsechny roky pod sebou - trend spotreby a nakladu. */
    private fun renderYears(sessions: List<ChargingSession>, capacity: Double) {
        val yearStats = EvCalc.statsByYear(sessions, capacity).sortedByDescending { it.key }
        if (yearStats.isEmpty()) return

        addHeader(
            getString(R.string.compare_col_electric),
            getString(R.string.compare_col_gasoline),
            getString(R.string.compare_col_cost)
        )

        drawChart(
            yearStats.sortedBy { it.key }.map {
                BarChartHelper.Bar(it.key, it.totals.totalCostPer100Km)
            },
            R.color.type_garage,
            getString(R.string.stats_cost_per_100km),
            decimals = 0
        )

        yearStats.forEach { st ->
            addRow(
                st.key,
                format(st.totals.electricKwhPer100Km?.takeIf { it > 0.0 }, Unit.KWH_100),
                format(st.totals.gasolineLitersPer100Km?.takeIf { it > 0.0 }, Unit.L_100),
                format(st.totals.totalCostPer100Km?.takeIf { it > 0.0 }, Unit.CZK)
            )
        }
    }

    // ---------------------------------------------------------------
    // Metriky a formatovani
    // ---------------------------------------------------------------

    private enum class Unit { KWH, LITER, KWH_100, L_100, CZK, PERCENT, KM }

    private class Metric(val label: String, val unit: Unit, val value: (EvCalc.PeriodStats) -> Double?)

    /** Metriky pouzite pri porovnani dvou obdobi. */
    private fun metrics(capacity: Double): List<Metric> = listOf(
        Metric(getString(R.string.stats_distance), Unit.KM) { it.totals.distanceKm.takeIf { d -> d > 0.0 } },
        Metric(getString(R.string.stats_electric_consumption), Unit.KWH_100) { it.totals.electricKwhPer100Km?.takeIf { v -> v > 0.0 } },
        Metric(getString(R.string.stats_battery_consumption), Unit.KWH_100) { it.batteryConsumptionPer100Km },
        Metric(getString(R.string.stats_avg_consumption), Unit.L_100) { it.totals.gasolineLitersPer100Km?.takeIf { v -> v > 0.0 } },
        Metric(getString(R.string.stats_electric_range), Unit.KM) { it.electricRangeKm },
        Metric(getString(R.string.stats_avg_efficiency), Unit.PERCENT) { it.averageEfficiency?.times(100) },
        Metric(getString(R.string.stats_cost_per_100km), Unit.CZK) { it.totals.totalCostPer100Km?.takeIf { v -> v > 0.0 } },
        Metric(getString(R.string.stats_total_kwh), Unit.KWH) { it.totals.electricKwh.takeIf { v -> v > 0.0 } },
        Metric(getString(R.string.stats_total_gas), Unit.LITER) { it.totals.gasolineLiters.takeIf { v -> v > 0.0 } },
        Metric(getString(R.string.stats_total_cost), Unit.CZK) { it.totals.totalCost.takeIf { v -> v > 0.0 } }
    )

    /**
     * Souhrn za obdobi urcene predponou data.
     * Pro celou historii se pouziji funkce pres vsechny zaznamy.
     */
    private fun statsForPrefix(
        sessions: List<ChargingSession>,
        capacity: Double,
        prefix: String?
    ): EvCalc.PeriodStats? {
        if (prefix == null) {
            val totals = EvCalc.totals(sessions) ?: return null
            return EvCalc.PeriodStats(
                key = "ALL",
                sessionCount = sessions.size,
                totals = totals,
                batteryConsumptionPer100Km = EvCalc.batteryConsumptionPer100Km(sessions, capacity),
                averageEfficiency = EvCalc.averageEfficiency(sessions, capacity),
                electricRangeKm = EvCalc.electricRangeKm(sessions, capacity)
            )
        }
        val length = prefix.length
        return EvCalc.statsByPeriod(sessions, capacity) { date ->
            date.take(length).takeIf { it.length == length }
        }[prefix]
    }

    private fun format(value: Double?, unit: Unit): String {
        if (value == null) return MISSING
        return when (unit) {
            Unit.KWH -> getString(R.string.stats_total_kwh_val, round1(value))
            Unit.LITER -> getString(R.string.stats_total_gas_val, round1(value))
            Unit.KWH_100 -> getString(R.string.fmt_kwh_per_100km, round1(value))
            Unit.L_100 -> getString(R.string.fmt_consumption, round1(value))
            Unit.CZK -> getString(R.string.fmt_cost, NumberUtil.formatCost(value))
            Unit.PERCENT -> getString(R.string.fmt_percent, round0(value))
            Unit.KM -> getString(R.string.fmt_km, round0(value))
        }
    }

    /** Rozdil B proti A se znamenkem; bez obou hodnot nema smysl. */
    private fun formatDiff(a: Double?, b: Double?, unit: Unit): String {
        if (a == null || b == null) return MISSING
        val diff = a - b
        val formatted = format(Math.abs(diff), unit)
        return when {
            diff > 0.0 -> "+$formatted"
            diff < 0.0 -> "-$formatted"
            else -> formatted
        }
    }

    // ---------------------------------------------------------------

    /** Vykresli graf nad tabulku; kdyz neni co kreslit, zustane skryty. */
    private fun drawChart(
        bars: List<BarChartHelper.Bar>,
        colorRes: Int,
        label: String,
        decimals: Int = 1
    ) {
        val drawn = BarChartHelper.show(binding.compareChart, bars, colorRes, decimals)
        binding.compareChart.visibility = if (drawn) View.VISIBLE else View.GONE
        binding.tvCompareChartLabel.visibility = if (drawn) View.VISIBLE else View.GONE
        binding.tvCompareChartLabel.text = label
    }

    private fun addHeader(a: String, b: String, diff: String) {
        val row = ViewCompareRowBinding.inflate(layoutInflater, binding.containerHeader, false)
        row.tvCompareLabel.text = ""
        row.tvCompareA.text = a
        row.tvCompareB.text = b
        row.tvCompareDiff.text = diff
        listOf(row.tvCompareA, row.tvCompareB, row.tvCompareDiff).forEach {
            it.setTextColor(
                androidx.core.content.ContextCompat.getColor(requireContext(), R.color.textSecondary)
            )
        }
        binding.containerHeader.addView(row.root)
    }

    private fun addRow(label: String, a: String, b: String, diff: String) {
        val row = ViewCompareRowBinding.inflate(layoutInflater, binding.containerResult, false)
        row.tvCompareLabel.text = label
        row.tvCompareA.text = a
        row.tvCompareB.text = b
        row.tvCompareDiff.text = diff
        binding.containerResult.addView(row.root)
    }

    private fun round0(value: Double): String = NumberUtil.format(Math.round(value).toDouble())
    private fun round1(value: Double): String = NumberUtil.format(Math.round(value * 10) / 10.0)

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
