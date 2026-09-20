package com.byd.charging.ui.addedit

import android.Manifest
import android.annotation.SuppressLint
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.byd.charging.R
import com.byd.charging.data.ChargingSession
import com.byd.charging.data.ChargingType
import com.byd.charging.databinding.ActivityAddEditBinding
import com.byd.charging.util.DateUtil
import com.byd.charging.util.EvCalc
import com.byd.charging.util.LocaleHelper
import com.byd.charging.util.NumberUtil
import com.byd.charging.ui.settings.SettingsActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar
import java.util.Locale

class AddEditActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_SESSION_ID = "extra_session_id"

        /** Oddelovac polozek v informacnim radku pod poli baterie. */
        private const val SOC_INFO_SEPARATOR = "  |  "

        /** HH:MM — přijímá 00:00–23:59 */
        private val TIME_REGEX = Regex("^([01]\\d|2[0-3]):[0-5]\\d$")
    }

    private lateinit var binding: ActivityAddEditBinding
    private val viewModel: AddEditViewModel by viewModels()
    private var sessionId: Long = 0L
    private var lastUsedDateIso: String = ""

    // --- GPS ---
    private var savedLatitude = 0.0
    private var savedLongitude = 0.0
    private var gpsLoading = false
    private var activeLocationListener: LocationListener? = null
    private val locationTimeoutHandler = Handler(Looper.getMainLooper())
    private val locationManager by lazy {
        getSystemService(Context.LOCATION_SERVICE) as LocationManager
    }

    /** Launcher pro runtime povolení polohy – musí být inicializován před onStart */
    private val locationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val granted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true
                || permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (granted) {
            loadGpsLocation()
        } else {
            Toast.makeText(this, R.string.gps_permission_denied, Toast.LENGTH_SHORT).show()
        }
    }

    // --- Lifecycle ---

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.applyLocale(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAddEditBinding.inflate(layoutInflater)
        setContentView(binding.root)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        setupTypeSpinner()
        setupGpsButton()

        sessionId = intent.getLongExtra(EXTRA_SESSION_ID, 0L)
        if (sessionId != 0L) {
            title = getString(R.string.edit_charging)
            viewModel.loadSession(sessionId)
            viewModel.currentSession.observe(this) { session ->
                session?.let { 
                    lastUsedDateIso = it.date
                    fillForm(it) 
                }
            }
        } else {
            title = getString(R.string.add_charging)
            prefillCurrentDateTime()
            viewModel.loadLastSession()
            viewModel.lastSession.observe(this) { last ->
                last?.let { prefillFromLast(it) }
            }
        }

        binding.etDate.setOnClickListener { showDatePicker() }
        binding.etStartTime.setOnClickListener { showTimePicker(isStart = true) }
        binding.etEndTime.setOnClickListener { showTimePicker(isStart = false) }
        binding.etDuration.setOnClickListener { showDurationPicker() }

        setupNowButtons()
        setupCalculations()

        binding.btnSave.setOnClickListener { saveSession() }
        binding.btnCancel.setOnClickListener { finish() }
    }

    override fun onDestroy() {
        super.onDestroy()
        locationTimeoutHandler.removeCallbacksAndMessages(null)
        activeLocationListener?.let { locationManager.removeUpdates(it) }
    }

    // --- Setup ---

    private fun setupTypeSpinner() {
        val labels = ChargingType.all.map { getString(it.labelRes()) }
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, labels)
            .also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        binding.spinnerType.adapter = adapter

        binding.spinnerType.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                val type = ChargingType.all[pos]
                val isGas = type == ChargingType.GASOLINE
                val isGarage = type == ChargingType.GARAGE

                // Místo zobrazit pro PUBLIC, GARAGE a GASOLINE
                binding.tilLocation.visibility =
                    if (type == ChargingType.PUBLIC || isGarage || isGas) View.VISIBLE
                    else View.GONE
                
                // Dynamické popisky
                binding.tilChargedKwh.hint = getString(if (isGas) R.string.label_charged_liters else R.string.label_charged_kwh)
                binding.tilPricePerKwh.hint = getString(if (isGas) R.string.label_price_per_liter else R.string.label_price_per_kwh)

                // Skrýt nerelevantní pole pro benzín
                val electricVisibility = if (isGas) View.GONE else View.VISIBLE
                binding.tilStartTime.visibility = electricVisibility
                binding.tilEndTime.visibility = electricVisibility
                binding.tilDuration.visibility = electricVisibility
                binding.tilPower.visibility = electricVisibility
                
                // Elektroměr auta (Start/End) - Vždy skrýt dle nového požadavku (používáme ODO a nabité kWh)
                binding.tilStartMeter.visibility = View.GONE
                binding.tilEndMeter.visibility = View.GONE

                // Hlavní elektroměr (Start/End) - pro GARAGE oba, pro ostatní elektřinu jen start, pro benzín nic
                binding.tilMainMeter.visibility = electricVisibility
                binding.tilMainMeterEnd.visibility = if (isGarage) View.VISIBLE else View.GONE
                
                // Pro non-garage elektřinu (PUBLIC, HOME) také skrýt hlavní elektroměr dle požadavku
                if (!isGarage && !isGas) {
                    binding.tilMainMeter.visibility = View.GONE
                }

                // Elektroměr garáže (Start/End) - jen pro GARAGE
                val garageMeterVisibility = if (isGarage) View.VISIBLE else View.GONE
                binding.tilGarageMeterStart.visibility = garageMeterVisibility
                binding.tilGarageMeterEnd.visibility = garageMeterVisibility

                // Stav baterie v procentech - jen pro elektricke nabijeni
                binding.tilSocStart.visibility = electricVisibility
                binding.tilSocEnd.visibility = electricVisibility
                updateSocInfo()

                // Tachometr - ODO bude všude
                binding.tilOdometer.visibility = View.VISIBLE

                // FVE: předvyplnit cenu 0 pokud je prázdná
                if (type == ChargingType.HOME_FVE && binding.etPricePerKwh.text.isNullOrBlank()) {
                    binding.etPricePerKwh.setText("0")
                }
            }
            override fun onNothingSelected(p: AdapterView<*>?) = Unit
        }
    }

    private fun setupGpsButton() {
        binding.tilLocation.setEndIconOnClickListener {
            if (!gpsLoading) requestGpsLocation()
        }
    }

    /**
     * Tlacitka v pravem okraji poli: jednim klepnutim doplni dnesek / aktualni cas / 100 %.
     * Klepnuti do pole samotneho stale otevira picker, takze rucni volba zustava dostupna.
     */
    private fun setupNowButtons() {
        binding.tilDate.setEndIconOnClickListener {
            val today = DateUtil.todayIso()
            lastUsedDateIso = today
            binding.etDate.setText(DateUtil.toDisplayDate(today))
        }

        binding.tilStartTime.setEndIconOnClickListener {
            binding.etStartTime.setText(DateUtil.currentTimeHHMM())
            afterStartTimeChanged()
        }

        binding.tilEndTime.setEndIconOnClickListener {
            binding.etEndTime.setText(DateUtil.currentTimeHHMM())
            afterEndTimeChanged()
        }

        binding.tilSocEnd.setEndIconOnClickListener {
            binding.etSocEnd.setText("100")
            updateSocInfo()
        }
    }

    // --- Form fill / save ---

    private fun fillForm(s: ChargingSession) {
        binding.etDate.setText(DateUtil.toDisplayDate(s.date))
        binding.etOdometer.setText(NumberUtil.formatForInput(s.odometer))
        binding.etStartTime.setText(s.startTime)
        binding.etEndTime.setText(s.endTime)
        binding.etPower.setText(NumberUtil.formatForInput(s.powerKw))
        binding.etStartMeter.setText(NumberUtil.formatForInput(s.startMeterKwh))
        binding.etEndMeter.setText(NumberUtil.formatForInput(s.startMeterKwh + s.chargedKwh))
        binding.etChargedKwh.setText(NumberUtil.formatForInput(s.chargedKwh))
        binding.etMainMeter.setText(NumberUtil.formatForInput(s.mainMeterKwh))
        binding.etMainMeterEnd.setText(NumberUtil.formatForInput(s.mainMeterEndKwh))
        binding.etGarageMeterStart.setText(NumberUtil.formatForInput(s.garageMeterStartKwh))
        binding.etGarageMeterEnd.setText(NumberUtil.formatForInput(s.garageMeterEndKwh))
        binding.etPricePerKwh.setText(NumberUtil.formatForInput(s.pricePerKwh))
        binding.etTotalPrice.setText(NumberUtil.formatForInput(s.totalCost))
        binding.etLocation.setText(s.locationName)
        binding.etNote.setText(s.note)

        // SOC: prazdne pole znamena "nezadano", nezobrazujeme sentinel -1
        binding.etSocStart.setText(if (s.hasSocStart) NumberUtil.formatForInput(s.socStart) else "")
        binding.etSocEnd.setText(if (s.hasSocEnd) NumberUtil.formatForInput(s.socEnd) else "")

        // Výpočet doby trvání pro zobrazení v HH:mm
        val duration = DateUtil.calculateDuration(s.startTime, s.endTime)
        binding.etDuration.setText(DateUtil.formatDuration(duration))

        // Obnovit GPS souřadnice při editaci
        savedLatitude = s.latitude
        savedLongitude = s.longitude

        val typeIndex = ChargingType.all.indexOf(s.type)
        if (typeIndex >= 0) binding.spinnerType.setSelection(typeIndex)

        updateSocInfo()
    }

    private fun prefillCurrentDateTime() {
        val today = DateUtil.todayIso()
        lastUsedDateIso = today
        binding.etDate.setText(DateUtil.toDisplayDate(today))
        binding.etStartTime.setText(DateUtil.currentTimeHHMM())
    }

    private fun prefillFromLast(last: ChargingSession) {
        // Pamatujeme si datum posledního nabíjení pro kalendář
        lastUsedDateIso = last.date
        
        // Předvyplníme hodnoty, které se často opakují
        binding.etPower.setText(NumberUtil.formatForInput(last.powerKw))
        binding.etPricePerKwh.setText(NumberUtil.formatForInput(last.pricePerKwh))
        binding.etLocation.setText(last.locationName)
        binding.etOdometer.setText(NumberUtil.formatForInput(last.odometer))
        
        // Nový stav elektroměru začíná tam, kde minulý skončil
        val nextStartMeter = last.startMeterKwh + last.chargedKwh
        binding.etStartMeter.setText(NumberUtil.formatForInput(nextStartMeter))
        
        val nextMainMeter = if (last.mainMeterEndKwh > 0) last.mainMeterEndKwh else last.mainMeterKwh
        binding.etMainMeter.setText(NumberUtil.formatForInput(nextMainMeter))
        
        val nextGarageMeter = if (last.garageMeterEndKwh > 0) last.garageMeterEndKwh else last.garageMeterStartKwh
        binding.etGarageMeterStart.setText(NumberUtil.formatForInput(nextGarageMeter))
        
        // Typ nabíjení
        val typeIndex = ChargingType.all.indexOf(last.type)
        if (typeIndex >= 0) binding.spinnerType.setSelection(typeIndex)

        // Reset GPS souřadnic na ty z minulého záznamu, pokud je to relevantní
        if (last.type == ChargingType.PUBLIC || last.type == ChargingType.GARAGE) {
            savedLatitude = last.latitude
            savedLongitude = last.longitude
        }
    }

    private fun setupCalculations() {
        // Pomocná funkce pro focus change
        val onFocusLost: (View, Boolean) -> Unit = { view, hasFocus ->
            if (!hasFocus) {
                when (view.id) {
                    R.id.etDuration -> recalculateTime(fromDuration = true)
                    R.id.etStartMeter, R.id.etEndMeter, R.id.etChargedKwh,
                    R.id.etGarageMeterStart, R.id.etGarageMeterEnd -> {
                        recalculateMeter(view.id)
                        if (view.id == R.id.etChargedKwh || view.id == R.id.etGarageMeterEnd) {
                            recalculatePrice(fromPricePerKwh = true)
                        }
                    }
                    R.id.etPricePerKwh -> recalculatePrice(fromPricePerKwh = true)
                    R.id.etTotalPrice -> recalculatePrice(fromTotal = true)
                    R.id.etSocStart, R.id.etSocEnd -> Unit  // info radek se obnovi nize
                }
                updateSocInfo()
            }
        }

        binding.etDuration.onFocusChangeListener = View.OnFocusChangeListener(onFocusLost)
        binding.etStartMeter.onFocusChangeListener = View.OnFocusChangeListener(onFocusLost)
        binding.etEndMeter.onFocusChangeListener = View.OnFocusChangeListener(onFocusLost)
        binding.etChargedKwh.onFocusChangeListener = View.OnFocusChangeListener(onFocusLost)
        binding.etMainMeter.onFocusChangeListener = View.OnFocusChangeListener(onFocusLost)
        binding.etMainMeterEnd.onFocusChangeListener = View.OnFocusChangeListener(onFocusLost)
        binding.etGarageMeterStart.onFocusChangeListener = View.OnFocusChangeListener(onFocusLost)
        binding.etGarageMeterEnd.onFocusChangeListener = View.OnFocusChangeListener(onFocusLost)
        binding.etPricePerKwh.onFocusChangeListener = View.OnFocusChangeListener(onFocusLost)
        binding.etTotalPrice.onFocusChangeListener = View.OnFocusChangeListener(onFocusLost)
        binding.etSocStart.onFocusChangeListener = View.OnFocusChangeListener(onFocusLost)
        binding.etSocEnd.onFocusChangeListener = View.OnFocusChangeListener(onFocusLost)
    }

    /**
     * Dopocet trojice zacatek / konec / doba trvani. Vzdy dopocitava tu hodnotu,
     * ktera chybi - zadana pole se neprepisuji.
     *
     * Zadana jen doba trvani (bez obou casu): konec = ted, zacatek = ted minus doba.
     * To je bezny pripad, kdy clovek prijde k autu a jen vi, jak dlouho se nabijelo.
     */
    private fun recalculateTime(fromDuration: Boolean = false) {
        val start = binding.etStartTime.text.toString().trim()
        val end = binding.etEndTime.text.toString().trim()
        val duration = DateUtil.parseDuration(binding.etDuration.text.toString())

        val hasStart = start.matches(TIME_REGEX)
        val hasEnd = end.matches(TIME_REGEX)
        val hasDuration = duration != null && duration > 0

        if (fromDuration) {
            if (!hasDuration) return
            when {
                hasStart -> binding.etEndTime.setText(DateUtil.addMinutes(start, duration!!))
                hasEnd   -> binding.etStartTime.setText(DateUtil.subtractMinutes(end, duration!!))
                else -> {
                    // Ani jeden cas neni zadany - bereme, ze nabijeni prave skoncilo
                    val now = DateUtil.currentTimeHHMM()
                    binding.etEndTime.setText(now)
                    binding.etStartTime.setText(DateUtil.subtractMinutes(now, duration!!))
                }
            }
        } else {
            when {
                hasStart && hasEnd ->
                    binding.etDuration.setText(DateUtil.formatDuration(DateUtil.calculateDuration(start, end)))
                hasStart && hasDuration ->
                    binding.etEndTime.setText(DateUtil.addMinutes(start, duration!!))
                hasEnd && hasDuration ->
                    binding.etStartTime.setText(DateUtil.subtractMinutes(end, duration!!))
            }
        }
        updateSocInfo()
    }

    /** Zacatek byl zmenen: dopocitat dobu trvani, nebo konec podle zadane doby. */
    private fun afterStartTimeChanged() {
        val end = binding.etEndTime.text.toString().trim()
        if (end.matches(TIME_REGEX)) recalculateTime(fromDuration = false)
        else recalculateTime(fromDuration = true)
    }

    /** Konec byl zmenen: dopocitat dobu trvani, nebo zacatek podle zadane doby. */
    private fun afterEndTimeChanged() {
        val start = binding.etStartTime.text.toString().trim()
        if (start.matches(TIME_REGEX)) {
            recalculateTime(fromDuration = false)
        } else {
            val duration = DateUtil.parseDuration(binding.etDuration.text.toString())
            if (duration != null && duration > 0) {
                binding.etStartTime.setText(DateUtil.subtractMinutes(binding.etEndTime.text.toString().trim(), duration))
            }
            updateSocInfo()
        }
    }

    private fun recalculateMeter(sourceViewId: Int) {
        val start = NumberUtil.parse(binding.etStartMeter.text.toString())
        val end = NumberUtil.parse(binding.etEndMeter.text.toString())
        val charged = NumberUtil.parse(binding.etChargedKwh.text.toString())
        
        val gStart = NumberUtil.parse(binding.etGarageMeterStart.text.toString())
        val gEnd = NumberUtil.parse(binding.etGarageMeterEnd.text.toString())

        if (sourceViewId == R.id.etEndMeter && start != null && end != null) {
            binding.etChargedKwh.setText(NumberUtil.formatForInput(end - start))
        } else if ((sourceViewId == R.id.etChargedKwh || sourceViewId == R.id.etStartMeter) && start != null && charged != null) {
            binding.etEndMeter.setText(NumberUtil.formatForInput(start + charged))
        } else if (sourceViewId == R.id.etGarageMeterEnd && gStart != null && gEnd != null) {
            // Pokud se mění elektroměr garáže, aktualizujeme nabité kWh
            binding.etChargedKwh.setText(NumberUtil.formatForInput(gEnd - gStart))
        } else if ((sourceViewId == R.id.etChargedKwh || sourceViewId == R.id.etGarageMeterStart) && gStart != null && charged != null) {
            binding.etGarageMeterEnd.setText(NumberUtil.formatForInput(gStart + charged))
        }
    }

    private fun recalculatePrice(fromTotal: Boolean = false, fromPricePerKwh: Boolean = false) {
        val charged = NumberUtil.parse(binding.etChargedKwh.text.toString()) ?: return
        if (charged <= 0) return

        if (fromTotal) {
            val total = NumberUtil.parse(binding.etTotalPrice.text.toString()) ?: return
            binding.etPricePerKwh.setText(NumberUtil.formatForInput(total / charged))
        } else if (fromPricePerKwh) {
            val price = NumberUtil.parse(binding.etPricePerKwh.text.toString()) ?: return
            val total = Math.round(price * charged).toDouble()
            binding.etTotalPrice.setText(NumberUtil.formatForInput(total))
        }
    }

    private fun saveSession() {
        if (gpsLoading) {
            Toast.makeText(this, R.string.gps_loading, Toast.LENGTH_SHORT).show()
            return
        }
        val displayDate   = binding.etDate.text.toString().trim()
        val odoStr        = binding.etOdometer.text.toString()
        val startTime     = binding.etStartTime.text.toString().trim()
        val endTime       = binding.etEndTime.text.toString().trim()
        val powerStr      = binding.etPower.text.toString()
        val startMeterStr = binding.etStartMeter.text.toString()
        val chargedStr    = binding.etChargedKwh.text.toString()
        val mainMeterStr  = binding.etMainMeter.text.toString()
        val mainEndStr    = binding.etMainMeterEnd.text.toString()
        val gStartStr     = binding.etGarageMeterStart.text.toString()
        val gEndStr       = binding.etGarageMeterEnd.text.toString()
        val priceStr      = binding.etPricePerKwh.text.toString()
        val socStartStr   = binding.etSocStart.text.toString()
        val socEndStr     = binding.etSocEnd.text.toString()
        val location      = binding.etLocation.text.toString().trim()
        val note          = binding.etNote.text.toString().trim()
        val type          = ChargingType.all[binding.spinnerType.selectedItemPosition]

        // --- Validace (uvolněná) ---
        val storageDate = DateUtil.toStorageDate(displayDate)
        if (!DateUtil.isValidIsoDate(storageDate)) {
            showError(R.string.fill_all_fields); return
        }
        
        // Pokud chybí časy, nastavíme aspoň start, pokud je zadaný, nebo 00:00
        val finalStart = if (startTime.matches(TIME_REGEX)) startTime else "00:00"
        val finalEnd = if (endTime.matches(TIME_REGEX)) endTime else finalStart

        val powerKw = NumberUtil.parsePositive(powerStr) ?: 0.0
        val odo = NumberUtil.parseNonNegative(odoStr) ?: 0.0
        val startMeter = NumberUtil.parseNonNegative(startMeterStr) ?: 0.0
        val chargedKwh = NumberUtil.parseNonNegative(chargedStr) ?: 0.0
        val mainMeter = NumberUtil.parseNonNegative(mainMeterStr) ?: 0.0
        val mainEnd = NumberUtil.parseNonNegative(mainEndStr) ?: 0.0
        val gStart = NumberUtil.parseNonNegative(gStartStr) ?: 0.0
        val gEnd = NumberUtil.parseNonNegative(gEndStr) ?: 0.0
        val pricePerKwh = NumberUtil.parseNonNegative(priceStr) ?: 0.0

        // --- Stav baterie: prazdne pole = nezadano, jinak musi byt 0-100 % ---
        val isGas = type == ChargingType.GASOLINE
        val socStart = if (isGas) ChargingSession.SOC_UNSET else parseSoc(socStartStr)
        val socEnd   = if (isGas) ChargingSession.SOC_UNSET else parseSoc(socEndStr)
        if (socStart == null || socEnd == null) {
            showError(R.string.error_soc_range); return
        }
        if (socStart >= 0.0 && socEnd >= 0.0 && socEnd <= socStart) {
            showError(R.string.error_soc_order); return
        }

        // Pokud byl typ změněn na domácí nabíjení, souřadnice nerelevantní → vynulovat
        val (lat, lng) = if (type == ChargingType.PUBLIC || type == ChargingType.GARAGE) {
            savedLatitude to savedLongitude
        } else {
            0.0 to 0.0
        }

        val session = ChargingSession(
            id            = sessionId,
            date          = storageDate,
            startTime     = finalStart,
            endTime       = finalEnd,
            powerKw       = powerKw,
            startMeterKwh = startMeter,
            chargedKwh    = chargedKwh,
            mainMeterKwh  = mainMeter,
            mainMeterEndKwh = mainEnd,
            garageMeterStartKwh = gStart,
            garageMeterEndKwh = gEnd,
            note          = note,
            chargingType  = type.name,
            pricePerKwh   = pricePerKwh,
            locationName  = location,
            latitude      = lat,
            longitude     = lng,
            odometer      = odo,
            socStart      = socStart,
            socEnd        = socEnd
        )

        lifecycleScope.launch {
            val duplicate = viewModel.findDuplicate(session)
            if (duplicate != null) {
                androidx.appcompat.app.AlertDialog.Builder(this@AddEditActivity)
                    .setTitle(R.string.duplicate_warning_title)
                    .setMessage(R.string.duplicate_warning_message)
                    .setPositiveButton(R.string.save) { _, _ -> 
                        viewModel.saveSession(session)
                        finish()
                    }
                    .setNegativeButton(R.string.cancel, null)
                    .show()
            } else {
                viewModel.saveSession(session)
                finish()
            }
        }
    }

    /**
     * Obnovi informacni radek pod poli baterie.
     *
     * Zamerne NIC nedopocitava do poli %: kWh ze zasuvky a % z auta jsou dve nezavisla
     * mereni, ktera se lisi o ztraty nabijeni. Prepsat jedno druhym by tu informaci
     * zahodilo, takze se odvozene hodnoty jen zobrazuji.
     */
    private fun updateSocInfo() {
        val info = binding.tvSocInfo
        val type = ChargingType.all.getOrNull(binding.spinnerType.selectedItemPosition)
        if (type == null || type == ChargingType.GASOLINE) {
            info.visibility = View.GONE
            return
        }

        val capacity = SettingsActivity.getBatteryCapacity(this)
        val socStart = NumberUtil.parse(binding.etSocStart.text.toString())
        val socEnd = NumberUtil.parse(binding.etSocEnd.text.toString())
        val charged = NumberUtil.parse(binding.etChargedKwh.text.toString())
        val duration = DateUtil.parseDuration(binding.etDuration.text.toString())

        val parts = mutableListOf<String>()
        var suspicious = false

        if (socStart != null && socEnd != null && socEnd > socStart) {
            val gain = EvCalc.socDeltaToKwh(socEnd - socStart, capacity)
            if (gain != null) {
                parts += getString(
                    R.string.fmt_soc_gain,
                    NumberUtil.format(gain),
                    NumberUtil.format(socEnd - socStart)
                )
                if (charged != null && charged > 0.0) {
                    val eff = gain / charged
                    parts += getString(R.string.fmt_efficiency, NumberUtil.format(Math.round(eff * 100).toDouble()))
                    suspicious = EvCalc.isEfficiencySuspicious(eff)
                }
            }
        } else if (socStart != null && charged != null && charged > 0.0) {
            // Konec nezadan - ukazeme horni odhad, do pole ho ale nezapisujeme
            val delta = EvCalc.kwhToSocDelta(charged, capacity)
            if (delta != null) {
                val estimate = minOf(100.0, socStart + delta)
                parts += getString(R.string.fmt_soc_gain_estimate, NumberUtil.format(Math.round(estimate).toDouble()))
            }
        }

        if (charged != null && charged > 0.0 && duration != null && duration > 0) {
            val realPower = charged / (duration / 60.0)
            parts += getString(R.string.fmt_real_power, NumberUtil.format(Math.round(realPower * 10) / 10.0))
        }

        if (parts.isEmpty()) {
            info.visibility = View.GONE
            return
        }

        val text = StringBuilder(parts.joinToString(SOC_INFO_SEPARATOR))
        if (suspicious) {
            text.appendLine().append(getString(R.string.soc_efficiency_suspicious))
        }

        info.text = text.toString()
        info.setTextColor(
            ContextCompat.getColor(
                this,
                if (suspicious) R.color.warning else R.color.textSecondary
            )
        )
        info.visibility = View.VISIBLE
    }

    // --- GPS ---

    private fun requestGpsLocation() {
        val hasFine = ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        val hasCoarse = ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        if (hasFine || hasCoarse) {
            loadGpsLocation()
        } else {
            locationPermissionLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
        }
    }

    @SuppressLint("MissingPermission")
    private fun loadGpsLocation() {
        gpsLoading = true
        binding.etLocation.setText(getString(R.string.gps_loading))

        // Zrušit předchozí požadavek na polohu
        locationTimeoutHandler.removeCallbacksAndMessages(null)
        activeLocationListener?.let { locationManager.removeUpdates(it) }
        activeLocationListener = null

        // Nejdříve zkusit poslední známou polohu (rychlé)
        val freshThreshold = System.currentTimeMillis() - 5 * 60 * 1000L  // 5 minut
        val lastKnown = getBestLastKnownLocation(freshThreshold)

        if (lastKnown != null) {
            onLocationObtained(lastKnown.latitude, lastKnown.longitude)
            return
        }

        // Žádná čerstvá poloha → vyžádat aktuální
        val provider = when {
            locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)     -> LocationManager.GPS_PROVIDER
            locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
            else -> {
                onGpsUnavailable()
                return
            }
        }

        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                locationManager.removeUpdates(this)
                locationTimeoutHandler.removeCallbacksAndMessages(null)
                activeLocationListener = null
                onLocationObtained(location.latitude, location.longitude)
            }

            @Deprecated("Deprecated in Java")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        }
        activeLocationListener = listener

        @Suppress("DEPRECATION")
        locationManager.requestSingleUpdate(provider, listener, mainLooper)

        // Timeout 30 s
        locationTimeoutHandler.postDelayed({
            activeLocationListener?.let {
                locationManager.removeUpdates(it)
                activeLocationListener = null
                onGpsTimeout()
            }
        }, 30_000L)
    }

    @SuppressLint("MissingPermission")
    private fun getBestLastKnownLocation(freshThreshold: Long): Location? {
        var best: Location? = null
        for (provider in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
            val loc = try { locationManager.getLastKnownLocation(provider) } catch (_: Exception) { null }
                ?: continue
            if (loc.time < freshThreshold) continue
            if (best == null || loc.accuracy < best.accuracy) {
                best = loc
            }
        }
        return best
    }

    private fun onLocationObtained(lat: Double, lng: Double) {
        savedLatitude = lat
        savedLongitude = lng

        // Reverzní geokódování na pozadí
        lifecycleScope.launch {
            val address = withContext(Dispatchers.IO) {
                reverseGeocode(lat, lng)
            }
            gpsLoading = false
            val displayText = address
                ?: getString(R.string.fmt_gps_coords, lat, lng)
            binding.etLocation.setText(displayText)
        }
    }

    private fun onGpsUnavailable() {
        gpsLoading = false
        binding.etLocation.setText("")
        Toast.makeText(this, R.string.gps_unavailable, Toast.LENGTH_SHORT).show()
    }

    private fun onGpsTimeout() {
        gpsLoading = false
        binding.etLocation.setText("")
        Toast.makeText(this, R.string.gps_timeout, Toast.LENGTH_SHORT).show()
    }

    /**
     * Reverzní geokódování: GPS souřadnice → textová adresa.
     * Musí být voláno z IO vlákna.
     */
    @Suppress("DEPRECATION")   // synchronní getFromLocation je deprecated v API 33, ale funkční
    private fun reverseGeocode(lat: Double, lng: Double): String? {
        if (!Geocoder.isPresent()) return null
        return try {
            val geocoder = Geocoder(this, Locale.getDefault())
            val addresses = geocoder.getFromLocation(lat, lng, 1)
            addresses?.firstOrNull()?.let { addr ->
                // Sestavit adresu: ulice + číslo + město
                val parts = listOfNotNull(
                    addr.thoroughfare,
                    addr.subThoroughfare,
                    addr.locality ?: addr.subAdminArea
                ).filter { it.isNotBlank() }

                if (parts.isNotEmpty()) parts.joinToString(", ")
                else addr.getAddressLine(0)  // fallback na kompletní řetězec
            }
        } catch (_: Exception) {
            null
        }
    }

    // --- Pomocné ---

    private fun showDatePicker() {
        val cal = Calendar.getInstance()
        // Priorita: 1. datum v poli, 2. datum posledního záznamu, 3. dnes
        val dateToParse = if (!binding.etDate.text.isNullOrBlank()) {
            DateUtil.toStorageDate(binding.etDate.text.toString())
        } else {
            lastUsedDateIso
        }

        DateUtil.parseDisplayToCalendar(DateUtil.toDisplayDate(dateToParse))
            ?.let { (y, m, d) -> cal.set(y, m, d) }

        DatePickerDialog(
            this,
            { _, y, m, d ->
                val selectedIso = String.format("%04d-%02d-%02d", y, m + 1, d)
                lastUsedDateIso = selectedIso
                binding.etDate.setText(DateUtil.toDisplayDate(selectedIso))
            },
            cal.get(Calendar.YEAR),
            cal.get(Calendar.MONTH),
            cal.get(Calendar.DAY_OF_MONTH)
        ).show()
    }

    private fun showTimePicker(isStart: Boolean) {
        val cal = Calendar.getInstance()
        TimePickerDialog(
            this,
            { _, h, min ->
                val timeStr = String.format("%02d:%02d", h, min)
                if (isStart) {
                    binding.etStartTime.setText(timeStr)
                    afterStartTimeChanged()
                } else {
                    binding.etEndTime.setText(timeStr)
                    afterEndTimeChanged()
                }
            },
            cal.get(Calendar.HOUR_OF_DAY),
            cal.get(Calendar.MINUTE),
            true
        ).show()
    }

    private fun showDurationPicker() {
        val current = binding.etDuration.text.toString()
        val totalMinutes = DateUtil.parseDuration(current) ?: 0
        val h = totalMinutes / 60
        val m = totalMinutes % 60

        TimePickerDialog(
            this,
            { _, hour, min ->
                binding.etDuration.setText(String.format("%02d:%02d", hour, min))
                recalculateTime(fromDuration = true)
            },
            h, m, true
        ).show()
    }

    /**
     * Prevede obsah pole se stavem baterie na hodnotu k ulozeni.
     * Prazdne pole -> SOC_UNSET (nezadano). Neplatna hodnota nebo mimo 0-100 -> null (chyba).
     */
    private fun parseSoc(input: String): Double? {
        if (input.isBlank()) return ChargingSession.SOC_UNSET
        val value = NumberUtil.parse(input) ?: return null
        return if (value in 0.0..100.0) value else null
    }

    private fun showError(msgRes: Int) {
        Toast.makeText(this, msgRes, Toast.LENGTH_SHORT).show()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}
