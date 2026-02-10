package com.fbreco.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.time.LocalDate

@Entity(tableName = "daily_records")
data class DailyRecord(
    @PrimaryKey
    val date: LocalDate,
    val totalTimeSeconds: Long = 0L,
    val totalDistanceMeters: Double = 0.0
)
