package com.fbreco.ui.home

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.fbreco.ble.BleConnectionState
import com.fbreco.service.RideSnapshot

@Composable
fun HomeScreen(
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val connectionState by viewModel.connectionState.collectAsState()
    val todayRecord by viewModel.todayRecord.collectAsState()
    val isActive by viewModel.isActive.collectAsState()
    val isScanning by viewModel.isScanning.collectAsState()
    val discoveredDevices by viewModel.discoveredDevices.collectAsState()
    val serviceRunning by viewModel.serviceRunning.collectAsState()
    val snapshot by viewModel.serviceSnapshot.collectAsState()
    var showScanDialog by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // ── 1. App Title ────────────────────────────────────────────────
        Text(
            text = "FBReco",
            style = MaterialTheme.typography.headlineLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground,
        )

        Spacer(modifier = Modifier.height(20.dp))

        // ── 2. Connection Status Badge ──────────────────────────────────
        ConnectionStatusBadge(state = connectionState)

        Spacer(modifier = Modifier.height(16.dp))

        // ── 2b. Scan Button / Connected Info ─────────────────────────────
        val connectedState = connectionState as? BleConnectionState.Connected
        if (connectedState != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "接続済み: ${connectedState.deviceName ?: "不明"}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color(0xFF2E7D32),
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(modifier = Modifier.width(12.dp))
                TextButton(onClick = { viewModel.disconnectDevice() }) {
                    Text("切断")
                }
            }
        } else {
            FilledTonalButton(onClick = {
                showScanDialog = true
                viewModel.startScan()
            }) {
                Text("バイクを探す")
            }
        }

        Spacer(modifier = Modifier.height(28.dp))

         // ── 3. Today's Stats Card ───────────────────────────────────────
         TodayStatsCard(
             totalTimeSeconds = if (serviceRunning) snapshot.totalTimeSeconds else (todayRecord?.totalTimeSeconds ?: 0L),
             totalDistanceMeters = if (serviceRunning) snapshot.totalDistanceMeters else (todayRecord?.totalDistanceMeters ?: 0.0),
         )

        Spacer(modifier = Modifier.height(20.dp))

        // ── 4. Activity Indicator ───────────────────────────────────────
        ActivityIndicator(isActive = isActive)
    }

    if (showScanDialog) {
        ScanDialog(
            isScanning = isScanning,
            discoveredDevices = discoveredDevices,
            onDeviceSelected = { address ->
                showScanDialog = false
                viewModel.connectToDevice(address)
            },
            onDismiss = { showScanDialog = false },
        )
    }
}

// ── Connection Status Badge ─────────────────────────────────────────────────

private data class StatusInfo(val label: String, val color: Color)

private fun resolveStatus(state: BleConnectionState): StatusInfo = when (state) {
    is BleConnectionState.Connected -> StatusInfo("接続中", Color(0xFF2E7D32))
    is BleConnectionState.Disconnected -> StatusInfo("未接続", Color(0xFF757575))
    is BleConnectionState.Scanning -> StatusInfo("スキャン中", Color(0xFF1565C0))
    is BleConnectionState.Connecting -> StatusInfo("接続待機中", Color(0xFFE65100))
    is BleConnectionState.Reconnecting -> StatusInfo("再接続中", Color(0xFFF9A825))
}

@Composable
private fun ConnectionStatusBadge(state: BleConnectionState) {
    val status = resolveStatus(state)

    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(status.color.copy(alpha = 0.12f))
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(status.color),
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = status.label,
            style = MaterialTheme.typography.labelLarge,
            color = status.color,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

// ── Today's Stats Card ──────────────────────────────────────────────────────

@Composable
private fun TodayStatsCard(
    totalTimeSeconds: Long,
    totalDistanceMeters: Double,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
        ) {
            Text(
                text = "今日の記録",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(modifier = Modifier.height(16.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                StatItem(
                    label = "走行時間",
                    value = formatTime(totalTimeSeconds),
                )
                StatItem(
                    label = "走行距離",
                    value = "${formatDistance(totalDistanceMeters)} km",
                )
            }
        }
    }
}

@Composable
private fun StatItem(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            letterSpacing = 1.sp,
        )
    }
}

// ── Activity Indicator ──────────────────────────────────────────────────────

@Composable
private fun ActivityIndicator(isActive: Boolean) {
    AnimatedVisibility(
        visible = isActive,
        enter = fadeIn(),
        exit = fadeOut(),
    ) {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.primaryContainer)
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text = "\uD83D\uDEB4", fontSize = 20.sp)
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "走行中",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
    }
}

// ── Format Helpers ──────────────────────────────────────────────────────────

internal fun formatTime(totalSeconds: Long): String {
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return String.format("%02d:%02d:%02d", hours, minutes, seconds)
}

internal fun formatDistance(meters: Double): String {
    return String.format("%.2f", meters / 1000.0)
}
