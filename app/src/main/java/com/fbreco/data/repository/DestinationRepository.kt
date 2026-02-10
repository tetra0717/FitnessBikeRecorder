package com.fbreco.data.repository

import com.fbreco.data.dao.DestinationDao
import com.fbreco.data.entity.Destination
import kotlinx.coroutines.flow.Flow
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DestinationRepository @Inject constructor(
    private val destinationDao: DestinationDao
) {
    fun getActiveDestination(): Flow<Destination?> = destinationDao.getActive()

    suspend fun getActiveDestinationOnce(): Destination? = destinationDao.getActiveOnce()

    suspend fun createDestination(name: String, latitude: Double, longitude: Double, targetDistanceMeters: Double): Long {
        // Deactivate any existing active destination first
        val existing = destinationDao.getActiveOnce()
        if (existing != null) {
            destinationDao.complete(
                id = existing.id,
                completedAt = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
            )
        }
        return destinationDao.insert(
            Destination(
                name = name,
                latitude = latitude,
                longitude = longitude,
                targetDistanceMeters = targetDistanceMeters
            )
        )
    }

    suspend fun addDistance(distanceMeters: Double) {
        val active = destinationDao.getActiveOnce() ?: return
        destinationDao.addDistance(active.id, distanceMeters)

        // Check if destination is completed
        val updated = destinationDao.getActiveOnce()
        if (updated != null && updated.accumulatedDistanceMeters >= updated.targetDistanceMeters) {
            destinationDao.complete(
                id = updated.id,
                completedAt = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
            )
        }
    }

    suspend fun completeDestination(id: Long) {
        destinationDao.complete(
            id = id,
            completedAt = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
        )
    }

    suspend fun deleteDestination(id: Long) {
        destinationDao.deleteById(id)
    }
}
