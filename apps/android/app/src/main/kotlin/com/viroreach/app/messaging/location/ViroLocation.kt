package com.viroreach.app.messaging.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.CancellationSignal
import androidx.core.content.ContextCompat
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import kotlin.coroutines.resume

/**
 * One fix, from Android's own location manager. No Play Services dependency:
 * plenty of phones here run without them, and a chat only needs a fix good
 * enough to find someone.
 */
object ViroLocation {
    /** The durations a live share may run for, matching what the server accepts. */
    val LIVE_CHOICES = listOf(15 * 60 to "15 minutes", 60 * 60 to "1 hour", 8 * 60 * 60 to "8 hours")

    fun hasPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    /** Whether the phone's location switch is on at all. */
    fun enabled(context: Context): Boolean {
        val lm = context.getSystemService(LocationManager::class.java) ?: return false
        return runCatching {
            lm.isProviderEnabled(LocationManager.GPS_PROVIDER) || lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
        }.getOrDefault(false)
    }

    /**
     * A current fix, or the last known one if nothing arrives in [timeoutMs].
     * Null when location is off, not permitted, or nothing is known yet.
     */
    @SuppressLint("MissingPermission")
    suspend fun current(context: Context, timeoutMs: Long = 12_000): Location? {
        if (!hasPermission(context)) return null
        val lm = context.getSystemService(LocationManager::class.java) ?: return null
        val provider = when {
            runCatching { lm.isProviderEnabled(LocationManager.GPS_PROVIDER) }.getOrDefault(false) -> LocationManager.GPS_PROVIDER
            runCatching { lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER) }.getOrDefault(false) -> LocationManager.NETWORK_PROVIDER
            else -> return null
        }
        val fresh = withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine<Location?> { cont ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    val signal = CancellationSignal()
                    cont.invokeOnCancellation { signal.cancel() }
                    runCatching {
                        lm.getCurrentLocation(provider, signal, context.mainExecutor) { location ->
                            if (cont.isActive) cont.resume(location)
                        }
                    }.onFailure { if (cont.isActive) cont.resume(null) }
                } else {
                    val listener = object : android.location.LocationListener {
                        override fun onLocationChanged(location: Location) {
                            runCatching { lm.removeUpdates(this) }
                            if (cont.isActive) cont.resume(location)
                        }

                        @Deprecated("Required on older Android")
                        override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) = Unit
                        override fun onProviderDisabled(provider: String) = Unit
                        override fun onProviderEnabled(provider: String) = Unit
                    }
                    cont.invokeOnCancellation { runCatching { lm.removeUpdates(listener) } }
                    runCatching { lm.requestLocationUpdates(provider, 0L, 0f, listener, context.mainLooper) }
                        .onFailure { if (cont.isActive) cont.resume(null) }
                }
            }
        }
        return fresh ?: runCatching { lm.getLastKnownLocation(provider) }.getOrNull()
    }

    /** Opens the place in whatever maps app the phone has. */
    fun openInMaps(context: Context, lat: Double, lng: Double, label: String?): Boolean {
        val point = String.format(Locale.US, "%.6f,%.6f", lat, lng)
        val name = label?.takeIf { it.isNotBlank() }?.let { Uri.encode(it) } ?: Uri.encode("Shared location")
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("geo:$point?q=$point($name)")).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return runCatching { context.startActivity(intent); true }.getOrDefault(false)
    }

    /** "-15.416700, 28.283300" — shown when there is no label. */
    fun format(lat: Double, lng: Double): String = String.format(Locale.US, "%.5f, %.5f", lat, lng)
}
