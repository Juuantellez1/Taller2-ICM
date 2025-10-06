package com.example.myapplication

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Geocoder
import android.location.Location
import android.os.Bundle
import android.view.inputmethod.EditorInfo
import android.widget.Toast
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.example.myapplication.databinding.ActivityMapsBinding
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.common.api.ResolvableApiException
import com.google.android.gms.location.*
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.SupportMapFragment
import com.google.android.gms.maps.model.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.*

class MapsActivity : AppCompatActivity(), OnMapReadyCallback, SensorEventListener {

    private lateinit var b: ActivityMapsBinding
    private var map: GoogleMap? = null

    private lateinit var fused: FusedLocationProviderClient
    private lateinit var locReq: LocationRequest
    private var locCb: LocationCallback? = null

    private var myMarker: Marker? = null
    private var pinMarker: Marker? = null
    private var lastLocation: Location? = null
    private lateinit var jsonFile: File

    // Sensor de luz
    private lateinit var sensorManager: SensorManager
    private var lightSensor: Sensor? = null

    // Lanzador moderno para resolver ajustes de ubicación
    private val resolutionLauncher =
        registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { /* no-op */ }

    // Permisos
    private val reqLocPerms =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { perms ->
            val fine = perms[Manifest.permission.ACCESS_FINE_LOCATION] == true
            val coarse = perms[Manifest.permission.ACCESS_COARSE_LOCATION] == true
            if (fine || coarse) checkLocationSettingsAndStart()
            else Toast.makeText(this, "Concede permiso de ubicación para usar el mapa", Toast.LENGTH_LONG).show()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMapsBinding.inflate(layoutInflater)
        setContentView(b.root)

        // Play Services disponible
        val code = GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(this)
        if (code != ConnectionResult.SUCCESS) {
            GoogleApiAvailability.getInstance().getErrorDialog(this, code, 0)?.show()
            return
        }

        jsonFile = File(filesDir, "locations.json")
        fused = LocationServices.getFusedLocationProviderClient(this)

        // Fragment del mapa
        val frag = supportFragmentManager.findFragmentById(R.id.map) as? SupportMapFragment
            ?: run {
                Toast.makeText(this, "No se encontró el fragmento del mapa (id=map)", Toast.LENGTH_LONG).show()
                return
            }
        frag.getMapAsync(this)

        // Sensor de luz
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        lightSensor = sensorManager.getDefaultSensor(Sensor.TYPE_LIGHT)

        // Búsqueda por texto
        b.etQuery.setOnEditorActionListener { v, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH || actionId == EditorInfo.IME_ACTION_DONE) {
                val q = v.text.toString().trim()
                if (q.isNotEmpty()) geocodeAndPin(q)
                true
            } else false
        }
    }

    override fun onResume() {
        super.onResume()
        lightSensor?.also { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL) }
    }

    override fun onPause() {
        super.onPause()
        sensorManager.unregisterListener(this)
        locCb?.let { fused.removeLocationUpdates(it) }
    }

    // Mapa listo
    override fun onMapReady(gm: GoogleMap) {
        map = gm
        gm.uiSettings.isZoomControlsEnabled = true

        // Long press -> pin + distancia
        gm.setOnMapLongClickListener { latLng ->
            val addr = reverseGeocode(latLng)
            pinMarker?.remove()
            pinMarker = gm.addMarker(MarkerOptions().position(latLng).title(addr))
            moveCamera(latLng, 15f)
            showDistanceToast()
        }

        // Permisos runtime
        val fine = Manifest.permission.ACCESS_FINE_LOCATION
        val coarse = Manifest.permission.ACCESS_COARSE_LOCATION
        if (ContextCompat.checkSelfPermission(this, fine) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, coarse) == PackageManager.PERMISSION_GRANTED
        ) {
            checkLocationSettingsAndStart()
        } else {
            reqLocPerms.launch(arrayOf(fine, coarse))
        }
    }

    /** Verifica que la ubicación de alta precisión esté activa y arranca actualizaciones */
    private fun checkLocationSettingsAndStart() {
        locReq = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 3000)
            .setMinUpdateDistanceMeters(30f)
            .build()

        val req = LocationSettingsRequest.Builder().addLocationRequest(locReq).build()
        val settingsClient = LocationServices.getSettingsClient(this)

        settingsClient.checkLocationSettings(req)
            .addOnSuccessListener { enableLocation() }
            .addOnFailureListener { ex ->
                if (ex is ResolvableApiException) {
                    val request = IntentSenderRequest.Builder(ex.resolution).build()
                    resolutionLauncher.launch(request)
                } else {
                    Toast.makeText(this, "Activa la ubicación (alta precisión)", Toast.LENGTH_LONG).show()
                }
            }
    }

    @SuppressLint("MissingPermission")
    private fun enableLocation() {
        val m = map ?: return
        m.isMyLocationEnabled = true

        locCb = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                val loc = result.lastLocation ?: return
                lastLocation = loc
                val ll = LatLng(loc.latitude, loc.longitude)

                if (myMarker == null) {
                    myMarker = m.addMarker(
                        MarkerOptions().position(ll).title("Aquí estoy")
                            .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_RED))
                    )
                    moveCamera(ll, 16f)
                } else {
                    myMarker!!.position = ll
                }
                appendLocationToJson(loc)
            }
        }
        fused.requestLocationUpdates(locReq, locCb as LocationCallback, mainLooper)
    }

    // Guarda cada punto en JSON (memoria interna)
    private fun appendLocationToJson(loc: Location) {
        try {
            val arr = if (jsonFile.exists()) JSONArray(jsonFile.readText()) else JSONArray()
            arr.put(JSONObject().apply {
                put("timestamp", System.currentTimeMillis())
                put("lat", loc.latitude)
                put("lng", loc.longitude)
                put("accuracy", loc.accuracy)
            })
            jsonFile.writeText(arr.toString())
        } catch (_: Exception) { /* no-op */ }
    }

    // Geocoder desde texto
    private fun geocodeAndPin(text: String) {
        try {
            val gc = Geocoder(this, Locale.getDefault())
            val res = gc.getFromLocationName(text, 1)
            if (!res.isNullOrEmpty()) {
                val a = res[0]
                val ll = LatLng(a.latitude, a.longitude)
                pinMarker?.remove()
                pinMarker = map?.addMarker(MarkerOptions().position(ll).title(a.getAddressLine(0)))
                moveCamera(ll, 16f)
                showDistanceToast()
            } else {
                Toast.makeText(this, "No se encontró la dirección", Toast.LENGTH_SHORT).show()
            }
        } catch (_: Exception) {
            Toast.makeText(this, "Error de geocodificación", Toast.LENGTH_SHORT).show()
        }
    }

    // Geocoder inverso para long-click
    private fun reverseGeocode(ll: LatLng): String {
        return try {
            val gc = Geocoder(this, Locale.getDefault())
            val res = gc.getFromLocation(ll.latitude, ll.longitude, 1)
            res?.firstOrNull()?.getAddressLine(0) ?: "${ll.latitude}, ${ll.longitude}"
        } catch (_: Exception) {
            "${ll.latitude}, ${ll.longitude}"
        }
    }

    private fun moveCamera(ll: LatLng, zoom: Float) {
        map?.animateCamera(CameraUpdateFactory.newLatLngZoom(ll, zoom))
    }

    private fun showDistanceToast() {
        val me = lastLocation ?: return
        val pin = pinMarker?.position ?: return
        val results = FloatArray(1)
        Location.distanceBetween(me.latitude, me.longitude, pin.latitude, pin.longitude, results)
        Toast.makeText(this, "Distancia a marcador: %.2f km".format(results[0] / 1000.0), Toast.LENGTH_LONG).show()
    }

    // Sensor de luz → estilo claro/oscuro
    override fun onSensorChanged(e: SensorEvent?) {
        val lux = e?.values?.firstOrNull() ?: return
        val style = if (lux < 20f) R.raw.map_night else R.raw.map_light
        try {
            map?.setMapStyle(MapStyleOptions.loadRawResourceStyle(this, style))
        } catch (_: Exception) { /* ignora errores de estilo */ }
    }
    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}
