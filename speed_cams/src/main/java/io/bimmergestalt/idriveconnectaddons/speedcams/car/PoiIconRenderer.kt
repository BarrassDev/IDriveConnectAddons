package io.bimmergestalt.idriveconnectaddons.speedcams.car

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import io.bimmergestalt.idriveconnectaddons.speedcams.cameras.CameraType
import java.io.ByteArrayOutputStream

/** Draws the round camera marker as a PNG for the car's map overlay */
object PoiIconRenderer {
    fun color(type: CameraType) = when (type) {
        CameraType.SPEED -> Color.rgb(229, 57, 53)
        CameraType.AVERAGE -> Color.rgb(142, 36, 170)
        CameraType.RED_LIGHT -> Color.rgb(251, 140, 0)
        CameraType.MOBILE -> Color.rgb(216, 27, 96)
    }

    fun render(type: CameraType, size: Int): ByteArray {
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val c = size / 2f
        val r = size / 2f - 2
        val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        fill.color = color(type)
        canvas.drawCircle(c, c, r, fill)
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = size / 14f; color = Color.WHITE }
        canvas.drawCircle(c, c, r - stroke.strokeWidth / 2, stroke)
        fill.color = Color.WHITE
        val w = r * 0.95f
        val h = r * 0.62f
        canvas.drawRoundRect(RectF(c - w / 2, c - h / 2 + 1, c + w / 2, c + h / 2 + 1), 3f, 3f, fill)
        canvas.drawRect(c - w * 0.18f, c - h / 2 - r * 0.16f, c + w * 0.18f, c - h / 2 + 1, fill)
        fill.color = color(type)
        canvas.drawCircle(c, c + 1, h * 0.32f, fill)
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
        bmp.recycle()
        return out.toByteArray()
    }
}
