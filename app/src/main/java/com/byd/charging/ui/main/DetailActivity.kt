package com.byd.charging.ui.main

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.byd.charging.R
import com.byd.charging.data.ChargingSession
import com.byd.charging.data.ChargingType
import com.byd.charging.databinding.ActivityDetailBinding
import com.byd.charging.ui.addedit.AddEditActivity
import com.byd.charging.ui.addedit.AddEditViewModel
import com.byd.charging.ui.settings.SettingsActivity
import com.byd.charging.util.DateUtil
import com.byd.charging.util.EvCalc
import com.byd.charging.util.LocaleHelper
import com.byd.charging.util.NumberUtil
import com.byd.charging.util.ZoomHelper

class DetailActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_SESSION_ID = "extra_session_id"
    }

    private lateinit var binding: ActivityDetailBinding
    private val viewModel: AddEditViewModel by viewModels()
    private var sessionId: Long = 0L

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.applyLocale(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        title = getString(R.string.tab_records)

        ZoomHelper.setupPinchToZoom(binding.root)

        sessionId = intent.getLongExtra(EXTRA_SESSION_ID, 0L)
        if (sessionId == 0L) {
            finish()
            return
        }

        viewModel.loadSession(sessionId)
        viewModel.loadAllSessions()

        // Odvozene hodnoty (dojezd) potrebuji historii, proto se prekresluje pri obou zdrojich
        viewModel.currentSession.observe(this) { session ->
            session?.let { displaySession(it) }
        }
        viewModel.allSessions.observe(this) {
            viewModel.currentSession.value?.let { displaySession(it) }
        }

        binding.btnDetailEdit.setOnClickListener {
            startActivity(Intent(this, AddEditActivity::class.java).apply {
                putExtra(AddEditActivity.EXTRA_SESSION_ID, sessionId)
            })
            finish()
        }
    }

    private fun displaySession(s: ChargingSession) {
        val type = s.type
        val isGas = type == ChargingType.GASOLINE
        val isGarage = type == ChargingType.GARAGE

        binding.tvDetailType.text = getString(type.labelRes())
        binding.tvDetailType.setBackgroundColor(ContextCompat.getColor(this, type.colorRes()))
        binding.tvDetailDate.text = DateUtil.toDisplayDate(s.date)

        setupRow(binding.rowOdometer, getString(R.string.label_odometer), NumberUtil.format(s.odometer) + " km")

        // Sekce elektrické
        binding.layoutDetailElectric.visibility = if (isGas) View.GONE else View.VISIBLE
        if (!isGas) {
            setupRow(binding.rowTime, getString(R.string.label_start_time), getString(R.string.fmt_time_range, s.startTime, s.endTime))
            val duration = DateUtil.calculateDuration(s.startTime, s.endTime)
            setupRow(binding.rowDuration, getString(R.string.label_duration), DateUtil.formatDuration(duration))
            setupRow(binding.rowPower, getString(R.string.label_power_kw), NumberUtil.format(s.powerKw) + " kW")
            displayBatteryRows(s)
        }

        // Nabito a Cena
        val chargedUnit = if (isGas) "l" else "kWh"
        setupRow(binding.rowCharged, getString(if (isGas) R.string.label_charged_liters else R.string.label_charged_kwh), NumberUtil.format(s.chargedKwh) + " " + chargedUnit)
        setupRow(binding.rowPricePerUnit, getString(if (isGas) R.string.label_price_per_liter else R.string.label_price_per_kwh), NumberUtil.format(s.pricePerKwh) + " Kč")
        setupRow(binding.rowTotalPrice, getString(R.string.label_total_price), NumberUtil.formatCost(s.totalCost) + " Kč")

        // Elektroměry
        binding.tvLabelMeters.visibility = if (isGas) View.GONE else View.VISIBLE
        
        // Car Meter
        binding.rowCarMeterStart.root.visibility = if (isGas || isGarage) View.GONE else View.VISIBLE
        binding.rowCarMeterEnd.root.visibility = if (isGas || isGarage) View.GONE else View.VISIBLE
        if (!isGas && !isGarage) {
            setupRow(binding.rowCarMeterStart, getString(R.string.label_start_meter), NumberUtil.format(s.startMeterKwh))
            setupRow(binding.rowCarMeterEnd, getString(R.string.label_end_meter), NumberUtil.format(s.startMeterKwh + s.chargedKwh))
        }

        // Main Meter
        binding.rowMainMeterStart.root.visibility = if (isGas) View.GONE else View.VISIBLE
        binding.rowMainMeterEnd.root.visibility = if (isGarage) View.VISIBLE else View.GONE
        if (!isGas) {
            setupRow(binding.rowMainMeterStart, getString(R.string.label_main_meter), NumberUtil.format(s.mainMeterKwh))
            if (isGarage) {
                setupRow(binding.rowMainMeterEnd, getString(R.string.label_main_meter_end), NumberUtil.format(s.mainMeterEndKwh))
            }
        }

        // Garage Meter
        binding.rowGarageMeterStart.root.visibility = if (isGarage) View.VISIBLE else View.GONE
        binding.rowGarageMeterEnd.root.visibility = if (isGarage) View.VISIBLE else View.GONE
        if (isGarage) {
            setupRow(binding.rowGarageMeterStart, getString(R.string.label_garage_meter_start), NumberUtil.format(s.garageMeterStartKwh))
            setupRow(binding.rowGarageMeterEnd, getString(R.string.label_garage_meter_end), NumberUtil.format(s.garageMeterEndKwh))
        }

        // Místo a Poznámka
        binding.rowLocation.root.visibility = if (s.locationName.isNotBlank()) View.VISIBLE else View.GONE
        if (s.locationName.isNotBlank()) {
            setupRow(binding.rowLocation, getString(R.string.label_location), s.locationName)
        }

        binding.rowNote.root.visibility = if (s.note.isNotBlank()) View.VISIBLE else View.GONE
        if (s.note.isNotBlank()) {
            setupRow(binding.rowNote, getString(R.string.label_note), s.note)
        }
    }

    /**
     * Radky odvozene ze stavu baterie. Kazdy se zobrazi jen tehdy, kdyz ho lze
     * z ulozenych dat skutecne spocitat - nic se neodhaduje.
     */
    private fun displayBatteryRows(s: ChargingSession) {
        val capacity = SettingsActivity.getBatteryCapacity(this)
        val allSessions = viewModel.allSessions.value.orEmpty()

        // Realny vykon behem nabijeni
        val realPower = EvCalc.realPowerKw(s)
        showRow(binding.rowRealPower, realPower != null, getString(R.string.label_real_power)) {
            NumberUtil.format(Math.round(realPower!! * 10) / 10.0) + " kW"
        }

        // Stav baterie zacatek -> konec
        val hasSocRange = s.hasSocStart && s.hasSocEnd
        showRow(binding.rowSoc, hasSocRange, getString(R.string.label_soc)) {
            getString(R.string.fmt_soc_range, NumberUtil.format(s.socStart), NumberUtil.format(s.socEnd))
        }

        // Energie, ktera pribyla v baterii
        val gain = EvCalc.batteryGainKwh(s, capacity)
        showRow(binding.rowBatteryGain, gain != null, getString(R.string.label_battery_gain)) {
            getString(
                R.string.fmt_soc_gain,
                NumberUtil.format(gain!!),
                NumberUtil.format(s.socDelta ?: 0.0)
            )
        }

        // Ucinnost nabijeni
        val efficiency = EvCalc.efficiency(s, capacity)
        showRow(binding.rowEfficiency, efficiency != null, getString(R.string.label_efficiency)) {
            getString(R.string.fmt_percent, NumberUtil.format(Math.round(efficiency!! * 100).toDouble()))
        }

        // Odhad, na kolik km nabita energie vystaci
        val rangeKm = EvCalc.sessionRangeKm(s, allSessions, capacity)
        showRow(binding.rowSessionRange, rangeKm != null, getString(R.string.stats_electric_range)) {
            getString(R.string.fmt_km, NumberUtil.format(Math.round(rangeKm!!).toDouble()))
        }
    }

    /** Zobrazi radek jen kdyz je hodnota k dispozici, jinak ho schova. */
    private fun showRow(
        rowBinding: com.byd.charging.databinding.ViewDetailRowBinding,
        visible: Boolean,
        label: String,
        value: () -> String
    ) {
        rowBinding.root.visibility = if (visible) View.VISIBLE else View.GONE
        if (visible) setupRow(rowBinding, label, value())
    }

    private fun setupRow(rowBinding: com.byd.charging.databinding.ViewDetailRowBinding, label: String, value: String) {
        rowBinding.tvLabel.text = label
        rowBinding.tvValue.text = value
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}
