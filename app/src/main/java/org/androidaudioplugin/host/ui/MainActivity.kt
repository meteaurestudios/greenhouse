package org.androidaudioplugin.host.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import org.androidaudioplugin.host.ui.screens.EngineSettingsScreen
import org.androidaudioplugin.host.ui.screens.PluginBrowserScreen
import org.androidaudioplugin.host.ui.screens.StudioRackScreen
import org.androidaudioplugin.host.ui.theme.*

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            AapHostTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    MainHostApp()
                }
            }
        }
    }
}

sealed class NavigationTab(val route: String, val title: String, val icon: ImageVector) {
    object Browser : NavigationTab("browser", "Browser", Icons.Default.Tune)
    object Rack : NavigationTab("rack", "Studio Rack", Icons.Default.MusicNote)
    object Settings : NavigationTab("settings", "Diagnostics", Icons.Default.Settings)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainHostApp(
    viewModel: HostViewModel = viewModel()
) {
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route ?: NavigationTab.Browser.route

    val tabs = listOf(
        NavigationTab.Browser,
        NavigationTab.Rack,
        NavigationTab.Settings
    )

    val navigateToRoute: (String) -> Unit = { targetRoute ->
        if (currentRoute != targetRoute) {
            navController.navigate(targetRoute) {
                popUpTo(navController.graph.findStartDestination().id) {
                    saveState = true
                }
                launchSingleTop = true
                restoreState = true
            }
        }
    }

    Scaffold(
        bottomBar = {
            NavigationBar(
                containerColor = StudioSurface,
                contentColor = TextPrimary,
                tonalElevation = 8.dp
            ) {
                tabs.forEach { tab ->
                    val isSelected = currentRoute == tab.route
                    NavigationBarItem(
                        selected = isSelected,
                        onClick = { navigateToRoute(tab.route) },
                        icon = {
                            Icon(
                                imageVector = tab.icon,
                                contentDescription = tab.title,
                                tint = if (isSelected) NeonCyan else TextMuted
                            )
                        },
                        label = {
                            Text(
                                text = tab.title,
                                fontSize = 11.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                color = if (isSelected) NeonCyan else TextMuted
                            )
                        },
                        colors = NavigationBarItemDefaults.colors(
                            indicatorColor = ElectricBlue.copy(alpha = 0.2f)
                        )
                    )
                }
            }
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            NavHost(
                navController = navController,
                startDestination = NavigationTab.Rack.route
            ) {
                composable(NavigationTab.Browser.route) {
                    PluginBrowserScreen(
                        viewModel = viewModel,
                        onNavigateToRack = { navigateToRoute(NavigationTab.Rack.route) }
                    )
                }

                composable(NavigationTab.Rack.route) {
                    StudioRackScreen(
                        viewModel = viewModel,
                        onNavigateToBrowser = { navigateToRoute(NavigationTab.Browser.route) }
                    )
                }

                composable(NavigationTab.Settings.route) {
                    EngineSettingsScreen(
                        viewModel = viewModel
                    )
                }
            }
        }
    }
}
