package ae.clearlane.app.location

import ae.clearlane.core.model.LatLng
import ae.clearlane.core.nav.Fix
import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Looper
import androidx.core.content.ContextCompat
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * Positions from the phone, as a flow of [Fix].
 *
 * Plain [LocationManager] rather than the fused provider from Play Services.
 * The fused one smooths better, but it drags in a dependency that is not on
 * every device and cannot be tested on a bare emulator image, and for this app
 * the GPS provider already gives what is needed: a position, a speed and a
 * course, at one hertz, outdoors, in a moving car.
 *
 * The flow emits nothing at all rather than throwing when permission has not
 * been granted, because the caller has a working screen either way.
 */
class Positions(private val context: Context) {

    val granted: Boolean
        get() = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    /** True when the device has any position source turned on at all. */
    val enabled: Boolean
        get() = runCatching {
            val manager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            manager.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
        }.getOrDefault(false)

    @SuppressLint("MissingPermission")
    fun fixes(): Flow<Fix> = callbackFlow {
        if (!granted) {
            close()
            return@callbackFlow
        }
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

        val listener = LocationListener { location -> trySend(location.toFix()) }

        val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .filter { runCatching { manager.isProviderEnabled(it) }.getOrDefault(false) }

        if (providers.isEmpty()) {
            close()
            return@callbackFlow
        }

        // Whatever the last known position was, so the screen has something to
        // draw before the first new fix arrives. A cold GPS can take half a
        // minute, and a blank speedometer for half a minute looks broken.
        providers.firstNotNullOfOrNull { provider ->
            runCatching { manager.getLastKnownLocation(provider) }.getOrNull()
        }?.let { trySend(it.toFix()) }

        providers.forEach { provider ->
            runCatching {
                manager.requestLocationUpdates(
                    provider,
                    MIN_INTERVAL_MS,
                    MIN_DISTANCE_M,
                    listener,
                    Looper.getMainLooper(),
                )
            }
        }

        awaitClose { runCatching { manager.removeUpdates(listener) } }
    }

    private companion object {
        /**
         * One second. Faster than this and the readings jitter without telling
         * the driver anything; slower and at motorway speed the car has moved
         * fifty metres between updates, which is enough to miss a camera
         * warning entirely.
         */
        const val MIN_INTERVAL_MS = 1_000L

        /** Let every update through: a car at a red light still needs the speed. */
        const val MIN_DISTANCE_M = 0f
    }
}

/**
 * A platform location as a [Fix].
 *
 * Speed and bearing are dropped unless the fix says it actually has them.
 * Android reports 0.0 for both when it does not know, and a speedometer that
 * reads zero because nothing was measured is worse than one that reads nothing.
 */
private fun Location.toFix(): Fix = Fix(
    at = LatLng(latitude, longitude),
    speedKph = if (hasSpeedCompat()) speed * 3.6 else null,
    headingDeg = if (hasBearingCompat()) bearing.toDouble() else null,
    accuracyMeters = if (hasAccuracy()) accuracy.toDouble() else null,
)

private fun Location.hasSpeedCompat(): Boolean =
    hasSpeed() && (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || speed > 0f || speedAccuracyKnown())

private fun Location.speedAccuracyKnown(): Boolean =
    runCatching { hasSpeedAccuracy() }.getOrDefault(false)

private fun Location.hasBearingCompat(): Boolean = hasBearing() && bearing != 0f
