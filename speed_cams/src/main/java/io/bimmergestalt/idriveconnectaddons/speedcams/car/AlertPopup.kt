package io.bimmergestalt.idriveconnectaddons.speedcams.car

import android.util.Log
import io.bimmergestalt.idriveconnectaddons.speedcams.TAG
import io.bimmergestalt.idriveconnectkit.rhmi.RHMIComponent
import io.bimmergestalt.idriveconnectkit.rhmi.RHMIEvent
import io.bimmergestalt.idriveconnectkit.rhmi.RHMIState

/**
 * The SmartThings layout's two-line popup, used as a temporary camera warning
 * that appears over whatever is on the iDrive screen, including the native navigation
 */
class AlertPopup(val state: RHMIState) {
    companion object {
        fun fits(state: RHMIState): Boolean {
            return state is RHMIState.PopupState &&
                    state.componentsList.filterIsInstance<RHMIComponent.Label>().size >= 2 &&
                    state.componentsList.none { it is RHMIComponent.List }
        }
    }

    private val labels = state.componentsList.filterIsInstance<RHMIComponent.Label>()
    private val event = state.app.events.values.filterIsInstance<RHMIEvent.PopupEvent>().firstOrNull { it.getTarget() == state }

    fun show(title: String, line1: String, line2: String) {
        try {
            state.getTextModel()?.asRaDataModel()?.value = title
            labels[0].getModel()?.asRaDataModel()?.value = line1
            labels[1].getModel()?.asRaDataModel()?.value = line2
            labels.forEach { it.setVisible(true) }
            event?.triggerEvent(mapOf(0 to true))
        } catch (e: Exception) {
            Log.w(TAG, "Could not show the camera popup", e)
        }
    }

    fun hide() {
        try {
            event?.triggerEvent(mapOf(0 to false))
        } catch (e: Exception) {
            Log.w(TAG, "Could not hide the camera popup", e)
        }
    }
}
