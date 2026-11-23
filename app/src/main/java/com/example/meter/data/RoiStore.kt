package com.example.meter.data

import android.content.Context
import com.example.meter.model.Roi

/**
 * Простое хранилище ROI между запусками (SharedPreferences).
 * Чтобы не прицеливаться заново при каждом открытии приложения.
 */
class RoiStore(context: Context) {

    private val prefs = context.getSharedPreferences("meter", Context.MODE_PRIVATE)

    fun load(): Roi? {
        if (!prefs.contains(KEY_U)) return null
        return Roi(
            centerU = prefs.getFloat(KEY_U, 0.5f),
            centerV = prefs.getFloat(KEY_V, 0.5f),
            widthNorm = prefs.getFloat(KEY_W, Roi.DEFAULT_W),
            heightNorm = prefs.getFloat(KEY_H, Roi.DEFAULT_H),
        )
    }

    fun save(roi: Roi) {
        prefs.edit()
            .putFloat(KEY_U, roi.centerU)
            .putFloat(KEY_V, roi.centerV)
            .putFloat(KEY_W, roi.widthNorm)
            .putFloat(KEY_H, roi.heightNorm)
            .apply()
    }

    fun clear() {
        prefs.edit().clear().apply()
    }

    private companion object {
        const val KEY_U = "roi_u"
        const val KEY_V = "roi_v"
        const val KEY_W = "roi_w"
        const val KEY_H = "roi_h"
    }
}
