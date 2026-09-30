package org.androidaudioplugin.greenhouse.ui.screens.rack

import android.os.Build
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.annotation.RequiresApi
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.OpenWith
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.rounded.PriorityHigh
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.androidaudioplugin.PluginInformation
import org.androidaudioplugin.greenhouse.ui.GreenhouseSurfaceControlHost
import org.androidaudioplugin.greenhouse.ui.HostViewModel
import org.androidaudioplugin.greenhouse.ui.RackSlotData
import org.androidaudioplugin.greenhouse.ui.components.CONTROL_CORNER_RADIUS
import org.androidaudioplugin.greenhouse.ui.components.CONTROL_HEIGHT
import org.androidaudioplugin.greenhouse.ui.components.CONTROL_LABEL_FONT_SIZE
import org.androidaudioplugin.greenhouse.ui.components.CONTROL_SPACING
import org.androidaudioplugin.greenhouse.ui.components.ControlButton
import org.androidaudioplugin.greenhouse.ui.components.StepperControl
import org.androidaudioplugin.greenhouse.ui.components.controlSurface
import org.androidaudioplugin.greenhouse.ui.theme.AccentCyan
import org.androidaudioplugin.greenhouse.ui.theme.AccentViolet
import org.androidaudioplugin.greenhouse.ui.theme.BerryRose
import org.androidaudioplugin.greenhouse.ui.theme.BlossomCoral
import org.androidaudioplugin.greenhouse.ui.theme.SproutGreen
import org.androidaudioplugin.greenhouse.ui.theme.StudioBackground
import org.androidaudioplugin.greenhouse.ui.theme.StudioSurfaceElevated
import org.androidaudioplugin.greenhouse.ui.theme.TextMuted
import org.androidaudioplugin.greenhouse.ui.theme.TextPrimary
import org.androidaudioplugin.greenhouse.ui.theme.TextSecondary
import org.androidaudioplugin.hosting.GuiHelper
import kotlin.time.Duration.Companion.milliseconds

private const val DEFAULT_NATIVE_UI_WIDTH = 800
private const val DEFAULT_NATIVE_UI_HEIGHT = 600
private const val NATIVE_UI_MIN_DIMENSION_PX = 100f
private val NATIVE_UI_CONNECT_TIMEOUT = 6000.milliseconds
private const val NATIVE_UI_FADE_ANIMATION_MS = 350
private const val NATIVE_UI_ZOOM_STEP = 0.15f
private const val NATIVE_UI_ZOOM_ROUNDING_SCALE = 20.0
private const val NATIVE_UI_MIN_SCALE = 0.05f
private const val NATIVE_UI_MAX_SCALE = 3.0f
private const val NATIVE_UI_FIT_SNAP_THRESHOLD = 0.02f
private const val PERCENT_SCALE = 100f
// Room for the widest zoom ("300%"), so the steps do not move while zooming
private val ZOOM_LABEL_WIDTH = 44.dp
private val SEGMENT_INSET = 3.dp
private val SEGMENT_SHAPE = RoundedCornerShape(CONTROL_CORNER_RADIUS - SEGMENT_INSET)
private val SEGMENT_HORIZONTAL_PADDING = 10.dp
private val SEGMENT_CONTENT_SPACING = 5.dp
private val SEGMENT_ICON_SIZE = 14.dp
private val SURFACE_CORNER_RADIUS = 12.dp
private val SURFACE_TOOLBAR_SPACING = 8.dp

private val FALLBACK_CARD_MAX_WIDTH = 420.dp
private val FALLBACK_CARD_PADDING = 24.dp
private val FALLBACK_ICON_SIZE = 30.dp
private val FALLBACK_ICON_SPACING = 8.dp
private val FALLBACK_TEXT_SPACING = 6.dp
private val FALLBACK_SECTION_SPACING = 18.dp
private val FALLBACK_TITLE_FONT_SIZE = 16.sp
private val FALLBACK_BODY_FONT_SIZE = 12.sp
private val FALLBACK_BODY_LINE_HEIGHT = 17.sp
private val FALLBACK_DETAILS_FONT_SIZE = 10.sp
private val FALLBACK_DETAILS_HORIZONTAL_PADDING = 10.dp
private val FALLBACK_DETAILS_VERTICAL_PADDING = 6.dp
private val FALLBACK_ACTION_SPACING = CONTROL_SPACING
private val FALLBACK_ACTION_HEIGHT = 40.dp

sealed interface NativeUiStatus {
    object Loading : NativeUiStatus
    object Ready : NativeUiStatus
    data class Error(
        val message: String,
        val details: String? = null,
        val isProcessDead: Boolean = false
    ) : NativeUiStatus
}

@Composable
fun NativeSurfaceZoomToolbar(
    isFitMode: Boolean,
    displayedScale: Float,
    onZoomIn: () -> Unit,
    onZoomOut: () -> Unit,
    showFullscreenButton: Boolean,
    isFullscreen: Boolean,
    onToggleFullscreen: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(CONTROL_SPACING)
    ) {
        // Zoom stepper: zooming out is disabled in FIT mode
        StepperControl(
            label = "${(displayedScale * PERCENT_SCALE).toInt()}%",
            canDecrement = !isFitMode,
            canIncrement = true,
            decrementDescription = "Zoom Out",
            incrementDescription = "Zoom In",
            onDecrement = onZoomOut,
            onIncrement = onZoomIn,
            labelWidth = ZOOM_LABEL_WIDTH
        )

        if (showFullscreenButton) {
            ControlButton(
                label = null,
                icon = if (isFullscreen) {
                    Icons.Default.FullscreenExit
                } else {
                    Icons.Default.Fullscreen
                },
                contentDescription = if (isFullscreen) {
                    "Exit Fullscreen"
                } else {
                    "Fullscreen"
                },
                isActive = isFullscreen,
                onClick = onToggleFullscreen
            )
        }
    }
}

/** Tweak (touches go to the plugin UI) or Move (touches pan it), as one segmented control. */
@Composable
fun NativeSurfaceInteractionToggle(
    isMoveMode: Boolean,
    onSelectTweakMode: () -> Unit,
    onSelectMoveMode: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .height(CONTROL_HEIGHT)
            .controlSurface()
            .padding(SEGMENT_INSET),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(SEGMENT_INSET)
    ) {
        InteractionSegment(
            label = "Tweak",
            icon = Icons.Default.TouchApp,
            isSelected = !isMoveMode,
            onClick = onSelectTweakMode
        )

        InteractionSegment(
            label = "Move",
            icon = Icons.Default.OpenWith,
            isSelected = isMoveMode,
            onClick = onSelectMoveMode
        )
    }
}

@Composable
private fun InteractionSegment(
    label: String,
    icon: ImageVector,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    val contentColor = if (isSelected) {
        TextPrimary
    } else {
        TextSecondary
    }

    Row(
        modifier = Modifier
            .fillMaxHeight()
            .clip(SEGMENT_SHAPE)
            .background(
                if (isSelected) {
                    StudioSurfaceElevated
                } else {
                    Color.Transparent
                }
            )
            .clickable(onClick = onClick)
            .padding(horizontal = SEGMENT_HORIZONTAL_PADDING),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(SEGMENT_CONTENT_SPACING, Alignment.CenterHorizontally)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = contentColor,
            modifier = Modifier.size(SEGMENT_ICON_SIZE)
        )

        Text(
            text = label,
            fontSize = CONTROL_LABEL_FONT_SIZE,
            fontWeight = FontWeight.Medium,
            color = contentColor,
            maxLines = 1
        )
    }
}

@Composable
fun NativeUiLoadingView(
    slot: RackSlotData,
    modifier: Modifier = Modifier
) {
    val themeColor = if (slot.index == 0) {
        AccentViolet
    } else {
        AccentCyan
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(SURFACE_CORNER_RADIUS))
            .background(StudioBackground),
        contentAlignment = Alignment.Center
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(36.dp),
            color = themeColor,
            strokeWidth = 3.dp
        )
    }
}

@RequiresApi(Build.VERSION_CODES.R)
@Composable
fun NativePluginSurfaceViewer(
    host: GreenhouseSurfaceControlHost,
    preferredSize: GuiHelper.Size,
    isFitMode: Boolean,
    isMoveMode: Boolean,
    currentScale: Float,
    panOffsetX: Float,
    panOffsetY: Float,
    onFitScaleCalculated: (Float) -> Unit,
    onEffectiveScaleCalculated: (Float) -> Unit,
    onPanDelta: (Float, Float) -> Unit,
    modifier: Modifier = Modifier
) {
    BoxWithConstraints(
        modifier = modifier
            .clip(RoundedCornerShape(SURFACE_CORNER_RADIUS))
            .background(StudioBackground),
        contentAlignment = Alignment.Center
    ) {
        val density = LocalDensity.current
        val containerWidthPx = with(density) { maxWidth.toPx() }
        val containerHeightPx = with(density) { maxHeight.toPx() }

        val nativeWidthPx = preferredSize.width.toFloat().coerceAtLeast(NATIVE_UI_MIN_DIMENSION_PX)
        val nativeHeightPx = preferredSize.height.toFloat().coerceAtLeast(NATIVE_UI_MIN_DIMENSION_PX)

        val fitScale = minOf(containerWidthPx / nativeWidthPx, containerHeightPx / nativeHeightPx, 1.0f).coerceAtLeast(NATIVE_UI_MIN_SCALE)

        val effectiveScale = if (isFitMode) {
            fitScale
        } else {
            currentScale.coerceAtLeast(NATIVE_UI_MIN_SCALE)
        }

        LaunchedEffect(fitScale) {
            onFitScaleCalculated(fitScale)
        }

        LaunchedEffect(effectiveScale) {
            onEffectiveScaleCalculated(effectiveScale)
        }

        val scaledContentWidth = nativeWidthPx * effectiveScale
        val scaledContentHeight = nativeHeightPx * effectiveScale

        // Strict out-of-bounds translation clamping in both axes
        val minTransX = if (scaledContentWidth <= containerWidthPx) {
            (containerWidthPx - scaledContentWidth) / 2f
        } else {
            containerWidthPx - scaledContentWidth
        }

        val maxTransX = if (scaledContentWidth <= containerWidthPx) {
            (containerWidthPx - scaledContentWidth) / 2f
        } else {
            0f
        }

        val minTransY = if (scaledContentHeight <= containerHeightPx) {
            (containerHeightPx - scaledContentHeight) / 2f
        } else {
            containerHeightPx - scaledContentHeight
        }

        val maxTransY = if (scaledContentHeight <= containerHeightPx) {
            (containerHeightPx - scaledContentHeight) / 2f
        } else {
            0f
        }

        val transX = if (isFitMode) {
            (containerWidthPx - scaledContentWidth) / 2f
        } else {
            panOffsetX.coerceIn(minTransX, maxTransX)
        }

        val transY = if (isFitMode) {
            (containerHeightPx - scaledContentHeight) / 2f
        } else {
            panOffsetY.coerceIn(minTransY, maxTransY)
        }

        AndroidView(
            factory = { ctx ->
                FrameLayout(ctx).apply {
                    clipChildren = true
                    clipToPadding = true
                    outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
                    clipToOutline = true
                    setBackgroundColor(android.graphics.Color.parseColor("#121614"))
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )

                    val surfaceView = host.surfaceView

                    if (surfaceView.parent != null) {
                        (surfaceView.parent as ViewGroup).removeView(surfaceView)
                    }

                    surfaceView.isClickable = true
                    (surfaceView as? android.view.SurfaceView)?.setZOrderMediaOverlay(true)

                    val lp = FrameLayout.LayoutParams(
                        preferredSize.width,
                        preferredSize.height
                    ).apply {
                        gravity = android.view.Gravity.TOP or android.view.Gravity.START
                    }
                    surfaceView.layoutParams = lp
                    surfaceView.pivotX = 0f
                    surfaceView.pivotY = 0f
                    surfaceView.scaleX = effectiveScale
                    surfaceView.scaleY = effectiveScale
                    surfaceView.translationX = transX
                    surfaceView.translationY = transY

                    addView(surfaceView)
                }
            },
            update = { frameLayout ->
                val surfaceView = host.surfaceView

                if (surfaceView.parent !== frameLayout) {
                    if (surfaceView.parent != null) {
                        (surfaceView.parent as ViewGroup).removeView(surfaceView)
                    }

                    frameLayout.removeAllViews()
                    frameLayout.addView(surfaceView)
                }

                surfaceView.isClickable = true
                (surfaceView as? android.view.SurfaceView)?.setZOrderMediaOverlay(true)

                val lp = (surfaceView.layoutParams as? FrameLayout.LayoutParams) ?: FrameLayout.LayoutParams(
                    preferredSize.width,
                    preferredSize.height
                )
                lp.width = preferredSize.width
                lp.height = preferredSize.height
                lp.gravity = android.view.Gravity.TOP or android.view.Gravity.START
                surfaceView.layoutParams = lp

                surfaceView.pivotX = 0f
                surfaceView.pivotY = 0f
                surfaceView.scaleX = effectiveScale
                surfaceView.scaleY = effectiveScale
                surfaceView.translationX = transX
                surfaceView.translationY = transY
            },
            modifier = Modifier.fillMaxSize()
        )

        // Transparent Move Drag Overlay
        if (!isFitMode && isMoveMode) {
            val currentOnPanDelta by rememberUpdatedState(onPanDelta)

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        detectDragGestures { change, dragAmount ->
                            change.consume()
                            currentOnPanDelta(dragAmount.x, dragAmount.y)
                        }
                    }
            )
        }
    }
}

/**
 * Shown in place of the plugin UI when it cannot be shown: what happened, the error's details, and
 * what to do next (retry the UI or reload the plugin, alone when its process died). The Parameters
 * tab above stays at hand meanwhile.
 */
@Composable
fun NativeUiErrorFallbackCard(
    plugin: PluginInformation,
    errorMessage: String,
    errorDetails: String?,
    isProcessDead: Boolean,
    onRetry: () -> Unit,
    onReloadPlugin: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = FALLBACK_CARD_MAX_WIDTH)
                .padding(FALLBACK_CARD_PADDING),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = Icons.Rounded.PriorityHigh,
                contentDescription = null,
                // As the load error snackbar shows it
                tint = BlossomCoral,
                modifier = Modifier.size(FALLBACK_ICON_SIZE)
            )

            Spacer(modifier = Modifier.height(FALLBACK_ICON_SPACING))

            Text(
                text = errorMessage,
                fontSize = FALLBACK_TITLE_FONT_SIZE,
                fontWeight = FontWeight.SemiBold,
                color = TextPrimary,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(FALLBACK_TEXT_SPACING))

            Text(
                text = if (isProcessDead) {
                    "${plugin.displayName} stopped running. Reload it to bring it back."
                } else {
                    "${plugin.displayName}'s interface didn't respond. Try again, or use the Parameters tab meanwhile."
                },
                fontSize = FALLBACK_BODY_FONT_SIZE,
                lineHeight = FALLBACK_BODY_LINE_HEIGHT,
                color = TextSecondary,
                textAlign = TextAlign.Center
            )

            if (!errorDetails.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(FALLBACK_SECTION_SPACING))

                // The technical details, for a bug report
                Text(
                    text = errorDetails,
                    fontSize = FALLBACK_DETAILS_FONT_SIZE,
                    fontFamily = FontFamily.Monospace,
                    color = TextMuted,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .controlSurface()
                        .padding(horizontal = FALLBACK_DETAILS_HORIZONTAL_PADDING, vertical = FALLBACK_DETAILS_VERTICAL_PADDING)
                )
            }

            Spacer(modifier = Modifier.height(FALLBACK_SECTION_SPACING))

            Row(
                horizontalArrangement = Arrangement.spacedBy(FALLBACK_ACTION_SPACING, Alignment.CenterHorizontally)
            ) {
                // Retrying is pointless once the process died: reloading is the way back
                if (!isProcessDead) {
                    ControlButton(
                        label = "Retry",
                        icon = Icons.Default.Refresh,
                        isActive = true,
                        height = FALLBACK_ACTION_HEIGHT,
                        onClick = onRetry
                    )
                }

                // Restarts the plugin: in the warning colour while the plugin still runs, the way
                // forward once its process died
                ControlButton(
                    label = "Reload plugin",
                    icon = Icons.Default.RestartAlt,
                    isActive = true,
                    accent = if (isProcessDead) {
                        SproutGreen
                    } else {
                        BerryRose
                    },
                    height = FALLBACK_ACTION_HEIGHT,
                    onClick = onReloadPlugin
                )
            }
        }
    }
}

@Composable
fun NativePluginSurfaceContainer(
    viewModel: HostViewModel,
    slot: RackSlotData,
    plugin: PluginInformation,
    isRackFolded: Boolean,
    onToggleFoldRack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val instance = slot.instance

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM && instance != null) {
        var surfaceHost by remember { mutableStateOf<GreenhouseSurfaceControlHost?>(null) }
        var preferredSize by remember { mutableStateOf(GuiHelper.Size(DEFAULT_NATIVE_UI_WIDTH, DEFAULT_NATIVE_UI_HEIGHT)) }
        var uiStatus by remember(instance.instanceId) { mutableStateOf<NativeUiStatus>(NativeUiStatus.Loading) }
        var retryCount by remember(instance.instanceId) { mutableIntStateOf(0) }

        val zoomState = viewModel.rack.slotUi[slot.index].nativeUiZoom
        var calculatedFitScale by remember { mutableFloatStateOf(1.0f) }
        var displayedScale by remember { mutableFloatStateOf(1.0f) }

        DisposableEffect(instance.instanceId, retryCount) {
            uiStatus = NativeUiStatus.Loading

            val host = GreenhouseSurfaceControlHost(
                context = context,
                pluginPackageName = plugin.packageName,
                pluginId = plugin.pluginId ?: "",
                instanceId = instance.instanceId
            )

            host.contentSizeChangedListeners.add { newWidth, newHeight ->
                if (newWidth > 0 && newHeight > 0) {
                    preferredSize = GuiHelper.Size(newWidth, newHeight)
                }
            }

            host.onConnected = {
                uiStatus = NativeUiStatus.Ready
            }

            host.onDisconnected = { reason ->
                uiStatus = NativeUiStatus.Error(
                    message = "Plugin stopped",
                    details = reason,
                    isProcessDead = true
                )
            }

            host.onError = { error ->
                uiStatus = NativeUiStatus.Error(
                    message = "Plugin error",
                    details = error.localizedMessage ?: error.javaClass.simpleName,
                    isProcessDead = true
                )
            }

            surfaceHost = host

            coroutineScope.launch {
                try {
                    val size = host.getPreferredSizeOrFallback(
                        DEFAULT_NATIVE_UI_WIDTH,
                        DEFAULT_NATIVE_UI_HEIGHT
                    )

                    preferredSize = size
                    host.connect(size.width, size.height)
                    host.show()

                    delay(NATIVE_UI_CONNECT_TIMEOUT)

                    if (uiStatus is NativeUiStatus.Loading) {
                        uiStatus = NativeUiStatus.Error(
                            message = "Connection timed out",
                            details = "The plugin UI did not respond within ${NATIVE_UI_CONNECT_TIMEOUT.inWholeSeconds} seconds",
                            isProcessDead = false
                        )
                    }
                } catch (e: Throwable) {
                    uiStatus = NativeUiStatus.Error(
                        message = "Couldn't open the plugin UI",
                        details = e.localizedMessage ?: e.javaClass.simpleName,
                        isProcessDead = false
                    )
                }
            }

            onDispose {
                try {
                    host.close()
                } catch (e: Throwable) {
                    // Ignore disposal errors
                }
            }
        }

        val currentStatus = uiStatus

        if (currentStatus is NativeUiStatus.Error) {
            NativeUiErrorFallbackCard(
                plugin = plugin,
                errorMessage = currentStatus.message,
                errorDetails = currentStatus.details,
                isProcessDead = currentStatus.isProcessDead,
                onRetry = {
                    retryCount++
                },
                onReloadPlugin = {
                    viewModel.loadPluginIntoSlot(slot.index, plugin)
                },
                modifier = modifier.fillMaxSize()
            )
        } else {
            surfaceHost?.let { host ->
                Column(
                    modifier = modifier.fillMaxSize()
                ) {
                    // Dedicated Top Toolbar Row above the native surface
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = SURFACE_TOOLBAR_SPACING),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(CONTROL_SPACING)
                        ) {
                            NativeSurfaceZoomToolbar(
                                isFitMode = zoomState.isFitMode,
                                displayedScale = displayedScale,
                                onZoomIn = {
                                    val base = if (zoomState.isFitMode) {
                                        calculatedFitScale
                                    } else {
                                        zoomState.currentScale
                                    }

                                    val target = (Math.round((base + NATIVE_UI_ZOOM_STEP) * NATIVE_UI_ZOOM_ROUNDING_SCALE) / NATIVE_UI_ZOOM_ROUNDING_SCALE).toFloat().coerceIn(NATIVE_UI_MIN_SCALE, NATIVE_UI_MAX_SCALE)

                                    zoomState.isFitMode = false
                                    zoomState.currentScale = target
                                },
                                onZoomOut = {
                                    val base = if (zoomState.isFitMode) {
                                        calculatedFitScale
                                    } else {
                                        zoomState.currentScale
                                    }

                                    val target = (Math.round((base - NATIVE_UI_ZOOM_STEP) * NATIVE_UI_ZOOM_ROUNDING_SCALE) / NATIVE_UI_ZOOM_ROUNDING_SCALE).toFloat()

                                    if (target <= calculatedFitScale + NATIVE_UI_FIT_SNAP_THRESHOLD) {
                                        zoomState.isFitMode = true
                                        zoomState.isMoveMode = false
                                        zoomState.panOffsetX = 0f
                                        zoomState.panOffsetY = 0f
                                    } else {
                                        zoomState.isFitMode = false
                                        zoomState.currentScale = target
                                    }
                                },
                                showFullscreenButton = true,
                                isFullscreen = isRackFolded,
                                onToggleFullscreen = onToggleFoldRack
                            )

                            if (!zoomState.isFitMode) {
                                NativeSurfaceInteractionToggle(
                                    isMoveMode = zoomState.isMoveMode,
                                    onSelectTweakMode = {
                                        zoomState.isMoveMode = false
                                    },
                                    onSelectMoveMode = {
                                        zoomState.isMoveMode = true
                                    }
                                )
                            }
                        }

                        if (isRackFolded) {
                            Text(
                                text = plugin.displayName,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = TextSecondary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(start = 8.dp)
                            )
                        }
                    }

                    // Sizable Centered Native Surface View + Smooth Loading Overlay
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        contentAlignment = Alignment.Center
                    ) {
                        NativePluginSurfaceViewer(
                            host = host,
                            preferredSize = preferredSize,
                            isFitMode = zoomState.isFitMode,
                            isMoveMode = zoomState.isMoveMode,
                            currentScale = zoomState.currentScale,
                            panOffsetX = zoomState.panOffsetX,
                            panOffsetY = zoomState.panOffsetY,
                            onFitScaleCalculated = { calculatedFitScale = it },
                            onEffectiveScaleCalculated = { displayedScale = it },
                            onPanDelta = { dx, dy ->
                                zoomState.panOffsetX += dx
                                zoomState.panOffsetY += dy
                            },
                            modifier = Modifier.fillMaxSize()
                        )

                        val isUiLoading = currentStatus is NativeUiStatus.Loading

                        val loadingAlpha by animateFloatAsState(
                            targetValue = if (isUiLoading) 1.0f else 0.0f,
                            animationSpec = tween(durationMillis = NATIVE_UI_FADE_ANIMATION_MS),
                            label = "NativeUiLoadingAlpha"
                        )

                        if (loadingAlpha > 0.0f) {
                            NativeUiLoadingView(
                                slot = slot,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .graphicsLayer { alpha = loadingAlpha }
                            )
                        }
                    }
                }
            }
        }
    } else {
        Box(
            modifier = modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Default.Layers, contentDescription = null, tint = TextMuted, modifier = Modifier.size(48.dp))

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = "Plugin UI requires Android 15+ (API 35+).",
                    color = TextSecondary,
                    fontSize = 13.sp
                )

                Text(
                    text = "Use the Parameters tab to tweak plugin values.",
                    color = TextMuted,
                    fontSize = 11.sp
                )
            }
        }
    }
}
