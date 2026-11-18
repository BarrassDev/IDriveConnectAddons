package io.bimmergestalt.idriveconnectaddons.speedcams.cameras

import io.bimmergestalt.idriveconnectaddons.speedcams.geo.Geo
import io.bimmergestalt.idriveconnectaddons.speedcams.geo.LatLon
import kotlin.math.abs

/**
 * The roads around the car, used to find the current road (and its speed limit)
 * and to predict the road ahead by following it the way a driver most likely continues
 */
class RoadNetwork(val roads: List<Road>, val center: LatLon, val radius: Double) {
    companion object {
        const val MATCH_DISTANCE = 30.0
        const val MAX_HEADING_DIFF = 60.0
        const val MAX_TURN = 100.0
    }

    /** Where the car sits on a road. [direction] is +1 along the node order, -1 against it */
    data class Match(val road: Road, val segment: Int, val fraction: Double, val direction: Int, val distance: Double)

    private val nodeIndex = HashMap<Long, MutableList<Pair<Road, Int>>>().also { index ->
        roads.forEach { road ->
            road.nodeIds.forEachIndexed { i, id -> index.getOrPut(id) { ArrayList(2) }.add(road to i) }
        }
    }

    /** Whether [pos] is far enough inside the downloaded area to still trust the network */
    fun covers(pos: LatLon, margin: Double): Boolean = Geo.distance(center, pos) + margin < radius

    /**
     * Finds the road the car is on.
     * With no usable heading (stopped), the nearest road is returned with an arbitrary direction.
     */
    fun match(pos: LatLon, heading: Double?): Match? {
        val proj = Geo.LocalProjection(pos)
        var best: Match? = null
        var bestCost = Double.MAX_VALUE
        for (road in roads) {
            for (i in 0 until road.points.size - 1) {
                val a = road.points[i]
                val b = road.points[i + 1]
                val ax = proj.x(a); val ay = proj.y(a)
                val bx = proj.x(b); val by = proj.y(b)
                val hit = Geo.pointToSegment(0.0, 0.0, ax, ay, bx, by)
                if (hit.distance > MATCH_DISTANCE) continue
                val segBearing = Geo.vectorBearing(bx - ax, by - ay)
                val directions = when (road.oneway) { 1 -> listOf(1); -1 -> listOf(-1); else -> listOf(1, -1) }
                for (dir in directions) {
                    val travel = if (dir == 1) segBearing else Geo.normalize(segBearing + 180)
                    val diff = if (heading == null) 0.0 else abs(Geo.angleDiff(heading, travel))
                    if (diff > MAX_HEADING_DIFF) continue
                    val cost = hit.distance + diff * 0.4
                    if (cost < bestCost) {
                        bestCost = cost
                        best = Match(road, i, hit.fraction, dir, hit.distance)
                    }
                }
            }
        }
        return best
    }

    /**
     * Follows the road from [match] for about [meters], continuing onto the
     * straightest connected road at each junction, and returns the path as points
     */
    fun walkAhead(match: Match, meters: Double): List<LatLon> {
        val path = ArrayList<LatLon>()
        val start = interpolate(match.road.points[match.segment], match.road.points[match.segment + 1], match.fraction)
        path.add(start)
        var travelled = 0.0
        var road = match.road
        var dir = match.direction
        // the index of the next point to visit on this road
        var next = if (dir == 1) match.segment + 1 else match.segment
        val visited = HashSet<Long>()
        visited.add(road.id)
        var hops = 0

        while (travelled < meters && hops < 40) {
            // walk along the current road
            while (next in road.points.indices && travelled < meters) {
                val p = road.points[next]
                travelled += Geo.distance(path.last(), p)
                path.add(p)
                next += dir
            }
            if (travelled >= meters) break

            // reached the end of this road, pick the straightest continuation
            val endIndex = next - dir
            val endNode = road.nodeIds[endIndex]
            val incoming = if (path.size >= 2) Geo.bearing(path[path.size - 2], path.last()) else null
            var bestRoad: Road? = null
            var bestDir = 0
            var bestIndex = 0
            var bestCost = Double.MAX_VALUE
            for ((candidate, idx) in nodeIndex[endNode].orEmpty()) {
                if (candidate.id in visited) continue
                for (cDir in listOf(1, -1)) {
                    if (candidate.oneway == 1 && cDir == -1) continue
                    if (candidate.oneway == -1 && cDir == 1) continue
                    val nIdx = idx + cDir
                    if (nIdx !in candidate.points.indices) continue
                    val outgoing = Geo.bearing(candidate.points[idx], candidate.points[nIdx])
                    val turn = if (incoming == null) 0.0 else abs(Geo.angleDiff(incoming, outgoing))
                    if (turn > MAX_TURN) continue
                    val sameRoad = candidate.name.isNotEmpty() && candidate.name == road.name
                    val cost = turn - (if (sameRoad) 25.0 else 0.0)
                    if (cost < bestCost) {
                        bestCost = cost
                        bestRoad = candidate
                        bestDir = cDir
                        bestIndex = nIdx
                    }
                }
            }
            road = bestRoad ?: break
            dir = bestDir
            next = bestIndex
            visited.add(road.id)
            hops += 1
        }
        return path
    }

    private fun interpolate(a: LatLon, b: LatLon, t: Double) =
        LatLon(a.lat + (b.lat - a.lat) * t, a.lon + (b.lon - a.lon) * t)
}
