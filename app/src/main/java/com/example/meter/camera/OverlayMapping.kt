package com.example.meter.camera

import android.graphics.RectF

/**
 * Пересчёт координат между кадром камеры (сенсор, «лежачий» 4:3) и экраном телефона (портрет).
 *
 * Чистая математика без зависимостей от CameraX — легко покрывается тестами на шаге 2.
 *
 * Соглашения:
 *  - буфер кадра: imageWidth x imageHeight в сенсорной ориентации (обычно 640x480, 4:3);
 *  - rotationDegrees — поворот по часовой стрелке, который приводит кадр в вертикальный вид;
 *  - на экране кадр показывается целиком (FIT_CENTER), с чёрными полями по краям.
 *
 * Нормированные координаты (u, v) в диапазоне 0..1 задаются в системе БУФЕРА кадра:
 * u — вдоль ширины, v — вдоль высоты. Это то, что будет читать детектор на шаге 2.
 */
class OverlayMapping(
    val viewWidth: Float,
    val viewHeight: Float,
    val imageWidth: Int,
    val imageHeight: Int,
    val rotationDegrees: Int,
) {
    /** Размер кадра после поворота (в «вертикальном» виде). */
    private val uprightWidth: Float
    private val uprightHeight: Float

    /** Прямоугольник, который кадр занимает на экране при FIT_CENTER. */
    val left: Float
    val top: Float
    val width: Float
    val height: Float

    init {
        val rotated = rotationDegrees == 90 || rotationDegrees == 270
        uprightWidth = (if (rotated) imageHeight else imageWidth).toFloat()
        uprightHeight = (if (rotated) imageWidth else imageHeight).toFloat()

        val scale = minOf(viewWidth / uprightWidth, viewHeight / uprightHeight)
        width = uprightWidth * scale
        height = uprightHeight * scale
        left = (viewWidth - width) / 2f
        top = (viewHeight - height) / 2f
    }

    /** Нормированные координаты буфера (0..1) -> точка на экране (пиксели). */
    fun bufferToView(u: Float, v: Float): FloatArray {
        val x: Float
        val y: Float
        when (rotationDegrees) {
            90 -> { x = 1f - v; y = u }
            180 -> { x = 1f - u; y = 1f - v }
            270 -> { x = v; y = 1f - u }
            else -> { x = u; y = v }
        }
        return floatArrayOf(left + x * width, top + y * height)
    }

    /** Точка на экране (пиксели) -> нормированные координаты буфера (0..1). */
    fun viewToBuffer(viewX: Float, viewY: Float): FloatArray {
        val x = ((viewX - left) / width).coerceIn(0f, 1f)
        val y = ((viewY - top) / height).coerceIn(0f, 1f)
        return when (rotationDegrees) {
            90 -> floatArrayOf(y, 1f - x)
            180 -> floatArrayOf(1f - x, 1f - y)
            270 -> floatArrayOf(1f - y, x)
            else -> floatArrayOf(x, y)
        }
    }

    /** Прямоугольник в нормированных координатах буфера -> прямоугольник на экране. */
    fun bufferRectToView(u0: Float, v0: Float, u1: Float, v1: Float): RectF {
        val a = bufferToView(u0, v0)
        val b = bufferToView(u1, v1)
        return RectF(
            minOf(a[0], b[0]),
            minOf(a[1], b[1]),
            maxOf(a[0], b[0]),
            maxOf(a[1], b[1]),
        )
    }
}
