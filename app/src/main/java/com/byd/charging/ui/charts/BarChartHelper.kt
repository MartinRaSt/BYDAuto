package com.byd.charging.ui.charts

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import androidx.core.content.ContextCompat
import com.byd.charging.util.NumberUtil
import com.github.mikephil.charting.charts.BarChart
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.data.BarData
import com.github.mikephil.charting.data.BarDataSet
import com.github.mikephil.charting.data.BarEntry
import com.github.mikephil.charting.formatter.IndexAxisValueFormatter
import com.github.mikephil.charting.formatter.ValueFormatter

/**
 * Spolecne nastaveni sloupcovych grafu pro rychly prehled na zalozkach.
 *
 * Duvod existence: barvy textu podle svetleho a tmaveho motivu, popisky osy
 * a formatovani hodnot jsou u kazdeho takoveho grafu stejne. Bez teto tridy
 * by se stejnych dvacet radku opakovalo v kazdem fragmentu a casem rozesly.
 */
object BarChartHelper {

    /** Jeden sloupec grafu. Hodnota null znamena "nelze spocitat" a sloupec se vynecha. */
    data class Bar(val label: String, val value: Double?)

    /**
     * Vykresli sloupce do grafu. Vraci false, pokud nebylo co kreslit -
     * volajici pak graf skryje a ukaze hlasku o chybejicich datech.
     *
     * [decimals] urcuje zaokrouhleni popisku nad sloupci.
     */
    fun show(
        chart: BarChart,
        bars: List<Bar>,
        colorRes: Int,
        decimals: Int = 1
    ): Boolean {
        val usable = bars.filter { it.value != null && it.value > 0.0 }
        if (usable.isEmpty()) return false

        val context = chart.context
        val textColor = textColorFor(context)

        val entries = usable.mapIndexed { i, bar -> BarEntry(i.toFloat(), bar.value!!.toFloat()) }
        val dataSet = BarDataSet(entries, "").apply {
            color = ContextCompat.getColor(context, colorRes)
            valueTextSize = 9f
            valueTextColor = textColor
            valueFormatter = decimalFormatter(decimals)
        }

        chart.apply {
            data = BarData(dataSet).apply { barWidth = 0.55f }
            description.isEnabled = false
            legend.isEnabled = false
            setScaleEnabled(false)
            setFitBars(true)

            xAxis.apply {
                position = XAxis.XAxisPosition.BOTTOM
                valueFormatter = IndexAxisValueFormatter(usable.map { it.label })
                granularity = 1f
                setDrawGridLines(false)
                setCenterAxisLabels(false)
                resetAxisMinimum()
                resetAxisMaximum()
                labelRotationAngle = if (usable.size > 6) -45f else 0f
                this.textColor = textColor
            }
            axisLeft.apply {
                axisMinimum = 0f
                this.textColor = textColor
            }
            axisRight.isEnabled = false
            animateY(400)
            invalidate()
        }
        return true
    }

    /** Barva textu podle aktualniho motivu; fixni barva by v jednom z nich zmizela. */
    fun textColorFor(context: Context): Int {
        val night = (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                Configuration.UI_MODE_NIGHT_YES
        return if (night) Color.WHITE else Color.BLACK
    }

    private fun decimalFormatter(decimals: Int): ValueFormatter = object : ValueFormatter() {
        override fun getFormattedValue(value: Float): String {
            if (value <= 0f) return ""
            val factor = Math.pow(10.0, decimals.toDouble())
            return NumberUtil.format(Math.round(value * factor) / factor)
        }
    }
}
