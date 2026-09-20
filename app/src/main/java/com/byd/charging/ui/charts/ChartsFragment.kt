package com.byd.charging.ui.charts

import android.app.DatePickerDialog
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import com.byd.charging.R
import com.byd.charging.data.ChargingType
import com.byd.charging.data.MonthlyStats
import com.byd.charging.data.TypeStats
import com.byd.charging.databinding.FragmentChartsBinding
import com.byd.charging.ui.main.MainViewModel
import com.byd.charging.data.ChargingSession
import com.byd.charging.ui.settings.SettingsActivity
import com.byd.charging.util.DateUtil
import com.byd.charging.util.EvCalc
import com.byd.charging.util.NumberUtil
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.data.*
import com.github.mikephil.charting.interfaces.datasets.ILineDataSet
import com.github.mikephil.charting.formatter.IndexAxisValueFormatter
import com.github.mikephil.charting.formatter.ValueFormatter
import java.util.Calendar

class ChartsFragment : Fragment() {

    private var _binding: FragmentChartsBinding? = null
    private val binding get() = _binding!!
    private val viewModel: MainViewModel by activityViewModels()

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentChartsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupFilters()

        binding.chipByType.isChecked = true
        binding.chipGroup.setOnCheckedStateChangeListener { _, _ -> updateChart() }

        viewModel.typeStats.observe(viewLifecycleOwner) { updateChart() }
        viewModel.monthlyStats.observe(viewLifecycleOwner) { updateChart() }

        // Souhrnné statistiky (z filtrovaných dat)
        viewModel.filteredSessions.observe(viewLifecycleOwner) { sessions ->
            val totalKwh  = sessions.filter { it.type != ChargingType.GASOLINE }.sumOf { it.chargedKwh }
            val totalCost = sessions.sumOf { it.totalCost }
            binding.tvTotalKwh.text = getString(R.string.stats_total_kwh_val,
                NumberUtil.format(totalKwh))
            binding.tvTotalCost.text = getString(R.string.stats_total_cost_val,
                NumberUtil.formatCost(totalCost))
            binding.tvTotalSessions.text = getString(R.string.stats_sessions_val,
                sessions.size)
            updateChart()
        }
    }

    private fun setupFilters() {
        // Výchozí hodnoty v UI
        binding.etDateFrom.setText("01.01.2000")
        binding.etDateTo.setText(DateUtil.todayDisplay())

        binding.etDateFrom.setOnClickListener {
            showDatePicker(binding.etDateFrom.text.toString()) { 
                binding.etDateFrom.setText(it)
                notifyFilterChanged()
            }
        }
        binding.etDateTo.setOnClickListener {
            showDatePicker(binding.etDateTo.text.toString()) { 
                binding.etDateTo.setText(it)
                notifyFilterChanged()
            }
        }

        // Spinner typů
        val types = mutableListOf(getString(R.string.type_all))
        types.addAll(ChargingType.all.map { getString(it.labelRes()) })
        val adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, types)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.spinnerFilterType.adapter = adapter

        binding.spinnerFilterType.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p0: AdapterView<*>?, p1: View?, pos: Int, p3: Long) {
                notifyFilterChanged()
            }
            override fun onNothingSelected(p0: AdapterView<*>?) {}
        }
    }

    private fun notifyFilterChanged() {
        val start = DateUtil.toStorageDate(binding.etDateFrom.text.toString())
        val end = DateUtil.toStorageDate(binding.etDateTo.text.toString())
        val typePos = binding.spinnerFilterType.selectedItemPosition
        val type = if (typePos == 0) null else ChargingType.all[typePos - 1].name
        
        viewModel.setDateRange(start, end)
        viewModel.setFilterType(type)
    }

    private fun showDatePicker(currentDisplay: String, onSelected: (String) -> Unit) {
        val cal = Calendar.getInstance()
        DateUtil.parseDisplayToCalendar(currentDisplay)?.let { (y, m, d) -> cal.set(y, m, d) }
        DatePickerDialog(requireContext(), { _, y, m, d ->
            val iso = String.format("%04d-%02d-%02d", y, m + 1, d)
            onSelected(DateUtil.toDisplayDate(iso))
        }, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)).show()
    }

    private fun updateChart() {
        val b = _binding ?: return
        val stats = viewModel.typeStats.value.orEmpty()
        val mStats = viewModel.monthlyStats.value.orEmpty()

        when {
            b.chipByType.isChecked ->
                showPieChart(stats)
            b.chipMonthlyKwh.isChecked ->
                // Pro kWh graf vyfiltrujeme benzín, protože litry nelze sčítat s kWh
                showBarChart(mStats.map { it.copy(totalKwh = filterOnlyElectricKwh(it.month)) }, showCost = false)
            b.chipMonthlyCost.isChecked ->
                showBarChart(mStats, showCost = true)
            b.chipBySeason.isChecked ->
                showSeasonChart(viewModel.filteredSessions.value.orEmpty())
            b.chipSoc.isChecked ->
                showSocChart(viewModel.filteredSessions.value.orEmpty())
            b.chipEfficiency.isChecked ->
                showEfficiencyChart(viewModel.filteredSessions.value.orEmpty())
        }
    }

    private fun filterOnlyElectricKwh(month: String): Double {
        val sessions = viewModel.filteredSessions.value.orEmpty()
        return sessions.filter { it.date.startsWith(month) && it.type != ChargingType.GASOLINE }
            .sumOf { it.chargedKwh }
    }

    private fun showPieChart(stats: List<TypeStats>) {
        binding.pieChart.visibility = View.VISIBLE
        binding.barChart.visibility = View.GONE
        binding.lineChart.visibility = View.GONE
        binding.tvNoData.visibility = View.GONE

        if (stats.isEmpty()) {
            binding.tvNoData.visibility = View.VISIBLE
            binding.pieChart.visibility = View.GONE
            return
        }

        val entries = stats.mapNotNull { s ->
            if (s.totalKwh <= 0.0) return@mapNotNull null
            val type = ChargingType.fromString(s.chargingType)
            PieEntry(s.totalKwh.toFloat(), getString(type.labelRes()))
        }
        if (entries.isEmpty()) {
            binding.tvNoData.visibility = View.VISIBLE
            binding.pieChart.visibility = View.GONE
            return
        }

        val colors = stats
            .filter { it.totalKwh > 0.0 }
            .map { s -> ContextCompat.getColor(requireContext(), ChargingType.fromString(s.chargingType).colorRes()) }

        val textColor = ContextCompat.getColor(requireContext(), android.R.color.tab_indicator_text)
        val isDarkMode = (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES
        val dynamicTextColor = if (isDarkMode) Color.WHITE else Color.BLACK

        val dataSet = PieDataSet(entries, "").apply {
            this.colors = colors
            valueTextSize = 12f
            valueTextColor = Color.WHITE
            valueFormatter = object : ValueFormatter() {
                override fun getPieLabel(value: Float, entry: PieEntry?): String {
                    val label = entry?.label ?: ""
                    val unit = if (label == getString(R.string.type_gasoline)) "l" else "kWh"
                    return "${NumberUtil.format(value.toDouble())} $unit"
                }
            }
        }

        binding.pieChart.apply {
            data = PieData(dataSet)
            description.isEnabled = false
            legend.apply {
                isEnabled = true
                this.textColor = dynamicTextColor
            }
            setEntryLabelColor(Color.WHITE)
            animateY(600)
            invalidate()
        }
    }

    private fun showBarChart(stats: List<MonthlyStats>, showCost: Boolean) {
        binding.pieChart.visibility = View.GONE
        binding.lineChart.visibility = View.GONE
        binding.barChart.visibility = View.VISIBLE
        binding.tvNoData.visibility = View.GONE

        if (stats.isEmpty()) {
            binding.tvNoData.visibility = View.VISIBLE
            binding.barChart.visibility = View.GONE
            return
        }

        val isDarkMode = (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES
        val dynamicTextColor = if (isDarkMode) Color.WHITE else Color.BLACK

        val labels = stats.map { s -> formatMonthLabel(s.month) }
        val entries = stats.mapIndexed { i, s ->
            BarEntry(i.toFloat(), if (showCost) s.totalCost.toFloat() else s.totalKwh.toFloat())
        }

        val barColor = ContextCompat.getColor(
            requireContext(),
            if (showCost) R.color.type_public else R.color.type_fve
        )

        val dataSet = BarDataSet(entries, "").apply {
            color = barColor
            valueTextSize = 10f
            valueTextColor = dynamicTextColor
        }

        binding.barChart.apply {
            data = BarData(dataSet).apply { barWidth = 0.6f }
            description.isEnabled = false
            xAxis.apply {
                position = XAxis.XAxisPosition.BOTTOM
                valueFormatter = IndexAxisValueFormatter(labels)
                granularity = 1f
                // Reset po sezonnim grafu, ktery osu prepina na skupinove sloupce
                setCenterAxisLabels(false)
                resetAxisMinimum()
                resetAxisMaximum()
                this.textColor = dynamicTextColor
            }
            legend.isEnabled = false
            axisLeft.textColor = dynamicTextColor
            axisRight.isEnabled = false
            legend.textColor = dynamicTextColor
            animateY(600)
            invalidate()
        }
    }

    /**
     * Porovnani sezon: kolik elektriny a kolik benzinu padne na 100 km.
     *
     * U hybridu je tohle to zajimave cislo - v lete jede vic na baterii, v zime
     * vic na benzin. Obe rady jsou vztazene na stejne kilometry, takze se daji
     * cist vedle sebe (jednotky se ale nescitaji).
     */
    private fun showSeasonChart(sessions: List<ChargingSession>) {
        val capacity = SettingsActivity.getBatteryCapacity(requireContext())
        val stats = EvCalc.seasonalStats(sessions, capacity)
            .filter { it.totals.distanceKm > 0.0 }

        if (stats.isEmpty()) {
            showEmptyBarChart()
            return
        }

        val labels = stats.map { getString(seasonLabelRes(it.season)) }
        val electricEntries = stats.mapIndexed { i, st ->
            BarEntry(i.toFloat(), (st.totals.electricKwhPer100Km ?: 0.0).toFloat())
        }
        val gasolineEntries = stats.mapIndexed { i, st ->
            BarEntry(i.toFloat(), (st.totals.gasolineLitersPer100Km ?: 0.0).toFloat())
        }

        binding.pieChart.visibility = View.GONE
        binding.lineChart.visibility = View.GONE
        binding.barChart.visibility = View.VISIBLE
        binding.tvNoData.visibility = View.GONE

        val isDarkMode = (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES
        val dynamicTextColor = if (isDarkMode) Color.WHITE else Color.BLACK

        val electricSet = BarDataSet(electricEntries, getString(R.string.chart_legend_electric)).apply {
            color = ContextCompat.getColor(requireContext(), R.color.type_fve)
            valueTextSize = 9f
            valueTextColor = dynamicTextColor
            valueFormatter = oneDecimalFormatter()
        }
        val gasolineSet = BarDataSet(gasolineEntries, getString(R.string.chart_legend_gasoline)).apply {
            color = ContextCompat.getColor(requireContext(), R.color.type_gasoline)
            valueTextSize = 9f
            valueTextColor = dynamicTextColor
            valueFormatter = oneDecimalFormatter()
        }

        // Skupinove sloupce: groupSpace + pocetRad * (barWidth + barSpace) musi dat presne 1
        val barWidth = 0.35f
        val barSpace = 0.03f
        val groupSpace = 1f - 2f * (barWidth + barSpace)

        binding.barChart.apply {
            data = BarData(electricSet, gasolineSet).apply { this.barWidth = barWidth }
            description.isEnabled = false
            xAxis.apply {
                position = XAxis.XAxisPosition.BOTTOM
                valueFormatter = IndexAxisValueFormatter(labels)
                granularity = 1f
                setCenterAxisLabels(true)
                axisMinimum = 0f
                axisMaximum = stats.size.toFloat()
                this.textColor = dynamicTextColor
            }
            axisLeft.apply {
                axisMinimum = 0f
                this.textColor = dynamicTextColor
            }
            axisRight.isEnabled = false
            legend.apply {
                isEnabled = true
                this.textColor = dynamicTextColor
            }
            groupBars(0f, groupSpace, barSpace)
            animateY(600)
            invalidate()
        }
    }

    private fun oneDecimalFormatter(): ValueFormatter = object : ValueFormatter() {
        override fun getFormattedValue(value: Float): String =
            if (value <= 0f) "" else NumberUtil.format(Math.round(value * 10) / 10.0)
    }

    private fun seasonLabelRes(season: EvCalc.Season): Int = when (season) {
        EvCalc.Season.SPRING -> R.string.season_spring
        EvCalc.Season.SUMMER -> R.string.season_summer
        EvCalc.Season.AUTUMN -> R.string.season_autumn
        EvCalc.Season.WINTER -> R.string.season_winter
    }

    private fun showEmptyBarChart() {
        binding.pieChart.visibility = View.GONE
        binding.barChart.visibility = View.GONE
        binding.lineChart.visibility = View.GONE
        binding.tvNoData.visibility = View.VISIBLE
    }

    /**
     * Vyvoj stavu baterie v case: dve krivky (zacatek a konec nabijeni).
     * Zobrazi jen zaznamy, u kterych je stav baterie skutecne zadan.
     */
    private fun showSocChart(sessions: List<ChargingSession>) {
        val ordered = sessions
            .filter { it.type != ChargingType.GASOLINE && (it.hasSocStart || it.hasSocEnd) }
            .sortedWith(compareBy({ it.date }, { it.startTime }))

        if (ordered.isEmpty()) {
            showEmptyLineChart()
            return
        }

        val startEntries = ordered.mapIndexedNotNull { i, s ->
            if (s.hasSocStart) Entry(i.toFloat(), s.socStart.toFloat()) else null
        }
        val endEntries = ordered.mapIndexedNotNull { i, s ->
            if (s.hasSocEnd) Entry(i.toFloat(), s.socEnd.toFloat()) else null
        }

        val sets = mutableListOf<ILineDataSet>()
        if (startEntries.isNotEmpty()) {
            sets += lineDataSet(startEntries, getString(R.string.chart_legend_soc_start), R.color.type_garage)
        }
        if (endEntries.isNotEmpty()) {
            sets += lineDataSet(endEntries, getString(R.string.chart_legend_soc_end), R.color.type_fve)
        }
        if (sets.isEmpty()) {
            showEmptyLineChart()
            return
        }

        drawLineChart(sets, ordered.map { DateUtil.toDisplayDate(it.date) }, maxY = 100f)
    }

    /**
     * Ucinnost nabijeni v case. Podezrele hodnoty (preklep v %, kWh nebo kapacite)
     * se do grafu nekresli, aby nezkreslily meritko.
     */
    private fun showEfficiencyChart(sessions: List<ChargingSession>) {
        val capacity = SettingsActivity.getBatteryCapacity(requireContext())
        val ordered = sessions.sortedWith(compareBy({ it.date }, { it.startTime }))

        val labels = mutableListOf<String>()
        val entries = mutableListOf<Entry>()
        for (s in ordered) {
            val efficiency = EvCalc.efficiency(s, capacity) ?: continue
            if (EvCalc.isEfficiencySuspicious(efficiency)) continue
            entries += Entry(entries.size.toFloat(), (efficiency * 100).toFloat())
            labels += DateUtil.toDisplayDate(s.date)
        }

        if (entries.isEmpty()) {
            showEmptyLineChart()
            return
        }

        drawLineChart(
            listOf(lineDataSet(entries, getString(R.string.chart_legend_efficiency), R.color.type_grid)),
            labels,
            maxY = 110f
        )
    }

    private fun lineDataSet(entries: List<Entry>, label: String, colorRes: Int): LineDataSet {
        val lineColor = ContextCompat.getColor(requireContext(), colorRes)
        return LineDataSet(entries, label).apply {
            color = lineColor
            setCircleColor(lineColor)
            lineWidth = 2f
            circleRadius = 3.5f
            setDrawCircleHole(false)
            setDrawValues(false)
        }
    }

    private fun drawLineChart(sets: List<ILineDataSet>, labels: List<String>, maxY: Float) {
        binding.pieChart.visibility = View.GONE
        binding.barChart.visibility = View.GONE
        binding.lineChart.visibility = View.VISIBLE
        binding.tvNoData.visibility = View.GONE

        val isDarkMode = (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES
        val dynamicTextColor = if (isDarkMode) Color.WHITE else Color.BLACK

        binding.lineChart.apply {
            data = LineData(sets)
            description.isEnabled = false
            xAxis.apply {
                position = XAxis.XAxisPosition.BOTTOM
                valueFormatter = IndexAxisValueFormatter(labels)
                granularity = 1f
                labelRotationAngle = -45f
                this.textColor = dynamicTextColor
            }
            axisLeft.apply {
                axisMinimum = 0f
                axisMaximum = maxY
                this.textColor = dynamicTextColor
            }
            axisRight.isEnabled = false
            legend.textColor = dynamicTextColor
            animateY(600)
            invalidate()
        }
    }

    private fun showEmptyLineChart() {
        binding.pieChart.visibility = View.GONE
        binding.barChart.visibility = View.GONE
        binding.lineChart.visibility = View.GONE
        binding.tvNoData.visibility = View.VISIBLE
    }

    private fun formatMonthLabel(month: String): String {
        return try {
            val parts = month.split("-")
            if (parts.size >= 2) "${parts[1]}/${parts[0].takeLast(2)}" else month
        } catch (_: Exception) { month }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
