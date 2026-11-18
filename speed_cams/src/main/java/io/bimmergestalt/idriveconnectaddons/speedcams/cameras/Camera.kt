package io.bimmergestalt.idriveconnectaddons.speedcams.cameras

import io.bimmergestalt.idriveconnectaddons.speedcams.geo.LatLon

enum class CameraType(val label: String, val spoken: String) {
    SPEED("Speed camera", "Speed camera"),
    AVERAGE("Average speed camera", "Average speed check"),
    RED_LIGHT("Red light camera", "Red light camera"),
    MOBILE("Mobile camera site", "Mobile camera site"),
}

data class Camera(
    val id: Long,
    val position: LatLon,
    val type: CameraType,
    val limit: SpeedLimit?,
)
