package org.androidaudioplugin.greenhouse.ui

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
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import org.androidaudioplugin.greenhouse.ui.screens.EngineSettingsScreen
import org.androidaudioplugin.greenhouse.ui.screens.PluginBrowserScreen
import org.androidaudioplugin.greenhouse.ui.screens.StudioRackScreen
import org.androidaudioplugin.greenhouse.ui.theme.*

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            GreenhouseTheme {
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

private const val ROUTE_RACK = "rack"
private const val ROUTE_BROWSER = "browser"
private const val ROUTE_SETTINGS = "settings"

private fun NavController.safeNavigate(route: String) {
    val currentLifecycle = currentBackStackEntry?.lifecycle?.currentState

    if (currentLifecycle == Lifecycle.State.RESUMED) {
        navigate(route) {
            launchSingleTop = true
        }
    }
}

private fun NavController.safePopBackToRack() {
    if (previousBackStackEntry == null) {
        return
    }

    val popped = popBackStack(ROUTE_RACK, inclusive = false)

    if (!popped && currentDestination?.route != ROUTE_RACK) {
        popBackStack()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainHostApp(
    viewModel: HostViewModel = viewModel()
) {
    val navController = rememberNavController()

    Scaffold(
        contentWindowInsets = WindowInsets.systemBars
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            NavHost(
                navController = navController,
                startDestination = ROUTE_RACK
            ) {
                composable(ROUTE_RACK) {
                    StudioRackScreen(
                        viewModel = viewModel,
                        onNavigateToBrowser = { navController.safeNavigate(ROUTE_BROWSER) },
                        onNavigateToSettings = { navController.safeNavigate(ROUTE_SETTINGS) }
                    )
                }

                composable(ROUTE_BROWSER) {
                    PluginBrowserScreen(
                        viewModel = viewModel,
                        onNavigateToRack = { navController.safePopBackToRack() }
                    )
                }

                composable(ROUTE_SETTINGS) {
                    EngineSettingsScreen(
                        viewModel = viewModel,
                        onNavigateBack = { navController.safePopBackToRack() }
                    )
                }
            }
        }
    }
}
