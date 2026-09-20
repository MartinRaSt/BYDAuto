package com.byd.charging.util

import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

/**
 * Utility pro práci s daty a časy.
 * Používá java.time (API 26+) — immutabilní, thread-safe, bez SimpleDateFormat.
 */
object DateUtil {

    // Oba formátovače jsou immutabilní → sdílení mezi vlákny je bezpečné
    private val STORAGE_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")
    private val DISPLAY_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM.yyyy")

    /** yyyy-MM-dd → dd.MM.yyyy; při chybě vrátí vstup beze změny */
    fun toDisplayDate(isoDate: String): String = try {
        LocalDate.parse(isoDate.trim(), STORAGE_FMT).format(DISPLAY_FMT)
    } catch (_: DateTimeParseException) {
        isoDate
    }

    /** dd.MM.yyyy → yyyy-MM-dd; při chybě vrátí vstup beze změny */
    fun toStorageDate(displayDate: String): String = try {
        LocalDate.parse(displayDate.trim(), DISPLAY_FMT).format(STORAGE_FMT)
    } catch (_: DateTimeParseException) {
        displayDate
    }

    /** Dnešní datum ve formátu dd.MM.yyyy */
    fun todayDisplay(): String = LocalDate.now().format(DISPLAY_FMT)

    /** Dnešní datum ve formátu yyyy-MM-dd */
    fun todayIso(): String = LocalDate.now().format(STORAGE_FMT)

    /** Aktuální čas ve formátu HH:MM */
    fun currentTimeHHMM(): String = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm"))

    /**
     * Parsuje zobrazovací datum (dd.MM.yyyy) a vrátí Triple(rok, měsíc-0based, den)
     * pro inicializaci DatePickerDialog. Vrací null při neplatném formátu.
     */
    fun parseDisplayToCalendar(displayDate: String): Triple<Int, Int, Int>? = try {
        val d = LocalDate.parse(displayDate.trim(), DISPLAY_FMT)
        Triple(d.year, d.monthValue - 1 /* Calendar.MONTH je 0-based */, d.dayOfMonth)
    } catch (_: DateTimeParseException) {
        null
    }

    /**
     * Ověří, že řetězec je platné ISO datum (yyyy-MM-dd).
     */
    fun isValidIsoDate(date: String): Boolean = try {
        LocalDate.parse(date.trim(), STORAGE_FMT); true
    } catch (_: DateTimeParseException) {
        false
    }

    /** HH:mm, HH:mm -> duration in minutes */
    fun calculateDuration(start: String, end: String): Int {
        return try {
            val s = LocalTime.parse(start.trim())
            val e = LocalTime.parse(end.trim())
            var diff = java.time.Duration.between(s, e).toMinutes().toInt()
            if (diff < 0) diff += 1440 // Přes půlnoc
            diff
        } catch (_: Exception) { 0 }
    }

    /** Minutes -> HH:mm */
    fun formatDuration(minutes: Int): String {
        val h = minutes / 60
        val m = minutes % 60
        return String.format("%02d:%02d", h, m)
    }

    /** HH:mm -> minutes */
    fun parseDuration(hhmm: String): Int? {
        val parts = hhmm.split(":")
        if (parts.size != 2) return hhmm.toIntOrNull() // Fallback na čisté minuty
        val h = parts[0].toIntOrNull() ?: return null
        val m = parts[1].toIntOrNull() ?: return null
        return h * 60 + m
    }

    /**
     * Absolutni rozdil dvou casu v minutach v ramci jednoho dne (0-720).
     *
     * Na rozdil od calculateDuration NEresi prechod pres pulnoc - je urcena
     * pro dotaz "jak daleko od sebe ty dva casy jsou", kde je 09:55 od 10:00
     * stejne daleko jako 10:05.
     */
    fun absMinutesDiff(a: String, b: String): Int {
        return try {
            val ta = LocalTime.parse(a.trim())
            val tb = LocalTime.parse(b.trim())
            val diff = Math.abs(java.time.Duration.between(ta, tb).toMinutes().toInt())
            minOf(diff, 1440 - diff)
        } catch (_: Exception) { Int.MAX_VALUE }
    }

    /** HH:mm, duration in minutes -> HH:mm */
    fun addMinutes(start: String, minutes: Int): String {
        return try {
            val s = LocalTime.parse(start.trim())
            s.plusMinutes(minutes.toLong()).format(DateTimeFormatter.ofPattern("HH:mm"))
        } catch (_: Exception) { start }
    }

    /** HH:mm, duration in minutes -> HH:mm (pres pulnoc se zacatek prenese do predchoziho dne) */
    fun subtractMinutes(end: String, minutes: Int): String {
        return try {
            val e = LocalTime.parse(end.trim())
            e.minusMinutes(minutes.toLong()).format(DateTimeFormatter.ofPattern("HH:mm"))
        } catch (_: Exception) { end }
    }
}
