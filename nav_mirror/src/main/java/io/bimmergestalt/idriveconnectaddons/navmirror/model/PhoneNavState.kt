package io.bimmergestalt.idriveconnectaddons.navmirror.model

/** The phone navigation apps that are watched */
enum class NavSource(val packageName: String, val label: String) {
    GOOGLE_MAPS("com.google.android.apps.maps", "Google Maps"),
    WAZE("com.waze", "Waze");

    companion object {
        fun fromPackage(packageName: String?): NavSource? {
            return values().firstOrNull { it.packageName == packageName }
        }
    }
}

/**
 * What the phone's navigation app is currently doing, as parsed from its ongoing notification
 * All text fields are locale-formatted strings straight from the notification
 */
data class PhoneNavState(
    val source: NavSource,
    val navigating: Boolean,
    val instruction: String? = null,        // "Turn right onto Main St"
    val distanceToNext: String? = null,     // "500 ft"
    val eta: String? = null,                // "10:32 AM"
    val remainingTime: String? = null,      // "12 min"
    val remainingDistance: String? = null,  // "5.2 mi"
    val destinationHint: String? = null,    // a destination name, if the notification mentions one
    val rawTexts: List<String> = emptyList(),
    val timestamp: Long = System.currentTimeMillis(),
) {
    /** "Turn right onto Main St · 500 ft" */
    val nextLine: String
        get() = listOfNotNull(instruction, distanceToNext).joinToString(" · ")

    /** "10:32 AM · 12 min · 5.2 mi" */
    val etaLine: String
        get() = listOfNotNull(eta, remainingTime, remainingDistance).joinToString(" · ")

    companion object {
        fun inactive(source: NavSource) = PhoneNavState(source, false)
    }
}
