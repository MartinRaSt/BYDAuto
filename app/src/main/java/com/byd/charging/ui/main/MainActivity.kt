package com.byd.charging.ui.main

import android.annotation.SuppressLint
import android.app.DatePickerDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.util.Patterns
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import com.byd.charging.R
import com.byd.charging.data.ChargingSession
import com.byd.charging.data.DbSchema
import com.byd.charging.data.ChargingType
import com.byd.charging.databinding.ActivityMainBinding
import com.byd.charging.databinding.DialogAboutBinding
import com.byd.charging.databinding.DialogExportBinding
import com.byd.charging.ui.addedit.AddEditActivity
import com.byd.charging.ui.charts.ChartsHostFragment
import com.byd.charging.util.CsvExporter
import com.byd.charging.util.CsvImporter
import com.byd.charging.util.DateUtil
import com.byd.charging.util.LocaleHelper
import com.byd.charging.util.StatsReport
import com.byd.charging.ui.settings.SettingsActivity
import com.google.android.material.tabs.TabLayoutMediator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Calendar

class MainActivity : AppCompatActivity() {

    private companion object {
        const val TAG = "MainActivity"
        const val PREF_EXPORT_EMAIL = "pref_export_email"
        const val GMAIL_PACKAGE = "com.google.android.gm"

        // Poradi zalozek. Pojmenovane, aby se pri pridani dalsi nerozesly
        // indexy v adapteru, v popiscich a v podmince pro FAB.
        const val TAB_DASHBOARD = 0
        const val TAB_RECORDS = 1
        const val TAB_CHARTS = 2
        const val TAB_COUNT = 3
    }

    private lateinit var binding: ActivityMainBinding
    private val viewModel: MainViewModel by viewModels()

    // SAF file picker — musí být registrován před onStart (je to property initializer)
    private val importLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let { handleImport(it) }
    }

    /** Vyber ciloveho souboru pro zalohu (uloziste telefonu, Disk Google, ...). */
    private val backupFileLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip")
    ) { uri: Uri? ->
        uri?.let { writeBackupToFile(it) }
    }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.applyLocale(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)

        setupViewPager()

        binding.fabAdd.setOnClickListener {
            startActivity(Intent(this, AddEditActivity::class.java))
        }
    }

    private fun setupViewPager() {
        binding.viewPager.adapter = MainPagerAdapter(this)
        TabLayoutMediator(binding.tabLayout, binding.viewPager) { tab, pos ->
            tab.text = when (pos) {
                TAB_DASHBOARD -> getString(R.string.tab_dashboard)
                TAB_RECORDS   -> getString(R.string.tab_records)
                TAB_CHARTS    -> getString(R.string.tab_charts)
                else -> ""
            }
        }.attach()

        // FAB jen na záložce Záznamy
        binding.viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                binding.fabAdd.visibility =
                    if (position == TAB_RECORDS) View.VISIBLE else View.GONE
            }
        })
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        R.id.action_export -> { startExport(); true }
        R.id.action_backup_file -> { startFileBackup(); true }
        R.id.action_send_stats -> { startStatsExport(); true }
        R.id.action_import -> { startImport(); true }
        R.id.action_settings -> {
            startActivity(Intent(this, com.byd.charging.ui.settings.SettingsActivity::class.java))
            true
        }
        R.id.action_about -> { showAboutDialog(); true }
        else -> super.onOptionsItemSelected(item)
    }

    // =========================================================
    // O aplikaci
    // =========================================================

    private fun showAboutDialog() {
        val pInfo = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                packageManager.getPackageInfo(packageName, 0)
            }
        } catch (_: Exception) { null }

        val versionName = pInfo?.versionName ?: "?"
        val versionCode = pInfo?.let {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) it.longVersionCode.toInt()
            else @Suppress("DEPRECATION") it.versionCode
        } ?: 0

        val dialogBinding = DialogAboutBinding.inflate(layoutInflater)
        dialogBinding.tvVersion.text = getString(R.string.about_version, versionName, versionCode)
        dialogBinding.tvDbVersion.text = getString(R.string.about_db_version, DbSchema.VERSION)  // aktuální verze DB

        AlertDialog.Builder(this)
            .setView(dialogBinding.root)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    // =========================================================
    // Export (záloha → ZIP CSV → Gmail)
    // =========================================================

    private fun startExport() {
        showExportDialog()
    }

    private fun showExportDialog() {
        val prefs = getPreferences(Context.MODE_PRIVATE)
        val savedEmail = prefs.getString(PREF_EXPORT_EMAIL, "") ?: ""

        val dialogBinding = DialogExportBinding.inflate(layoutInflater)
        dialogBinding.etEmail.setText(savedEmail)
        if (savedEmail.isNotBlank()) dialogBinding.etEmail.setSelection(savedEmail.length)

        // Předvyplnění dat (vše)
        dialogBinding.etDateFrom.setText("01.01.2000")
        dialogBinding.etDateTo.setText(DateUtil.todayDisplay())

        dialogBinding.etDateFrom.setOnClickListener { showDatePicker(dialogBinding.etDateFrom.text.toString()) { dialogBinding.etDateFrom.setText(it) } }
        dialogBinding.etDateTo.setOnClickListener { showDatePicker(dialogBinding.etDateTo.text.toString()) { dialogBinding.etDateTo.setText(it) } }

        // Spinner typů
        val types = mutableListOf(getString(R.string.type_all))
        types.addAll(ChargingType.all.map { getString(it.labelRes()) })
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, types)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        dialogBinding.spinnerExportType.adapter = adapter

        AlertDialog.Builder(this)
            .setTitle(R.string.export_dialog_title)
            .setView(dialogBinding.root)
            .setPositiveButton(R.string.export_csv) { _, _ ->
                val email = dialogBinding.etEmail.text.toString().trim()
                if (Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
                    prefs.edit().putString(PREF_EXPORT_EMAIL, email).apply()
                    
                    val startStr = DateUtil.toStorageDate(dialogBinding.etDateFrom.text.toString())
                    val endStr = DateUtil.toStorageDate(dialogBinding.etDateTo.text.toString())
                    val typePos = dialogBinding.spinnerExportType.selectedItemPosition
                    val selectedType = if (typePos == 0) null else ChargingType.all[typePos - 1].name

                    performFilteredExport(email, startStr, endStr, selectedType)
                } else {
                    Toast.makeText(this, R.string.invalid_email, Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showDatePicker(currentDisplay: String, onSelected: (String) -> Unit) {
        val cal = Calendar.getInstance()
        DateUtil.parseDisplayToCalendar(currentDisplay)?.let { (y, m, d) -> cal.set(y, m, d) }
        DatePickerDialog(this, { _, y, m, d ->
            val iso = String.format("%04d-%02d-%02d", y, m + 1, d)
            onSelected(DateUtil.toDisplayDate(iso))
        }, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)).show()
    }

    private fun performFilteredExport(email: String, start: String, end: String, type: String?) {
        lifecycleScope.launch {
            val sessions = viewModel.getFilteredSessionsOnce(start, end, type)
            if (sessions.isEmpty()) {
                Toast.makeText(this@MainActivity, R.string.no_data_to_export, Toast.LENGTH_SHORT).show()
                return@launch
            }
            sendZipEmail(sessions, email)
        }
    }

    private fun sendZipEmail(sessions: List<ChargingSession>, targetEmail: String) {
        lifecycleScope.launch {
            val zipFile = try {
                withContext(Dispatchers.IO) {
                    CsvExporter.exportZip(this@MainActivity, sessions)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Export failed", e)
                Toast.makeText(this@MainActivity, R.string.export_failed, Toast.LENGTH_LONG).show()
                return@launch
            }

            sendZipByEmail(
                zipFile = zipFile,
                targetEmail = targetEmail,
                subject = getString(R.string.export_email_subject),
                body = getString(R.string.export_email_body),
                chooserTitle = getString(R.string.send_csv)
            )
        }
    }

    // =========================================================
    // Statistiky emailem (textovy prehled + CSV se stejnymi cisly)
    // =========================================================

    private fun startStatsExport() {
        val prefs = getPreferences(Context.MODE_PRIVATE)
        val savedEmail = prefs.getString(PREF_EXPORT_EMAIL, "") ?: ""

        val dialogBinding = DialogExportBinding.inflate(layoutInflater)
        dialogBinding.etEmail.setText(savedEmail)
        if (savedEmail.isNotBlank()) dialogBinding.etEmail.setSelection(savedEmail.length)

        // Statistiky se pocitaji vzdy z cele historie - filtr obdobi a typu by
        // rozbil sezonni srovnani i vypocet spotreby mezi nabijenimi
        dialogBinding.layoutExportFilter.visibility = View.GONE

        AlertDialog.Builder(this)
            .setTitle(R.string.action_send_stats)
            .setView(dialogBinding.root)
            .setPositiveButton(R.string.send_stats) { _, _ ->
                val email = dialogBinding.etEmail.text.toString().trim()
                if (Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
                    prefs.edit().putString(PREF_EXPORT_EMAIL, email).apply()
                    performStatsExport(email)
                } else {
                    Toast.makeText(this, R.string.invalid_email, Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun performStatsExport(email: String) {
        lifecycleScope.launch {
            val sessions = viewModel.getAllSessionsOnce()
            if (sessions.isEmpty()) {
                Toast.makeText(this@MainActivity, R.string.no_data_to_export, Toast.LENGTH_SHORT).show()
                return@launch
            }

            val capacity = SettingsActivity.getBatteryCapacity(this@MainActivity)
            val reportText = StatsReport.buildText(this@MainActivity, sessions, capacity)

            val zipFile = try {
                withContext(Dispatchers.IO) {
                    CsvExporter.exportStatsZip(
                        this@MainActivity,
                        StatsReport.buildCsv(sessions, capacity)
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "Stats export failed", e)
                Toast.makeText(this@MainActivity, R.string.export_failed, Toast.LENGTH_LONG).show()
                return@launch
            }

            sendZipByEmail(
                zipFile = zipFile,
                targetEmail = email,
                subject = getString(R.string.stats_email_subject),
                body = reportText,
                chooserTitle = getString(R.string.send_stats)
            )
        }
    }

    /**
     * Odesle ZIP jako prilohu emailu. Preferuje Gmail, jinak nabidne chooser.
     *
     * Suppress: queries v manifestu deklaruje Gmail, ostatni emailove aplikace
     * jsou dostupne pres ACTION_SEND bez query declaration (standardni intent).
     */
    @SuppressLint("QueryPermissionsNeeded")
    private fun sendZipByEmail(
        zipFile: File,
        targetEmail: String,
        subject: String,
        body: String,
        chooserTitle: String
    ) {
        val uri: Uri = try {
            FileProvider.getUriForFile(this, "${packageName}.fileprovider", zipFile)
        } catch (e: Exception) {
            Log.e(TAG, "FileProvider failed for ${zipFile.absolutePath}", e)
            Toast.makeText(this, R.string.export_failed, Toast.LENGTH_LONG).show()
            return
        }

        val baseIntent = Intent(Intent.ACTION_SEND).apply {
            type = "application/zip"
            putExtra(Intent.EXTRA_EMAIL, arrayOf(targetEmail))
            putExtra(Intent.EXTRA_SUBJECT, subject)
            putExtra(Intent.EXTRA_TEXT, body)
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        val gmailIntent = Intent(baseIntent).setPackage(GMAIL_PACKAGE)
        when {
            packageManager.queryIntentActivities(gmailIntent, 0).isNotEmpty() ->
                startActivity(gmailIntent)
            packageManager.queryIntentActivities(baseIntent, 0).isNotEmpty() ->
                startActivity(Intent.createChooser(baseIntent, chooserTitle))
            else -> {
                Log.w(TAG, "No email app found to send export")
                Toast.makeText(this, R.string.no_email_app, Toast.LENGTH_LONG).show()
            }
        }
    }

    // =========================================================
    // Zaloha do souboru (ZIP ulozeny pres SAF)
    // =========================================================

    private fun startFileBackup() {
        backupFileLauncher.launch(CsvExporter.backupFileName())
    }

    /**
     * Zapise celou historii jako ZIP do souboru vybraneho uzivatelem.
     *
     * Zalohuje se vzdy vse bez filtru - zaloha, ze ktere nejde obnovit vsechno,
     * neni zaloha. Stejny ZIP prijima i "Obnovit ze zalohy".
     */
    private fun writeBackupToFile(uri: Uri) {
        lifecycleScope.launch {
            val sessions = viewModel.getAllSessionsOnce()
            if (sessions.isEmpty()) {
                Toast.makeText(this@MainActivity, R.string.no_data_to_export, Toast.LENGTH_SHORT).show()
                return@launch
            }

            val ok = withContext(Dispatchers.IO) {
                try {
                    contentResolver.openOutputStream(uri)?.use { out ->
                        CsvExporter.writeBackupZip(sessions, out)
                    } ?: return@withContext false
                    true
                } catch (e: Exception) {
                    Log.e(TAG, "Backup to file failed for $uri", e)
                    false
                }
            }

            if (ok) {
                Toast.makeText(
                    this@MainActivity,
                    getString(R.string.backup_saved, sessions.size),
                    Toast.LENGTH_LONG
                ).show()
            } else {
                Toast.makeText(this@MainActivity, R.string.backup_save_failed, Toast.LENGTH_LONG).show()
            }
        }
    }

    // =========================================================
    // Import (obnovení zálohy ze ZIP/CSV)
    // =========================================================

    private fun startImport() {
        importLauncher.launch(arrayOf(
            "application/zip",
            "application/x-zip-compressed",
            "text/csv",
            "text/comma-separated-values",
            "*/*"
        ))
    }

    private fun handleImport(uri: Uri) {
        lifecycleScope.launch {
            val sessions = withContext(Dispatchers.IO) {
                CsvImporter.parseSessions(uri, this@MainActivity)
            }
            if (sessions.isEmpty()) {
                Toast.makeText(this@MainActivity, R.string.import_no_data, Toast.LENGTH_LONG).show()
            } else {
                showImportConfirmDialog(sessions)
            }
        }
    }

    private fun showImportConfirmDialog(sessions: List<ChargingSession>) {
        AlertDialog.Builder(this)
            .setTitle(R.string.import_title)
            .setMessage(getString(R.string.import_found_records, sessions.size))
            .setPositiveButton(R.string.import_add) { _, _ ->
                performImport(sessions, replaceAll = false)
            }
            .setNeutralButton(R.string.import_replace) { _, _ ->
                // Druhé potvrzení – destruktivní operace
                AlertDialog.Builder(this)
                    .setTitle(R.string.import_replace)
                    .setMessage(R.string.import_replace_confirm)
                    .setPositiveButton(R.string.import_replace) { _, _ ->
                        performImport(sessions, replaceAll = true)
                    }
                    .setNegativeButton(R.string.cancel, null)
                    .show()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun performImport(sessions: List<ChargingSession>, replaceAll: Boolean) {
        viewModel.importSessions(sessions, replaceAll) { count ->
            if (count >= 0) {
                Toast.makeText(this, getString(R.string.import_done, count), Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, R.string.import_failed, Toast.LENGTH_LONG).show()
            }
        }
    }

    // =========================================================
    // Adapter pro ViewPager2
    // =========================================================

    private inner class MainPagerAdapter(fa: FragmentActivity) : FragmentStateAdapter(fa) {
        override fun getItemCount() = TAB_COUNT
        override fun createFragment(position: Int): Fragment = when (position) {
            TAB_DASHBOARD -> DashboardFragment()
            TAB_RECORDS   -> SessionListFragment()
            // Grafy maji vlastni vnitrni zalozky Total / Elektrina / Benzin / Porovnani
            TAB_CHARTS    -> ChartsHostFragment()
            else -> throw IllegalStateException("Unknown page position: $position")
        }
    }
}
