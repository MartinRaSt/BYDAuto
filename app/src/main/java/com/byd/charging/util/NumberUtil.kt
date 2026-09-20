package com.byd.charging.util

import java.text.NumberFormat
import java.util.Locale

/**
 * Utility pro formátování a parsování čísel.
 * NumberFormat se vytváří jako nová instance (thread-safe — sdílení instance je UNSAFE).
 */
object NumberUtil {

    /**
     * Parsuje číslo ze vstupu uživatele.
     * Akceptuje čárku i tečku jako desetinný oddělovač, ignoruje mezery.
     * Vrací null pokud vstup není platné číslo.
     */
    fun parse(input: String): Double? {
        val cleaned = input.trim().replace(" ", "").replace(",", ".")
        if (cleaned.isEmpty()) return null
        return cleaned.toDoubleOrNull()
    }

    /**
     * Parsuje číslo a ověří, že je >= 0. Vrací null pokud vstup není platné nezáporné číslo.
     */
    fun parseNonNegative(input: String): Double? {
        val v = parse(input) ?: return null
        return if (v >= 0.0) v else null
    }

    /**
     * Parsuje číslo a ověří, že je > 0. Vrací null pokud vstup není platné kladné číslo.
     */
    fun parsePositive(input: String): Double? {
        val v = parse(input) ?: return null
        return if (v > 0.0) v else null
    }

    /**
     * Formátuje číslo dle aktuálního locale (v češtině čárka, v angličtině tečka).
     * Používá pro zobrazení v UI a v exportovaném CSV.
     */
    fun format(value: Double): String {
        val nf = NumberFormat.getInstance(Locale.getDefault())
        nf.minimumFractionDigits = 0
        nf.maximumFractionDigits = 3
        nf.isGroupingUsed = false
        return nf.format(value)
    }

    /**
     * Formátuje celkovou cenu (vždy zaokrouhleno na celé koruny).
     */
    fun formatCost(value: Double): String {
        val nf = NumberFormat.getInstance(Locale.getDefault())
        nf.minimumFractionDigits = 0
        nf.maximumFractionDigits = 0
        nf.isGroupingUsed = false
        return nf.format(Math.round(value))
    }

    /**
     * Formátuje číslo vždy s tečkou (pro pole formuláře s android:inputType="numberDecimal").
     * Android's numberDecimal klávesnice na řadě zařízení odmítá čárku, proto vždy tečka.
     */
    fun formatForInput(value: Double): String {
        val nf = NumberFormat.getInstance(Locale.US)
        nf.minimumFractionDigits = 0
        nf.maximumFractionDigits = 3
        nf.isGroupingUsed = false
        return nf.format(value)
    }
}
