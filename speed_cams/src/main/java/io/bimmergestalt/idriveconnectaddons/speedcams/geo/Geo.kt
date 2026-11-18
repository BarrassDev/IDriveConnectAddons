package io.bimmergestalt.idriveconnectaddons.speedcams.geo

import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt

data class LatLon(val lat: Double, val lon: Double)

const val METERS_PER_MILE = 1609.344
const val EARTH_RADIUS = 6371008.8

object Geo {
    fun toRad(deg: Double) = deg * PI / 180.0
    fun toDeg(rad: Double) = rad * 180.0 / PI

    /** Great circle distance in meters */
    fun distance(a: LatLon, b: LatLon): Double = distance(a.lat, a.lon, b.lat, b.lon)

    fun distance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = toRad(lat2 - lat1)
        val dLon = toRad(lon2 - lon1)
        val h = sin(dLat / 2) * sin(dLat / 2) +
                cos(toRad(lat1)) * cos(toRad(lat2)) * sin(dLon / 2) * sin(dLon / 2)
        return 2 * EARTH_RADIUS * asin(min(1.0, sqrt(h)))
    }

    /** Initial bearing from a to b, degrees clockwise from north, 0..360 */
    fun bearing(a: LatLon, b: LatLon): Double {
        val lat1 = toRad(a.lat)
        val lat2 = toRad(b.lat)
        val dLon = toRad(b.lon - a.lon)
        val y = sin(dLon) * cos(lat2)
        val x = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(dLon)
        return normalize(toDeg(atan2(y, x)))
    }

    /** The point reached by travelling [meters] from [start] on [bearing] */
    fun destination(start: LatLon, bearing: Double, meters: Double): LatLon {
        val d = meters / EARTH_RADIUS
        val b = toRad(bearing)
        val lat1 = toRad(start.lat)
        val lon1 = toRad(start.lon)
        val lat2 = asin(sin(lat1) * cos(d) + cos(lat1) * sin(d) * cos(b))
        val lon2 = lon1 + atan2(sin(b) * sin(d) * cos(lat1), cos(d) - sin(lat1) * sin(lat2))
        return LatLon(toDeg(lat2), ((toDeg(lon2) + 540) % 360) - 180)
    }

    fun normalize(deg: Double): Double {
        val r = deg % 360.0
        return if (r < 0) r + 360.0 else r
    }

    /** Smallest signed difference b - a, in -180..180 */
    fun angleDiff(a: Double, b: Double): Double {
        var d = (b - a) % 360.0
        if (d > 180) d -= 360.0
        if (d < -180) d += 360.0
        return d
    }

    /**
     * Local flat projection around an origin, in meters east and north.
     * Accurate to well under a meter over the few km this app looks at.
     */
    class LocalProjection(val origin: LatLon) {
        private val mPerDegLat = 111_132.954 - 559.822 * cos(2 * toRad(origin.lat))
        private val mPerDegLon = 111_412.84 * cos(toRad(origin.lat))
        fun x(p: LatLon) = (p.lon - origin.lon) * mPerDegLon
        fun y(p: LatLon) = (p.lat - origin.lat) * mPerDegLat
        fun toLatLon(x: Double, y: Double) = LatLon(origin.lat + y / mPerDegLat, origin.lon + x / mPerDegLon)
    }

    /**
     * Distance from point p to segment a-b in a local projection, plus the fraction along the segment
     */
    data class SegmentHit(val distance: Double, val fraction: Double)

    fun pointToSegment(px: Double, py: Double, ax: Double, ay: Double, bx: Double, by: Double): SegmentHit {
        val dx = bx - ax
        val dy = by - ay
        val len2 = dx * dx + dy * dy
        val t = if (len2 <= 0.0) 0.0 else max(0.0, min(1.0, ((px - ax) * dx + (py - ay) * dy) / len2))
        val cx = ax + t * dx
        val cy = ay + t * dy
        return SegmentHit(sqrt((px - cx) * (px - cx) + (py - cy) * (py - cy)), t)
    }

    /** Bearing of a projected vector, degrees clockwise from north */
    fun vectorBearing(dx: Double, dy: Double): Double = normalize(toDeg(atan2(dx, dy)))

    /** Undirected angle between two bearings, 0..90, for matching roads that may be driven either way */
    fun axisDiff(a: Double, b: Double): Double {
        val d = abs(angleDiff(a, b))
        return if (d > 90) 180 - d else d
    }
}
