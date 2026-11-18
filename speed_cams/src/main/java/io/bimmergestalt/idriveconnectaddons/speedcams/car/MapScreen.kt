package io.bimmergestalt.idriveconnectaddons.speedcams.car

import io.bimmergestalt.idriveconnectaddons.speedcams.L
import io.bimmergestalt.idriveconnectkit.RHMIDimensions
import io.bimmergestalt.idriveconnectkit.rhmi.FocusCallback
import io.bimmergestalt.idriveconnectkit.rhmi.RHMIComponent
import io.bimmergestalt.idriveconnectkit.rhmi.RHMIModel
import io.bimmergestalt.idriveconnectkit.rhmi.RHMIProperty
import io.bimmergestalt.idriveconnectkit.rhmi.RHMIState
import io.bimmergestalt.idriveconnectkit.rhmi.VisibleCallback

/**
 * The full-screen map page: one image component stretched over the whole content area,
 * pulled up and left over the layout's padding, the same way screen_mirror does it
 */
class MapScreen(val state: RHMIState, private val dims: RHMIDimensions, private val onVisibilityChanged: () -> Unit) {
    companion object {
        fun fits(state: RHMIState): Boolean {
            return state is RHMIState.PlainState &&
                    state.componentsList.filterIsInstance<RHMIComponent.Image>().any { it.getModel() is RHMIModel.RaImageModel } &&
                    state.componentsList.filterIsInstance<RHMIComponent.List>().isNotEmpty()
        }
    }

    private val image = state.componentsList.filterIsInstance<RHMIComponent.Image>().first { it.getModel() is RHMIModel.RaImageModel }
    private val imageModel = image.getModel()!!.asRaImageModel()!!

    private var focused = false
    private var shown = false
    val visible: Boolean get() = focused || shown

    fun initWidgets() {
        state.setProperty(RHMIProperty.PropertyId.HMISTATE_TABLETYPE, 3)
        state.setProperty(RHMIProperty.PropertyId.HMISTATE_TABLELAYOUT, "1,0,7")
        state.getTextModel()?.asRaDataModel()?.value = L.CAR_TITLE
        state.componentsList.forEach { it.setVisible(false) }
        image.setProperty(RHMIProperty.PropertyId.WIDTH, dims.visibleWidth)
        image.setProperty(RHMIProperty.PropertyId.HEIGHT, dims.visibleHeight)
        image.setProperty(RHMIProperty.PropertyId.POSITION_X, -dims.paddingLeft)
        image.setProperty(RHMIProperty.PropertyId.POSITION_Y, -dims.paddingTop)
        image.setVisible(true)

        state.focusCallback = FocusCallback { isFocused ->
            focused = isFocused
            onVisibilityChanged()
        }
        state.visibleCallback = VisibleCallback { isVisible ->
            shown = isVisible
            onVisibilityChanged()
        }
    }

    fun showFrame(jpeg: ByteArray) {
        imageModel.value = jpeg
    }
}
