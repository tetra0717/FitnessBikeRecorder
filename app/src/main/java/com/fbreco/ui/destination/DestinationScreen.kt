package com.fbreco.ui.destination

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import org.maplibre.spatialk.geojson.Position
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.compose.camera.rememberCameraState
import org.maplibre.compose.expressions.dsl.const
import org.maplibre.compose.expressions.value.LineCap
import org.maplibre.compose.expressions.value.LineJoin
import org.maplibre.compose.layers.CircleLayer
import org.maplibre.compose.layers.LineLayer
import org.maplibre.compose.map.MaplibreMap
import org.maplibre.compose.sources.GeoJsonData
import org.maplibre.compose.sources.rememberGeoJsonSource
import org.maplibre.compose.style.BaseStyle
import org.maplibre.compose.util.ClickResult

@Composable
fun DestinationScreen(
    viewModel: DestinationViewModel = hiltViewModel(),
) {
    val activeDestination by viewModel.activeDestination.collectAsState()
    val selectedPoint by viewModel.selectedPoint.collectAsState()
    val progressPercent by viewModel.progressPercent.collectAsState()
    val isCompleted by viewModel.isCompleted.collectAsState()
    val startPoint by viewModel.startPoint.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()
    val searchResults by viewModel.searchResults.collectAsState()
    val isSearching by viewModel.isSearching.collectAsState()
    val snapshot by viewModel.serviceSnapshot.collectAsState()

    val cameraState = rememberCameraState(
        firstPosition = CameraPosition(
            target = Position(longitude = 139.6503, latitude = 35.6762),
            zoom = 5.0,
        ),
    )

    LaunchedEffect(startPoint) {
        cameraState.animateTo(CameraPosition(target = startPoint, zoom = 5.0))
    }

    val pointsGeoJson = remember(selectedPoint, activeDestination, startPoint) {
        buildPointsGeoJson(selectedPoint, activeDestination, startPoint)
    }

    val lineGeoJson = remember(activeDestination, startPoint) {
        buildLineGeoJson(activeDestination, startPoint)
    }

    val progressMarkerGeoJson = remember(startPoint, activeDestination, snapshot) {
        buildProgressMarkerGeoJson(startPoint, activeDestination, snapshot)
    }

    Box(modifier = Modifier.fillMaxSize()) {
        MaplibreMap(
            modifier = Modifier.fillMaxSize(),
            cameraState = cameraState,
            baseStyle = BaseStyle.Uri("https://tiles.openfreemap.org/styles/liberty"),
            onMapLongClick = { pos, _ ->
                if (activeDestination == null) {
                    viewModel.onMapLongClick(pos)
                }
                ClickResult.Pass
            },
        ) {
            val pointSource = rememberGeoJsonSource(data = GeoJsonData.JsonString(pointsGeoJson))
            val lineSource = rememberGeoJsonSource(data = GeoJsonData.JsonString(lineGeoJson))
            val markerSource = rememberGeoJsonSource(data = GeoJsonData.JsonString(progressMarkerGeoJson))

            LineLayer(
                id = "route-line",
                source = lineSource,
                color = const(Color(0xFF4488FF)),
                width = const(3.dp),
                cap = const(LineCap.Round),
                join = const(LineJoin.Round),
                opacity = const(0.6f),
            )

            CircleLayer(
                id = "points-circle",
                source = pointSource,
                color = const(Color.Red),
                radius = const(10.dp),
                strokeColor = const(Color.White),
                strokeWidth = const(2.dp),
            )

            CircleLayer(
                id = "progress-marker",
                source = markerSource,
                color = const(Color(0xFF00CC00)),
                radius = const(12.dp),
                strokeColor = const(Color.White),
                strokeWidth = const(3.dp),
            )
        }

        if (activeDestination == null && selectedPoint == null) {
            Card(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(16.dp)
                    .fillMaxWidth(),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { viewModel.updateSearchQuery(it) },
                        label = { Text("目的地を検索") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                    if (isSearching) {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            CircularProgressIndicator(
                                modifier = Modifier
                                    .padding(8.dp)
                                    .align(Alignment.CenterHorizontally),
                            )
                        }
                    }
                    if (searchResults.isNotEmpty()) {
                        LazyColumn(modifier = Modifier.height(200.dp)) {
                            items(searchResults) { result ->
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { viewModel.selectSearchResult(result) }
                                        .padding(vertical = 8.dp, horizontal = 4.dp),
                                ) {
                                    Text(
                                        text = result.name,
                                        style = MaterialTheme.typography.bodyLarge,
                                    )
                                    val subtitle = listOfNotNull(result.city, result.country).joinToString(", ")
                                    if (subtitle.isNotEmpty()) {
                                        Text(
                                            text = subtitle,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                                HorizontalDivider()
                            }
                        }
                    } else if (searchQuery.isEmpty()) {
                        Text(
                            text = "地図を長押し、または検索で目的地を設定",
                            modifier = Modifier.padding(top = 8.dp),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        activeDestination?.let { dest ->
            var showResetDialog by remember { mutableStateOf(false) }
            val liveDistance = dest.accumulatedDistanceMeters + snapshot.accumulatorDistanceMeters
            val livePercent = (liveDistance / dest.targetDistanceMeters * 100.0).coerceIn(0.0, 100.0)

            Card(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(16.dp),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "${String.format("%.1f", liveDistance / 1000.0)} km / " +
                            "${String.format("%.1f", dest.targetDistanceMeters / 1000.0)} km " +
                            "(${String.format("%.0f", livePercent)}%)",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    TextButton(onClick = { showResetDialog = true }) {
                        Text("目的地をリセット")
                    }
                }
            }

            if (showResetDialog) {
                AlertDialog(
                    onDismissRequest = { showResetDialog = false },
                    title = { Text("目的地のリセット") },
                    text = { Text("現在の目的地をリセットしますか？\n進捗は失われます。") },
                    confirmButton = {
                        TextButton(onClick = {
                            viewModel.resetDestination()
                            showResetDialog = false
                        }) {
                            Text("リセット")
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { showResetDialog = false }) {
                            Text("キャンセル")
                        }
                    },
                )
            }
        }
    }

    selectedPoint?.let { point ->
        val distance = viewModel.calculateStraightLineDistance(startPoint, point)
        AlertDialog(
            onDismissRequest = { viewModel.clearSelection() },
            title = { Text("目的地の確認") },
            text = {
                Text(
                    "この場所を目的地に設定しますか？\n" +
                        "直線距離: ${String.format("%.1f", distance / 1000.0)} km",
                )
            },
            confirmButton = {
                TextButton(onClick = { viewModel.confirmDestination() }) {
                    Text("この場所を目的地にする")
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.clearSelection() }) {
                    Text("キャンセル")
                }
            },
        )
    }

    if (isCompleted) {
        AlertDialog(
            onDismissRequest = { },
            title = { Text("🎉 目的地に到達！") },
            text = {
                Text(
                    "おめでとうございます！\n" +
                        "目標距離を達成しました！（${String.format("%.0f", progressPercent)}%）",
                )
            },
            confirmButton = {
                TextButton(onClick = { viewModel.completeAndReset() }) {
                    Text("新しい目的地を設定")
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.completeAndReset() }) {
                    Text("閉じる")
                }
            },
        )
    }
}

private fun buildPointsGeoJson(
    selectedPoint: Position?,
    activeDestination: com.fbreco.data.entity.Destination?,
    startPoint: Position,
): String {
    val features = mutableListOf<String>()

    selectedPoint?.let { p ->
        features.add(pointFeature(p.longitude, p.latitude))
    }

    activeDestination?.let { dest ->
        features.add(pointFeature(startPoint.longitude, startPoint.latitude))
        features.add(pointFeature(dest.longitude, dest.latitude))
    }

    return """{"type":"FeatureCollection","features":[${features.joinToString(",")}]}"""
}

private fun buildLineGeoJson(
    activeDestination: com.fbreco.data.entity.Destination?,
    startPoint: Position,
): String {
    val dest = activeDestination ?: return """{"type":"FeatureCollection","features":[]}"""
    return """{"type":"FeatureCollection","features":[{"type":"Feature","geometry":{"type":"LineString","coordinates":[[${startPoint.longitude},${startPoint.latitude}],[${dest.longitude},${dest.latitude}]]}}]}"""
}

private fun buildProgressMarkerGeoJson(
    startPoint: Position,
    activeDestination: com.fbreco.data.entity.Destination?,
    snapshot: com.fbreco.service.RideSnapshot,
): String {
    val dest = activeDestination ?: return """{"type":"FeatureCollection","features":[]}"""
    val totalDistance = dest.accumulatedDistanceMeters + snapshot.accumulatorDistanceMeters
    val ratio = (totalDistance / dest.targetDistanceMeters).coerceIn(0.0, 1.0)
    val lat = startPoint.latitude + (dest.latitude - startPoint.latitude) * ratio
    val lng = startPoint.longitude + (dest.longitude - startPoint.longitude) * ratio
    return """{"type":"FeatureCollection","features":[${pointFeature(lng, lat)}]}"""
}

private fun pointFeature(lng: Double, lat: Double): String =
    """{"type":"Feature","geometry":{"type":"Point","coordinates":[$lng,$lat]}}"""

