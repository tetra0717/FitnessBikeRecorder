package com.fbreco.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.fbreco.data.entity.Destination
import kotlinx.coroutines.flow.Flow

@Dao
interface DestinationDao {
    @Query("SELECT * FROM destinations WHERE isActive = 1 LIMIT 1")
    fun getActive(): Flow<Destination?>

    @Query("SELECT * FROM destinations WHERE isActive = 1 LIMIT 1")
    suspend fun getActiveOnce(): Destination?

    @Insert
    suspend fun insert(destination: Destination): Long

    @Update
    suspend fun update(destination: Destination)

    @Query("UPDATE destinations SET isActive = 0, completedAt = :completedAt WHERE id = :id")
    suspend fun complete(id: Long, completedAt: String)

    @Query("DELETE FROM destinations WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("UPDATE destinations SET accumulatedDistanceMeters = accumulatedDistanceMeters + :distanceMeters WHERE id = :id")
    suspend fun addDistance(id: Long, distanceMeters: Double)
}
