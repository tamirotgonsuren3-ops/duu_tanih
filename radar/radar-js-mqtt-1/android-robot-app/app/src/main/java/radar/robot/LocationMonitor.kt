package radar.robot

import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import androidx.core.content.ContextCompat

class LocationMonitor(private val context: Context, private val state: SensorState) {

    private val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    private val locationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            state.lat = location.latitude
            state.lon = location.longitude
            state.alt = location.altitude
            state.speed = location.speed
            state.accuracy = location.accuracy
            state.hasLocation = true
            state.ts = System.currentTimeMillis()
        }

        @Deprecated("deprecated in newer APIs, still implemented for old ones")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}

        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
    }

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

    fun start() {
        if (!hasPermission()) return
        try {
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 1f, locationListener)
            lm.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 1000L, 1f, locationListener)
            val last = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                ?: lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
            if (last != null) {
                state.lat = last.latitude
                state.lon = last.longitude
                state.alt = last.altitude
                state.hasLocation = true
            }
        } catch (e: Exception) { /* location service unavailable */ }
    }

    fun stop() {
        try { lm.removeUpdates(locationListener) } catch (e: Exception) {}
    }
}