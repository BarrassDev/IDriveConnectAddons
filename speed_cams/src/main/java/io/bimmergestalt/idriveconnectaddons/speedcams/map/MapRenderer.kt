package io.bimmergestalt.idriveconnectaddons.speedcams.map

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import io.bimmergestalt.idriveconnectaddons.speedcams.DriveState
import io.bimmergestalt.idriveconnectaddons.speedcams.cameras.CameraType
import io.bimmergestalt.idriveconnectaddons.speedcams.geo.METERS_PER_MILE
import kotlin.math.PI
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.roundToInt
import kotlin.math.tan

/**
 * Draws the car screen picture: a moving map with the car, the predicted road,
 * the cameras, a Waze-style speed readout and the camera warning banner.
 *
 * Important information stays in the left ~60% of the picture,
 * so it is still visible when the iDrive split screen covers the right side.
 */
class MapRenderer(private val tiles: TileCache) {
    data class Options(val headingUp: Boolean, val showMph: Boolean, val dark: Boolean, val alertMeters: Double)

    private var zoom = 16
    private val tilePaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD) }
    private val small = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.SANS_SERIF }

    private var bitmap: Bitmap? = null

    fun render(state: DriveState, width: Int, height: Int, options: Options): Bitmap {
        val bmp = bitmap?.takeIf { it.width == width && it.height == height }
            ?: Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { bitmap = it }
        val canvas = Canvas(bmp)
        val bg = if (options.dark) Color.rgb(28, 30, 34) else Color.rgb(236, 233, 226)
        canvas.drawColor(bg)

        val fix = state.fix
        val focusWidth = (width * 0.62f).coerceAtLeast(400f).coerceAtMost(width.toFloat())
        if (fix == null) {
            centeredText(canvas, "Waiting for GPS…", focusWidth / 2, height / 2f, 34f, if (options.dark) Color.WHITE else Color.DKGRAY)
            drawAttribution(canvas, width, height, options)
            return bmp
        }

        val speed = state.speed ?: 0.0
        zoom = when {
            speed > 24 -> 15                // above ~54 mph
            speed < 20 -> 16                // below ~45 mph
            else -> zoom
        }
        val heading = state.heading ?: 0.0
        val rotation = if (options.headingUp) heading.toFloat() else 0f
        val carX = focusWidth / 2
        val carY = if (options.headingUp) height * 0.70f else height * 0.5f

        val worldSize = TileCache.TILE_SIZE * (1 shl zoom).toDouble()
        val px = lonToX(fix.position.lon, worldSize)
        val py = latToY(fix.position.lat, worldSize)

        // world pixel -> screen
        val matrix = Matrix().apply {
            postTranslate(-px.toFloat(), -py.toFloat())
            postRotate(-rotation)
            postTranslate(carX, carY)
        }

        val style = if (options.dark) TileCache.Style.DARK else TileCache.Style.LIGHT
        canvas.save()
        canvas.concat(matrix)
        val reach = hypot(width.toDouble(), height.toDouble())
        val minTx = floor((px - reach) / TileCache.TILE_SIZE).toInt()
        val maxTx = floor((px + reach) / TileCache.TILE_SIZE).toInt()
        val minTy = floor((py - reach) / TileCache.TILE_SIZE).toInt()
        val maxTy = floor((py + reach) / TileCache.TILE_SIZE).toInt()
        val dst = RectF()
        val src = Rect()
        for (tx in minTx..maxTx) {
            for (ty in minTy..maxTy) {
                dst.set((tx * TileCache.TILE_SIZE).toFloat(), (ty * TileCache.TILE_SIZE).toFloat(),
                    ((tx + 1) * TileCache.TILE_SIZE).toFloat(), ((ty + 1) * TileCache.TILE_SIZE).toFloat())
                val tile = tiles.get(style, zoom, tx, ty)
                if (tile != null) {
                    canvas.drawBitmap(tile, null, dst, tilePaint)
                } else {
                    // stretch a cached lower zoom tile while this one loads
                    for (levels in 1..3) {
                        val parent = tiles.getParent(style, zoom, tx, ty, levels) ?: continue
                        val n = 1 shl levels
                        val part = TileCache.TILE_SIZE / n
                        val max = 1 shl zoom
                        val wx = ((tx % max) + max) % max
                        val ox = (wx % n) * part
                        val oy = (ty % n) * part
                        src.set(ox, oy, ox + part, oy + part)
                        canvas.drawBitmap(parent, src, dst, tilePaint)
                        break
                    }
                }
            }
        }

        // the predicted road ahead
        if (state.path.size >= 2) {
            val path = Path()
            state.path.forEachIndexed { i, p ->
                val x = lonToX(p.lon, worldSize).toFloat()
                val y = latToY(p.lat, worldSize).toFloat()
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            stroke.strokeWidth = 14f
            stroke.color = if (options.dark) Color.argb(150, 66, 165, 245) else Color.argb(140, 25, 118, 210)
            canvas.drawPath(path, stroke)
        }
        canvas.restore()

        // cameras, drawn upright on top of the rotated map
        val alertIds = state.ahead.filter { it.distance <= options.alertMeters }.map { it.camera.id }.toSet()
        val pt = FloatArray(2)
        for (camera in state.nearby) {
            pt[0] = lonToX(camera.position.lon, worldSize).toFloat()
            pt[1] = latToY(camera.position.lat, worldSize).toFloat()
            matrix.mapPoints(pt)
            if (pt[0] < -40 || pt[1] < -40 || pt[0] > width + 40 || pt[1] > height + 40) continue
            drawCameraIcon(canvas, pt[0], pt[1], camera.type, if (camera.id in alertIds) 24f else 17f)
        }

        drawCar(canvas, carX, carY, if (options.headingUp) 0f else heading.toFloat())

        state.alert?.let { drawAlertBanner(canvas, it.camera.type, it.distance, it.camera.limit?.display(options.showMph), focusWidth, options) }
        drawSpeed(canvas, state, height, options)
        state.roadName?.takeIf { it.isNotBlank() }?.let { drawRoadName(canvas, it, carX, height, options) }
        drawAttribution(canvas, width, height, options)
        return bmp
    }

    private fun drawCar(canvas: Canvas, x: Float, y: Float, rotation: Float) {
        canvas.save()
        canvas.rotate(rotation, x, y)
        val arrow = Path().apply {
            moveTo(x, y - 24)
            lineTo(x + 17, y + 18)
            lineTo(x, y + 9)
            lineTo(x - 17, y + 18)
            close()
        }
        fill.color = Color.rgb(33, 150, 243)
        canvas.drawPath(arrow, fill)
        stroke.strokeWidth = 4f
        stroke.color = Color.WHITE
        canvas.drawPath(arrow, stroke)
        canvas.restore()
    }

    private fun cameraColor(type: CameraType) = when (type) {
        CameraType.SPEED -> Color.rgb(229, 57, 53)
        CameraType.AVERAGE -> Color.rgb(142, 36, 170)
        CameraType.RED_LIGHT -> Color.rgb(251, 140, 0)
        CameraType.MOBILE -> Color.rgb(216, 27, 96)
    }

    private fun drawCameraIcon(canvas: Canvas, x: Float, y: Float, type: CameraType, r: Float) {
        fill.color = cameraColor(type)
        canvas.drawCircle(x, y, r, fill)
        stroke.strokeWidth = 3f
        stroke.color = Color.WHITE
        canvas.drawCircle(x, y, r, stroke)
        // a little camera body and lens
        fill.color = Color.WHITE
        val w = r * 0.95f
        val h = r * 0.62f
        canvas.drawRoundRect(RectF(x - w / 2, y - h / 2 + 1, x + w / 2, y + h / 2 + 1), 3f, 3f, fill)
        canvas.drawRect(x - w * 0.18f, y - h / 2 - r * 0.16f, x + w * 0.18f, y - h / 2 + 1, fill)
        fill.color = cameraColor(type)
        canvas.drawCircle(x, y + 1, h * 0.32f, fill)
    }

    private fun drawAlertBanner(canvas: Canvas, type: CameraType, distance: Double, limit: Int?, focusWidth: Float, options: Options) {
        val left = 16f
        val top = 14f
        val right = focusWidth - 16f
        val bottom = top + 86f
        fill.color = cameraColor(type)
        canvas.drawRoundRect(RectF(left, top, right, bottom), 18f, 18f, fill)
        drawCameraIcon(canvas, left + 46, (top + bottom) / 2, type, 28f)
        text.color = Color.WHITE
        text.textSize = 32f
        text.textAlign = Paint.Align.LEFT
        canvas.drawText(type.label.uppercase(), left + 88, top + 38, text)
        small.color = Color.WHITE
        small.textSize = 28f
        small.textAlign = Paint.Align.LEFT
        val unit = if (options.showMph) "mph" else "km/h"
        val line = formatDistance(distance, options.showMph) + (limit?.let { "  ·  $it $unit limit" } ?: "")
        canvas.drawText(line, left + 88, top + 72, small)
    }

    private fun drawSpeed(canvas: Canvas, state: DriveState, height: Int, options: Options) {
        val speedMs = state.speed
        val shown = speedMs?.let { (if (options.showMph) it * 2.236936 else it * 3.6).roundToInt() }
        val limit = state.alert?.camera?.limit ?: state.limit
        val over = shown != null && limit != null && speedMs!! > limit.metersPerSecond + 0.45
        val boxW = 132f
        val boxH = 112f
        val left = 16f
        val top = height - 16f - boxH
        fill.color = when {
            over -> Color.rgb(229, 57, 53)
            options.dark -> Color.rgb(48, 52, 58)
            else -> Color.WHITE
        }
        val box = RectF(left, top, left + boxW, top + boxH)
        canvas.drawRoundRect(box, 22f, 22f, fill)
        stroke.strokeWidth = 3f
        stroke.color = if (options.dark || over) Color.argb(120, 255, 255, 255) else Color.argb(80, 0, 0, 0)
        canvas.drawRoundRect(box, 22f, 22f, stroke)
        val fg = if (over || options.dark) Color.WHITE else Color.rgb(33, 33, 33)
        centeredText(canvas, shown?.toString() ?: "--", box.centerX(), top + 70, 64f, fg)
        small.color = fg
        small.textSize = 22f
        small.textAlign = Paint.Align.CENTER
        canvas.drawText(if (options.showMph) "mph" else "km/h", box.centerX(), top + 98, small)

        // the speed limit roundel next to it
        if (limit != null) {
            val cx = left + boxW + 16 + 48
            val cy = top + boxH / 2
            fill.color = Color.WHITE
            canvas.drawCircle(cx, cy, 48f, fill)
            stroke.color = Color.rgb(211, 47, 47)
            stroke.strokeWidth = 10f
            canvas.drawCircle(cx, cy, 43f, stroke)
            centeredText(canvas, limit.display(options.showMph).toString(), cx, cy + 15, 42f, Color.BLACK)
        }

        // where the speed comes from
        state.fix?.let { fix ->
            small.textSize = 18f
            small.textAlign = Paint.Align.LEFT
            small.color = if (options.dark) Color.LTGRAY else Color.DKGRAY
            val acc = fix.accuracy?.let { " ±${it.roundToInt()} m" } ?: ""
            canvas.drawText(fix.source.label + acc, left + 4, top - 8, small)
        }
    }

    private fun drawRoadName(canvas: Canvas, name: String, x: Float, height: Int, options: Options) {
        small.textSize = 24f
        small.textAlign = Paint.Align.CENTER
        val w = small.measureText(name) + 28
        val left = (x - w / 2).coerceAtLeast(16f + 132 + 16 + 96 + 16)
        val rect = RectF(left, height - 58f, left + w, height - 22f)
        fill.color = if (options.dark) Color.argb(220, 48, 52, 58) else Color.argb(230, 255, 255, 255)
        canvas.drawRoundRect(rect, 18f, 18f, fill)
        small.color = if (options.dark) Color.WHITE else Color.rgb(33, 33, 33)
        canvas.drawText(name, rect.centerX(), rect.bottom - 10, small)
    }

    private fun drawAttribution(canvas: Canvas, width: Int, height: Int, options: Options) {
        small.textSize = 14f
        small.textAlign = Paint.Align.RIGHT
        small.color = if (options.dark) Color.argb(170, 255, 255, 255) else Color.argb(170, 0, 0, 0)
        canvas.drawText("© OpenStreetMap contributors © CARTO", width - 10f, height - 8f, small)
    }

    private fun centeredText(canvas: Canvas, s: String, x: Float, baseline: Float, size: Float, color: Int) {
        text.textSize = size
        text.color = color
        text.textAlign = Paint.Align.CENTER
        canvas.drawText(s, x, baseline, text)
    }

    companion object {
        fun lonToX(lon: Double, worldSize: Double) = (lon + 180.0) / 360.0 * worldSize
        fun latToY(lat: Double, worldSize: Double): Double {
            val r = lat * PI / 180
            return (1 - ln(tan(r) + 1 / kotlin.math.cos(r)) / PI) / 2 * worldSize
        }

        fun formatDistance(meters: Double, mph: Boolean): String {
            return if (mph) {
                val miles = meters / METERS_PER_MILE
                if (miles < 0.1) "${(meters * 1.0936133 / 10).roundToInt() * 10} yd" else "%.1f mi".format(miles)
            } else {
                if (meters < 1000) "${(meters / 10).roundToInt() * 10} m" else "%.1f km".format(meters / 1000)
            }
        }

        fun spokenDistance(meters: Double, mph: Boolean): String {
            return if (mph) {
                val miles = meters / METERS_PER_MILE
                if (miles < 0.15) "${(meters * 1.0936133 / 50).roundToInt() * 50} yards" else "%.1f miles".format(miles)
            } else {
                "${(meters / 50).roundToInt() * 50} meters"
            }
        }
    }
}
