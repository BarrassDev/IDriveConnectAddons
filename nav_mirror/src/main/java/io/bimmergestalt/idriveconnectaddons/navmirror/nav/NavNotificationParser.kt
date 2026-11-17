package io.bimmergestalt.idriveconnectaddons.navmirror.nav

import io.bimmergestalt.idriveconnectaddons.navmirror.model.NavSource
import io.bimmergestalt.idriveconnectaddons.navmirror.model.PhoneNavState

/** The texts found in a notification, from its extras and from its inflated custom layout */
data class NotificationTexts(
    val title: String? = null,
    val text: String? = null,
    val subText: String? = null,
    val bigText: String? = null,
    val infoText: String? = null,
    val lines: List<String> = emptyList(),
    /** resource entry name -> text, in layout order, for custom notification layouts */
    val viewTexts: List<Pair<String, String>> = emptyList(),
) {
    companion object {
        /** views from the system notification template that carry no navigation data */
        val HEADER_VIEWS = setOf("time", "chronometer", "app_name_text", "header_text_divider", "header_text_secondary_divider", "expand_button", "profile_badge")
    }

    fun all(): List<String> {
        return (listOf(title, text, subText, bigText, infoText) + lines + viewTexts.filter { it.first !in HEADER_VIEWS }.map { it.second })
            .filterNotNull().map { it.trim() }.filter { it.isNotEmpty() && it != "•" && it != "·" }.distinct()
    }

    /** Finds the text of the first named view that has any text */
    fun view(vararg names: String): String? {
        return names.firstNotNullOfOrNull { name ->
            viewTexts.firstOrNull { it.first == name && it.second.isNotBlank() }?.second?.trim()
        }
    }
}

/**
 * Interprets the ongoing notification of a navigation app
 * Pure Kotlin, so that it can be unit tested with captured notification texts
 */
object NavNotificationParser {
    val TIME_REGEX = Regex("(?<![0-9])(?:[01]?[0-9]|2[0-3])[:.][0-5][0-9](?:\\s*[AaPp]\\.?[Mm]\\.?)?(?![0-9])")
    val DURATION_REGEX = Regex("(?<![0-9])\\d+\\s*(?:h|hr|hrs|hour|hours|min|mins|minute|minutes)\\b(?:\\s*\\d+\\s*(?:min|mins)\\b)?", RegexOption.IGNORE_CASE)
    val DISTANCE_REGEX = Regex("(?<![0-9])\\d+(?:[.,]\\d+)?\\s*(?:km|mi|m|ft|yd|miles|meters|metres)\\b", RegexOption.IGNORE_CASE)
    val SEPARATOR = Regex("\\s*[·•|]\\s*|\\s+[-–—]\\s+")
    val DESTINATION_HINT = Regex("^(?:(?:driving|navigating|heading|going|on the way|en route|route|directions)\\s+)?to\\s+(.+)$", RegexOption.IGNORE_CASE)
    val REROUTING = Regex("rerouting|recalculating|searching for gps|waiting for gps", RegexOption.IGNORE_CASE)

    fun parse(source: NavSource, texts: NotificationTexts): PhoneNavState {
        return when (source) {
            NavSource.GOOGLE_MAPS -> parseGoogleMaps(texts)
            NavSource.WAZE -> parseGeneric(source, texts)
        }
    }

    /**
     * Google Maps uses a custom notification layout
     * These view ids are the ones found in its turn-by-turn notification layouts
     */
    fun parseGoogleMaps(texts: NotificationTexts): PhoneNavState {
        val distanceToNext = texts.view("nav_title")
        val instruction = texts.view("nav_description")
        val summary = texts.view("nav_time", "header_text")
        val lockscreen = texts.view("lockscreen_directions", "lockscreen_oneliner")
        // current Google Maps builds use the standard template (title = distance, text = instruction, subText = summary);
        // header_text alone is just the standard template showing the subText
        if (distanceToNext == null && instruction == null && lockscreen == null) {
            return parseGeneric(NavSource.GOOGLE_MAPS, texts)
        }

        var next = instruction
        var distance = distanceToNext
        if (next == null && lockscreen != null) {
            // "500 ft · Turn right onto Main St"
            val parts = lockscreen.split(SEPARATOR).map { it.trim() }.filter { it.isNotEmpty() }
            distance = distance ?: parts.firstOrNull { DISTANCE_REGEX.containsMatchIn(it) }
            next = parts.firstOrNull { !DISTANCE_REGEX.containsMatchIn(it) } ?: lockscreen
        }
        val all = texts.all()
        if (next == null) {
            next = all.firstOrNull { REROUTING.containsMatchIn(it) }
        }
        val (eta, remainingTime, remainingDistance) = parseSummary(summary ?: texts.subText ?: texts.view("lockscreen_eta"))
        return PhoneNavState(
            NavSource.GOOGLE_MAPS, navigating = true,
            instruction = next, distanceToNext = distance,
            eta = eta, remainingTime = remainingTime, remainingDistance = remainingDistance,
            rawTexts = all,
        )
    }

    /** "12 min · 5.2 mi · 10:32 AM" -> (eta, remainingTime, remainingDistance) */
    fun parseSummary(summary: String?): Triple<String?, String?, String?> {
        summary ?: return Triple(null, null, null)
        val parts = summary.split(SEPARATOR).map { it.trim() }.filter { it.isNotEmpty() }
        val time = parts.firstOrNull { DURATION_REGEX.containsMatchIn(it) }
        val eta = parts.firstOrNull { it != time && TIME_REGEX.containsMatchIn(it) }
        val distance = parts.firstOrNull { it != time && it != eta && DISTANCE_REGEX.containsMatchIn(it) }
        return Triple(cleanEta(eta), time, distance)
    }

    /** "04:31 ETA" -> "04:31" */
    private fun cleanEta(eta: String?): String? {
        return eta?.replace(ETA_WORD, "")?.replace(Regex("\\s+"), " ")?.trim()?.takeIf { it.isNotEmpty() }
    }
    private val ETA_WORD = Regex("\\bETA\\b:?", RegexOption.IGNORE_CASE)

    /** Best-effort parsing for standard notification layouts, used for Waze */
    fun parseGeneric(source: NavSource, texts: NotificationTexts): PhoneNavState {
        val all = texts.all()
        if (all.isEmpty()) return PhoneNavState.inactive(source)
        val parts = all.flatMap { it.split(SEPARATOR) }.map { it.trim() }.filter { it.isNotEmpty() }

        val remainingTime = parts.firstOrNull { DURATION_REGEX.containsMatchIn(it) }
        val etaPart = parts.firstOrNull { it != remainingTime && TIME_REGEX.containsMatchIn(it) }
        val eta = cleanEta(etaPart)
        val distanceParts = parts.filter { it != remainingTime && it != etaPart && DISTANCE_REGEX.containsMatchIn(it) }
        val destinationHint = parts.firstNotNullOfOrNull { DESTINATION_HINT.matchEntire(it)?.groupValues?.get(1)?.trim() }
            ?.takeIf { it.isNotBlank() && !TIME_REGEX.containsMatchIn(it) && !DISTANCE_REGEX.containsMatchIn(it) }
        val rerouting = all.any { REROUTING.containsMatchIn(it) }

        // a title that is only a distance is the distance to the next turn, and the text is the instruction
        val titleIsDistance = texts.title?.let { DISTANCE_REGEX.matchEntire(it.trim()) != null } == true
        val distanceToNext = if (titleIsDistance) texts.title?.trim() else null
        val instruction = listOfNotNull(texts.text, texts.bigText, if (titleIsDistance) null else texts.title)
            .map { it.trim() }
            .firstOrNull { it.isNotBlank() && it != etaPart && it != remainingTime }
        val remainingDistance = distanceParts.firstOrNull { it != distanceToNext && it != instruction }

        val navigating = eta != null || remainingDistance != null || distanceToNext != null || destinationHint != null || rerouting
        return PhoneNavState(
            source, navigating,
            instruction = if (navigating) instruction else null,
            distanceToNext = distanceToNext,
            eta = eta, remainingTime = remainingTime, remainingDistance = remainingDistance,
            destinationHint = destinationHint,
            rawTexts = all,
        )
    }
}
