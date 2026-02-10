package com.fbreco.data.repository

import com.fbreco.data.dao.DailyRecordDao
import com.fbreco.data.entity.DailyRecord
import kotlinx.coroutines.flow.Flow
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DailyRecordRepository @Inject constructor(
    private val dailyRecordDao: DailyRecordDao
) {
    suspend fun getByDate(date: LocalDate): DailyRecord? = dailyRecordDao.getByDate(date)

    fun observeToday(): Flow<DailyRecord?> = dailyRecordDao.observeByDate(LocalDate.now())

    fun getRange(startDate: LocalDate, endDate: LocalDate): Flow<List<DailyRecord>> =
        dailyRecordDao.getRange(startDate, endDate)

    fun getAll(): Flow<List<DailyRecord>> = dailyRecordDao.getAll()

    /**
     * Add riding time and distance to today's record.
     * Creates record if it doesn't exist (upsert).
     */
    suspend fun addRidingData(date: LocalDate, additionalTimeSeconds: Long, additionalDistanceMeters: Double) {
        val existing = dailyRecordDao.getByDate(date) ?: DailyRecord(date = date)
        dailyRecordDao.upsert(
            existing.copy(
                totalTimeSeconds = existing.totalTimeSeconds + additionalTimeSeconds,
                totalDistanceMeters = existing.totalDistanceMeters + additionalDistanceMeters
            )
        )
    }
}
