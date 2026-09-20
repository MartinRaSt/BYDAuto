package com.byd.charging.ui.settings

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.ArrayAdapter
import androidx.appcompat.app.AppCompatActivity
import com.byd.charging.R
import com.byd.charging.databinding.ActivitySettingsBinding
import com.byd.charging.ui.main.MainActivity
import com.byd.charging.util.LocaleHelper

class SettingsActivity : AppCompatActivity() {

    companion object {
        const val PREFS_NAME = "byd_prefs"
        const val KEY_TANK_CAPACITY = "tank_capacity"
        const val KEY_BATTERY_CAPACITY = "battery_capacity"
        const val KEY_THEME = "app_theme"
        
        const val THEME_SYSTEM = "system"
        const val THEME_LIGHT = "light"
        const val THEME_DARK = "dark"
        
        fun getTankCapacity(context: Context): Double {
            return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString(KEY_TANK_CAPACITY, "43")?.toDoubleOrNull() ?: 43.0
        }
        
        fun getBatteryCapacity(context: Context): Double {
            return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString(KEY_BATTERY_CAPACITY, "18")?.toDoubleOrNull() ?: 18.0
        }
    }

    private lateinit var binding: ActivitySettingsBinding

    private val languages = listOf(
        LocaleHelper.LANG_CS to "Čeština",
        LocaleHelper.LANG_EN to "English"
    )

    private val themes = listOf(
        THEME_SYSTEM to R.string.theme_system,
        THEME_LIGHT to R.string.theme_light,
        THEME_DARK to R.string.theme_dark
    )

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.applyLocale(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        title = getString(R.string.settings)

        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        binding.etTankCapacity.setText(prefs.getString(KEY_TANK_CAPACITY, "43"))
        binding.etBatteryCapacity.setText(prefs.getString(KEY_BATTERY_CAPACITY, "18"))

        val adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            languages.map { it.second }
        ).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        binding.spinnerLanguage.adapter = adapter

        val currentLang = LocaleHelper.getLanguage(this)
        val idx = languages.indexOfFirst { it.first == currentLang }
        if (idx >= 0) binding.spinnerLanguage.setSelection(idx)

        // Theme spinner
        val themeAdapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            themes.map { getString(it.second) }
        ).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        binding.spinnerTheme.adapter = themeAdapter

        val currentTheme = prefs.getString(KEY_THEME, THEME_SYSTEM)
        val themeIdx = themes.indexOfFirst { it.first == currentTheme }
        if (themeIdx >= 0) binding.spinnerTheme.setSelection(themeIdx)

        binding.btnApply.setOnClickListener {
            val selected = languages[binding.spinnerLanguage.selectedItemPosition].first
            LocaleHelper.setLanguage(this, selected)

            val selectedTheme = themes[binding.spinnerTheme.selectedItemPosition].first

            prefs.edit()
                .putString(KEY_TANK_CAPACITY, binding.etTankCapacity.text.toString())
                .putString(KEY_BATTERY_CAPACITY, binding.etBatteryCapacity.text.toString())
                .putString(KEY_THEME, selectedTheme)
                .apply()

            applyTheme(selectedTheme)

            // Restartovat celou aplikaci – nutné pro přepnutí locale a theme
            val intent = Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            }
            startActivity(intent)
            finish()
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    private fun applyTheme(theme: String?) {
        val mode = when (theme) {
            THEME_LIGHT -> androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_NO
            THEME_DARK -> androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES
            else -> androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        }
        androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode(mode)
    }
}
