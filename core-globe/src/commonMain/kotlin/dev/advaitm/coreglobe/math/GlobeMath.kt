package dev.advaitm.coreglobe.math

import dev.advaitm.coreglobe.api.Coordinates
import kotlin.math.*

object GlobeMath {

    fun latLngTo3D(lat: Double, lng: Double, radius: Float = 1f): Triple<Float, Float, Float> {
        val phi   = (90.0 - lat) * PI / 180.0
        val theta = (lng + 180.0) * PI / 180.0
        return Triple(
            (-radius * sin(phi) * cos(theta)).toFloat(),
            (radius  * cos(phi)).toFloat(),
            (radius  * sin(phi) * sin(theta)).toFloat()
        )
    }

    fun greatCircleDistanceKm(a: Coordinates, b: Coordinates): Double {
        val r     = 6371.0
        val dLat  = (b.lat - a.lat) * PI / 180
        val dLng  = (b.lng - a.lng) * PI / 180
        val sinDLat = sin(dLat / 2)
        val sinDLng = sin(dLng / 2)
        val h = sinDLat * sinDLat +
                cos(a.lat * PI / 180) * cos(b.lat * PI / 180) * sinDLng * sinDLng
        return 2 * r * asin(sqrt(h))
    }
}
