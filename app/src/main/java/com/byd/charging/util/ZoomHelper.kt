package com.byd.charging.util

import android.content.Context
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewGroup
import android.widget.TextView

/**
 * Pomocník pro gesta Pinch-to-Zoom.
 * Dynamicky mění velikost textů a prvků v kontejneru.
 */
object ZoomHelper {

    private const val PREFS_NAME = "zoom_prefs"
    private const val KEY_SCALE = "current_scale"
    private const val MIN_SCALE = 0.8f
    private const val MAX_SCALE = 2.5f

    fun setupPinchToZoom(view: View) {
        val context = view.context
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        var currentScale = prefs.getFloat(KEY_SCALE, 1.0f)

        // Prvotní aplikace uloženého měřítka
        applyScale(view, currentScale)

        val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                currentScale *= detector.scaleFactor
                currentScale = currentScale.coerceIn(MIN_SCALE, MAX_SCALE)
                
                applyScale(view, currentScale)
                
                prefs.edit().putFloat(KEY_SCALE, currentScale).apply()
                return true
            }
        })

        view.setOnTouchListener { v, event ->
            scaleDetector.onTouchEvent(event)
            // Musíme vrátit false nebo true podle toho, zda chceme konzumovat event.
            // Protože jde o kontejner (ScrollView), vracíme false, aby fungoval i scroll.
            false
        }
    }

    private fun applyScale(view: View, scale: Float) {
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                applyScale(view.getChildAt(i), scale)
            }
        }
        
        if (view is TextView) {
            // Změna velikosti písma
            // Poznámka: text.textSize vrací velikost v pixelech. setTextSize(unit, size) nastavuje velikost.
            // Abychom se vyhnuli kumulativnímu růstu, musíme znát původní velikost nebo použít trik.
            // Trik: použijeme tag pro uložení původní velikosti v SP.
            val originalSize = view.getTag(com.byd.charging.R.id.tag_original_text_size) as? Float
                ?: run {
                    val size = view.textSize / view.resources.displayMetrics.scaledDensity
                    view.setTag(com.byd.charging.R.id.tag_original_text_size, size)
                    size
                }
            view.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, originalSize * scale)
        }
        
        // Můžeme měnit i paddingy nebo marginy, ale pro začátek stačí text
    }
}
