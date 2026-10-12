package com.example.drivedistance

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import java.io.File
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var tvStatus: TextView
    private lateinit var tvRoute: TextView
    private lateinit var tvDistance: TextView
    private lateinit var tvPoints: TextView
    private lateinit var btnStart: Button
    private lateinit var btnStop: Button

    private lateinit var map: MapView
    private lateinit var routeLine: Polyline
    private lateinit var startMarker: Marker
    private lateinit var endMarker: Marker
    private lateinit var meMarker: Marker
    private var fittedAfterStop = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // osmdroid setup: identify the app + keep tile cache inside app cache dir
        val cfg = Configuration.getInstance()
        cfg.userAgentValue = packageName
        val base = File(cacheDir, "osmdroid")
        cfg.osmdroidBasePath = base
        cfg.osmdroidTileCache = File(base, "tiles")

        setContentView(R.layout.activity_main)

        tvStatus = findViewById(R.id.tvStatus)
        tvRoute = findViewById(R.id.tvRoute)
        tvDistance = findViewById(R.id.tvDistance)
        tvPoints = findViewById(R.id.tvPoints)
        btnStart = findViewById(R.id.btnStart)
        btnStop = findViewById(R.id.btnStop)
        map = findViewById(R.id.map)

        setupMap()

        btnStart.setOnClickListener {
            if (hasPermissions()) startTrip() else requestPermissions()
        }
        btnStop.setOnClickListener {
            startService(Intent(this, TripService::class.java).setAction(TripService.ACTION_STOP))
        }
    }

    private fun setupMap() {
        map.setTileSource(TileSourceFactory.MAPNIK)
        map.setMultiTouchControls(true)
        map.controller.setZoom(5.0)
        map.controller.setCenter(GeoPoint(20.5937, 78.9629))   // India overview until first GPS fix

        routeLine = Polyline()
        routeLine.outlinePaint.color = Color.rgb(25, 118, 210)
        routeLine.outlinePaint.strokeWidth = 12f
        map.overlays.add(routeLine)

        startMarker = Marker(map)
        startMarker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
        tint(startMarker, Color.rgb(46, 125, 50))      // green = A

        endMarker = Marker(map)
        endMarker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
        tint(endMarker, Color.rgb(198, 40, 40))        // red = B

        meMarker = Marker(map)
        meMarker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
        meMarker.icon = ContextCompat.getDrawable(this, android.R.drawable.ic_menu_mylocation)
        meMarker.title = "You are here"
    }

    private fun tint(m: Marker, color: Int) {
        try {
            val d = m.icon?.mutate()
            if (d != null) {
                d.setTint(color)
                m.icon = d
            }
        } catch (e: Exception) { /* keep default icon */ }
    }

    override fun onResume() {
        super.onResume()
        map.onResume()
        TripTracker.listener = { runOnUiThread { refresh() } }
        refresh()
    }

    override fun onPause() {
        TripTracker.listener = null
        map.onPause()
        super.onPause()
    }

    private fun startTrip() {
        fittedAfterStop = false
        ContextCompat.startForegroundService(
            this, Intent(this, TripService::class.java).setAction(TripService.ACTION_START)
        )
    }

    private fun nameOrDots(s: String) = if (s.isBlank()) "locating…" else s

    private fun refresh() {
        val km = TripTracker.distanceMeters / 1000.0
        tvDistance.text = String.format(Locale.getDefault(), "%.2f km", km)
        tvPoints.text = "GPS points: ${TripTracker.pointCount()}"
        btnStart.isEnabled = !TripTracker.running
        btnStop.isEnabled = TripTracker.running

        val finished = !TripTracker.running && TripTracker.endTime > 0
        tvStatus.text = when {
            TripTracker.running -> "Driving… (tracking)"
            finished -> {
                val mins = (TripTracker.endTime - TripTracker.startTime) / 60000
                "Trip finished • $mins min"
            }
            else -> "Ready"
        }
        tvRoute.text = when {
            TripTracker.running && TripTracker.pointCount() == 0 -> "Waiting for GPS fix…"
            TripTracker.running -> "${nameOrDots(TripTracker.startName)}  →  …"
            finished -> "${nameOrDots(TripTracker.startName)}  →  ${nameOrDots(TripTracker.endName)}"
            else -> "—"
        }

        redrawMap(finished)
    }

    private fun setShown(m: Marker, show: Boolean) {
        val has = map.overlays.contains(m)
        if (show && !has) map.overlays.add(m)
        else if (!show && has) map.overlays.remove(m)
    }

    private fun redrawMap(finished: Boolean) {
        val pts = TripTracker.snapshot()
        routeLine.setPoints(pts)

        if (pts.isEmpty()) {
            setShown(startMarker, false)
            setShown(endMarker, false)
            setShown(meMarker, false)
            map.invalidate()
            return
        }

        val first = pts.first()
        val last = pts.last()
        startMarker.position = first
        startMarker.title = "A: " + nameOrDots(TripTracker.startName)
        setShown(startMarker, true)

        if (TripTracker.running) {
            setShown(endMarker, false)
            meMarker.position = last
            setShown(meMarker, true)
            // follow the vehicle
            if (map.zoomLevelDouble < 12.0) map.controller.setZoom(17.0)
            if (!map.boundingBox.contains(last.latitude, last.longitude) || pts.size == 1) {
                map.controller.animateTo(last)
            }
        } else {
            setShown(meMarker, false)
            if (pts.size > 1) {
                endMarker.position = last
                endMarker.title = "B: " + nameOrDots(TripTracker.endName)
                setShown(endMarker, true)
            }
            // after the trip: zoom to show the whole route once
            if (finished && !fittedAfterStop) {
                fittedAfterStop = true
                if (pts.size > 1) {
                    map.post {
                        try {
                            map.zoomToBoundingBox(BoundingBox.fromGeoPoints(pts), true, 120)
                        } catch (e: Exception) { /* ignore */ }
                    }
                } else {
                    map.controller.setCenter(last)
                }
            }
        }
        map.invalidate()
    }

    private fun hasPermissions() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED

    private fun requestPermissions() {
        val perms = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= 33) perms.add(Manifest.permission.POST_NOTIFICATIONS)
        ActivityCompat.requestPermissions(this, perms.toTypedArray(), 100)
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<String>, res: IntArray) {
        super.onRequestPermissionsResult(code, perms, res)
        if (code == 100 && hasPermissions()) startTrip()
    }
}
