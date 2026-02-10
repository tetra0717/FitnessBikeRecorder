package com.fbreco.ui.destination

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
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

    val cameraState = rememberCameraState(
        firstPosition = CameraPosition(
            target = Position(longitude = 139.6503, latitude = 35.6762),
            zoom = 5.0,
        ),
    )

    val pointsGeoJson = remember(selectedPoint, activeDestination) {
        buildPointsGeoJson(selectedPoint, activeDestination, viewModel.startPoint)
    }

    val lineGeoJson = remember(activeDestination) {
        buildLineGeoJson(activeDestination, viewModel.startPoint)
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
        }

        if (activeDestination == null && selectedPoint == null) {
            Card(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(16.dp),
            ) {
                Text(
                    text = "\u5730\u56F3\u3092\u9577\u62BC\u3057\u3057\u3066\u76EE\u7684\u5730\u3092\u8A2D\u5B9A",
                    modifier = Modifier.padding(16.dp),
                    style = MaterialTheme.typography.titleMedium,
                )
            }
        }

        activeDestination?.let { dest ->
            Card(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(16.dp),
            ) {
                Text(
                    text = "${String.format("%.1f", dest.accumulatedDistanceMeters / 1000.0)} km / " +
                        "${String.format("%.1f", dest.targetDistanceMeters / 1000.0)} km " +
                        "(${String.format("%.0f", progressPercent)}%)",
                    modifier = Modifier.padding(16.dp),
                    style = MaterialTheme.typography.titleMedium,
                )
            }
        }
    }

    selectedPoint?.let { point ->
        val distance = viewModel.calculateStraightLineDistance(viewModel.startPoint, point)
        AlertDialog(
            onDismissRequest = { viewModel.clearSelection() },
            title = { Text("\u76EE\u7684\u5730\u306E\u78BA\u8A8D") },
            text = {
                Text(
                    "\u3053\u306E\u5834\u6240\u3092\u76EE\u7684\u5730\u306B\u8A2D\u5B9A\u3057\u307E\u3059\u304B\uFF1F\n" +
                        "\u76F4\u7DDA\u8DDD\u96E2: ${String.format("%.1f", distance / 1000.0)} km",
                )
            },
            confirmButton = {
                TextButton(onClick = { viewModel.confirmDestination() }) {
                    Text("\u3053\u306E\u5834\u6240\u3092\u76EE\u7684\u5730\u306B\u3059\u308B")
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.clearSelection() }) {
                    Text("\u30AD\u30E3\u30F3\u30BB\u30EB")
                }
            },
        )
    }

    if (isCompleted) {
        AlertDialog(
            onDismissRequest = { },
            title = { Text("\uD83C\uDF89 \u76EE\u7684\u5730\u306B\u5230\u9054\uFF01") },
            text = {
                Text(
                    "\u304A\u3081\u3067\u3068\u3046\u3054\u3056\u3044\u307E\u3059\uFF01\n" +
                        "\u76EE\u6A19\u8DDD\u96E2\u3092\u9054\u6210\u3057\u307E\u3057\u305F\uFF01\uFF08${String.format("%.0f", progressPercent)}%\uFF09",
                )
            },
            confirmButton = {
                TextButton(onClick = { viewModel.completeAndReset() }) {
                    Text("\u65B0\u3057\u3044\u76EE\u7684\u5730\u3092\u8A2D\u5B9A")
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.completeAndReset() }) {
                    Text("\u9589\u3058\u308B")
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

private fun pointFeature(lng: Double, lat: Double): String =
    """{"type":"Feature","geometry":{"type":"Point","coordinates":[$lng,$lat]}}"""
