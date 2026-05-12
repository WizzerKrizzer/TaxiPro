package com.taxipro.data.db

import com.google.android.gms.maps.model.LatLng

// ── Palette of 12 vivid, distinct zone colors (ARGB) ─────────────
val ZONE_PALETTE: List<Int> = listOf(
    0xFFE53935.toInt(), // Red
    0xFF1E88E5.toInt(), // Blue
    0xFF43A047.toInt(), // Green
    0xFFFF8F00.toInt(), // Amber
    0xFF8E24AA.toInt(), // Purple
    0xFF00ACC1.toInt(), // Cyan
    0xFFE91E63.toInt(), // Pink
    0xFFFF5722.toInt(), // Deep Orange
    0xFF3949AB.toInt(), // Indigo
    0xFF00897B.toInt(), // Teal
    0xFF9E9D24.toInt(), // Olive
    0xFF546E7A.toInt(), // Blue Grey
)

// ── JSON parser ───────────────────────────────────────────────────

/** Parse "[[lat,lng],[lat,lng],...]" into a list of LatLng. */
fun parseZonePoints(json: String): List<LatLng> {
    if (json == "[]" || json.isBlank()) return emptyList()
    return Regex("""\[([+-]?\d+\.?\d*),([+-]?\d+\.?\d*)\]""")
        .findAll(json)
        .map { LatLng(it.groupValues[1].toDouble(), it.groupValues[2].toDouble()) }
        .toList()
}

/** Serialise a list of LatLng back to JSON. */
fun serializeZonePoints(points: List<LatLng>): String =
    points.joinToString(",", "[", "]") { "[${it.latitude},${it.longitude}]" }

// ── Geometry ──────────────────────────────────────────────────────

/** Ray-casting point-in-polygon test. Returns true if (lat, lng) is inside. */
fun pointInPolygon(lat: Double, lng: Double, polygon: List<LatLng>): Boolean {
    if (polygon.size < 3) return false
    var inside = false
    var j = polygon.lastIndex
    for (i in polygon.indices) {
        val xi = polygon[i].longitude; val yi = polygon[i].latitude
        val xj = polygon[j].longitude; val yj = polygon[j].latitude
        if ((yi > lat) != (yj > lat) &&
            lng < (xj - xi) * (lat - yi) / (yj - yi) + xi
        ) inside = !inside
        j = i
    }
    return inside
}

/** Returns the first Zone containing (lat, lng), or null if outside all zones. */
fun findZone(lat: Double, lng: Double, zones: List<Zone>): Zone? {
    if (lat == 0.0 && lng == 0.0) return null
    return zones.firstOrNull { zone ->
        pointInPolygon(lat, lng, parseZonePoints(zone.pointsJson))
    }
}

// ── Ride coordinate helpers ───────────────────────────────────────

fun rideStartLatLng(ride: Ride): Pair<Double, Double>? {
    if (ride.fromLat != 0.0 || ride.fromLng != 0.0) return ride.fromLat to ride.fromLng
    return parseZonePoints(ride.routePointsJson).firstOrNull()
        ?.let { it.latitude to it.longitude }
}

fun rideEndLatLng(ride: Ride): Pair<Double, Double>? {
    if (ride.toLat != 0.0 || ride.toLng != 0.0) return ride.toLat to ride.toLng
    return parseZonePoints(ride.routePointsJson).lastOrNull()
        ?.let { it.latitude to it.longitude }
}

// ── Ride route label helper ───────────────────────────────────────

/**
 * Returns (zoneLabel, addressLabel) for displaying a ride's route.
 * zoneLabel  — "Zone A → Zone B", or "" if no zones match / list empty.
 * addressLabel — "Street X → Street Y", or "" if no addresses stored.
 * Callers should fall back to "Ride #N" when both are empty.
 *
 * [outsideLabel] is shown when a coordinate exists but falls outside all zones
 * (e.g. "Outside zones"). Pass "" to keep the old blank behaviour.
 */
fun rideRouteLabels(
    ride: Ride,
    zones: List<Zone>,
    outsideLabel: String = "",
): Pair<String, String> {
    val zoneLabel = if (zones.isNotEmpty()) {
        val startCoords = rideStartLatLng(ride)
        val endCoords   = rideEndLatLng(ride)
        val (sLat, sLng) = startCoords ?: (0.0 to 0.0)
        val (eLat, eLng) = endCoords   ?: (0.0 to 0.0)
        val fromZone = findZone(sLat, sLng, zones)
        val toZone   = findZone(eLat, eLng, zones)
        // Use outsideLabel when we have real coordinates but they match no zone
        val fromLabel = fromZone?.name ?: if (startCoords != null) outsideLabel else null
        val toLabel   = toZone?.name   ?: if (endCoords   != null) outsideLabel else null
        when {
            fromLabel != null && toLabel != null -> "$fromLabel → $toLabel"
            fromLabel != null                    -> "$fromLabel →"
            toLabel   != null                    -> "→ $toLabel"
            else                                 -> ""
        }
    } else ""

    val addressLabel = when {
        ride.fromAddress.isNotEmpty() && ride.toAddress.isNotEmpty() ->
            ride.fromAddress.substringBefore(",") + " → " + ride.toAddress.substringBefore(",")
        ride.fromAddress.isNotEmpty() -> ride.fromAddress.substringBefore(",")
        ride.toAddress.isNotEmpty()   -> ride.toAddress.substringBefore(",")
        else                          -> ""
    }

    return zoneLabel to addressLabel
}

// ── Zone statistics models ────────────────────────────────────────

data class ZoneStat(
    val zoneName: String,
    val zone: Zone?,               // null = "outside zones"
    val pickupCount: Int,
    val dropoffCount: Int,
    val avgRevenue: Double,
    val avgTip: Double,
    val avgKm: Double,
    val avgWaitMs: Long = 0L,
    // Individual rides for drill-down, sorted newest-first
    val pickupRides: List<Ride>  = emptyList(),
    val dropoffRides: List<Ride> = emptyList(),
)

data class ZoneRouteStat(
    val fromZone: String,
    val toZone: String,
    val count: Int,
    val avgRevenue: Double,
)

// ── Stats computation ─────────────────────────────────────────────

fun computeZoneStats(
    rides: List<Ride>,
    zones: List<Zone>,
    outsideLabel: String,
    waitSessions: List<ZoneWaitSession> = emptyList(),
): Pair<List<ZoneStat>, List<ZoneRouteStat>> {

    val pickups  = mutableMapOf<String, MutableList<Ride>>()
    val dropoffs = mutableMapOf<String, MutableList<Ride>>()
    val routes   = mutableMapOf<Pair<String, String>, MutableList<Ride>>()

    rides.forEach { ride ->
        val (sLat, sLng) = rideStartLatLng(ride) ?: (0.0 to 0.0)
        val (eLat, eLng) = rideEndLatLng(ride)   ?: (0.0 to 0.0)

        val fromName = findZone(sLat, sLng, zones)?.name ?: outsideLabel
        val toName   = findZone(eLat, eLng, zones)?.name ?: outsideLabel

        pickups .getOrPut(fromName) { mutableListOf() }.add(ride)
        dropoffs.getOrPut(toName)   { mutableListOf() }.add(ride)
        routes  .getOrPut(fromName to toName) { mutableListOf() }.add(ride)
    }

    val allNames = (pickups.keys + dropoffs.keys).toSet()
    val zoneStats = allNames.map { name ->
        val pr = pickups[name]  ?: emptyList()
        val dr = dropoffs[name] ?: emptyList()
        ZoneStat(
            zoneName     = name,
            zone         = zones.firstOrNull { it.name == name },
            pickupCount  = pr.size,
            dropoffCount = dr.size,
            avgRevenue   = if (pr.isNotEmpty()) pr.sumOf { it.price }      / pr.size else 0.0,
            avgTip       = if (pr.isNotEmpty()) pr.sumOf { it.tip }        / pr.size else 0.0,
            avgKm        = if (pr.isNotEmpty()) pr.sumOf { it.kilometers } / pr.size else 0.0,
            avgWaitMs    = zones.firstOrNull { it.name == name }?.let { computeAverageZoneWaitMs(waitSessions, it.id) } ?: 0L,
            pickupRides  = pr.sortedByDescending { it.startTime },
            dropoffRides = dr.sortedByDescending { it.startTime },
        )
    }

    val routeStats = routes.map { (pair, list) ->
        ZoneRouteStat(
            fromZone   = pair.first,
            toZone     = pair.second,
            count      = list.size,
            avgRevenue = if (list.isNotEmpty()) list.sumOf { it.price } / list.size else 0.0,
        )
    }.sortedByDescending { it.count }

    return zoneStats to routeStats
}
