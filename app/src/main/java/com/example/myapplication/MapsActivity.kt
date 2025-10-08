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
import android.util.Log
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
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

    private val TAG = "MapsActivity"

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

        // Búsqueda por texto - CORREGIDO
        b.etQuery.setOnEditorActionListener { v, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH || actionId == EditorInfo.IME_ACTION_DONE) {
                val q = v.text.toString().trim()
                if (q.isNotEmpty()) {
                    geocodeAndPin(q)
                    // Ocultar teclado
                    val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                    imm.hideSoftInputFromWindow(v.windowToken, 0)
                } else {
                    Toast.makeText(this, "Ingresa una dirección", Toast.LENGTH_SHORT).show()
                }
                true
            } else false
        }

        // Botón de búsqueda adicional
        b.btnSearch.setOnClickListener {
            val q = b.etQuery.text.toString().trim()
            if (q.isNotEmpty()) {
                geocodeAndPin(q)
                // Ocultar teclado
                val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                imm.hideSoftInputFromWindow(b.etQuery.windowToken, 0)
            } else {
                Toast.makeText(this, "Ingresa una dirección", Toast.LENGTH_SHORT).show()
            }
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

        // Long press -> pin + distancia - CORREGIDO
        gm.setOnMapLongClickListener { latLng ->
            Log.d(TAG, "Long click en: $latLng")
            val addr = reverseGeocode(latLng)
            pinMarker?.remove()
            pinMarker = gm.addMarker(
                MarkerOptions()
                    .position(latLng)
                    .title(addr)
                    .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_BLUE))
            )
            pinMarker?.showInfoWindow()
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

                Log.d(TAG, "Ubicación actualizada: $ll")

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
        } catch (e: Exception) {
            Log.e(TAG, "Error guardando ubicación en JSON", e)
        }
    }

    // Geocoder desde texto - CORREGIDO
    private fun geocodeAndPin(text: String) {
        Log.d(TAG, "Buscando: $text")
        try {
            val gc = Geocoder(this, Locale.getDefault())

            @Suppress("DEPRECATION")
            val res = gc.getFromLocationName(text, 1)

            if (!res.isNullOrEmpty()) {
                val a = res[0]
                val ll = LatLng(a.latitude, a.longitude)
                val address = a.getAddressLine(0) ?: "Dirección encontrada"

                Log.d(TAG, "Encontrado: $address en $ll")

                pinMarker?.remove()
                pinMarker = map?.addMarker(
                    MarkerOptions()
                        .position(ll)
                        .title(address)
                        .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_GREEN))
                )
                pinMarker?.showInfoWindow()
                moveCamera(ll, 16f)
                showDistanceToast()

                Toast.makeText(this, "Ubicación encontrada", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "No se encontró la dirección '$text'", Toast.LENGTH_LONG).show()
                Log.w(TAG, "Sin resultados para: $text")
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Error de geocodificación: ${e.message}", Toast.LENGTH_LONG).show()
            Log.e(TAG, "Error en geocodeAndPin", e)
        }
    }

    // Geocoder inverso para long-click - CORREGIDO
    private fun reverseGeocode(ll: LatLng): String {
        return try {
            val gc = Geocoder(this, Locale.getDefault())

            @Suppress("DEPRECATION")
            val res = gc.getFromLocation(ll.latitude, ll.longitude, 1)

            val address = res?.firstOrNull()?.getAddressLine(0) ?: "${ll.latitude}, ${ll.longitude}"
            Log.d(TAG, "Geocodificación inversa: $address")
            address
        } catch (e: Exception) {
            Log.e(TAG, "Error en reverseGeocode", e)
            "${ll.latitude}, ${ll.longitude}"
        }
    }

    private fun moveCamera(ll: LatLng, zoom: Float) {
        map?.animateCamera(CameraUpdateFactory.newLatLngZoom(ll, zoom))
    }

    // Mostrar distancia - CORREGIDO
    private fun showDistanceToast() {
        val me = lastLocation
        val pin = pinMarker?.position

        if (me == null) {
            Toast.makeText(this, "Esperando ubicación GPS...", Toast.LENGTH_SHORT).show()
            Log.w(TAG, "lastLocation es null")
            return
        }

        if (pin == null) {
            Log.w(TAG, "pinMarker es null")
            return
        }

        val results = FloatArray(1)
        Location.distanceBetween(me.latitude, me.longitude, pin.latitude, pin.longitude, results)
        val distanceKm = results[0] / 1000.0

        val message = if (distanceKm < 1.0) {
            "Distancia: %.0f metros".format(results[0])
        } else {
            "Distancia: %.2f km".format(distanceKm)
        }

        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        Log.d(TAG, "Distancia calculada: $distanceKm km")
    }

    // Sensor de luz → estilo claro/oscuro
    override fun onSensorChanged(e: SensorEvent?) {
        val lux = e?.values?.firstOrNull() ?: return
        Log.d(TAG, "Luminosidad: $lux lux")

        val style = if (lux < 20f) R.raw.map_night else R.raw.map_light
        try {
            map?.setMapStyle(MapStyleOptions.loadRawResourceStyle(this, style))
        } catch (ex: Exception) {
            Log.e(TAG, "Error aplicando estilo de mapa", ex)
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}