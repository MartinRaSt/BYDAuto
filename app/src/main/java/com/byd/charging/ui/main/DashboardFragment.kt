package com.byd.charging.ui.main

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import com.byd.charging.R
import com.byd.charging.data.ChargingSession
import com.byd.charging.data.ChargingType
import com.byd.charging.databinding.FragmentDashboardBinding
import com.byd.charging.ui.addedit.AddEditActivity
import com.byd.charging.ui.settings.SettingsActivity
import com.byd.charging.util.EvCalc
import com.byd.charging.util.NumberUtil
import com.byd.charging.util.ZoomHelper
import java.time.LocalDate
import java.time.format.DateTimeFormatter

class DashboardFragment : Fragment() {

    private var _binding: FragmentDashboardBinding? = null
    private val binding get() = _binding!!
    private val viewModel: MainViewModel by activityViewModels()

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentDashboardBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        ZoomHelper.setupPinchToZoom(binding.root)

        binding.btnDashboardAdd.setOnClickListener {
            startActivity(Intent(requireContext(), AddEditActivity::class.java))
        }

        viewModel.allSessions.observe(viewLifecycleOwner) { sessions ->
            updateStats(sessions)
        }
    }

    private fun updateStats(sessions: List<ChargingSession>) {
        // --- Vše ---
        val totalSessions = sessions.size
        val totalKwh = sessions.filter { it.type != ChargingType.GASOLINE }.sumOf { it.chargedKwh }
        val totalGas = sessions.filter { it.type == ChargingType.GASOLINE }.sumOf { it.chargedKwh }
        val totalCost = sessions.sumOf { it.totalCost }
        
        binding.tvTotalSessions.text = getString(R.string.stats_sessions_val, totalSessions)
        binding.tvTotalKwh.text = getString(R.string.stats_total_kwh_val, NumberUtil.format(totalKwh))
        binding.tvTotalGas.text = getString(R.string.stats_total_gas_val, NumberUtil.format(totalGas))
        binding.tvTotalCost.text = getString(R.string.stats_total_cost_val, NumberUtil.formatCost(totalCost))
        
        updateGasolineConsumption(sessions)
        updateElectricStats(sessions)
        updateSeasonStats(sessions)
        
        binding.layoutTotalKwh.visibility = if (totalKwh > 0) View.VISIBLE else View.GONE
        binding.layoutTotalGas.visibility = if (totalGas > 0) View.VISIBLE else View.GONE
        binding.layoutTotalCost.visibility = if (totalCost > 0) View.VISIBLE else View.GONE
        
        binding.tvTotalBreakdown.text = formatBreakdown(sessions)

        // --- Aktuální měsíc ---
        val currentMonthPrefix = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM"))
        val monthSessions = sessions.filter { it.date.startsWith(currentMonthPrefix) }
        val monthKwh = monthSessions.filter { it.type != ChargingType.GASOLINE }.sumOf { it.chargedKwh }
        val monthGas = monthSessions.filter { it.type == ChargingType.GASOLINE }.sumOf { it.chargedKwh }
        val monthCost = monthSessions.sumOf { it.totalCost }
        
        binding.tvMonthSessions.text = getString(R.string.stats_sessions_val, monthSessions.size)
        binding.tvMonthKwh.text = getString(R.string.stats_total_kwh_val, NumberUtil.format(monthKwh))
        binding.tvMonthGas.text = getString(R.string.stats_total_gas_val, NumberUtil.format(monthGas))
        binding.tvMonthCost.text = getString(R.string.stats_total_cost_val, NumberUtil.formatCost(monthCost))

        binding.layoutMonthKwh.visibility = if (monthKwh > 0) View.VISIBLE else View.GONE
        binding.layoutMonthGas.visibility = if (monthGas > 0) View.VISIBLE else View.GONE
        binding.layoutMonthCost.visibility = if (monthCost > 0) View.VISIBLE else View.GONE

        binding.tvMonthBreakdown.text = formatBreakdown(monthSessions)
    }

    /**
     * Spotreba benzinu. Vypocet je v EvCalc.gasolineConsumption, aby dashboard
     * a zalozka Benzin ukazovaly stejne cislo a nemohly se rozejit.
     */
    private fun updateGasolineConsumption(sessions: List<ChargingSession>) {
        val consumption = EvCalc.gasolineConsumption(sessions)

        val last = consumption?.lastPer100Km
        show(binding.layoutLastConsumption, last != null) {
            binding.tvLastConsumption.text =
                getString(R.string.fmt_consumption, NumberUtil.format(round1(last!!)))
        }

        val average = consumption?.averagePer100Km
        show(binding.layoutAvgConsumption, average != null) {
            binding.tvAvgConsumption.text =
                getString(R.string.fmt_consumption, NumberUtil.format(round1(average!!)))
        }
    }

    /**
     * Statistiky odvozene z tachometru a stavu baterie.
     * Kazdy radek se skryje, pokud pro nej nejsou data - nic se neodhaduje.
     */
    private fun updateElectricStats(sessions: List<ChargingSession>) {
        val capacity = SettingsActivity.getBatteryCapacity(requireContext())
        val totals = EvCalc.totals(sessions)

        // Najeto celkem podle tachometru
        val distance = totals?.distanceKm
        show(binding.layoutDistance, distance != null && distance > 0.0) {
            binding.tvDistance.text = getString(R.string.fmt_km, NumberUtil.format(Math.round(distance!!).toDouble()))
        }

        // Elektrina na 100 km pres vsechny ujete kilometry
        val electricPer100 = totals?.electricKwhPer100Km
        show(binding.layoutElectricConsumption, electricPer100 != null && electricPer100 > 0.0) {
            binding.tvElectricConsumption.text =
                getString(R.string.fmt_kwh_per_100km, NumberUtil.format(round1(electricPer100!!)))
        }

        // Realna spotreba baterie z useku jizdy na baterii
        val batteryPer100 = EvCalc.batteryConsumptionPer100Km(sessions, capacity)
        show(binding.layoutBatteryConsumption, batteryPer100 != null) {
            binding.tvBatteryConsumption.text =
                getString(R.string.fmt_kwh_per_100km, NumberUtil.format(round1(batteryPer100!!)))
        }

        // Odhad dojezdu na plnou baterii
        val rangeKm = EvCalc.electricRangeKm(sessions, capacity)
        show(binding.layoutElectricRange, rangeKm != null) {
            binding.tvElectricRange.text =
                getString(R.string.fmt_km, NumberUtil.format(Math.round(rangeKm!!).toDouble()))
        }

        // Prumerna ucinnost nabijeni
        val efficiency = EvCalc.averageEfficiency(sessions, capacity)
        show(binding.layoutAvgEfficiency, efficiency != null) {
            binding.tvAvgEfficiency.text =
                getString(R.string.fmt_percent, NumberUtil.format(Math.round(efficiency!! * 100).toDouble()))
        }

        // Naklady na 100 km s rozpadem na elektrinu a benzin
        val costPer100 = totals?.totalCostPer100Km
        show(binding.layoutCostPer100Km, costPer100 != null && costPer100 > 0.0) {
            binding.tvCostPer100Km.text = getString(
                R.string.fmt_cost_split,
                NumberUtil.formatCost(costPer100!!),
                NumberUtil.formatCost(totals.electricCostPer100Km ?: 0.0),
                NumberUtil.formatCost(totals.gasolineCostPer100Km ?: 0.0)
            )
        }
    }

    /**
     * Porovnani sezon napric vsemi roky. U kazde sezony se ukaze to nejpresnejsi,
     * co z dat vyjde: realna spotreba baterie a dojezd, jinak spotreba na 100 km,
     * jinak alespon pocet zaznamu.
     */
    private fun updateSeasonStats(sessions: List<ChargingSession>) {
        val capacity = SettingsActivity.getBatteryCapacity(requireContext())
        val stats = EvCalc.seasonalStats(sessions, capacity)

        if (stats.isEmpty()) {
            binding.layoutSeasons.visibility = View.GONE
            return
        }

        val lines = stats.map { st ->
            val name = getString(seasonLabelRes(st.season))
            val battery = st.batteryConsumptionPer100Km
            val range = st.electricRangeKm
            val electric = st.totals.electricKwhPer100Km
            val gasoline = st.totals.gasolineLitersPer100Km

            when {
                battery != null && range != null -> getString(
                    R.string.fmt_season_battery,
                    name,
                    NumberUtil.format(round1(battery)),
                    NumberUtil.format(Math.round(range).toDouble())
                )
                electric != null || gasoline != null -> getString(
                    R.string.fmt_season_combined,
                    name,
                    NumberUtil.format(round1(electric ?: 0.0)),
                    NumberUtil.format(round1(gasoline ?: 0.0))
                )
                else -> getString(R.string.fmt_season_minimal, name, st.sessionCount)
            }
        }

        binding.layoutSeasons.visibility = View.VISIBLE
        binding.tvSeasons.text = lines.joinToString(System.lineSeparator())
    }

    private fun seasonLabelRes(season: EvCalc.Season): Int = when (season) {
        EvCalc.Season.SPRING -> R.string.season_spring
        EvCalc.Season.SUMMER -> R.string.season_summer
        EvCalc.Season.AUTUMN -> R.string.season_autumn
        EvCalc.Season.WINTER -> R.string.season_winter
    }

    /** Zobrazi blok jen kdyz je hodnota k dispozici. */
    private fun show(view: View, visible: Boolean, fill: () -> Unit) {
        view.visibility = if (visible) View.VISIBLE else View.GONE
        if (visible) fill()
    }

    /** Zaokrouhleni na jedno desetinne misto. */
    private fun round1(value: Double): Double = Math.round(value * 10) / 10.0

    private fun formatBreakdown(sessions: List<ChargingSession>): String {
        if (sessions.isEmpty()) return "-"
        
        return sessions.groupBy { it.chargingType }
            .map { (typeStr, list) ->
                val type = ChargingType.fromString(typeStr)
                val unit = if (type == ChargingType.GASOLINE) "l" else "kWh"
                val value = list.sumOf { it.chargedKwh }
                val cost = list.sumOf { it.totalCost }
                val breakdownText = if (cost > 0) {
                    getString(R.string.fmt_breakdown_item, getString(type.labelRes()), NumberUtil.format(value) + " " + unit, list.size) + 
                            " (" + NumberUtil.formatCost(cost) + " Kč)"
                } else {
                    getString(R.string.fmt_breakdown_item, getString(type.labelRes()), NumberUtil.format(value) + " " + unit, list.size)
                }
                breakdownText
            }
            .joinToString("\n")
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
