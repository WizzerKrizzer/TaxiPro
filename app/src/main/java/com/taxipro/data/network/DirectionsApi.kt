package com.taxipro.data.network

import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET
import retrofit2.http.Query

// ── Response models ─────────────────────────────────────────
data class DirectionsResponse(
    val routes: List<DirectionRoute> = emptyList(),
    val status: String = ""
)

data class DirectionRoute(
    val legs: List<DirectionLeg> = emptyList(),
    val overview_polyline: EncodedPolyline? = null,
    val summary: String = ""
)

data class DirectionLeg(
    val distance: ValueText = ValueText(),
    val duration: ValueText = ValueText(),
    val duration_in_traffic: ValueText? = null,
    val start_address: String = "",
    val end_address: String = "",
    val start_location: LatLngLiteral = LatLngLiteral(),
    val end_location: LatLngLiteral = LatLngLiteral(),
)

data class ValueText(val value: Int = 0, val text: String = "")
data class LatLngLiteral(val lat: Double = 0.0, val lng: Double = 0.0)
data class EncodedPolyline(val points: String = "")

// ── Autocomplete models ──────────────────────────────────────
data class AutocompleteResponse(
    val predictions: List<AutocompletePrediction> = emptyList(),
    val status: String = ""
)

data class AutocompletePrediction(
    val description: String = "",
    val place_id: String = ""
)

// ── Geocoding models ─────────────────────────────────────────
data class GeocodingResponse(
    val results: List<GeocodingResult> = emptyList(),
    val status: String = ""
)

data class GeocodingResult(
    val formatted_address: String = ""
)

// ── Retrofit interface ──────────────────────────────────────
interface DirectionsApi {

    @GET("directions/json")
    suspend fun getDirections(
        @Query("origin") origin: String,
        @Query("destination") destination: String,
        @Query("alternatives") alternatives: Boolean = false,
        @Query("key") apiKey: String,
        @Query("language") language: String = "bg",
        @Query("departure_time") departureTime: Long? = null,
        @Query("traffic_model") trafficModel: String? = null,
    ): DirectionsResponse

    @GET("place/autocomplete/json")
    suspend fun autocomplete(
        @Query("input")        input: String,
        @Query("key")          apiKey: String,
        @Query("language")     language: String = "bg",
        @Query("types")        types: String = "geocode",
        /** "lat,lng" — biases/restricts results toward this point */
        @Query("location")     location: String? = null,
        /** Bias radius in metres */
        @Query("radius")       radius: Int? = null,
        /** When true, returns ONLY results within location+radius (strict filter, not just bias) */
        @Query("strictbounds") strictbounds: Boolean? = null,
    ): AutocompleteResponse

    @GET("geocode/json")
    suspend fun reverseGeocode(
        @Query("latlng") latLng: String,
        @Query("key") apiKey: String,
        @Query("language") language: String = "bg",
    ): GeocodingResponse

    companion object {
        private const val BASE_URL = "https://maps.googleapis.com/maps/api/"

        fun create(): DirectionsApi = Retrofit.Builder()
            .baseUrl(BASE_URL)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(DirectionsApi::class.java)
    }
}

// ── Polyline decoder (Google Encoded Polyline Algorithm) ────
fun decodePolyline(encoded: String): List<Pair<Double, Double>> {
    val poly = mutableListOf<Pair<Double, Double>>()
    var index = 0
    var lat = 0
    var lng = 0

    while (index < encoded.length) {
        var shift = 0; var result = 0
        do {
            val b = encoded[index++].code - 63
            result = result or ((b and 0x1F) shl shift)
            shift += 5
        } while (b >= 0x20)
        lat += if (result and 1 != 0) (result shr 1).inv() else result shr 1

        shift = 0; result = 0
        do {
            val b = encoded[index++].code - 63
            result = result or ((b and 0x1F) shl shift)
            shift += 5
        } while (b >= 0x20)
        lng += if (result and 1 != 0) (result shr 1).inv() else result shr 1

        poly.add(Pair(lat / 1E5, lng / 1E5))
    }
    return poly
}
