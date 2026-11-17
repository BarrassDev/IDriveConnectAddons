package io.bimmergestalt.idriveconnectaddons.navmirror.nav

import android.content.Context
import android.location.Address
import android.location.Geocoder
import io.bimmergestalt.idriveconnectaddons.navmirror.model.Destination
import java.io.IOException
import java.util.Locale

/** Resolves a free-text query to a Destination */
interface AddressSearcher {
    fun search(query: String): Destination?
}

/** Uses the phone's Geocoder, which must be called from a background thread */
class GeocoderSearcher(context: Context): AddressSearcher {
    companion object {
        private val NUM_MATCHER = Regex("^([0-9]+[A-Za-z]?)\\s+(.*)")

        fun toDestination(address: Address, fallbackLabel: String = ""): Destination {
            // Android usually puts the house number in subThoroughfare,
            // but some providers put "123 Main St" in thoroughfare
            val houseAddress = NUM_MATCHER.matchEntire(address.thoroughfare ?: "")
            val houseNumber = address.subThoroughfare ?: houseAddress?.groupValues?.getOrNull(1) ?: ""
            val street = houseAddress?.groupValues?.getOrNull(2) ?: address.thoroughfare ?: ""
            // featureName is often just the house number again, which is not a useful label
            val feature = address.featureName ?: ""
            val label = if (feature.isNotBlank() && feature != houseNumber && feature != address.thoroughfare) feature else fallbackLabel
            return Destination(
                latitude = address.latitude,
                longitude = address.longitude,
                label = label,
                street = street,
                houseNumber = houseNumber,
                postalCode = address.postalCode ?: "",
                locality = address.locality ?: address.subAdminArea ?: "",
                countryCode = address.countryCode ?: "",
            )
        }
    }

    private val geocoder = Geocoder(context, Locale.getDefault())

    override fun search(query: String): Destination? {
        if (!Geocoder.isPresent()) return null
        return try {
            @Suppress("DEPRECATION")
            geocoder.getFromLocationName(query, 1)?.firstOrNull()?.let { toDestination(it, query) }
        } catch (e: IOException) {
            null
        } catch (e: IllegalArgumentException) {
            null
        }
    }
}
