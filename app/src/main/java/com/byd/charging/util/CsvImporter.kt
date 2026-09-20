package com.byd.charging.util

import android.content.Context
import android.net.Uri
import android.util.Log
import com.byd.charging.data.ChargingSession
import com.byd.charging.data.ChargingType
import java.util.zip.ZipInputStream

/**
 * Parsuje zálohu (ZIP nebo raw CSV) a vrátí seznam relací připravených k uložení.
 *
 * Formát CSV (středníkový oddělovač, UTF-8 BOM):
 * Sloupce 0-8:   povinné (starý formát)
 * Sloupce 9-11:  volitelné (typ nabíjení, cena/kWh, místo)
 * Sloupce 12-13: volitelné (GPS šířka, GPS délka)
 * Sloupce 14-16: volitelné (Hlavní konec, Garáž start, Garáž konec)
 * Sloupec 17:    volitelné (Tachometr)
 * Sloupce 18-19: volitelné (Baterie začátek/konec v %, prázdná buňka = nezadáno)
 *
 * Starší zálohy s méně sloupci se načtou dál - chybějící sloupce dostanou výchozí hodnotu.
 */
object CsvImporter {

    private const val TAG = "CsvImporter"

    /**
     * Přečte URI (ZIP nebo CSV) a vrátí seznam relací.
     * Musí být voláno z IO dispatcheru.
     */
    fun parseSessions(uri: Uri, context: Context): List<ChargingSession> {
        val bytes = try {
            context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
        } catch (e: Exception) {
            Log.e(TAG, "Cannot open URI: $uri", e)
            return emptyList()
        } ?: run {
            Log.w(TAG, "InputStream is null for URI: $uri")
            return emptyList()
        }

        val csvBytes = tryExtractCsvFromZip(bytes) ?: bytes
        return parseCsvText(String(csvBytes, Charsets.UTF_8))
    }

    /**
     * Rozparsuje obsah CSV. Oddelene od cteni souboru, aby sel format otestovat
     * bez Androidu.
     *
     * Radek, ktery neodpovida formatu zalohy, se preskoci. Diky tomu se cizi CSV
     * (napriklad export statistik) nenacte jako data - vrati prazdny seznam
     * a volajici zobrazi hlasku, misto aby vznikly nesmyslne zaznamy.
     */
    fun parseCsvText(rawText: String): List<ChargingSession> {
        val text = rawText.removePrefix("﻿")  // strip BOM

        val lines = text.lines()
        if (lines.size < 2) {
            Log.w(TAG, "CSV has fewer than 2 lines (no data rows)")
            return emptyList()
        }

        var skipped = 0
        val sessions = lines.drop(1)     // první řádek = hlavička
            .filter { it.isNotBlank() }
            .mapNotNull { line ->
                val session = parseLine(line)
                if (session == null) skipped++
                session
            }

        Log.i(TAG, "Import parsed: ${sessions.size} ok, $skipped skipped")
        return sessions
    }

    // ---- Privátní ----

    private fun tryExtractCsvFromZip(bytes: ByteArray): ByteArray? {
        return try {
            ZipInputStream(bytes.inputStream()).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory && entry.name.endsWith(".csv", ignoreCase = true)) {
                        val csvBytes = zis.readBytes()
                        Log.d(TAG, "Found CSV in ZIP: ${entry.name} (${csvBytes.size} bytes)")
                        return@use csvBytes
                    }
                    entry = zis.nextEntry
                }
                Log.w(TAG, "No .csv file found inside ZIP")
                null
            }
        } catch (e: Exception) {
            // Není to ZIP – zkusíme jako raw CSV
            Log.d(TAG, "Not a ZIP file, trying as raw CSV")
            null
        }
    }

    private fun parseLine(line: String): ChargingSession? {
        return try {
            val f = parseCsvRow(line)

            // Musíme mít alespoň 8 datových sloupců (ignorujeme ID sloupec f[0])
            if (f.size < 8) {
                Log.w(TAG, "Skipping line with ${f.size} columns (need ≥8): $line")
                return null
            }

            // Kontrola, že řádek není prázdný (všechna pole prázdná)
            if (f.all { it.isBlank() }) return null

            // Sloupce 0–8: povinné
            val date      = parseDate(f[1])
            val startTime = f[2].trim()
            val endTime   = f[3].trim()

            if (!isValidTime(startTime) || !isValidTime(endTime)) {
                Log.w(TAG, "Skipping line with invalid time ($startTime/$endTime)")
                return null
            }

            val powerKw    = parseNum(f[4]) ?: run {
                Log.w(TAG, "Skipping line – invalid power: '${f[4]}'"); return null
            }
            val startMeter = parseNum(f[5]) ?: run {
                Log.w(TAG, "Skipping line – invalid startMeter: '${f[5]}'"); return null
            }
            val charged    = parseNum(f[6]) ?: run {
                Log.w(TAG, "Skipping line – invalid charged: '${f[6]}'"); return null
            }
            val mainMeter  = parseNum(f[7]) ?: run {
                Log.w(TAG, "Skipping line – invalid mainMeter: '${f[7]}'"); return null
            }
            val note = if (f.size > 8) f[8] else ""

            // Sloupce 9–11: volitelné (starý formát má jen 9 sloupců)
            val chargingType = if (f.size > 9 && f[9].isNotBlank())
                ChargingType.fromString(f[9].trim()).name
            else
                ChargingType.HOME_GRID.name

            val pricePerKwh  = if (f.size > 10) parseNum(f[10]) ?: 0.0 else 0.0
            val locationName = if (f.size > 11) f[11] else ""

            // Sloupce 12-13: GPS souřadnice (volitelné, prázdné = nezadáno)
            val latitude  = if (f.size > 12 && f[12].isNotBlank()) parseNum(f[12]) ?: 0.0 else 0.0
            val longitude = if (f.size > 13 && f[13].isNotBlank()) parseNum(f[13]) ?: 0.0 else 0.0

            // Sloupce 14-16: Rozšířené stavy elektroměrů (volitelné)
            val mainEnd   = if (f.size > 14 && f[14].isNotBlank()) parseNum(f[14]) ?: 0.0 else 0.0
            val garageStart = if (f.size > 15 && f[15].isNotBlank()) parseNum(f[15]) ?: 0.0 else 0.0
            val garageEnd   = if (f.size > 16 && f[16].isNotBlank()) parseNum(f[16]) ?: 0.0 else 0.0
            val odo         = if (f.size > 17 && f[17].isNotBlank()) parseNum(f[17]) ?: 0.0 else 0.0

            // Sloupce 18-19: stav baterie v % (volitelne; prazdna bunka = nezadano)
            val socStart = parseSoc(f, 18)
            val socEnd   = parseSoc(f, 19)

            ChargingSession(
                id            = 0L,    // Room přiřadí nové auto-generované ID
                date          = date,
                startTime     = startTime,
                endTime       = endTime,
                powerKw       = powerKw,
                startMeterKwh = startMeter,
                chargedKwh    = charged,
                mainMeterKwh  = mainMeter,
                note          = note,
                chargingType  = chargingType,
                pricePerKwh   = pricePerKwh,
                locationName  = locationName,
                latitude      = latitude,
                longitude     = longitude,
                mainMeterEndKwh = mainEnd,
                garageMeterStartKwh = garageStart,
                garageMeterEndKwh = garageEnd,
                odometer      = odo,
                socStart      = socStart,
                socEnd        = socEnd
            )
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected error parsing line: $line", e)
            null
        }
    }

    /**
     * Nacte stav baterie ze sloupce. Chybejici nebo prazdna bunka znamena "nezadano",
     * stejne tak hodnota mimo rozsah 0-100 (radeji nic nez nesmyslne procento).
     */
    private fun parseSoc(fields: List<String>, index: Int): Double {
        if (fields.size <= index || fields[index].isBlank()) return ChargingSession.SOC_UNSET
        val value = parseNum(fields[index]) ?: return ChargingSession.SOC_UNSET
        return if (value in 0.0..100.0) value else ChargingSession.SOC_UNSET
    }

    /**
     * RFC 4180 CSV parser: rozparuje jeden řádek respektující uvozovkovaná pole.
     * Uvnitř uvozovek může být středník a "" reprezentuje jeden uvozovkový znak.
     */
    private fun parseCsvRow(line: String): List<String> {
        val result  = mutableListOf<String>()
        val current = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                c == '"' && !inQuotes -> inQuotes = true
                c == '"' && inQuotes && i + 1 < line.length && line[i + 1] == '"' -> {
                    current.append('"')
                    i++   // přeskočit druhý uvozovkový znak
                }
                c == '"' && inQuotes -> inQuotes = false
                c == ';' && !inQuotes -> {
                    result.add(current.toString())
                    current.clear()
                }
                else -> current.append(c)
            }
            i++
        }
        result.add(current.toString())
        return result
    }

    /** Akceptuje dd.MM.yyyy i yyyy-MM-dd, vrátí vždy yyyy-MM-dd. */
    private fun parseDate(s: String): String {
        val t = s.trim()
        return if (ISO_DATE_REGEX.matches(t)) t else DateUtil.toStorageDate(t)
    }

    /** Akceptuje čárku i tečku jako desetinný oddělovač. */
    private fun parseNum(s: String): Double? =
        s.trim().replace(",", ".").toDoubleOrNull()

    private fun isValidTime(t: String): Boolean = TIME_REGEX.matches(t)

    private val ISO_DATE_REGEX = Regex("\\d{4}-\\d{2}-\\d{2}")
    private val TIME_REGEX = Regex("^([01]\\d|2[0-3]):[0-5]\\d$")
}
