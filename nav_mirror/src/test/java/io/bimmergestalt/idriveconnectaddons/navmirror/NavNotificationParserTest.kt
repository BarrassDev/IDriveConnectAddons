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
        assertEquals("ETA 18:45", state.eta)
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
    fun testEmpty() {
        val state = NavNotificationParser.parse(NavSource.WAZE, NotificationTexts())
        assertFalse(state.navigating)
        val mapsState = NavNotificationParser.parse(NavSource.GOOGLE_MAPS, NotificationTexts())
        assertFalse(mapsState.navigating)
    }
}
