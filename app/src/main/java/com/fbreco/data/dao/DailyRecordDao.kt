package com.fbreco.data.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.fbreco.data.entity.DailyRecord
import kotlinx.coroutines.flow.Flow
import java.time.LocalDate

@Dao
interface DailyRecordDao {
    @Query("SELECT * FROM daily_records WHERE date = :date")
    suspend fun getByDate(date: LocalDate): DailyRecord?

    @Query("SELECT * FROM daily_records WHERE date = :date")
    fun observeByDate(date: LocalDate): Flow<DailyRecord?>

    @Query("SELECT * FROM daily_records WHERE date BETWEEN :startDate AND :endDate ORDER BY date DESC")
    fun getRange(startDate: LocalDate, endDate: LocalDate): Flow<List<DailyRecord>>

    @Query("SELECT * FROM daily_records ORDER BY date DESC")
    fun getAll(): Flow<List<DailyRecord>>

    @Upsert
    suspend fun upsert(record: DailyRecord)
}
