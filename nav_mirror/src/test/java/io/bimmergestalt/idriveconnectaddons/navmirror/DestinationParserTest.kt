package io.bimmergestalt.idriveconnectaddons.navmirror

import io.bimmergestalt.idriveconnectaddons.navmirror.model.Destination
import io.bimmergestalt.idriveconnectaddons.navmirror.nav.AddressSearcher
import io.bimmergestalt.idriveconnectaddons.navmirror.nav.DestinationParser
import io.bimmergestalt.idriveconnectaddons.navmirror.nav.UrlRedirector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class DestinationParserTest {
    class FakeSearcher: AddressSearcher {
        val searches = ArrayList<String>()
        override fun search(query: String): Destination? {
            searches.add(query)
            return when (query) {
                "Taronga Zoo, Sydney" -> Destination(-33.8433, 151.2411, "Taronga Zoo")
                "1600 Amphitheatre Parkway, Mountain View" -> Destination(37.4220, -122.0841, "", "Amphitheatre Parkway", "1600", "94043", "Mountain View", "US")
                "San Francisco" -> Destination(37.7749, -122.4194, "San Francisco")
                "Some Office Ltd" -> Destination(51.5074, -0.1278, "", "Some Street", "1", "EC1", "London", "GB")
                else -> null
            }
        }
    }

    class FakeRedirector(val redirects: Map<String, String>): UrlRedirector() {
        override fun tryRedirect(url: String): String? = redirects[url]
    }

    val searcher = FakeSearcher()
    val redirector = FakeRedirector(mapOf(
        "https://maps.app.goo.gl/abc123" to "https://www.google.com/maps/place/Golden+Gate+Bridge/@37.8199,-122.4783,17z/data=!3m1!4b1!4m6!3m5!1s0x808586deffffffc3:0xcded139783705509!8m2!3d37.8199286!4d-122.4782551!16zL20vMDM1cDM?g_ep=abc",
        "https://waze.com/ul/hABCDEF" to "https://www.waze.com/live-map/directions?to=ll.37.8199%2C-122.4783",
    ))
    val parser = DestinationParser(searcher, redirector)

    @Test
    fun testGeoLatLng() {
        val result = parser.parse("geo:37.7749,-122.4194")
        assertNotNull(result)
        assertEquals(37.7749, result!!.latitude, 0.00001)
        assertEquals(-122.4194, result.longitude, 0.00001)
    }

    @Test
    fun testGeoQueryWithLabel() {
        val result = parser.parse("geo:0,0?q=37.7749,-122.4194(Union Square)")
        assertNotNull(result)
        assertEquals(37.7749, result!!.latitude, 0.00001)
        assertEquals("Union Square", result.label)
    }

    @Test
    fun testGeoQueryText() {
        val result = parser.parse("geo:0,0?q=1600+Amphitheatre+Parkway,+Mountain+View")
        assertNotNull(result)
        assertEquals("Mountain View", result!!.locality)
        assertEquals(";;Amphitheatre Parkway;1600;94043;Mountain View;US;${(37.4220 / 360 * Int.MAX_VALUE * 2).toBigDecimal()};${(-122.0841 / 360 * Int.MAX_VALUE * 2).toBigDecimal()};", result.toRhmiNavigation())
    }

    @Test
    fun testGeoZeroIsInvalid() {
        assertNull(parser.parse("geo:0,0"))
    }

    @Test
    fun testGoogleNavigation() {
        val latlng = parser.parse("google.navigation:q=37.7749,-122.4194")
        assertEquals(37.7749, latlng!!.latitude, 0.00001)
        val text = parser.parse("google.navigation:q=Taronga+Zoo,+Sydney")
        assertEquals("Taronga Zoo", text!!.label)
    }

    @Test
    fun testGooglePlaceDataBlob() {
        val result = parser.parse("https://www.google.com/maps/place/Golden+Gate+Bridge/@37.8,-122.4,17z/data=!3m1!4b1!4m6!3m5!1s0x8085:0xcded!8m2!3d37.8199286!4d-122.4782551")
        assertNotNull(result)
        assertEquals(37.8199286, result!!.latitude, 0.0000001)
        assertEquals(-122.4782551, result.longitude, 0.0000001)
        assertEquals("Golden Gate Bridge", result.label)
    }

    @Test
    fun testGooglePlaceCenter() {
        val result = parser.parse("http://maps.google.com/maps/place/Some+Cafe/@47.5951518,-122.3316393,15z")
        assertEquals(47.5951518, result!!.latitude, 0.0000001)
        assertEquals("Some Cafe", result.label)
    }

    @Test
    fun testGoogleDirections() {
        val result = parser.parse("https://www.google.com/maps/dir/Current+Location/47.5951518,-122.3316393/@47.59,-122.33,12z")
        assertEquals(47.5951518, result!!.latitude, 0.0000001)
        assertEquals(-122.3316393, result.longitude, 0.0000001)
    }

    @Test
    fun testGoogleDirectionsApi() {
        val result = parser.parse("https://www.google.com/maps/dir/?api=1&destination=Taronga+Zoo%2C+Sydney")
        assertEquals("Taronga Zoo", result!!.label)
        val latlng = parser.parse("https://www.google.com/maps/dir/?api=1&destination=47.5951518%2C-122.3316393")
        assertEquals(47.5951518, latlng!!.latitude, 0.0000001)
    }

    @Test
    fun testGoogleSearch() {
        val result = parser.parse("https://www.google.com/maps/search/?api=1&query=47.5951518%2C-122.3316393")
        assertEquals(47.5951518, result!!.latitude, 0.0000001)
        val path = parser.parse("https://www.google.com/maps/search/47.5951518,-122.3316393")
        assertEquals(-122.3316393, path!!.longitude, 0.0000001)
    }

    @Test
    fun testGoogleQ() {
        val result = parser.parse("https://maps.google.com/?q=47.5951518,-122.3316393")
        assertEquals(47.5951518, result!!.latitude, 0.0000001)
        val loc = parser.parse("https://maps.google.com/maps?q=loc:47.5951518,-122.3316393")
        assertEquals(47.5951518, loc!!.latitude, 0.0000001)
    }

    @Test
    fun testGoogleShortLink() {
        val result = parser.parse("https://maps.app.goo.gl/abc123")
        assertNotNull(result)
        assertEquals(37.8199286, result!!.latitude, 0.0000001)
        assertEquals("Golden Gate Bridge", result.label)
    }

    @Test
    fun testSharedTextWithLabel() {
        // Google Maps share sheet text
        val result = parser.parse("Golden Gate Bridge\nhttps://maps.google.com/?q=37.8199,-122.4783")
        assertEquals(37.8199, result!!.latitude, 0.00001)
        assertEquals("Golden Gate Bridge", result.label)
    }

    @Test
    fun testWazeUl() {
        val result = parser.parse("https://waze.com/ul?ll=-33.8569%2C151.2152&navigate=yes&q=Opera%20House")
        assertEquals(-33.8569, result!!.latitude, 0.00001)
        assertEquals(151.2152, result.longitude, 0.00001)
        assertEquals("Opera House", result.label)
    }

    @Test
    fun testWazeScheme() {
        val result = parser.parse("waze://?ll=45.6906304,-120.810983&navigate=yes")
        assertEquals(45.6906304, result!!.latitude, 0.0000001)
    }

    @Test
    fun testWazeShortLink() {
        val result = parser.parse("Drive with me: https://waze.com/ul/hABCDEF")
        assertEquals(37.8199, result!!.latitude, 0.00001)
        assertEquals(-122.4783, result.longitude, 0.00001)
    }

    @Test
    fun testWazeTextQuery() {
        val result = parser.parse("https://www.waze.com/ul?q=Taronga%20Zoo%2C%20Sydney&navigate=yes")
        assertEquals("Taronga Zoo", result!!.label)
    }

    @Test
    fun testWazeSharedDriveText() {
        // a shared drive link has no location, only the text names the destination
        val text = "I'll arrive at 04:04 at Some Office Ltd (ABC). Follow my drive on Waze: https://www.waze.com/ul?a=sd3&token=JJ5eHnPwEXRvwE4okPuB&min_version=5.24"
        val result = parser.parse(text)
        assertNotNull(result)
        assertEquals(51.5074, result!!.latitude, 0.00001)
        assertEquals("Some Office Ltd (ABC)", result.label)
        assertEquals("London", result.locality)
        // tried the full name first, then without the parenthetical
        assertEquals(listOf("Some Office Ltd (ABC)", "Some Office Ltd"), searcher.searches.takeLast(2))
    }

    @Test
    fun testLinkWithoutLocationAndUnknownText() {
        assertNull(parser.parse("Nothing useful here https://www.waze.com/ul?a=sd3&token=abc"))
    }

    @Test
    fun testPlusCode() {
        val result = parser.parse("https://plus.codes/849VCWC8+R9")
        assertNotNull(result)
        // the Googleplex
        assertEquals(37.4221, result!!.latitude, 0.001)
        assertEquals(-122.0841, result.longitude, 0.001)
    }

    @Test
    fun testShortPlusCodeInGoogleDirections() {
        val result = parser.parse("https://www.google.com/maps/dir//QJQ5+XX,San%20Francisco")
        assertNotNull(result)
        assertEquals(37.79, result!!.latitude, 0.01)
        assertEquals(-122.40, result.longitude, 0.01)
    }

    @Test
    fun testPlainAddress() {
        val result = parser.parse("Taronga Zoo, Sydney")
        assertEquals("Taronga Zoo", result!!.label)
    }

    @Test
    fun testUnknown() {
        assertNull(parser.parse("https://example.com/nothing"))
        assertNull(parser.parse(""))
        assertNull(parser.parse(null))
    }
}
