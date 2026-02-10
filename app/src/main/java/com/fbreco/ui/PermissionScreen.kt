package com.fbreco.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat

private enum class PermissionStep {
    Explanation,
    Denied,
    BatteryOptimization,
}

// ── Required permissions (API-level dependent) ──────────────────────────────

internal fun requiredPermissions(): Array<String> =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        arrayOf(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.POST_NOTIFICATIONS,
        )
    } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        arrayOf(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.ACCESS_FINE_LOCATION,
        )
    } else {
        arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
        )
    }

internal fun allPermissionsGranted(context: Context): Boolean =
    requiredPermissions().all { permission ->
        ContextCompat.checkSelfPermission(context, permission) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
    }

// ── Composable ──────────────────────────────────────────────────────────────

@Composable
fun PermissionScreen(onAllPermissionsGranted: () -> Unit) {
    val context = LocalContext.current

    var step by remember { mutableStateOf(PermissionStep.Explanation) }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
    ) { results ->
        val allGranted = results.values.all { it }
        if (allGranted) {
            step = PermissionStep.BatteryOptimization
        } else {
            step = PermissionStep.Denied
        }
    }

    val settingsLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
    ) {
        if (allPermissionsGranted(context)) {
            step = PermissionStep.BatteryOptimization
        }
    }

    when (step) {
        PermissionStep.Explanation -> ExplanationContent(
            onAllow = { permissionLauncher.launch(requiredPermissions()) },
        )

        PermissionStep.Denied -> DeniedContent(
            onRetry = { permissionLauncher.launch(requiredPermissions()) },
            onOpenSettings = {
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.fromParts("package", context.packageName, null)
                }
                settingsLauncher.launch(intent)
            },
        )

        PermissionStep.BatteryOptimization -> BatteryOptimizationContent(
            onRequest = {
                val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
                if (!pm.isIgnoringBatteryOptimizations(context.packageName)) {
                    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = Uri.parse("package:${context.packageName}")
                    }
                    context.startActivity(intent)
                }
                onAllPermissionsGranted()
            },
            onSkip = { onAllPermissionsGranted() },
        )
    }
}

// ── Step 1: Explanation ─────────────────────────────────────────────────────

@Composable
private fun ExplanationContent(onAllow: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 28.dp, vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = "\uD83D\uDD11",
            fontSize = 48.sp,
        )

        Spacer(modifier = Modifier.height(24.dp))

        // 権限の設定
        Text(
            text = "\u6A29\u9650\u306E\u8A2D\u5B9A",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground,
        )

        Spacer(modifier = Modifier.height(16.dp))

        // このアプリはBLEフィットネスバイクに接続するため、Bluetooth権限と位置情報の権限が必要です。
        Text(
            text = "\u3053\u306E\u30A2\u30D7\u30EA\u306FBLE\u30D5\u30A3\u30C3\u30C8\u30CD\u30B9\u30D0\u30A4\u30AF\u306B\u63A5\u7D9A\u3059\u308B\u305F\u3081\u3001Bluetooth\u6A29\u9650\u3068\u4F4D\u7F6E\u60C5\u5831\u306E\u6A29\u9650\u304C\u5FC5\u8981\u3067\u3059\u3002",
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.8f),
        )

        Spacer(modifier = Modifier.height(12.dp))

        // 位置情報はBluetooth機器の検出にのみ使用され、実際の位置情報は保存されません。
        Text(
            text = "\u4F4D\u7F6E\u60C5\u5831\u306FBluetooth\u6A5F\u5668\u306E\u691C\u51FA\u306B\u306E\u307F\u4F7F\u7528\u3055\u308C\u3001\u5B9F\u969B\u306E\u4F4D\u7F6E\u60C5\u5831\u306F\u4FDD\u5B58\u3055\u308C\u307E\u305B\u3093\u3002",
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
        )

        Spacer(modifier = Modifier.height(32.dp))

        // 許可する
        Button(
            onClick = onAllow,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(text = "\u8A31\u53EF\u3059\u308B")
        }
    }
}

// ── Step 2: Denied ──────────────────────────────────────────────────────────

@Composable
private fun DeniedContent(
    onRetry: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 28.dp, vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = "\u26A0\uFE0F",
            fontSize = 48.sp,
        )

        Spacer(modifier = Modifier.height(24.dp))

        // 権限が必要です
        Text(
            text = "\u6A29\u9650\u304C\u5FC5\u8981\u3067\u3059",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground,
        )

        Spacer(modifier = Modifier.height(16.dp))

        // このアプリはBluetooth権限と位置情報の権限がないと正常に動作しません。
        // 「設定」から手動で権限を許可してください。
        Text(
            text = "\u3053\u306E\u30A2\u30D7\u30EA\u306FBluetooth\u6A29\u9650\u3068\u4F4D\u7F6E\u60C5\u5831\u306E\u6A29\u9650\u304C\u306A\u3044\u3068\u6B63\u5E38\u306B\u52D5\u4F5C\u3057\u307E\u305B\u3093\u3002\n\u300C\u8A2D\u5B9A\u300D\u304B\u3089\u624B\u52D5\u3067\u6A29\u9650\u3092\u8A31\u53EF\u3057\u3066\u304F\u3060\u3055\u3044\u3002",
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.8f),
        )

        Spacer(modifier = Modifier.height(32.dp))

        // 再度許可する
        Button(
            onClick = onRetry,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(text = "\u518D\u5EA6\u8A31\u53EF\u3059\u308B")
        }

        Spacer(modifier = Modifier.height(12.dp))

        // 設定を開く
        OutlinedButton(
            onClick = onOpenSettings,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(text = "\u8A2D\u5B9A\u3092\u958B\u304F")
        }
    }
}

// ── Step 3: Battery Optimization ────────────────────────────────────────────

@Composable
private fun BatteryOptimizationContent(
    onRequest: () -> Unit,
    onSkip: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 28.dp, vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = "\uD83D\uDD0B",
            fontSize = 48.sp,
        )

        Spacer(modifier = Modifier.height(24.dp))

        // バッテリー最適化
        Text(
            text = "\u30D0\u30C3\u30C6\u30EA\u30FC\u6700\u9069\u5316",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground,
        )

        Spacer(modifier = Modifier.height(16.dp))

        // バッテリー最適化を解除すると、バックグラウンドでもBluetooth接続が安定します。
        Text(
            text = "\u30D0\u30C3\u30C6\u30EA\u30FC\u6700\u9069\u5316\u3092\u89E3\u9664\u3059\u308B\u3068\u3001\u30D0\u30C3\u30AF\u30B0\u30E9\u30A6\u30F3\u30C9\u3067\u3082Bluetooth\u63A5\u7D9A\u304C\u5B89\u5B9A\u3057\u307E\u3059\u3002",
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.8f),
        )

        Spacer(modifier = Modifier.height(32.dp))

        // バッテリー最適化を解除
        Button(
            onClick = onRequest,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(text = "\u30D0\u30C3\u30C6\u30EA\u30FC\u6700\u9069\u5316\u3092\u89E3\u9664")
        }

        Spacer(modifier = Modifier.height(12.dp))

        // スキップ
        TextButton(onClick = onSkip) {
            Text(text = "\u30B9\u30AD\u30C3\u30D7")
        }
    }
}
