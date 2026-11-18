package io.bimmergestalt.idriveconnectaddons.speedcams.cameras

import io.bimmergestalt.idriveconnectaddons.speedcams.geo.LatLon
import org.json.JSONObject

/**
 * Builds Overpass API queries and parses their JSON responses
 * into speed cameras and drivable roads
 */
object OverpassParser {
    const val ROAD_TYPES = "motorway|trunk|primary|secondary|tertiary|unclassified|residential|" +
            "motorway_link|trunk_link|primary_link|secondary_link|tertiary_link|living_street|road"

    /** Every speed and red light camera in a bounding box, with enforcement relations for average speed zones */
    fun cameraQuery(south: Double, west: Double, north: Double, east: Double): String {
        val bbox = "%.5f,%.5f,%.5f,%.5f".format(java.util.Locale.ROOT, south, west, north, east)
        return """
            [out:json][timeout:90];
            (
              node["highway"="speed_camera"]($bbox);
              relation["type"="enforcement"]($bbox);
            );
            out body;
            >;
            out skel qt;
        """.trimIndent()
    }

    /**
     * Drivable roads around a point with only the tags this app reads.
     * Stripping the other tags and node ids keeps a dense city patch to a few hundred KB gzipped;
     * junctions are found from shared coordinates instead.
     */
    fun roadQuery(lat: Double, lon: Double, radius: Int): String {
        val center = "%.6f,%.6f".format(java.util.Locale.ROOT, lat, lon)
        return """
            [out:json][timeout:25];
            way["highway"~"^($ROAD_TYPES)$"](around:$radius,$center);
            convert way ::id=id(),::geom=geom(),highway=t["highway"],maxspeed=t["maxspeed"],"maxspeed:type"=t["maxspeed:type"],oneway=t["oneway"],junction=t["junction"],ref=t["ref"],name=t["name"];
            out geom qt;
        """.trimIndent()
    }

    /** A stable id for a coordinate, so roads that share a point share a node */
    fun nodeKey(p: LatLon): Long {
        val lat = Math.round(p.lat * 1e7)
        val lon = Math.round(p.lon * 1e7)
        return (lat shl 32) xor (lon and 0xffffffffL)
    }

    fun parseCameras(json: String): List<Camera> {
        val elements = JSONObject(json).optJSONArray("elements") ?: return emptyList()
        val nodes = HashMap<Long, LatLon>()
        val cameras = LinkedHashMap<Long, Camera>()
        val relations = ArrayList<JSONObject>()

        for (i in 0 until elements.length()) {
            val el = elements.getJSONObject(i)
            when (el.optString("type")) {
                "node" -> {
                    val id = el.getLong("id")
                    val pos = LatLon(el.getDouble("lat"), el.getDouble("lon"))
                    nodes[id] = pos
                    val tags = el.optJSONObject("tags")
                    if (tags != null && tags.optString("highway") == "speed_camera") {
                        cameras[id] = Camera(id, pos, nodeType(tags), SpeedLimit.parse(tags.optString("maxspeed", null)))
                    }
                }
                "relation" -> relations.add(el)
            }
        }

        for (rel in relations) {
            val tags = rel.optJSONObject("tags") ?: continue
            val enforcement = tags.optString("enforcement")
            val type = when (enforcement) {
                "maxspeed" -> CameraType.SPEED
                "average_speed" -> CameraType.AVERAGE
                "traffic_signals" -> CameraType.RED_LIGHT
                else -> continue
            }
            val limit = SpeedLimit.parse(tags.optString("maxspeed", null))
            val members = rel.optJSONArray("members") ?: continue
            // the camera itself is the device, an average speed zone starts at "from"
            val wantedRoles = if (type == CameraType.AVERAGE) setOf("device", "from") else setOf("device")
            for (m in 0 until members.length()) {
                val member = members.getJSONObject(m)
                if (member.optString("type") != "node" || member.optString("role") !in wantedRoles) continue
                val ref = member.getLong("ref")
                val pos = nodes[ref] ?: continue
                val existing = cameras[ref]
                cameras[ref] = if (existing == null) {
                    Camera(ref, pos, type, limit)
                } else {
                    existing.copy(
                        type = if (existing.type == CameraType.SPEED) type else existing.type,
                        limit = existing.limit ?: limit
                    )
                }
            }
        }
        return cameras.values.toList()
    }

    private fun nodeType(tags: JSONObject): CameraType {
        val hints = listOf("enforcement", "speed_camera", "camera:type", "description")
            .joinToString(" ") { tags.optString(it) }.lowercase()
        return when {
            hints.contains("average") -> CameraType.AVERAGE
            hints.contains("traffic_signals") || hints.contains("red_light") -> CameraType.RED_LIGHT
            hints.contains("mobile") -> CameraType.MOBILE
            else -> CameraType.SPEED
        }
    }

    fun parseRoads(json: String): List<Road> {
        val elements = JSONObject(json).optJSONArray("elements") ?: return emptyList()
        val roads = ArrayList<Road>()
        for (i in 0 until elements.length()) {
            val el = elements.getJSONObject(i)
            if (el.optString("type") != "way") continue
            // GeoJSON LineString from the convert statement: [[lon, lat], ...]
            val coordinates = el.optJSONObject("geometry")?.optJSONArray("coordinates") ?: continue
            if (coordinates.length() < 2) continue
            val tags = el.optJSONObject("tags") ?: JSONObject()
            val highway = tags.optString("highway")
            val points = List(coordinates.length()) {
                val c = coordinates.getJSONArray(it)
                LatLon(c.getDouble(1), c.getDouble(0))
            }
            val ids = LongArray(points.size) { nodeKey(points[it]) }
            val oneway = when (tags.optString("oneway")) {
                "yes", "true", "1" -> 1
                "-1", "reverse" -> -1
                "no" -> 0
                else -> if (highway == "motorway" || highway == "motorway_link" ||
                        tags.optString("junction") == "roundabout") 1 else 0
            }
            val limit = SpeedLimit.parse(tags.optString("maxspeed").ifEmpty { null }, tags.optString("maxspeed:type").ifEmpty { null }, highway)
            val name = tags.optString("ref").ifEmpty { tags.optString("name") }
            roads.add(Road(el.getLong("id"), ids, points, highway, oneway, limit, name))
        }
        return roads
    }
}
