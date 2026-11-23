package com.example.meter.model

/**
 * Прямоугольник интереса (ROI) в НОРМИРОВАННЫХ координатах кадра (0..1).
 *
 * Хранится независимо от разрешения превью и анализа, поэтому переживает смену разрешения.
 * По умолчанию — квадрат 64x64 пикселя при кадре 640x480.
 */
data class Roi(
    val centerU: Float,
    val centerV: Float,
    val widthNorm: Float = DEFAULT_W,
    val heightNorm: Float = DEFAULT_H,
) {
    val u0: Float get() = (centerU - widthNorm / 2f).coerceIn(0f, 1f)
    val v0: Float get() = (centerV - heightNorm / 2f).coerceIn(0f, 1f)
    val u1: Float get() = (centerU + widthNorm / 2f).coerceIn(0f, 1f)
    val v1: Float get() = (centerV + heightNorm / 2f).coerceIn(0f, 1f)

    companion object {
        // 64 x 64 пикселя при кадре 640 x 480
        const val DEFAULT_W = 64f / 640f
        const val DEFAULT_H = 64f / 480f
    }
}
