package org.androidaudioplugin.greenhouse.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import org.androidaudioplugin.greenhouse.ui.screens.EngineSettingsScreen
import org.androidaudioplugin.greenhouse.ui.screens.PluginBrowserScreen
import org.androidaudioplugin.greenhouse.ui.screens.StudioRackScreen
import org.androidaudioplugin.greenhouse.ui.screens.rack.BannerSnackbarHost
import org.androidaudioplugin.greenhouse.ui.theme.*

private const val ROUTE_RACK = "rack"
private const val ROUTE_BROWSER = "browser"
private const val ROUTE_SETTINGS = "settings"

// Lines the snackbar up with the rack's header banner, which it covers
private val SNACKBAR_SCREEN_PADDING = 12.dp

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
    val view = LocalView.current
    val isProcessing = viewModel.audio.isProcessing

    // Keep the screen awake while the engine runs, so playing a plugin does not dim or lock
    DisposableEffect(view, isProcessing) {
        view.keepScreenOn = isProcessing

        onDispose {
            view.keepScreenOn = false
        }
    }

    val snackbarHostState = remember { SnackbarHostState() }
    var bannerHeight by remember { mutableStateOf(Dp.Unspecified) }

    LaunchedEffect(viewModel) {
        viewModel.rack.loadErrors.collect { message ->
            snackbarHostState.showSnackbar(message)
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets.systemBars
    ) { paddingValues ->
        val layoutDirection = LocalLayoutDirection.current
        // Clear of the system bars, except below: an edge-to-edge screen scrolls under the navigation bar
        val edgeToEdgePadding = PaddingValues(
            start = paddingValues.calculateStartPadding(layoutDirection),
            top = paddingValues.calculateTopPadding(),
            end = paddingValues.calculateEndPadding(layoutDirection)
        )

        Box(modifier = Modifier.fillMaxSize()) {
            NavHost(
                navController = navController,
                startDestination = ROUTE_RACK,
                modifier = Modifier.fillMaxSize()
            ) {
                composable(ROUTE_RACK) {
                    Box(modifier = Modifier.padding(paddingValues)) {
                        StudioRackScreen(
                            viewModel = viewModel,
                            onNavigateToBrowser = { navController.safeNavigate(ROUTE_BROWSER) },
                            onNavigateToSettings = { navController.safeNavigate(ROUTE_SETTINGS) },
                            onBannerHeightChanged = { bannerHeight = it }
                        )
                    }
                }

                composable(ROUTE_BROWSER) {
                    Box(modifier = Modifier.padding(edgeToEdgePadding)) {
                        PluginBrowserScreen(
                            viewModel = viewModel,
                            onNavigateToRack = { navController.safePopBackToRack() }
                        )
                    }
                }

                composable(ROUTE_SETTINGS) {
                    Box(modifier = Modifier.padding(paddingValues)) {
                        EngineSettingsScreen(
                            viewModel = viewModel,
                            onNavigateBack = { navController.safePopBackToRack() }
                        )
                    }
                }
            }

            BannerSnackbarHost(
                hostState = snackbarHostState,
                bannerHeight = bannerHeight,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(edgeToEdgePadding)
                    .padding(SNACKBAR_SCREEN_PADDING)
            )
        }
    }
}
