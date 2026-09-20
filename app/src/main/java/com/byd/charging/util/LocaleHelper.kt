package com.byd.charging.util

import android.content.Context
import android.content.res.Configuration
import androidx.preference.PreferenceManager
import java.util.Locale

object LocaleHelper {

    const val PREF_LANGUAGE = "pref_language"
    const val LANG_CS = "cs"
    const val LANG_EN = "en"
    private const val DEFAULT_LANGUAGE = LANG_CS

    fun applyLocale(context: Context): Context {
        val lang = getLanguage(context)
        val locale = Locale(lang)
        Locale.setDefault(locale)
        val config = Configuration(context.resources.configuration)
        config.setLocale(locale)
        return context.createConfigurationContext(config)
    }

    fun setLanguage(context: Context, lang: String) {
        PreferenceManager.getDefaultSharedPreferences(context)
            .edit().putString(PREF_LANGUAGE, lang).apply()
    }

    fun getLanguage(context: Context): String {
        return PreferenceManager.getDefaultSharedPreferences(context)
            .getString(PREF_LANGUAGE, DEFAULT_LANGUAGE) ?: DEFAULT_LANGUAGE
    }
}
