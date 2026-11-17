package io.bimmergestalt.idriveconnectaddons.navmirror

import io.bimmergestalt.idriveconnectaddons.navmirror.carapp.NavigationTrigger
import io.bimmergestalt.idriveconnectaddons.navmirror.model.Destination
import io.bimmergestalt.idriveconnectkit.rhmi.RHMIAction
import io.bimmergestalt.idriveconnectkit.rhmi.RHMIApplicationConcrete
import io.bimmergestalt.idriveconnectkit.rhmi.RHMIEvent
import io.bimmergestalt.idriveconnectkit.rhmi.RHMIModelLive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NavigationTriggerTest {
    @Test
    fun testRhmiFormat() {
        // the same expected value as AAIdrive's NavTriggerTest
        val destination = Destination(1.0, 2.0, "Test Place")
        assertEquals(";;;;;;;11930464.705555556;23860929.411111113;Test Place", destination.toRhmiNavigation())
    }

    @Test
    fun testRhmiFormatAddress() {
        val destination = Destination(1.0, 2.0, "Semi;colon", "Main St", "123", "94000", "Town", "US")
        assertEquals(";;Main St;123;94000;Town;US;11930464.705555556;23860929.411111113;Semi colon", destination.toRhmiNavigation())
    }

    @Test
    fun testNavTrigger() {
        val app = RHMIApplicationConcrete()
        val navModel = RHMIModelLive.RaDataModel(app, 550)
        val navAction = RHMIAction.LinkAction(app, 563)
        navAction.linkModel = navModel.id
        navAction.actionType = "navigate"
        val navEvent = RHMIEvent.ActionEvent(app, 3)
        navEvent.action = navAction.id
        app.models[navModel.id] = navModel
        app.actions[navAction.id] = navAction
        app.events[navEvent.id] = navEvent

        val trigger = NavigationTrigger(app)
        trigger.triggerNavigation(Destination(1.0, 2.0, "Test Place"))
        assertEquals(";;;;;;;11930464.705555556;23860929.411111113;Test Place", navModel.value)
        assertTrue(app.triggeredEvents.containsKey(navEvent.id))
    }

    @Test
    fun testMissingNavigate() {
        val app = RHMIApplicationConcrete()
        assertNull(NavigationTrigger.findEvent(app))
        try {
            NavigationTrigger(app)
            assertTrue("Expected an exception", false)
        } catch (e: IllegalArgumentException) {
            // expected
        }
    }

    @Test
    fun testJsonRoundTrip() {
        val destination = Destination(37.5, -122.25, "Home", "Main St", "1", "94000", "Town", "US")
        assertEquals(destination, Destination.fromJson(destination.toJson()))
        assertNull(Destination.fromJson(null))
        assertNull(Destination.fromJson("garbage"))
    }
}
