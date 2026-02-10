package com.fbreco.data

import com.fbreco.data.dao.DailyRecordDao
import com.fbreco.data.entity.DailyRecord
import com.fbreco.data.repository.DailyRecordRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.time.LocalDate

class DailyRecordRepositoryTest {

    // ── Fake DAO ─────────────────────────────────────────────────────

    private class FakeDailyRecordDao : DailyRecordDao {
        val records = mutableMapOf<LocalDate, DailyRecord>()

        override suspend fun getByDate(date: LocalDate): DailyRecord? = records[date]

        override fun observeByDate(date: LocalDate): Flow<DailyRecord?> =
            MutableStateFlow(records[date])

        override fun getRange(startDate: LocalDate, endDate: LocalDate): Flow<List<DailyRecord>> =
            flowOf(
                records.values
                    .filter { it.date in startDate..endDate }
                    .sortedByDescending { it.date }
            )

        override fun getAll(): Flow<List<DailyRecord>> =
            flowOf(records.values.sortedByDescending { it.date })

        override suspend fun upsert(record: DailyRecord) {
            records[record.date] = record
        }
    }

    // ── Setup ────────────────────────────────────────────────────────

    private lateinit var fakeDao: FakeDailyRecordDao
    private lateinit var repository: DailyRecordRepository

    @Before
    fun setup() {
        fakeDao = FakeDailyRecordDao()
        repository = DailyRecordRepository(fakeDao)
    }

    // ── 1. addRidingData creates new record when none exists ─────────

    @Test
    fun `addRidingData creates new record when none exists`() = runTest {
        val date = LocalDate.of(2025, 6, 15)

        repository.addRidingData(date, additionalTimeSeconds = 300, additionalDistanceMeters = 1500.0)

        val record = fakeDao.records[date]
        assertNotNull(record)
        assertEquals(300L, record!!.totalTimeSeconds)
        assertEquals(1500.0, record.totalDistanceMeters, 0.001)
    }

    // ── 2. addRidingData accumulates into existing record ────────────

    @Test
    fun `addRidingData accumulates into existing record`() = runTest {
        val date = LocalDate.of(2025, 6, 15)

        repository.addRidingData(date, additionalTimeSeconds = 300, additionalDistanceMeters = 1500.0)
        repository.addRidingData(date, additionalTimeSeconds = 200, additionalDistanceMeters = 1000.0)

        val record = fakeDao.records[date]
        assertNotNull(record)
        assertEquals(500L, record!!.totalTimeSeconds)
        assertEquals(2500.0, record.totalDistanceMeters, 0.001)
    }

    // ── 3. Different dates create separate records (midnight boundary) ─

    @Test
    fun `addRidingData for different dates creates separate records`() = runTest {
        val day1 = LocalDate.of(2025, 6, 15)
        val day2 = LocalDate.of(2025, 6, 16)

        repository.addRidingData(day1, additionalTimeSeconds = 300, additionalDistanceMeters = 1500.0)
        repository.addRidingData(day2, additionalTimeSeconds = 600, additionalDistanceMeters = 3000.0)

        assertEquals(2, fakeDao.records.size)

        val record1 = fakeDao.records[day1]!!
        assertEquals(300L, record1.totalTimeSeconds)
        assertEquals(1500.0, record1.totalDistanceMeters, 0.001)

        val record2 = fakeDao.records[day2]!!
        assertEquals(600L, record2.totalTimeSeconds)
        assertEquals(3000.0, record2.totalDistanceMeters, 0.001)
    }

    // ── 4. observeToday returns Flow of today's record ───────────────

    @Test
    fun `observeToday returns flow of today's record`() = runTest {
        val today = LocalDate.now()
        fakeDao.records[today] = DailyRecord(
            date = today,
            totalTimeSeconds = 120,
            totalDistanceMeters = 500.0
        )

        val result = repository.observeToday().first()

        assertNotNull(result)
        assertEquals(120L, result!!.totalTimeSeconds)
        assertEquals(500.0, result.totalDistanceMeters, 0.001)
    }

    // ── 5. getRange returns correct date window ──────────────────────

    @Test
    fun `getRange returns records within date window only`() = runTest {
        val day1 = LocalDate.of(2025, 6, 10)
        val day2 = LocalDate.of(2025, 6, 15)
        val day3 = LocalDate.of(2025, 6, 20)

        fakeDao.records[day1] = DailyRecord(date = day1, totalTimeSeconds = 100, totalDistanceMeters = 400.0)
        fakeDao.records[day2] = DailyRecord(date = day2, totalTimeSeconds = 200, totalDistanceMeters = 800.0)
        fakeDao.records[day3] = DailyRecord(date = day3, totalTimeSeconds = 300, totalDistanceMeters = 1200.0)

        val results = repository.getRange(
            LocalDate.of(2025, 6, 8),
            LocalDate.of(2025, 6, 17)
        ).first()

        assertEquals(2, results.size)
        assertTrue(results.any { it.date == day1 })
        assertTrue(results.any { it.date == day2 })
        assertFalse(results.any { it.date == day3 })
    }
}
