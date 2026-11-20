package os.flow.launcher

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.CancellationSignal
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.math.roundToInt

/** Météo actuelle : température arrondie, code WMO, jour ou nuit. */
data class Weather(val tempC: Int, val code: Int, val isDay: Boolean) {
    /** Icône simple selon le code météo WMO (https://open-meteo.com/en/docs). */
    val icon: String
        get() = when (code) {
            0 -> if (isDay) "☀️" else "🌙"
            1, 2 -> if (isDay) "🌤️" else "☁️"
            3 -> "☁️"
            45, 48 -> "🌫️"
            in 51..67, in 80..82 -> "🌧️"
            in 71..77, 85, 86 -> "❄️"
            in 95..99 -> "⛈️"
            else -> "🌡️"
        }
}

/**
 * Météo de l'accueil, façon « En un coup d'œil » du Pixel.
 * Position approximative uniquement (arrondie à ~1 km avant envoi), source Open-Meteo (sans compte ni clé).
 */
class WeatherRepository(private val context: Context) {

    fun hasPermission(): Boolean =
        context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    private suspend fun location(): Location? {
        if (!hasPermission()) return null
        val lm = context.getSystemService(LocationManager::class.java) ?: return null
        val providers = listOf(
            LocationManager.FUSED_PROVIDER, LocationManager.NETWORK_PROVIDER,
            LocationManager.PASSIVE_PROVIDER, LocationManager.GPS_PROVIDER,
        )
        val last = providers.mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
            .maxByOrNull { it.time }
        if (last != null && System.currentTimeMillis() - last.time < 6 * 3600_000L) return last
        val provider = providers.firstOrNull { runCatching { lm.isProviderEnabled(it) }.getOrDefault(false) }
            ?: return last
        val fresh = withTimeoutOrNull(15_000) {
            suspendCancellableCoroutine<Location?> { cont ->
                val signal = CancellationSignal()
                cont.invokeOnCancellation { signal.cancel() }
                runCatching {
                    lm.getCurrentLocation(provider, signal, context.mainExecutor) { loc -> if (cont.isActive) cont.resume(loc) }
                }.onFailure { if (cont.isActive) cont.resume(null) }
            }
        }
        return fresh ?: last
    }

    suspend fun current(): Weather? {
        val loc = location() ?: run { Log.w(TAG, "pas de position"); return null }
        // Arrondi à 2 décimales (~1 km) : on n'envoie pas la position précise.
        val lat = String.format(Locale.US, "%.2f", loc.latitude)
        val lon = String.format(Locale.US, "%.2f", loc.longitude)
        return withContext(Dispatchers.IO) {
            runCatching {
                val url = URL(
                    "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon" +
                        "&current=temperature_2m,weather_code,is_day&timezone=auto",
                )
                val conn = (url.openConnection() as HttpURLConnection).apply {
                    connectTimeout = 8_000; readTimeout = 8_000
                }
                try {
                    val body = conn.inputStream.bufferedReader().use { it.readText() }
                    val cur = JSONObject(body).getJSONObject("current")
                    Weather(
                        tempC = cur.getDouble("temperature_2m").roundToInt(),
                        code = cur.getInt("weather_code"),
                        isDay = cur.optInt("is_day", 1) == 1,
                    )
                } finally {
                    conn.disconnect()
                }
            }.onFailure { Log.w(TAG, "échec météo", it) }.getOrNull()
        }
    }

    private companion object { const val TAG = "FlowWeather" }
}
