package io.bimmergestalt.idriveconnectaddons.navmirror.carapp

/**
 * Thrown from an RHMIActionCallback to signal that rhmi_onActionEvent should ack with success=false,
 * which keeps the car on the current screen
 */
class RHMIActionAbort: Exception()
