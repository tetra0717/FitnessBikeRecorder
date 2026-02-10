package com.fbreco.data

import com.fbreco.data.dao.DestinationDao
import com.fbreco.data.entity.Destination
import com.fbreco.data.repository.DestinationRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class DestinationRepositoryTest {

    // ── Fake DAO ─────────────────────────────────────────────────────

    private class FakeDestinationDao : DestinationDao {
        private val store = mutableMapOf<Long, Destination>()
        private var nextId = 1L

        override fun getActive(): Flow<Destination?> =
            MutableStateFlow(store.values.firstOrNull { it.isActive })

        override suspend fun getActiveOnce(): Destination? =
            store.values.firstOrNull { it.isActive }

        override suspend fun insert(destination: Destination): Long {
            val id = nextId++
            store[id] = destination.copy(id = id)
            return id
        }

        override suspend fun update(destination: Destination) {
            store[destination.id] = destination
        }

        override suspend fun complete(id: Long, completedAt: String) {
            store[id]?.let { store[id] = it.copy(isActive = false, completedAt = java.time.LocalDateTime.parse(completedAt)) }
        }

        override suspend fun deleteById(id: Long) {
            store.remove(id)
        }

        override suspend fun addDistance(id: Long, distanceMeters: Double) {
            store[id]?.let {
                store[id] = it.copy(accumulatedDistanceMeters = it.accumulatedDistanceMeters + distanceMeters)
            }
        }

        fun get(id: Long): Destination? = store[id]
    }

    // ── Setup ────────────────────────────────────────────────────────

    private lateinit var fakeDao: FakeDestinationDao
    private lateinit var repository: DestinationRepository

    @Before
    fun setup() {
        fakeDao = FakeDestinationDao()
        repository = DestinationRepository(fakeDao)
    }

    // ── 1. createDestination inserts new destination ─────────────────

    @Test
    fun `createDestination inserts new destination`() = runTest {
        val id = repository.createDestination(
            name = "Tokyo",
            latitude = 35.6762,
            longitude = 139.6503,
            targetDistanceMeters = 100_000.0
        )

        val dest = fakeDao.get(id)
        assertNotNull(dest)
        assertEquals("Tokyo", dest!!.name)
        assertEquals(100_000.0, dest.targetDistanceMeters, 0.001)
        assertTrue(dest.isActive)
        assertEquals(0.0, dest.accumulatedDistanceMeters, 0.001)
    }

    // ── 2. createDestination deactivates existing active ─────────────

    @Test
    fun `createDestination deactivates any existing active destination`() = runTest {
        val firstId = repository.createDestination(
            name = "Tokyo",
            latitude = 35.6762,
            longitude = 139.6503,
            targetDistanceMeters = 100_000.0
        )

        val secondId = repository.createDestination(
            name = "Seoul",
            latitude = 37.5665,
            longitude = 126.9780,
            targetDistanceMeters = 50_000.0
        )

        val first = fakeDao.get(firstId)!!
        assertFalse(first.isActive)
        assertNotNull(first.completedAt)

        val second = fakeDao.get(secondId)!!
        assertTrue(second.isActive)
    }

    // ── 3. addDistance accumulates distance ───────────────────────────

    @Test
    fun `addDistance accumulates distance on active destination`() = runTest {
        val id = repository.createDestination(
            name = "Tokyo",
            latitude = 35.6762,
            longitude = 139.6503,
            targetDistanceMeters = 100_000.0
        )

        repository.addDistance(5_000.0)
        repository.addDistance(3_000.0)

        val dest = fakeDao.get(id)!!
        assertEquals(8_000.0, dest.accumulatedDistanceMeters, 0.001)
        assertTrue(dest.isActive)
    }

    // ── 4. addDistance auto-completes at target ───────────────────────

    @Test
    fun `addDistance auto-completes destination when accumulated reaches target`() = runTest {
        val id = repository.createDestination(
            name = "Nearby",
            latitude = 35.0,
            longitude = 139.0,
            targetDistanceMeters = 1_000.0
        )

        repository.addDistance(1_200.0)

        val dest = fakeDao.get(id)!!
        assertFalse(dest.isActive)
        assertNotNull(dest.completedAt)
        assertEquals(1_200.0, dest.accumulatedDistanceMeters, 0.001)
    }

    // ── 5. addDistance does nothing when no active destination ────────

    @Test
    fun `addDistance does nothing when no active destination exists`() = runTest {
        repository.addDistance(5_000.0)

        val active = repository.getActiveDestinationOnce()
        assertNull(active)
    }

    // ── 6. getActiveDestination returns flow of active ───────────────

    @Test
    fun `getActiveDestination returns flow with current active`() = runTest {
        repository.createDestination(
            name = "Tokyo",
            latitude = 35.6762,
            longitude = 139.6503,
            targetDistanceMeters = 100_000.0
        )

        val result = repository.getActiveDestination().first()
        assertNotNull(result)
        assertEquals("Tokyo", result!!.name)
    }
}
