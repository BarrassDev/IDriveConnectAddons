package io.bimmergestalt.idriveconnectaddons.speedcams

import io.bimmergestalt.idriveconnectaddons.speedcams.alert.AheadCamera
import io.bimmergestalt.idriveconnectaddons.speedcams.cameras.Camera
import io.bimmergestalt.idriveconnectaddons.speedcams.cameras.SpeedLimit
import io.bimmergestalt.idriveconnectaddons.speedcams.geo.LatLon
import io.bimmergestalt.idriveconnectaddons.speedcams.location.LocationFix

/** Everything the map and the phone screen show, rebuilt on every position update */
data class DriveState(
    val fix: LocationFix? = null,
    /** smoothed heading, kept while stopped */
    val heading: Double? = null,
    /** meters per second */
    val speed: Double? = null,
    val limit: SpeedLimit? = null,
    val roadName: String? = null,
    /** the predicted road ahead, starting at the car */
    val path: List<LatLon> = emptyList(),
    val ahead: List<AheadCamera> = emptyList(),
    /** cameras close enough to draw on the map */
    val nearby: List<Camera> = emptyList(),
    /** the closest camera ahead inside the alert distance */
    val alert: AheadCamera? = null,
    val cameraCount: Int = 0,
    val status: String = "",
)
