package com.fbreco.ui.destination

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fbreco.data.entity.Destination
import com.fbreco.data.repository.DestinationRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import org.maplibre.spatialk.geojson.Position
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class DestinationViewModel @Inject constructor(
    private val destinationRepository: DestinationRepository,
) : ViewModel() {

    val activeDestination: StateFlow<Destination?> = destinationRepository
        .getActiveDestination()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    private val _selectedPoint = MutableStateFlow<Position?>(null)
    val selectedPoint: StateFlow<Position?> = _selectedPoint.asStateFlow()

    val startPoint: Position = Position(longitude = 139.6503, latitude = 35.6762)

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
        val distance = calculateStraightLineDistance(startPoint, point)
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
