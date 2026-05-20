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
    return zones
        .sortedByDescending { if (it.parentZoneId > 0L) 1 else 0 }
        .firstOrNull { zone ->
        pointInPolygon(lat, lng, parseZonePoints(zone.pointsJson))
    }
}

fun zoneDisplayName(zone: Zone, zones: List<Zone>): String {
    val parent = zones.firstOrNull { it.id == zone.parentZoneId }
    return if (parent != null) "${parent.name} (${zone.name})" else zone.name
}

fun matchingZoneLabels(lat: Double, lng: Double, zones: List<Zone>, outsideLabel: String): List<String> {
    if (lat == 0.0 && lng == 0.0) return emptyList()
    val matches = zones.filter { zone -> pointInPolygon(lat, lng, parseZonePoints(zone.pointsJson)) }
    if (matches.isEmpty()) return if (outsideLabel.isNotBlank()) listOf(outsideLabel) else emptyList()
    return matches.map { zone -> zoneDisplayName(zone, zones) }.distinct()
}

fun primaryZoneLabel(lat: Double, lng: Double, zones: List<Zone>, outsideLabel: String): String? {
    val zone = findZone(lat, lng, zones)
    return zone?.let { zoneDisplayName(it, zones) } ?: outsideLabel.takeIf { it.isNotBlank() }
}

fun simplifyRideAddress(address: String): String {
    val trimmed = address.trim()
    if (trimmed.isBlank()) return ""

    val firstPart = trimmed.substringBefore(",").trim()
    return if (firstPart.isNotBlank()) firstPart else trimmed
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
        val fromZone = primaryZoneLabel(sLat, sLng, zones, outsideLabel)
        val toZone   = primaryZoneLabel(eLat, eLng, zones, outsideLabel)
        // Use outsideLabel when we have real coordinates but they match no zone
        val fromLabel = fromZone ?: if (startCoords != null) outsideLabel else null
        val toLabel   = toZone   ?: if (endCoords   != null) outsideLabel else null
        when {
            fromLabel != null && toLabel != null -> "$fromLabel → $toLabel"
            fromLabel != null                    -> "$fromLabel →"
            toLabel   != null                    -> "→ $toLabel"
            else                                 -> ""
        }
    } else ""

    val fromAddress = simplifyRideAddress(ride.fromAddress)
    val toAddress = simplifyRideAddress(ride.toAddress)
    val addressLabel = when {
        fromAddress.isNotEmpty() && toAddress.isNotEmpty() -> "$fromAddress → $toAddress"
        fromAddress.isNotEmpty() -> fromAddress
        toAddress.isNotEmpty()   -> toAddress
        else                     -> ""
    }

    return zoneLabel to addressLabel
}

// ── Zone statistics models ────────────────────────────────────────

data class ZoneStat(
    val zoneName: String,
    val zone: Zone?,               // null = "outside zones"
    val pickupCount: Int,
    val dropoffCount: Int,
    val totalRevenue: Double,
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

        val fromNames = matchingZoneLabels(sLat, sLng, zones, outsideLabel)
        val toNames   = matchingZoneLabels(eLat, eLng, zones, outsideLabel)

        fromNames.forEach { fromName -> pickups.getOrPut(fromName) { mutableListOf() }.add(ride) }
        toNames.forEach { toName -> dropoffs.getOrPut(toName) { mutableListOf() }.add(ride) }
        val primaryFrom = primaryZoneLabel(sLat, sLng, zones, outsideLabel) ?: outsideLabel
        val primaryTo   = primaryZoneLabel(eLat, eLng, zones, outsideLabel) ?: outsideLabel
        routes.getOrPut(primaryFrom to primaryTo) { mutableListOf() }.add(ride)
    }

    val allNames = (pickups.keys + dropoffs.keys).toSet()
    val zoneStats = allNames.map { name ->
        val pr = pickups[name]  ?: emptyList()
        val dr = dropoffs[name] ?: emptyList()
        ZoneStat(
            zoneName     = name,
            zone         = zones.firstOrNull { zoneDisplayName(it, zones) == name },
            pickupCount  = pr.size,
            dropoffCount = dr.size,
            totalRevenue = (pr + dr).distinctBy { it.id }.sumOf { it.price + it.tip },
            avgRevenue   = if (pr.isNotEmpty()) pr.sumOf { it.price }      / pr.size else 0.0,
            avgTip       = if (pr.isNotEmpty()) pr.sumOf { it.tip }        / pr.size else 0.0,
            avgKm        = if (pr.isNotEmpty()) pr.sumOf { it.kilometers } / pr.size else 0.0,
            avgWaitMs    = zones.firstOrNull { zoneDisplayName(it, zones) == name }?.let { computeAverageZoneWaitMs(waitSessions, it.id) } ?: 0L,
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
