package com.fbreco.ui.destination

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fbreco.data.GeocodingResult
import com.fbreco.data.GeocodingService
import com.fbreco.data.entity.Destination
import com.fbreco.data.repository.DestinationRepository
import com.fbreco.service.BikeForegroundService
import com.fbreco.service.RideSnapshot
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.Priority
import dagger.hilt.android.lifecycle.HiltViewModel
import org.maplibre.spatialk.geojson.Position
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import javax.inject.Inject

@HiltViewModel
class DestinationViewModel @Inject constructor(
    private val destinationRepository: DestinationRepository,
    private val fusedLocationClient: FusedLocationProviderClient,
    private val geocodingService: GeocodingService,
) : ViewModel() {

    val activeDestination: StateFlow<Destination?> = destinationRepository
        .getActiveDestination()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    private val _selectedPoint = MutableStateFlow<Position?>(null)
    val selectedPoint: StateFlow<Position?> = _selectedPoint.asStateFlow()

    private val _startPoint = MutableStateFlow(Position(longitude = 139.6503, latitude = 35.6762))
    val startPoint: StateFlow<Position> = _startPoint.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _searchResults = MutableStateFlow<List<GeocodingResult>>(emptyList())
    val searchResults: StateFlow<List<GeocodingResult>> = _searchResults.asStateFlow()

    private val _isSearching = MutableStateFlow(false)
    val isSearching: StateFlow<Boolean> = _isSearching.asStateFlow()

    val serviceSnapshot: StateFlow<RideSnapshot> = BikeForegroundService.currentSnapshot

    val progressPercent: StateFlow<Double> = activeDestination
        .map { dest ->
            if (dest == null || dest.targetDistanceMeters <= 0.0) {
                0.0
            } else {
                (dest.accumulatedDistanceMeters / dest.targetDistanceMeters * 100.0)
                    .coerceIn(0.0, 100.0)
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0.0)

    val isCompleted: StateFlow<Boolean> = activeDestination
        .map { dest ->
            dest != null && dest.isActive &&
                dest.accumulatedDistanceMeters >= dest.targetDistanceMeters
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    init {
        // Fetch current device location, fall back to Tokyo on failure
        viewModelScope.launch {
            try {
                val location = fusedLocationClient.getCurrentLocation(
                    Priority.PRIORITY_BALANCED_POWER_ACCURACY,
                    null
                ).await()
                if (location != null) {
                    _startPoint.value = Position(
                        longitude = location.longitude,
                        latitude = location.latitude
                    )
                }
            } catch (_: SecurityException) {
                // Permission not granted — keep Tokyo fallback
            } catch (_: Exception) {
                // Any other error — keep Tokyo fallback
            }
        }

        // Debounced search trigger
        viewModelScope.launch {
            @OptIn(FlowPreview::class)
            _searchQuery.debounce(300).collectLatest { query ->
                if (query.length >= 2) {
                    _isSearching.value = true
                    _searchResults.value = geocodingService.search(query)
                    _isSearching.value = false
                } else {
                    _searchResults.value = emptyList()
                }
            }
        }
    }

    fun completeAndReset() {
        val dest = activeDestination.value ?: return
        viewModelScope.launch {
            destinationRepository.completeDestination(dest.id)
        }
    }

    fun onMapLongClick(position: Position) {
        _selectedPoint.value = position
    }

    fun clearSelection() {
        _selectedPoint.value = null
    }

    fun confirmDestination() {
        val point = _selectedPoint.value ?: return
        val distance = calculateStraightLineDistance(_startPoint.value, point)
        viewModelScope.launch {
            destinationRepository.createDestination(
                name = "\u76EE\u7684\u5730",
                latitude = point.latitude,
                longitude = point.longitude,
                targetDistanceMeters = distance,
            )
            _selectedPoint.value = null
        }
    }

    fun updateSearchQuery(query: String) {
        _searchQuery.value = query
    }

    fun selectSearchResult(result: GeocodingResult) {
        _selectedPoint.value = Position(
            longitude = result.longitude,
            latitude = result.latitude
        )
        _searchQuery.value = ""
        _searchResults.value = emptyList()
    }

    fun clearSearch() {
        _searchQuery.value = ""
        _searchResults.value = emptyList()
    }

    fun resetDestination() {
        val dest = activeDestination.value ?: return
        viewModelScope.launch {
            destinationRepository.completeDestination(dest.id)
        }
    }

    fun calculateStraightLineDistance(from: Position, to: Position): Double {
        val r = 6_371_000.0
        val lat1 = Math.toRadians(from.latitude)
        val lat2 = Math.toRadians(to.latitude)
        val dLat = Math.toRadians(to.latitude - from.latitude)
        val dLng = Math.toRadians(to.longitude - from.longitude)
        val a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
            Math.cos(lat1) * Math.cos(lat2) *
            Math.sin(dLng / 2) * Math.sin(dLng / 2)
        val c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
        return r * c
    }
}
