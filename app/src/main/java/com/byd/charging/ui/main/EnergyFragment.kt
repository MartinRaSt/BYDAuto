package com.byd.charging.ui.main

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import com.byd.charging.R
import com.byd.charging.data.ChargingSession
import com.byd.charging.data.ChargingType
import com.byd.charging.databinding.FragmentEnergyBinding
import com.byd.charging.databinding.ViewDetailRowBinding
import com.byd.charging.ui.charts.BarChartHelper
import com.byd.charging.ui.settings.SettingsActivity
import com.byd.charging.util.EvCalc
import com.byd.charging.util.NumberUtil
import com.byd.charging.util.ZoomHelper
import java.time.LocalDate

/**
 * Zalozka venovana jednomu druhu energie - elektrine nebo benzinu.
 *
 * Jeden fragment pro oba druhy zamerne: sekce jsou stejne (spotreba, celkem,
 * mesice, sezony), lisi se jen jednotky a sada hodnot. Dve skoro shodne tridy
 * by se casem rozesly.
 *
 * Radek se vlozi jen tehdy, kdyz jde hodnota z dat spocitat - nic se neodhaduje.
 */
class EnergyFragment : Fragment() {

    /** Druh energie, ktery zalozka zobrazuje. */
    enum class Kind { ELECTRIC, GASOLINE }

    companion object {
        private const val ARG_KIND = "arg_kind"

        fun newInstance(kind: Kind): EnergyFragment = EnergyFragment().apply {
            arguments = Bundle().apply { putString(ARG_KIND, kind.name) }
        }
    }

    private var _binding: FragmentEnergyBinding? = null
    private val binding get() = _binding!!
    private val viewModel: MainViewModel by activityViewModels()

    private val kind: Kind by lazy {
        Kind.valueOf(arguments?.getString(ARG_KIND) ?: Kind.ELECTRIC.name)
    }

    /** Zvolene obdobi: null = cele obdobi, jinak rok ve tvaru "2026". */
    private var selectedYear: String? = null

    /** Zvoleny mesic ve tvaru "03", nebo null pro cely rok. Plati jen kdyz je zvoleny rok. */
    private var selectedMonth: String? = null

    /** Roky nabizene ve spinneru, v poradi jako v nem. Prvni polozka je cele obdobi. */
    private var spinnerYears: List<String> = emptyList()

    /** Mesice nabizene ve spinneru pro zvoleny rok. */
    private var spinnerMonths: List<String> = emptyList()

    /** Blokuje prekresleni, dokud se plni spinnery. */
    private var updatingSpinners = false

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentEnergyBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        ZoomHelper.setupPinchToZoom(binding.root)

        binding.tvEnergyTitle.setText(
            if (kind == Kind.ELECTRIC) R.string.energy_electric else R.string.energy_gasoline
        )
        binding.tvEnergyEmpty.setText(
            if (kind == Kind.ELECTRIC) R.string.energy_no_electric else R.string.energy_no_gasoline
        )

        binding.spinnerPeriod.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                if (updatingSpinners) return
                // Prvni polozka je "Cele obdobi", dalsi jsou jednotlive roky
                val year = if (pos == 0) null else spinnerYears.getOrNull(pos - 1)
                if (year != selectedYear) {
                    selectedYear = year
                    selectedMonth = null
                    val list = viewModel.allSessions.value.orEmpty()
                    setupMonthSpinner(list)
                    render(list)
                }
            }

            override fun onNothingSelected(p: AdapterView<*>?) = Unit
        }

        binding.spinnerMonth.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                if (updatingSpinners) return
                // Prvni polozka je "Cely rok", dalsi jsou jednotlive mesice
                val month = if (pos == 0) null else spinnerMonths.getOrNull(pos - 1)
                if (month != selectedMonth) {
                    selectedMonth = month
                    render(viewModel.allSessions.value.orEmpty())
                }
            }

            override fun onNothingSelected(p: AdapterView<*>?) = Unit
        }

        binding.chipGroupMetric.setOnCheckedStateChangeListener { _, _ ->
            if (!updatingSpinners) renderChart(viewModel.allSessions.value.orEmpty())
        }

        viewModel.allSessions.observe(viewLifecycleOwner) { sessions ->
            val list = sessions.orEmpty()
            setupPeriodSpinner(list)
            setupMonthSpinner(list)
            render(list)
        }
    }

    // ---------------------------------------------------------------
    // Vyber obdobi
    // ---------------------------------------------------------------

    /**
     * Naplni spinner roky, ze kterych existuji zaznamy.
     * Adapter se prestavuje jen pri zmene nabidky, aby vyber uzivatele neodskakoval.
     */
    private fun setupPeriodSpinner(sessions: List<ChargingSession>) {
        val years = EvCalc.availableYears(sessions)
        if (years == spinnerYears && binding.spinnerPeriod.adapter != null) return

        spinnerYears = years
        val currentYear = LocalDate.now().year.toString()

        val labels = mutableListOf(getString(R.string.period_all))
        labels += years.map { year ->
            if (year == currentYear) getString(R.string.period_current_year, year) else year
        }

        updatingSpinners = true
        binding.spinnerPeriod.adapter =
            ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, labels)
                .also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }

        // Zachovat drive zvoleny rok, pokud v nabidce zustal
        val keepIndex = selectedYear?.let { years.indexOf(it).takeIf { i -> i >= 0 }?.plus(1) } ?: 0
        binding.spinnerPeriod.setSelection(keepIndex)
        selectedYear = if (keepIndex == 0) null else years[keepIndex - 1]
        updatingSpinners = false

        binding.tvPeriodLabel.visibility = View.VISIBLE
        binding.spinnerPeriod.visibility = View.VISIBLE
    }

    /**
     * Naplni spinner mesicu pro zvoleny rok.
     * Bez zvoleneho roku nema vyber mesice smysl a spinner se skryje.
     */
    private fun setupMonthSpinner(sessions: List<ChargingSession>) {
        val year = selectedYear
        if (year == null) {
            binding.spinnerMonth.visibility = View.INVISIBLE
            spinnerMonths = emptyList()
            selectedMonth = null
            return
        }

        val months = EvCalc.availableMonths(sessions, year)
        spinnerMonths = months
        binding.spinnerMonth.visibility = View.VISIBLE

        val labels = mutableListOf(getString(R.string.period_whole_year))
        labels += months.map { "$it/$year" }

        updatingSpinners = true
        binding.spinnerMonth.adapter =
            ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, labels)
                .also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }

        val keepIndex = selectedMonth?.let { months.indexOf(it).takeIf { i -> i >= 0 }?.plus(1) } ?: 0
        binding.spinnerMonth.setSelection(keepIndex)
        selectedMonth = if (keepIndex == 0) null else months[keepIndex - 1]
        updatingSpinners = false
    }

    // ---------------------------------------------------------------
    // Vykresleni
    // ---------------------------------------------------------------

    private fun render(allSessions: List<ChargingSession>) {
        if (_binding == null) return

        val inPeriod = allSessions.filter { inSelectedPeriod(it) }
        val own = inPeriod.filter { matchesKind(it) }

        val hasData = own.isNotEmpty()
        setSectionsVisible(hasData)
        binding.tvEnergyEmpty.visibility = if (hasData) View.GONE else View.VISIBLE
        if (!hasData) return

        val capacity = SettingsActivity.getBatteryCapacity(requireContext())

        // Souhrn za obdobi. Vzdalenost se bere z useku mezi odecty tachometru
        // pres celou historii, zapocitaji se jen useky uvnitr obdobi.
        val stats = periodStats(allSessions, capacity)

        // Nadpis sekce podle toho, jestli jde o celou historii nebo jeden rok
        binding.tvHeaderTotal.setText(
            if (selectedYear == null) R.string.dashboard_all_time else R.string.energy_section_period
        )

        renderChart(allSessions)
        renderConsumption(allSessions, inPeriod, stats)
        renderTotals(own, binding.containerTotal)
        renderSeasons(allSessions, capacity)
        renderBreakdown(own)
    }

    /** Predpona data pro zvolene obdobi: null = vse, "2026" = rok, "2026-03" = mesic. */
    private fun periodPrefix(): String? {
        val year = selectedYear ?: return null
        val month = selectedMonth ?: return year
        return "$year-$month"
    }

    /** Souhrn za zvolene obdobi; pro "cele obdobi" se pocita z cele historie. */
    private fun periodStats(allSessions: List<ChargingSession>, capacity: Double): EvCalc.PeriodStats? {
        val prefix = periodPrefix()
        if (prefix != null) {
            val length = prefix.length
            return EvCalc.statsByPeriod(allSessions, capacity) { date ->
                date.take(length).takeIf { it.length == length }
            }[prefix]
        }
        val totals = EvCalc.totals(allSessions) ?: return null
        return EvCalc.PeriodStats(
            key = "ALL",
            sessionCount = allSessions.size,
            totals = totals,
            batteryConsumptionPer100Km = EvCalc.batteryConsumptionPer100Km(allSessions, capacity),
            averageEfficiency = EvCalc.averageEfficiency(allSessions, capacity),
            electricRangeKm = EvCalc.electricRangeKm(allSessions, capacity)
        )
    }

    private fun inSelectedPeriod(session: ChargingSession): Boolean {
        val prefix = periodPrefix() ?: return true
        return session.date.startsWith(prefix)
    }

    private fun matchesKind(session: ChargingSession): Boolean =
        if (kind == Kind.GASOLINE) session.type == ChargingType.GASOLINE
        else session.type != ChargingType.GASOLINE

    // ---------------------------------------------------------------
    // Rychly prehled (sloupcovy graf)
    // ---------------------------------------------------------------

    /**
     * Sloupcovy graf o uroven nize, nez je zvolene obdobi:
     * cela historie se ukaze po letech, zvoleny rok po mesicich.
     *
     * Kdyz je zvoleny konkretni mesic, kresli se stejne mesice jeho roku -
     * jediny sloupec by nerekl nic, kdezto v kontextu roku je videt,
     * jak ten mesic vychazi proti ostatnim.
     */
    private fun renderChart(allSessions: List<ChargingSession>) {
        if (_binding == null) return

        val capacity = SettingsActivity.getBatteryCapacity(requireContext())
        val year = selectedYear

        val buckets: List<EvCalc.PeriodStats>
        val titleRes: Int
        if (year == null) {
            buckets = EvCalc.statsByYear(allSessions, capacity)
            titleRes = R.string.chart_by_years
        } else {
            buckets = EvCalc.statsByMonth(allSessions, capacity).filter { it.key.startsWith(year) }
            titleRes = R.string.chart_by_months
        }

        binding.tvChartTitle.text =
            getString(R.string.chart_quick_overview) + " \u2013 " + getString(titleRes)

        val bars = buckets.map { st ->
            BarChartHelper.Bar(chartLabel(st.key), metricValue(st))
        }

        val colorRes = if (kind == Kind.GASOLINE) R.color.type_gasoline else R.color.type_fve
        val decimals = if (binding.chipCost.isChecked) 0 else 1
        val drawn = BarChartHelper.show(binding.energyChart, bars, colorRes, decimals)

        binding.energyChart.visibility = if (drawn) View.VISIBLE else View.GONE
        binding.tvChartEmpty.visibility = if (drawn) View.GONE else View.VISIBLE
    }

    /** Hodnota sloupce podle zvolene metriky. */
    private fun metricValue(st: EvCalc.PeriodStats): Double? {
        val isGas = kind == Kind.GASOLINE
        return when {
            binding.chipAmount.isChecked ->
                if (isGas) st.totals.gasolineLiters else st.totals.electricKwh
            binding.chipCost.isChecked ->
                if (isGas) st.totals.gasolineCost else st.totals.electricCost
            else ->
                if (isGas) st.totals.gasolineLitersPer100Km else st.totals.electricKwhPer100Km
        }?.takeIf { it > 0.0 }
    }

    /** "2026-03" -> "03", "2026" -> "2026" */
    private fun chartLabel(key: String): String =
        if (key.length == 7) key.substring(5, 7) else key

    // ---------------------------------------------------------------
    // Spotreba
    // ---------------------------------------------------------------

    private fun renderConsumption(
        allSessions: List<ChargingSession>,
        inPeriod: List<ChargingSession>,
        stats: EvCalc.PeriodStats?
    ) {
        val container = binding.containerConsumption
        container.removeAllViews()

        val wholeHistory = selectedYear == null
        if (kind == Kind.GASOLINE) {
            renderGasolineConsumption(allSessions, inPeriod, stats, wholeHistory, container)
        } else {
            renderElectricConsumption(inPeriod, stats, container)
        }

        val visible = container.childCount > 0
        binding.tvHeaderConsumption.visibility = if (visible) View.VISIBLE else View.GONE
        binding.tvConsumptionHint.visibility = if (visible) View.VISIBLE else View.GONE
    }

    private fun renderGasolineConsumption(
        allSessions: List<ChargingSession>,
        inPeriod: List<ChargingSession>,
        stats: EvCalc.PeriodStats?,
        wholeHistory: Boolean,
        container: ViewGroup
    ) {
        if (wholeHistory) {
            // Pres celou historii lze pouzit presnejsi metodu plne nadrze
            binding.tvConsumptionHint.setText(R.string.hint_full_tank)
            val consumption = EvCalc.gasolineConsumption(allSessions)

            consumption?.averagePer100Km?.let {
                addRow(container, getString(R.string.stats_avg_consumption),
                    getString(R.string.fmt_consumption, round1(it)))
            }
            consumption?.lastPer100Km?.let {
                addRow(container, getString(R.string.stats_last_consumption),
                    getString(R.string.fmt_consumption, round1(it)))
            }
        } else {
            // Uvnitr roku: litry v obdobi na kilometry najete v obdobi
            binding.tvConsumptionHint.setText(R.string.hint_period_consumption)
            stats?.totals?.gasolineLitersPer100Km?.takeIf { it > 0.0 }?.let {
                addRow(container, getString(R.string.stats_avg_consumption),
                    getString(R.string.fmt_consumption, round1(it)))
            }
        }

        stats?.totals?.gasolineCostPer100Km?.takeIf { it > 0.0 }?.let {
            addRow(container, getString(R.string.stats_cost_per_100km),
                getString(R.string.fmt_cost_per_100km, NumberUtil.formatCost(it)))
        }
        EvCalc.gasolineConsumption(inPeriod)?.averagePricePerLiter?.let {
            addRow(container, getString(R.string.stats_avg_price_liter),
                getString(R.string.fmt_cost, round1(it)))
        }
        stats?.totals?.distanceKm?.takeIf { it > 0.0 }?.let {
            addRow(container, getString(R.string.stats_distance), getString(R.string.fmt_km, round0(it)))
        }
    }

    private fun renderElectricConsumption(
        inPeriod: List<ChargingSession>,
        stats: EvCalc.PeriodStats?,
        container: ViewGroup
    ) {
        binding.tvConsumptionHint.setText(
            if (selectedYear == null) R.string.hint_combined_consumption
            else R.string.hint_period_consumption
        )

        stats?.totals?.electricKwhPer100Km?.takeIf { it > 0.0 }?.let {
            addRow(container, getString(R.string.stats_electric_consumption),
                getString(R.string.fmt_kwh_per_100km, round1(it)))
        }
        stats?.batteryConsumptionPer100Km?.let {
            addRow(container, getString(R.string.stats_battery_consumption),
                getString(R.string.fmt_kwh_per_100km, round1(it)))
        }
        stats?.electricRangeKm?.let {
            addRow(container, getString(R.string.stats_electric_range),
                getString(R.string.fmt_km, round0(it)))
        }
        stats?.averageEfficiency?.let {
            addRow(container, getString(R.string.stats_avg_efficiency),
                getString(R.string.fmt_percent, round0(it * 100)))
        }
        stats?.totals?.electricCostPer100Km?.takeIf { it > 0.0 }?.let {
            addRow(container, getString(R.string.stats_cost_per_100km),
                getString(R.string.fmt_cost_per_100km, NumberUtil.formatCost(it)))
        }
        EvCalc.averagePricePerKwh(inPeriod)?.let {
            addRow(container, getString(R.string.stats_avg_price_kwh),
                getString(R.string.fmt_cost, round1(it)))
        }
        stats?.totals?.distanceKm?.takeIf { it > 0.0 }?.let {
            addRow(container, getString(R.string.stats_distance), getString(R.string.fmt_km, round0(it)))
        }
    }

    // ---------------------------------------------------------------
    // Souhrny
    // ---------------------------------------------------------------

    private fun renderTotals(sessions: List<ChargingSession>, container: ViewGroup) {
        container.removeAllViews()

        val isGas = kind == Kind.GASOLINE
        val amount = sessions.sumOf { it.chargedKwh }
        val cost = sessions.sumOf { it.totalCost }

        addRow(container, getString(R.string.stats_sessions),
            getString(R.string.stats_sessions_val, sessions.size))

        if (amount > 0.0) {
            addRow(
                container,
                getString(if (isGas) R.string.stats_total_gas else R.string.stats_total_kwh),
                getString(
                    if (isGas) R.string.stats_total_gas_val else R.string.stats_total_kwh_val,
                    round1(amount)
                )
            )
        }
        if (cost > 0.0) {
            addRow(container, getString(R.string.stats_total_cost),
                getString(R.string.stats_total_cost_val, NumberUtil.formatCost(cost)))
        }
    }

    /**
     * Sezonni spotreba. Pri zvolenem roce se sezony pocitaji v ramci toho roku,
     * aby se zima 2025 a zima 2026 necitaly dohromady.
     */
    private fun renderSeasons(allSessions: List<ChargingSession>, capacity: Double) {
        val container = binding.containerSeasons
        container.removeAllViews()

        // U jednoho mesice nema cleneni po sezonach smysl - je cely v jedne z nich
        if (selectedMonth != null) {
            binding.tvHeaderSeasons.visibility = View.GONE
            binding.dividerSeasons.visibility = View.GONE
            return
        }

        val year = selectedYear
        val byKey = EvCalc.statsByPeriod(allSessions, capacity) { date ->
            EvCalc.Season.fromIsoDate(date)?.let { season ->
                if (year == null) season.name else "${date.take(4)}-${season.name}"
            }
        }

        EvCalc.Season.ordered.forEach { season ->
            val key = if (year == null) season.name else "$year-${season.name}"
            val st = byKey[key] ?: return@forEach
            val value = if (kind == Kind.GASOLINE) {
                st.totals.gasolineLitersPer100Km?.takeIf { it > 0.0 }
                    ?.let { getString(R.string.fmt_consumption, round1(it)) }
            } else {
                // Realna spotreba baterie je presnejsi; kdyz na ni nejsou data,
                // pouzije se spotreba pres vsechny ujete kilometry
                st.batteryConsumptionPer100Km?.let { getString(R.string.fmt_kwh_per_100km, round1(it)) }
                    ?: st.totals.electricKwhPer100Km?.takeIf { it > 0.0 }
                        ?.let { getString(R.string.fmt_kwh_per_100km, round1(it)) }
            }
            if (value != null) addRow(container, getString(seasonLabelRes(season)), value)
        }

        val visible = container.childCount > 0
        binding.tvHeaderSeasons.visibility = if (visible) View.VISIBLE else View.GONE
        binding.dividerSeasons.visibility = if (visible) View.VISIBLE else View.GONE
    }

    /** Cleneni podle typu nabijeni - u benzinu nedava smysl, je jen jeden typ. */
    private fun renderBreakdown(own: List<ChargingSession>) {
        val container = binding.containerBreakdown
        container.removeAllViews()

        if (kind == Kind.GASOLINE) {
            setBreakdownVisible(false)
            return
        }

        own.groupBy { it.chargingType }
            .toList()
            .sortedByDescending { (_, list) -> list.sumOf { it.chargedKwh } }
            .forEach { (typeName, list) ->
                val type = ChargingType.fromString(typeName)
                val kwh = list.sumOf { it.chargedKwh }
                addRow(
                    container,
                    getString(type.labelRes()),
                    getString(R.string.fmt_breakdown_value, round1(kwh), list.size)
                )
            }

        setBreakdownVisible(container.childCount > 0)
    }

    // ---------------------------------------------------------------
    // Pomocne
    // ---------------------------------------------------------------

    private fun addRow(container: ViewGroup, label: String, value: String) {
        val row = ViewDetailRowBinding.inflate(layoutInflater, container, false)
        row.tvLabel.text = label
        row.tvValue.text = value
        container.addView(row.root)
    }

    private fun setSectionsVisible(visible: Boolean) {
        val v = if (visible) View.VISIBLE else View.GONE
        binding.tvHeaderConsumption.visibility = v
        binding.tvConsumptionHint.visibility = v
        binding.tvHeaderTotal.visibility = v
        binding.tvHeaderSeasons.visibility = v
        binding.tvChartTitle.visibility = v
        binding.chipGroupMetric.visibility = v
        if (!visible) {
            binding.energyChart.visibility = View.GONE
            binding.tvChartEmpty.visibility = View.GONE
        }
        binding.dividerSeasons.visibility = v
        if (!visible) {
            binding.containerConsumption.removeAllViews()
            binding.containerTotal.removeAllViews()
            binding.containerSeasons.removeAllViews()
            setBreakdownVisible(false)
        }
    }

    private fun setBreakdownVisible(visible: Boolean) {
        val v = if (visible) View.VISIBLE else View.GONE
        binding.tvHeaderBreakdown.visibility = v
        binding.dividerBreakdown.visibility = v
        binding.containerBreakdown.visibility = v
    }

    private fun seasonLabelRes(season: EvCalc.Season): Int = when (season) {
        EvCalc.Season.SPRING -> R.string.season_spring
        EvCalc.Season.SUMMER -> R.string.season_summer
        EvCalc.Season.AUTUMN -> R.string.season_autumn
        EvCalc.Season.WINTER -> R.string.season_winter
    }

    private fun round0(value: Double): String = NumberUtil.format(Math.round(value).toDouble())
    private fun round1(value: Double): String = NumberUtil.format(Math.round(value * 10) / 10.0)

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
