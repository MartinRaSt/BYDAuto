package com.byd.charging

import android.app.Application
import android.content.Context
import android.util.Log
import com.byd.charging.util.LocaleHelper
import java.io.File

class ChargingApp : Application() {

    private companion object {
        const val TAG = "ChargingApp"
    }

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(LocaleHelper.applyLocale(base))
    }

    override fun onCreate() {
        super.onCreate()
        applyTheme()
        cleanExportCache()
    }

    private fun applyTheme() {
        val prefs = getSharedPreferences("byd_prefs", Context.MODE_PRIVATE)
        val theme = prefs.getString("app_theme", "system")
        val mode = when (theme) {
            "light" -> androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_NO
            "dark" -> androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES
            else -> androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        }
        androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode(mode)
    }

    /**
     * Smaže starý obsah export adresáře při každém spuštění aplikace.
     * ZIP soubory z předchozích exportů jsou zbytečné — Android je může smazat
     * sám (jsou v cacheDir), ale takto aktivně uvolňujeme místo.
     */
    private fun cleanExportCache() {
        try {
            val exportDir = File(cacheDir, "exports")
            if (exportDir.exists()) {
                exportDir.listFiles()?.forEach { file ->
                    if (!file.delete()) {
                        Log.w(TAG, "Could not delete cached export: ${file.name}")
                    }
                }
            }
        } catch (e: Exception) {
            // Nekritická chyba – aplikace může normálně pokračovat
            Log.w(TAG, "Export cache cleanup failed", e)
        }
    }
}
