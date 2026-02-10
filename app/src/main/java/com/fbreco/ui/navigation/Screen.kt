package com.fbreco.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Place
import androidx.compose.ui.graphics.vector.ImageVector

sealed class Screen(val route: String, val label: String, val icon: ImageVector) {
    data object Home : Screen("home", "ホーム", Icons.Default.Home)
    data object History : Screen("history", "履歴", Icons.AutoMirrored.Filled.List)
    data object Destination : Screen("destination", "目的地", Icons.Default.Place)

    companion object {
        val all = listOf(Home, History, Destination)
    }
}
