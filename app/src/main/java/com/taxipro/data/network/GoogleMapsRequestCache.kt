package com.taxipro.data.network

import android.content.Context
import com.google.gson.Gson
import java.security.MessageDigest
import java.util.Locale

object GoogleMapsRequestCache {
    private const val PREFS = "google_maps_request_cache"
    private const val AUTOCOMPLETE_TTL_MS = 7L * 24 * 60 * 60 * 1000
    private const val GEOCODE_TTL_MS = 30L * 24 * 60 * 60 * 1000
    private const val DIRECTIONS_TTL_MS = 24L * 60 * 60 * 1000
    private const val TRAFFIC_DIRECTIONS_TTL_MS = 15L * 60 * 1000
    private const val DEPARTURE_BUCKET_SEC = 15L * 60

    private val gson = Gson()

    suspend fun cachedAutocomplete(
        context: Context,
        api: DirectionsApi,
        input: String,
        apiKey: String,
        language: String,
        location: String?,
        radius: Int?,
    ): AutocompleteResponse {
        val normalizedInput = input.normalized()
        val key = cacheKey("autocomplete", normalizedInput, language, location.orEmpty(), radius?.toString().orEmpty())
        return get(context, key, AUTOCOMPLETE_TTL_MS, AutocompleteResponse::class.java)
            ?: api.autocomplete(
                input = input.trim(),
                apiKey = apiKey,
                language = language,
                location = location,
                radius = radius,
            ).also { put(context, key, it) }
    }

    suspend fun cachedDirections(
        context: Context,
        api: DirectionsApi,
        origin: String,
        destination: String,
        alternatives: Boolean,
        apiKey: String,
        language: String,
        departureTime: Long?,
        trafficModel: String?,
    ): DirectionsResponse {
        val departureBucket = departureTime?.let { it / DEPARTURE_BUCKET_SEC }
        val key = cacheKey(
            "directions",
            origin.normalized(),
            destination.normalized(),
            alternatives.toString(),
            language,
            departureBucket?.toString().orEmpty(),
            trafficModel.orEmpty(),
        )
        val ttl = if (departureTime != null) TRAFFIC_DIRECTIONS_TTL_MS else DIRECTIONS_TTL_MS
        return get(context, key, ttl, DirectionsResponse::class.java)
            ?: api.getDirections(
                origin = origin.trim(),
                destination = destination.trim(),
                alternatives = alternatives,
                apiKey = apiKey,
                language = language,
                departureTime = departureTime,
                trafficModel = trafficModel,
            ).also { put(context, key, it) }
    }

    suspend fun cachedReverseGeocode(
        context: Context,
        api: DirectionsApi,
        lat: Double,
        lng: Double,
        apiKey: String,
        language: String,
    ): GeocodingResponse {
        val latLng = "%.5f,%.5f".format(Locale.US, lat, lng)
        val key = cacheKey("reverse_geocode", latLng, language)
        return get(context, key, GEOCODE_TTL_MS, GeocodingResponse::class.java)
            ?: api.reverseGeocode(
                latLng = "$lat,$lng",
                apiKey = apiKey,
                language = language,
            ).also { put(context, key, it) }
    }

    private fun <T> get(context: Context, key: String, ttlMs: Long, clazz: Class<T>): T? {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val savedAt = prefs.getLong("$key:time", 0L)
        if (savedAt == 0L || System.currentTimeMillis() - savedAt > ttlMs) return null
        val json = prefs.getString("$key:data", null) ?: return null
        return runCatching { gson.fromJson(json, clazz) }.getOrNull()
    }

    private fun put(context: Context, key: String, value: Any) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.edit()
            .putLong("$key:time", System.currentTimeMillis())
            .putString("$key:data", gson.toJson(value))
            .apply()
    }

    private fun cacheKey(vararg parts: String): String =
        sha256(parts.joinToString("|"))

    private fun String.normalized(): String =
        trim().lowercase(Locale.ROOT).replace(Regex("\\s+"), " ")

    private fun sha256(value: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
