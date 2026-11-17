package io.bimmergestalt.idriveconnectaddons.navmirror.nav

import com.google.openlocationcode.OpenLocationCode
import io.bimmergestalt.idriveconnectaddons.navmirror.model.Destination
import java.net.URI
import java.net.URISyntaxException
import java.net.URLDecoder

/**
 * Turns shared text and map links into a Destination
 *
 * Supports:
 *   geo:lat,lng  geo:0,0?q=lat,lng(Label)  geo:0,0?q=Some+Address
 *   google.navigation:q=...
 *   https://maps.app.goo.gl/... (resolved through redirects)
 *   https://www.google.com/maps/place/Name/@lat,lng,...  /maps/dir/Origin/Destination  /maps/search/...  ?q= ?query= ?daddr= ?destination=
 *   https://waze.com/ul?ll=lat,lng&q=Name  waze://?ll=lat,lng  https://www.waze.com/live-map/directions/...?to=ll.lat,lng
 *   https://plus.codes/849VCWC8+R9  and plus codes inside Google links
 *   a plain address, which is geocoded
 *
 * Must be called from a background thread, because of the network and geocoder lookups
 */
class DestinationParser(private val addressSearcher: AddressSearcher, private val redirector: UrlRedirector) {
    companion object {
        val URL_MATCHER = Regex("(?:https?://|geo:|google\\.navigation:|waze://)[^\\s]+")
        private const val NUM = "[-+]?[0-9]{1,3}(?:\\.[0-9]+)?"
        val LATLNG_MATCHER = Regex("^\\s*($NUM)\\s*,\\s*($NUM)\\s*$")
        val LATLNG_PREFIX_MATCHER = Regex("^\\s*($NUM)\\s*,\\s*($NUM)")
        val GEO_Q_MATCHER = Regex("^\\s*($NUM)\\s*,\\s*($NUM)\\s*(?:,[-0-9.]+)?\\s*(?:\\((.*)\\))?\\s*$")
        val PLUSCODE_MATCHER = Regex("^([2-9CFGHJMPQRVWX]{2,8}\\+[2-9CFGHJMPQRVWX]{2,})(?:[, ]+(.*))?$")
        val GOOGLE_DATA_LATLNG = Regex("!3d($NUM)!4d($NUM)")
        val GOOGLE_AT_LATLNG = Regex("@($NUM),($NUM)")
        val GOOGLE_PLACE_PATH = Regex("/maps/place/([^/@]+)")
        val GOOGLE_SEARCH_PATH = Regex("/maps/search/([^/@]+)")
        val GOOGLE_DIR_PATH = Regex("/maps/dir/(.*)")
        val SHORT_HOSTS = setOf("goo.gl", "maps.app.goo.gl", "g.co")
        val WAZE_DRIVE_MATCHER = Regex("arrive at \\d{1,2}[:.]\\d{2}(?:\\s*[AaPp]\\.?[Mm]\\.?)? at (.+?)\\.?\\s*(?:Follow my drive.*)?$", RegexOption.IGNORE_CASE)
        const val MAX_REDIRECTS = 3

        /** URL-decodes, turning + into a space */
        fun decode(value: String): String = try {
            URLDecoder.decode(value, "UTF-8")
        } catch (e: Exception) {
            value
        }

        /** URL-decodes, keeping + as a literal character (for plus codes) */
        fun decodeKeepPlus(value: String): String = decode(value.replace("+", "%2B"))

        fun parseQuery(query: String?): Map<String, String> {
            query ?: return emptyMap()
            return query.split('&').filter { it.isNotBlank() }.associate { pair ->
                decode(pair.substringBefore('=')) to decode(pair.substringAfter('=', ""))
            }
        }

        fun parseUri(url: String): URI? {
            return try {
                URI(url)
            } catch (e: URISyntaxException) {
                try {
                    URI(url.replace(" ", "%20").replace("|", "%7C"))
                } catch (e: URISyntaxException) {
                    null
                }
            }
        }

        fun latLng(text: String?, label: String = ""): Destination? {
            text ?: return null
            val m = LATLNG_MATCHER.matchEntire(text) ?: return null
            return toDestination(m.groupValues[1], m.groupValues[2], label)
        }

        fun toDestination(lat: String, lng: String, label: String): Destination? {
            val latitude = lat.toDoubleOrNull() ?: return null
            val longitude = lng.toDoubleOrNull() ?: return null
            if (latitude == 0.0 && longitude == 0.0) return null
            if (latitude < -90 || latitude > 90 || longitude < -180 || longitude > 180) return null
            return Destination(latitude, longitude, label.trim())
        }
    }

    /** Parses shared text: a bare link, a link embedded in text, or a plain address */
    fun parse(text: String?): Destination? {
        text ?: return null
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return null
        val urlMatch = URL_MATCHER.find(trimmed)
        if (urlMatch != null) {
            // geo: and google.navigation: links can carry unencoded spaces in their label or query,
            // so when the whole text is such a link, keep it intact
            val matched = urlMatch.value
            val wholeTextIsUrl = urlMatch.range.first == 0 && !trimmed.contains('\n') &&
                (matched.startsWith("geo:") || matched.startsWith("google.navigation:"))
            val url = if (wholeTextIsUrl) trimmed else matched
            val result = parseUrl(url)
            if (result != null) {
                // Google Maps shares "Place Name\nhttps://maps.app.goo.gl/..", keep the name as a label
                val textLabel = trimmed.substring(0, urlMatch.range.first).lines()
                    .map { it.trim() }.firstOrNull { it.isNotBlank() }
                return if (result.label.isBlank() && textLabel != null) result.copy(label = textLabel) else result
            }
            // the link carried no location (a Waze shared-drive token, for example),
            // so fall back to the text around it
            return parseSurroundingText(trimmed.replace(URL_MATCHER, " "))
        }
        latLng(trimmed)?.let { return it }
        parsePlusCode(trimmed)?.let { return it }
        if (trimmed.length <= 200) {
            return addressSearcher.search(trimmed)
        }
        return null
    }

    /**
     * Geocodes a place name found in shared text whose link had no location
     * Waze shared drives look like: "I'll arrive at 04:04 at Some Place (ABC). Follow my drive on Waze: https://..."
     */
    private fun parseSurroundingText(text: String): Destination? {
        val flat = text.replace(Regex("\\s+"), " ").trim()
        if (flat.isEmpty()) return null
        val candidates = ArrayList<String>()
        val wazeName = WAZE_DRIVE_MATCHER.find(flat)?.groupValues?.get(1)?.trim()
        if (!wazeName.isNullOrBlank()) {
            candidates.add(wazeName)
            // "Some Place (ABC)" -> "Some Place"
            val withoutParens = wazeName.replace(Regex("\\s*\\([^)]*\\)"), "").trim()
            if (withoutParens.isNotBlank() && withoutParens != wazeName) candidates.add(withoutParens)
        } else {
            val firstLine = text.lines().map { it.trim() }.firstOrNull { it.isNotBlank() }
            if (firstLine != null && firstLine.length <= 200) candidates.add(firstLine)
        }
        val label = candidates.firstOrNull() ?: return null
        for (candidate in candidates.distinct()) {
            addressSearcher.search(candidate)?.let { return it.copy(label = label) }
        }
        return null
    }

    fun parseUrl(url: String, depth: Int = 0): Destination? {
        return when {
            url.startsWith("geo:") -> parseGeo(url)
            url.startsWith("google.navigation:") -> parseGoogleNavigation(url)
            url.startsWith("waze:") -> parseWazeParams(parseQuery(url.substringAfter('?', "")), null)
            url.startsWith("http") -> parseHttp(url, depth)
            else -> null
        }
    }

    private fun parseGeo(url: String): Destination? {
        // geo:37.7749,-122.4194 | geo:0,0?q=37.7749,-122.4194(Label) | geo:0,0?q=1600+Amphitheatre+Parkway
        val data = url.removePrefix("geo:")
        val location = data.substringBefore('?')
        val params = parseQuery(data.substringAfter('?', ""))
        val q = params["q"]
        if (q != null) {
            val m = GEO_Q_MATCHER.matchEntire(q)
            if (m != null) {
                toDestination(m.groupValues[1], m.groupValues[2], m.groupValues[3])?.let { return it }
            } else {
                addressSearcher.search(q)?.let { return it }
            }
        }
        LATLNG_PREFIX_MATCHER.find(location)?.let { m ->
            toDestination(m.groupValues[1], m.groupValues[2], "")?.let { return it }
        }
        return null
    }

    private fun parseGoogleNavigation(url: String): Destination? {
        // google.navigation:q=37.7749,-122.4194 | google.navigation:q=Taronga+Zoo,+Sydney
        val params = parseQuery(url.removePrefix("google.navigation:").removePrefix("?"))
        val q = params["q"] ?: params["ll"] ?: return null
        return latLng(q) ?: addressSearcher.search(q)
    }

    private fun parseHttp(url: String, depth: Int): Destination? {
        val uri = parseUri(url) ?: return null
        val host = uri.host?.lowercase() ?: return null
        val path = uri.rawPath ?: ""
        if (host in SHORT_HOSTS || (host.endsWith("waze.com") && path.startsWith("/ul/h"))) {
            if (depth >= MAX_REDIRECTS) return null
            val redirect = redirector.tryRedirect(url) ?: return null
            return parseUrl(redirect, depth + 1)
        }
        return when {
            host.endsWith("waze.com") -> parseWaze(uri)
            host == "plus.codes" -> parsePlusCode(decodeKeepPlus(path.trimStart('/')))
            host.contains("google.") -> parseGoogle(uri)
            else -> null
        }
    }

    private fun parseWaze(uri: URI): Destination? {
        val params = parseQuery(uri.rawQuery)
        // https://www.waze.com/live-map/directions/golden-gate-bridge?to=ll.37.8199,-122.4783
        val pathLabel = uri.rawPath?.trim('/')?.split('/')?.lastOrNull()
            ?.takeIf { it.isNotBlank() && it != "ul" && it != "directions" && it != "live-map" }
            ?.let { decode(it).replace('-', ' ') }
        return parseWazeParams(params, pathLabel)
    }

    private fun parseWazeParams(params: Map<String, String>, pathLabel: String?): Destination? {
        val label = params["q"] ?: pathLabel ?: ""
        val coords = params["ll"] ?: params["latlng"]
            ?: params["to"]?.takeIf { it.startsWith("ll.") }?.removePrefix("ll.")
        latLng(coords, label)?.let { return it }
        val q = params["q"] ?: return null
        return addressSearcher.search(q)
    }

    private fun parseGoogle(uri: URI): Destination? {
        val params = parseQuery(uri.rawQuery)
        val rawPath = uri.rawPath ?: ""
        val path = decode(rawPath)

        // 1. a place link carries the precise place coordinates in its data blob
        // /maps/place/Golden+Gate+Bridge/@37.8,-122.4,15z/data=!3m1!4b1!4m5!3m4!1s0x..!8m2!3d37.8199!4d-122.4783
        val placeName = GOOGLE_PLACE_PATH.find(path)?.groupValues?.get(1)?.trim()
        if (placeName != null) {
            GOOGLE_DATA_LATLNG.find(rawPath)?.let { m ->
                toDestination(m.groupValues[1], m.groupValues[2], placeName)?.let { return it }
            }
        }

        // 2. query parameters: ?api=1&destination=.. | ?daddr=.. | ?query=.. | ?q=..
        val queryValue = params["destination"] ?: params["daddr"] ?: params["query"] ?: params["q"]
        if (queryValue != null) {
            val cleaned = queryValue.removePrefix("loc:").trim()
            latLng(cleaned)?.let { return it }
            parsePlusCode(cleaned)?.let { return it }
            addressSearcher.search(cleaned)?.let { return it }
        }

        // 3. directions: the destination is the last path segment before any @center or data= blob
        // /maps/dir/Current+Location/47.5951518,-122.3316393  /maps/dir//QJQ5+XX,San%20Francisco
        val dirSegments = GOOGLE_DIR_PATH.find(rawPath)?.groupValues?.get(1)?.split('/')
            ?.filter { it.isNotBlank() && !it.startsWith("@") && !it.startsWith("data=") }
        val dirDest = dirSegments?.lastOrNull()
        if (dirDest != null) {
            latLng(decode(dirDest))?.let { return it }
            parsePlusCode(decodeKeepPlus(dirDest))?.let { return it }
            addressSearcher.search(decode(dirDest))?.let { return it }
        }

        // 4. /maps/place/Name/@lat,lng or /maps/search/lat,lng or /maps/search/query
        if (placeName != null) {
            latLng(placeName)?.let { return it }
            GOOGLE_AT_LATLNG.find(rawPath)?.let { m ->
                toDestination(m.groupValues[1], m.groupValues[2], placeName)?.let { return it }
            }
            addressSearcher.search(placeName)?.let { return it }
        }
        val searchQuery = GOOGLE_SEARCH_PATH.find(path)?.groupValues?.get(1)?.trim()
        if (searchQuery != null) {
            latLng(searchQuery)?.let { return it }
            parsePlusCode(decodeKeepPlus(GOOGLE_SEARCH_PATH.find(rawPath)?.groupValues?.get(1) ?: ""))?.let { return it }
            addressSearcher.search(searchQuery)?.let { return it }
        }

        // 5. the map center, as a last resort
        latLng(params["ll"])?.let { return it }
        GOOGLE_AT_LATLNG.find(rawPath)?.let { m ->
            toDestination(m.groupValues[1], m.groupValues[2], "")?.let { return it }
        }
        return null
    }

    private fun parsePlusCode(text: String?): Destination? {
        text ?: return null
        val m = PLUSCODE_MATCHER.matchEntire(text.trim()) ?: return null
        val code = m.groupValues[1]
        val reference = m.groupValues.getOrNull(2)?.trim() ?: ""
        if (!OpenLocationCode.isValidCode(code)) return null
        var olc = try {
            OpenLocationCode(code)
        } catch (e: IllegalArgumentException) {
            return null
        }
        if (olc.isShort) {
            if (reference.isBlank()) return null
            val referencePoint = addressSearcher.search(reference) ?: return null
            olc = olc.recover(referencePoint.latitude, referencePoint.longitude)
        }
        val area = olc.decode()
        return Destination(area.centerLatitude, area.centerLongitude, reference)
    }
}
