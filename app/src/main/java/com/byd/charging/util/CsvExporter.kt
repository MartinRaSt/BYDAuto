package com.byd.charging.util

import android.content.Context
import android.util.Log
import com.byd.charging.data.ChargingSession
import java.io.ByteArrayOutputStream
import java.util.Locale
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object CsvExporter {

    private const val TAG = "CsvExporter"
    private const val SEP = ";"
    private const val CSV_FILENAME = "byd_logger_nabijeni.csv"
    const val ZIP_FILENAME = "byd_logger_export.zip"

    private const val STATS_CSV_FILENAME = "byd_logger_statistiky.csv"
    const val STATS_ZIP_FILENAME = "byd_logger_statistiky.zip"

    /** Adresář uvnitř cacheDir vymezený pro exporty (FileProvider ho sdílí). */
    private const val EXPORT_SUBDIR = "exports"

    /**
     * Nazev souboru zalohy s dnesnim datem, napr. byd_logger_zaloha_2026-09-20.zip.
     * Datum v nazvu je zamerne: uzivatel pak v ulozisti pozna, ktera zaloha je novejsi.
     */
    fun backupFileName(): String = "byd_logger_zaloha_${DateUtil.todayIso()}.zip"

    /**
     * Zapise zalohu (ZIP s CSV) do libovolneho vystupniho proudu.
     *
     * Pouziva se pro ulozeni zalohy do souboru vybraneho uzivatelem (SAF).
     * Proud se zavira volajicim - ten ho take otevrel.
     * Volat z IO dispatcheru.
     */
    fun writeBackupZip(sessions: List<ChargingSession>, output: OutputStream) {
        ZipOutputStream(output).use { zos ->
            zos.putNextEntry(ZipEntry(CSV_FILENAME))
            zos.write(buildCsv(sessions))
            zos.closeEntry()
        }
    }

    /**
     * Vytvoří ZIP s CSV a vrátí soubor připravený ke sdílení přes FileProvider.
     * Volat z IO dispatcheru.
     */
    fun exportZip(context: Context, sessions: List<ChargingSession>): File {
        val zipFile = zipSingleFile(context, ZIP_FILENAME, CSV_FILENAME, buildCsv(sessions))
        Log.d(TAG, "Export done: ${zipFile.length()} bytes, ${sessions.size} sessions")
        return zipFile
    }

    /**
     * Zabali hotove CSV se statistikami do ZIP pro odeslani emailem.
     * CSV sestavuje StatsReport - tahle trida jen resi soubor a archiv.
     */
    fun exportStatsZip(context: Context, statsCsv: ByteArray): File {
        val zipFile = zipSingleFile(context, STATS_ZIP_FILENAME, STATS_CSV_FILENAME, statsCsv)
        Log.d(TAG, "Stats export done: ${zipFile.length()} bytes")
        return zipFile
    }

    /**
     * Zapise jeden soubor do ZIP v cache adresari pro exporty.
     * Pripadny starsi archiv stejneho jmena se maze - nechceme je na disku hromadit.
     * Volat z IO dispatcheru; chybu nechava probublat, volajici zobrazi hlasku.
     */
    private fun zipSingleFile(
        context: Context,
        zipName: String,
        entryName: String,
        content: ByteArray
    ): File {
        val exportDir = File(context.cacheDir, EXPORT_SUBDIR).also {
            if (!it.exists() && !it.mkdirs()) {
                Log.w(TAG, "Could not create export directory: ${it.absolutePath}")
            }
        }
        val zipFile = File(exportDir, zipName)
        if (zipFile.exists()) zipFile.delete()

        try {
            ZipOutputStream(FileOutputStream(zipFile)).use { zos ->
                zos.putNextEntry(ZipEntry(entryName))
                zos.write(content)
                zos.closeEntry()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Zip creation failed for $zipName", e)
            throw e
        }
        return zipFile
    }

    /** Sestavi CSV zalohy. Verejne kvuli testu, ktery overuje kolecko export - import. */
    fun buildCsv(sessions: List<ChargingSession>): ByteArray {
        val out = ByteArrayOutputStream()
        // BOM – správné otevření v českém Excelu
        out.write(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()))
        OutputStreamWriter(out, Charsets.UTF_8).use { writer ->
            writer.appendLine(
                listOf(
                    "ID", "Datum", "Začátek", "Konec",
                    "Výkon (kW)", "Elektroměr auto začátek (kWh)",
                    "Nabito (kWh)", "Hlavní elektroměr (kWh)", "Poznámka",
                    "Typ nabíjení", "Cena/kWh", "Místo",
                    "GPS šířka", "GPS délka",
                    "Hlavní elektroměr konec (kWh)", "Garáž začátek (kWh)", "Garáž konec (kWh)",
                    "Tachometr (km)",
                    "Baterie začátek (%)", "Baterie konec (%)"
                ).joinToString(SEP)
            )
            sessions.forEach { s ->
                // GPS souřadnice – vždy s tečkou (Locale.US), aby nevadily české locale
                val lat = if (s.hasGpsCoordinates) String.format(Locale.US, "%.8f", s.latitude)  else ""
                val lng = if (s.hasGpsCoordinates) String.format(Locale.US, "%.8f", s.longitude) else ""
                writer.appendLine(
                    listOf(
                        s.id.toString(),
                        DateUtil.toDisplayDate(s.date),
                        s.startTime,
                        s.endTime,
                        NumberUtil.format(s.powerKw),
                        NumberUtil.format(s.startMeterKwh),
                        NumberUtil.format(s.chargedKwh),
                        NumberUtil.format(s.mainMeterKwh),
                        csvQuote(s.note),
                        s.chargingType,
                        NumberUtil.format(s.pricePerKwh),
                        csvQuote(s.locationName),
                        lat,
                        lng,
                        NumberUtil.format(s.mainMeterEndKwh),
                        NumberUtil.format(s.garageMeterStartKwh),
                        NumberUtil.format(s.garageMeterEndKwh),
                        NumberUtil.format(s.odometer),
                        soc(s.socStart),
                        soc(s.socEnd)
                    ).joinToString(SEP)
                )
            }
        }
        return out.toByteArray()
    }

    /** Nezadany stav baterie se exportuje jako prazdna bunka, ne jako sentinel -1. */
    private fun soc(value: Double): String =
        if (value >= 0.0) NumberUtil.format(value) else ""

    /** RFC 4180: obalí hodnotu uvozovkami a escapuje vnitřní uvozovky jako "". */
    private fun csvQuote(value: String): String = "\"${value.replace("\"", "\"\"")}\""
}
