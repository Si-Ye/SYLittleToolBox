package io.github.siye.littletoolbox.tools.trail

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import android.os.SystemClock

internal const val FIX_EXPIRY_MS = 15_000L

internal class TrailLocation(context: Context) {
    private val manager = context.getSystemService(LocationManager::class.java)
    private val app = context.applicationContext

    val hasPrecisePermission: Boolean
        get() = app.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    val gpsEnabled: Boolean
        get() = manager.isProviderEnabled(LocationManager.GPS_PROVIDER)

    @SuppressLint("MissingPermission")
    fun listen(onFix: (TrailFix) -> Unit, onDisabled: () -> Unit): () -> Unit {
        if (!hasPrecisePermission) throw SecurityException("Precise location permission is missing")
        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                val elapsedMs = location.elapsedRealtimeNanos / 1_000_000
                if (location.hasAccuracy() && location.accuracy in 0f..50f &&
                    SystemClock.elapsedRealtime() - elapsedMs in 0..FIX_EXPIRY_MS
                ) {
                    onFix(TrailFix(location.latitude, location.longitude, location.accuracy, location.time, elapsedMs))
                }
            }

            override fun onProviderDisabled(provider: String) = onDisabled()

            override fun onProviderEnabled(provider: String) = Unit

            @Deprecated("Kept for Android 9")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
        }
        manager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 3_000L, 0f, listener, Looper.getMainLooper())
        return { manager.removeUpdates(listener) }
    }
}
