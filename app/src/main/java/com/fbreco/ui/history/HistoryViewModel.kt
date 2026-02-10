package com.fbreco.ui.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fbreco.data.entity.DailyRecord
import com.fbreco.data.repository.DailyRecordRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.time.LocalDate
import java.time.temporal.WeekFields
import javax.inject.Inject

data class WeeklySummary(
    val weekStart: LocalDate,
    val totalTimeSeconds: Long,
    val totalDistanceMeters: Double,
    val dayCount: Int,
)

@HiltViewModel
class HistoryViewModel @Inject constructor(
    dailyRecordRepository: DailyRecordRepository,
) : ViewModel() {

    val allRecords: StateFlow<List<DailyRecord>> = dailyRecordRepository.getAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val weeklySummaries: StateFlow<List<WeeklySummary>> = dailyRecordRepository.getAll()
        .map { records -> aggregateByWeek(records) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private fun aggregateByWeek(records: List<DailyRecord>): List<WeeklySummary> {
        val weekFields = WeekFields.ISO
        return records
            .groupBy { record ->
                val year = record.date.get(weekFields.weekBasedYear())
                val week = record.date.get(weekFields.weekOfWeekBasedYear())
                year to week
            }
            .map { (_, weekRecords) ->
                val weekStart = weekRecords.minOf { it.date }
                    .with(weekFields.dayOfWeek(), 1)
                WeeklySummary(
                    weekStart = weekStart,
                    totalTimeSeconds = weekRecords.sumOf { it.totalTimeSeconds },
                    totalDistanceMeters = weekRecords.sumOf { it.totalDistanceMeters },
                    dayCount = weekRecords.size,
                )
            }
            .sortedByDescending { it.weekStart }
    }
}
