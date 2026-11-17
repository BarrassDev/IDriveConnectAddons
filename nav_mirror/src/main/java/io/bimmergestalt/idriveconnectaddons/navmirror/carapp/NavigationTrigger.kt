package io.bimmergestalt.idriveconnectaddons.navmirror.carapp

import io.bimmergestalt.idriveconnectaddons.navmirror.model.Destination
import io.bimmergestalt.idriveconnectkit.rhmi.RHMIApplication
import io.bimmergestalt.idriveconnectkit.rhmi.RHMIEvent

/**
 * Starts the car's native navigation through the app layout's `navigate` linkAction
 *
 * The linkAction points at a raDataModel of type Address,
 * which is set to the destination string and then the actionEvent is triggered.
 * This is the same mechanism that AAIdrive and the official BMW Connected apps use.
 */
class NavigationTrigger(app: RHMIApplication) {
    companion object {
        fun findEvent(app: RHMIApplication): RHMIEvent.ActionEvent? {
            return app.events.values.filterIsInstance<RHMIEvent.ActionEvent>().firstOrNull {
                it.getAction()?.asLinkAction()?.actionType == "navigate"
            }
        }
    }

    val event = findEvent(app)
        ?: throw IllegalArgumentException("This car app layout has no navigate linkAction")
    val model = event.getAction()?.asLinkAction()?.getLinkModel()?.asRaDataModel()
        ?: throw IllegalArgumentException("The navigate linkAction has no data model")

    fun triggerNavigation(destination: Destination) {
        model.value = destination.toRhmiNavigation()
        event.triggerEvent()
    }
}
