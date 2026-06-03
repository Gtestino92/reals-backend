package com.reals.backend.service.matching

import com.reals.backend.domain.MatchmakingCandidatePair
import com.reals.backend.domain.Profile
import org.springframework.stereotype.Component
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

@Component
class SearchLocationMatchFilter {

    fun passes(
        pair: MatchmakingCandidatePair,
        profileA: Profile,
        profileB: Profile
    ): Boolean {
        val distanceKm = distanceKm(
            latitudeA = pair.userALatitude,
            longitudeA = pair.userALongitude,
            latitudeB = pair.userBLatitude,
            longitudeB = pair.userBLongitude
        )

        return distanceKm <= profileA.maxDistanceKm &&
            distanceKm <= profileB.maxDistanceKm
    }

    private fun distanceKm(
        latitudeA: Double,
        longitudeA: Double,
        latitudeB: Double,
        longitudeB: Double
    ): Double {
        val radiusKm = 6371.0
        val deltaLatitude = degreesToRadians(latitudeB - latitudeA)
        val deltaLongitude = degreesToRadians(longitudeB - longitudeA)
        val latA = degreesToRadians(latitudeA)
        val latB = degreesToRadians(latitudeB)

        val haversine =
            sin(deltaLatitude / 2).pow(2) +
                cos(latA) * cos(latB) * sin(deltaLongitude / 2).pow(2)

        val normalizedHaversine = haversine.coerceIn(0.0, 1.0)

        return radiusKm * 2 * atan2(
            sqrt(normalizedHaversine),
            sqrt(1 - normalizedHaversine)
        )
    }

    private fun degreesToRadians(value: Double): Double =
        value * PI / 180.0
}
