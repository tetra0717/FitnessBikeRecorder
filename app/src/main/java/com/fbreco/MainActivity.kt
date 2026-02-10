package com.fbreco

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.fbreco.service.BikeForegroundService
import com.fbreco.ui.PermissionScreen
import com.fbreco.ui.allPermissionsGranted
import com.fbreco.ui.navigation.FBRecoNavigation
import com.fbreco.ui.theme.FBRecoTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            FBRecoTheme {
                var permissionsGranted by remember {
                    mutableStateOf(allPermissionsGranted(this@MainActivity))
                }
                LaunchedEffect(permissionsGranted) {
                    if (permissionsGranted) {
                        val intent = Intent(this@MainActivity, BikeForegroundService::class.java)
                        startForegroundService(intent)
                    }
                }
                if (permissionsGranted) {
                    FBRecoNavigation()
                } else {
                    PermissionScreen(onAllPermissionsGranted = { permissionsGranted = true })
                }
            }
        }
    }
}
