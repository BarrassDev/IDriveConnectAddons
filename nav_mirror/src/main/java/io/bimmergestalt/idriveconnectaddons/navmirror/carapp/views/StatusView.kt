package io.bimmergestalt.idriveconnectaddons.navmirror.carapp.views

import android.util.Log
import io.bimmergestalt.idriveconnectaddons.navmirror.L
import io.bimmergestalt.idriveconnectaddons.navmirror.TAG
import io.bimmergestalt.idriveconnectaddons.navmirror.carapp.RHMIActionAbort
import io.bimmergestalt.idriveconnectaddons.navmirror.model.Destination
import io.bimmergestalt.idriveconnectaddons.navmirror.model.PhoneNavState
import io.bimmergestalt.idriveconnectkit.rhmi.RHMIActionListCallback
import io.bimmergestalt.idriveconnectkit.rhmi.RHMIComponent
import io.bimmergestalt.idriveconnectkit.rhmi.RHMIModel
import io.bimmergestalt.idriveconnectkit.rhmi.RHMIProperty
import io.bimmergestalt.idriveconnectkit.rhmi.RHMIState
import io.bimmergestalt.idriveconnectkit.rhmi.VisibleCallback

/**
 * The single screen of the car app:
 * a list showing the phone's navigation status, the captured destination,
 * and a row to start the car navigation
 */
class StatusView(val state: RHMIState) {
    companion object {
        fun fits(state: RHMIState): Boolean {
            return state is RHMIState.PlainState &&
                state.componentsList.any { it is RHMIComponent.List }
        }
    }

    private val list = state.componentsList.filterIsInstance<RHMIComponent.List>().first()

    var visible = false
        private set
    /** whether the car layout supports starting navigation */
    var canSend = false
    /** a transient message shown at the bottom of the list */
    var feedback: String? = null
    var onSend: () -> Unit = {}

    private var sendRowIndex = -1
    private var phoneNav: PhoneNavState? = null
    private var destination: Destination? = null
    private var carNavigating = false

    fun initWidgets() {
        state.getTextModel()?.asRaDataModel()?.value = L.CAR_TITLE
        state.componentsList.forEach { it.setVisible(false) }
        list.setVisible(true)
        list.setEnabled(true)
        list.setSelectable(true)
        list.setProperty(RHMIProperty.PropertyId.LIST_COLUMNWIDTH, "*")

        list.getAction()?.asRAAction()?.rhmiActionCallback = RHMIActionListCallback { index ->
            if (index == sendRowIndex && canSend) {
                onSend()
            }
            // stay on this screen
            throw RHMIActionAbort()
        }
        state.visibleCallback = VisibleCallback { isVisible ->
            visible = isVisible
            if (isVisible) {
                redraw()
            }
        }
        redraw()
    }

    fun update(phoneNav: PhoneNavState?, destination: Destination?, carNavigating: Boolean) {
        this.phoneNav = phoneNav
        this.destination = destination
        this.carNavigating = carNavigating
        if (visible) {
            redraw()
        }
    }

    fun redraw() {
        val rows = ArrayList<String>()
        val nav = phoneNav
        if (nav != null && nav.navigating) {
            rows.add("${L.CAR_PHONE_NAV}: ${nav.source.label}")
            if (nav.nextLine.isNotBlank()) rows.add("${L.CAR_NEXT}: ${nav.nextLine}")
            if (nav.etaLine.isNotBlank()) rows.add("${L.CAR_ETA}: ${nav.etaLine}")
        } else {
            rows.add(L.CAR_NOT_NAVIGATING)
        }

        val target = destination
        if (target != null) {
            rows.add("${L.CAR_DESTINATION}: ${target.displayName}")
            if (canSend) {
                sendRowIndex = rows.size
                rows.add(L.CAR_SEND)
            } else {
                sendRowIndex = -1
                rows.add(L.CAR_NO_NAVIGATE)
            }
        } else {
            sendRowIndex = -1
            rows.add(L.CAR_NO_DESTINATION)
        }
        if (carNavigating) {
            rows.add(L.CAR_NAV_ACTIVE)
        }
        feedback?.also { rows.add(it) }

        try {
            list.getModel()?.value = RHMIModel.RaListModel.RHMIListAdapter(1, rows)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to update the car status list", e)
        }
    }
}
