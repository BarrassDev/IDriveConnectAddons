package io.bimmergestalt.idriveconnectaddons.speedcams

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings as AndroidSettings
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.switchmaterial.SwitchMaterial
import io.bimmergestalt.idriveconnectaddons.speedcams.map.MapRenderer
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity() {
    private lateinit var grantLocation: Button
    private lateinit var backgroundLocation: Button

    private val requestForeground = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        SpeedCams.restartLocation()
        updatePermissionButtons()
    }
    private val requestBackground = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        updatePermissionButtons()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        SpeedCams.init(applicationContext)
        setContentView(R.layout.activity_main)
        val settings = SpeedCams.settings

        val preview = findViewById<ImageView>(R.id.preview)
        val drive = findViewById<TextView>(R.id.drive)
        val status = findViewById<TextView>(R.id.status)
        grantLocation = findViewById(R.id.grantLocation)
        backgroundLocation = findViewById(R.id.backgroundLocation)

        SpeedCams.preview.observe(this) { bmp -> if (bmp != null) preview.setImageBitmap(bmp) }
        SpeedCams.state.observe(this) { s ->
            drive.text = describeDrive(s)
            status.text = s.status
        }

        grantLocation.setOnClickListener {
            val perms = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
            if (Build.VERSION.SDK_INT >= 33) perms += Manifest.permission.POST_NOTIFICATIONS
            requestForeground.launch(perms.toTypedArray())
        }
        backgroundLocation.setOnClickListener {
            if (Build.VERSION.SDK_INT >= 29) {
                requestBackground.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
            }
            if (Build.VERSION.SDK_INT >= 30) {
                // Android 11+ only grants "all the time" from the settings page
                Toast.makeText(this, "Choose Location > Allow all the time", Toast.LENGTH_LONG).show()
                startActivity(Intent(AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
            }
        }
        findViewById<Button>(R.id.refresh).setOnClickListener {
            SpeedCams.forceCameraRefresh()
        }
        findViewById<Button>(R.id.simulate).setOnClickListener {
            val result = SpeedCams.simulateApproach() ?: "No cameras loaded yet, wait for a GPS fix and the download"
            Toast.makeText(this, result, Toast.LENGTH_LONG).show()
        }
        findViewById<Button>(R.id.testAlert).setOnClickListener { SpeedCams.testAlert() }

        findViewById<SwitchMaterial>(R.id.voice).apply {
            isChecked = settings.voice
            setOnCheckedChangeListener { _, checked -> settings.voice = checked }
        }
        findViewById<SwitchMaterial>(R.id.headingUp).apply {
            isChecked = settings.headingUp
            setOnCheckedChangeListener { _, checked -> settings.headingUp = checked }
        }
        findViewById<SwitchMaterial>(R.id.kmh).apply {
            isChecked = settings.showKmh
            setOnCheckedChangeListener { _, checked -> settings.showKmh = checked }
        }
        findViewById<RadioGroup>(R.id.distance).apply {
            check(when (settings.alertMiles) { 0.2f -> R.id.dist02; 0.5f -> R.id.dist05; else -> R.id.dist03 })
            setOnCheckedChangeListener { _, id ->
                settings.alertMiles = when (id) { R.id.dist02 -> 0.2f; R.id.dist05 -> 0.5f; else -> 0.3f }
            }
        }
        findViewById<RadioGroup>(R.id.style).apply {
            check(when (settings.mapStyle) { MapStyle.LIGHT -> R.id.styleLight; MapStyle.DARK -> R.id.styleDark; else -> R.id.styleAuto })
            setOnCheckedChangeListener { _, id ->
                settings.mapStyle = when (id) { R.id.styleLight -> MapStyle.LIGHT; R.id.styleDark -> MapStyle.DARK; else -> MapStyle.AUTO }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        updatePermissionButtons()
        SpeedCams.previewActive = true
        SpeedCams.acquire()
    }

    override fun onStop() {
        super.onStop()
        SpeedCams.previewActive = false
        SpeedCams.release()
    }

    private fun granted(permission: String) =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    private fun updatePermissionButtons() {
        val foreground = granted(Manifest.permission.ACCESS_FINE_LOCATION)
        grantLocation.visibility = if (foreground) View.GONE else View.VISIBLE
        val background = Build.VERSION.SDK_INT < 29 || granted(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        backgroundLocation.visibility = if (foreground && !background) View.VISIBLE else View.GONE
    }

    private fun describeDrive(s: DriveState): String {
        val fix = s.fix ?: return "Waiting for a position"
        val mph = SpeedCams.settings.showMph
        val unit = if (mph) "mph" else "km/h"
        val speed = s.speed?.let { (if (mph) it * 2.236936 else it * 3.6).roundToInt() }
        val lines = ArrayList<String>()
        lines += "Speed ${speed ?: "--"} $unit from ${fix.source.label}" + (fix.accuracy?.let { " (±${it.roundToInt()} m)" } ?: "")
        s.roadName?.takeIf { it.isNotBlank() }?.let { lines += "On $it" + (s.limit?.let { l -> ", limit ${l.display(mph)} $unit" } ?: "") }
        val next = s.ahead.firstOrNull()
        lines += if (next != null) {
            "${next.camera.type.label} ${MapRenderer.formatDistance(next.distance, mph)} ahead"
        } else {
            "No camera on the road ahead"
        }
        return lines.joinToString("\n")
    }
}
