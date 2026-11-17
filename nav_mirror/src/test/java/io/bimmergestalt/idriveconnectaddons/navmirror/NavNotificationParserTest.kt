package io.bimmergestalt.idriveconnectaddons.navmirror

import io.bimmergestalt.idriveconnectaddons.navmirror.model.NavSource
import io.bimmergestalt.idriveconnectaddons.navmirror.nav.NavNotificationParser
import io.bimmergestalt.idriveconnectaddons.navmirror.nav.NotificationTexts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NavNotificationParserTest {
    @Test
    fun testGoogleMapsCustomLayout() {
        val texts = NotificationTexts(viewTexts = listOf(
            "nav_title" to "500 ft",
            "nav_description" to "Turn right onto Main St",
            "nav_time" to "12 min · 5.2 mi · 10:32 AM",
        ))
        val state = NavNotificationParser.parse(NavSource.GOOGLE_MAPS, texts)
        assertTrue(state.navigating)
        assertEquals("500 ft", state.distanceToNext)
        assertEquals("Turn right onto Main St", state.instruction)
        assertEquals("10:32 AM", state.eta)
        assertEquals("12 min", state.remainingTime)
        assertEquals("5.2 mi", state.remainingDistance)
        assertEquals("Turn right onto Main St · 500 ft", state.nextLine)
        assertEquals("10:32 AM · 12 min · 5.2 mi", state.etaLine)
    }

    @Test
    fun testGoogleMapsLockscreenLayout() {
        val texts = NotificationTexts(viewTexts = listOf(
            "lockscreen_directions" to "2.1 km · Keep left",
            "lockscreen_eta" to "1 hr 5 min · 85 km · 14:05",
        ))
        val state = NavNotificationParser.parse(NavSource.GOOGLE_MAPS, texts)
        assertTrue(state.navigating)
        assertEquals("2.1 km", state.distanceToNext)
        assertEquals("Keep left", state.instruction)
        assertEquals("14:05", state.eta)
        assertEquals("1 hr 5 min", state.remainingTime)
        assertEquals("85 km", state.remainingDistance)
    }

    @Test
    fun testGoogleMapsStandardExtras() {
        val texts = NotificationTexts(title = "500 ft", text = "Turn right onto Main St", subText = "10:32 AM · 12 min · 5.2 mi")
        val state = NavNotificationParser.parse(NavSource.GOOGLE_MAPS, texts)
        assertTrue(state.navigating)
        assertEquals("500 ft", state.distanceToNext)
        assertEquals("Turn right onto Main St", state.instruction)
        assertEquals("10:32 AM", state.eta)
        assertEquals("5.2 mi", state.remainingDistance)
    }

    @Test
    fun testGoogleMapsStandardTemplateFromPhone() {
        // as captured from a Samsung phone running a 2026 Google Maps build
        val texts = NotificationTexts(title = "0 ft", text = "towards Wolverton Park Rd", subText = "1 hr 20 min · 59 mi · 04:31 ETA", viewTexts = listOf(
            "app_name_text" to "Maps",
            "header_text_divider" to "•",
            "header_text" to "1 hr 20 min · 59 mi · 04:31 ETA",
            "time" to "03:11",
            "title" to "0 ft",
            "text" to "towards Wolverton Park Rd",
        ))
        val state = NavNotificationParser.parse(NavSource.GOOGLE_MAPS, texts)
        assertTrue(state.navigating)
        assertEquals("0 ft", state.distanceToNext)
        assertEquals("towards Wolverton Park Rd", state.instruction)
        assertEquals("04:31", state.eta)
        assertEquals("1 hr 20 min", state.remainingTime)
        assertEquals("59 mi", state.remainingDistance)

        // later updates leave the title empty
        val later = NavNotificationParser.parse(NavSource.GOOGLE_MAPS, texts.copy(title = "", viewTexts = texts.viewTexts.filter { it.first != "title" }))
        assertEquals("towards Wolverton Park Rd", later.instruction)
        assertNull(later.distanceToNext)
        assertEquals("04:31", later.eta)
    }

    @Test
    fun testGoogleMapsRerouting() {
        val texts = NotificationTexts(viewTexts = listOf("nav_description" to "Rerouting..."))
        val state = NavNotificationParser.parse(NavSource.GOOGLE_MAPS, texts)
        assertTrue(state.navigating)
        assertEquals("Rerouting...", state.instruction)
    }

    @Test
    fun testWazeNavigating() {
        val texts = NotificationTexts(title = "Driving to Home", text = "Turn left in 300 m", subText = "ETA 18:45 · 24 min · 12 km")
        val state = NavNotificationParser.parse(NavSource.WAZE, texts)
        assertTrue(state.navigating)
        assertEquals("Home", state.destinationHint)
        assertEquals("Turn left in 300 m", state.instruction)
        assertEquals("18:45", state.eta)
        assertEquals("24 min", state.remainingTime)
        assertEquals("12 km", state.remainingDistance)
    }

    @Test
    fun testWazeIdle() {
        val texts = NotificationTexts(title = "Waze", text = "Waze is running")
        val state = NavNotificationParser.parse(NavSource.WAZE, texts)
        assertFalse(state.navigating)
        assertNull(state.instruction)
        assertNull(state.destinationHint)
    }

    @Test
    fun testWazeRunningWithSystemHeader() {
        // as captured from a Samsung phone: the inflated layout includes the header timestamp and an action button
        val texts = NotificationTexts(title = "Waze", text = "Running. Tap to open.", viewTexts = listOf(
            "app_name_text" to "Waze",
            "header_text_divider" to "•",
            "time" to "03:04",
            "title" to "Waze",
            "text" to "Running. Tap to open.",
            "action0" to "Switch Off",
        ))
        val state = NavNotificationParser.parse(NavSource.WAZE, texts)
        assertFalse(state.navigating)
        assertNull(state.eta)
    }

    @Test
    fun testEmpty() {
        val state = NavNotificationParser.parse(NavSource.WAZE, NotificationTexts())
        assertFalse(state.navigating)
        val mapsState = NavNotificationParser.parse(NavSource.GOOGLE_MAPS, NotificationTexts())
        assertFalse(mapsState.navigating)
    }
}
