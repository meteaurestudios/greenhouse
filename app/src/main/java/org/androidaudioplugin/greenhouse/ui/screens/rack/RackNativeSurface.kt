package org.androidaudioplugin.greenhouse.ui.screens.rack

import android.os.Build
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.OpenWith
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
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
import org.androidaudioplugin.greenhouse.ui.StudioRackViewMode
import org.androidaudioplugin.greenhouse.ui.theme.AccentCyan
import org.androidaudioplugin.greenhouse.ui.theme.AccentGold
import org.androidaudioplugin.greenhouse.ui.theme.AccentViolet
import org.androidaudioplugin.greenhouse.ui.theme.BerryRose
import org.androidaudioplugin.greenhouse.ui.theme.BlossomCoralSoft
import org.androidaudioplugin.greenhouse.ui.theme.NeonCyan
import org.androidaudioplugin.greenhouse.ui.theme.SproutGreen
import org.androidaudioplugin.greenhouse.ui.theme.StudioBackground
import org.androidaudioplugin.greenhouse.ui.theme.StudioPanelBorder
import org.androidaudioplugin.greenhouse.ui.theme.StudioSurface
import org.androidaudioplugin.greenhouse.ui.theme.StudioSurfaceElevated
import org.androidaudioplugin.greenhouse.ui.theme.StudioSurfaceVariant
import org.androidaudioplugin.greenhouse.ui.theme.TextMuted
import org.androidaudioplugin.greenhouse.ui.theme.TextPrimary
import org.androidaudioplugin.greenhouse.ui.theme.TextSecondary
import org.androidaudioplugin.hosting.GuiHelper

private const val DEFAULT_NATIVE_UI_WIDTH = 800
private const val DEFAULT_NATIVE_UI_HEIGHT = 600
private const val NATIVE_UI_MIN_DIMENSION_PX = 100f
private const val NATIVE_UI_STABILIZATION_DELAY_MS = 180L
private const val NATIVE_UI_FADE_ANIMATION_MS = 350
private const val NATIVE_UI_ZOOM_STEP = 0.15f
private const val NATIVE_UI_ZOOM_ROUNDING_SCALE = 20.0
private const val NATIVE_UI_MIN_SCALE = 0.05f
private const val NATIVE_UI_MAX_SCALE = 3.0f
private const val NATIVE_UI_FIT_SNAP_THRESHOLD = 0.02f

private val FALLBACK_CARD_MAX_WIDTH = 460.dp
private val FALLBACK_CARD_CORNER_RADIUS = 20.dp
private val FALLBACK_CARD_PADDING = 24.dp
private val FALLBACK_ICON_SIZE = 40.dp
private val FALLBACK_ICON_CONTAINER_SIZE = 64.dp
private const val FALLBACK_BORDER_ALPHA = 0.45f
private const val FALLBACK_ICON_BG_ALPHA = 0.15f
private val FALLBACK_ACTION_SPACING = 10.dp
private val FALLBACK_ACTION_CORNER_RADIUS = 10.dp
private val FALLBACK_BUTTON_HORIZONTAL_PADDING = 12.dp
private val FALLBACK_BUTTON_VERTICAL_PADDING = 10.dp
private val FALLBACK_BUTTON_ICON_SIZE = 16.dp
private val FALLBACK_BUTTON_ICON_SPACING = 6.dp

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
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(StudioSurfaceVariant.copy(alpha = 0.95f))
            .border(1.dp, StudioPanelBorder, RoundedCornerShape(12.dp))
            .padding(horizontal = 6.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        // Zoom Out (-) Button (Disabled when in FIT mode)
        val canZoomOut = !isFitMode
        Box(
            modifier = Modifier
                .size(24.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(if (canZoomOut) StudioSurfaceElevated else StudioSurface)
                .clickable(
                    enabled = canZoomOut,
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) { onZoomOut() },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.Remove,
                contentDescription = "Zoom Out",
                tint = if (canZoomOut) TextPrimary else TextMuted.copy(alpha = 0.35f),
                modifier = Modifier.size(14.dp)
            )
        }

        // Zoom Percentage readout
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .background(if (isFitMode) StudioSurfaceElevated else Color.Transparent)
                .padding(horizontal = 6.dp, vertical = 3.dp)
        ) {
            val zoomPercent = "${(displayedScale * 100).toInt()}%"
            Text(
                text = zoomPercent,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                color = if (isFitMode) NeonCyan else AccentGold
            )
        }

        // Zoom In (+) Button
        Box(
            modifier = Modifier
                .size(24.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(StudioSurfaceElevated)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) { onZoomIn() },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.Add,
                contentDescription = "Zoom In",
                tint = TextPrimary,
                modifier = Modifier.size(14.dp)
            )
        }

        if (showFullscreenButton) {
            Spacer(modifier = Modifier.width(2.dp))

            Box(
                modifier = Modifier
                    .size(24.dp)
                    .clip(CircleShape)
                    .background(StudioSurfaceElevated)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) { onToggleFullscreen() },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (isFullscreen) Icons.Default.FullscreenExit else Icons.Default.Fullscreen,
                    contentDescription = if (isFullscreen) "Exit Fullscreen" else "Fullscreen",
                    tint = TextPrimary,
                    modifier = Modifier.size(15.dp)
                )
            }
        }
    }
}

@Composable
fun NativeSurfaceInteractionToggle(
    isMoveMode: Boolean,
    onSelectTweakMode: () -> Unit,
    onSelectMoveMode: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        // TWEAK Button
        val isTweakActive = !isMoveMode
        val tweakInteractionSource = remember { MutableInteractionSource() }
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .background(if (isTweakActive) StudioSurfaceElevated else StudioSurface.copy(alpha = 0.6f))
                .border(1.dp, if (isTweakActive) StudioPanelBorder else Color.Transparent, RoundedCornerShape(8.dp))
                .clickable(
                    interactionSource = tweakInteractionSource,
                    indication = ripple(bounded = true, color = Color.White)
                ) { onSelectTweakMode() }
                .padding(horizontal = 6.dp, vertical = 4.dp),
            contentAlignment = Alignment.Center
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.TouchApp,
                    contentDescription = "Tweak Mode",
                    tint = if (isTweakActive) TextPrimary else TextSecondary,
                    modifier = Modifier.size(13.dp)
                )

                Text(
                    text = "TWEAK",
                    fontSize = 9.sp,
                    fontWeight = if (isTweakActive) FontWeight.Bold else FontWeight.Medium,
                    color = if (isTweakActive) TextPrimary else TextSecondary
                )
            }
        }

        // MOVE Button
        val isMoveActive = isMoveMode
        val moveInteractionSource = remember { MutableInteractionSource() }
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .background(if (isMoveActive) StudioSurfaceElevated else StudioSurface.copy(alpha = 0.6f))
                .border(1.dp, if (isMoveActive) StudioPanelBorder else Color.Transparent, RoundedCornerShape(8.dp))
                .clickable(
                    interactionSource = moveInteractionSource,
                    indication = ripple(bounded = true, color = Color.White)
                ) { onSelectMoveMode() }
                .padding(horizontal = 6.dp, vertical = 4.dp),
            contentAlignment = Alignment.Center
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.OpenWith,
                    contentDescription = "Move Mode",
                    tint = if (isMoveActive) TextPrimary else TextSecondary,
                    modifier = Modifier.size(13.dp)
                )

                Text(
                    text = "MOVE",
                    fontSize = 9.sp,
                    fontWeight = if (isMoveActive) FontWeight.Bold else FontWeight.Medium,
                    color = if (isMoveActive) TextPrimary else TextSecondary
                )
            }
        }
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
            .clip(RoundedCornerShape(16.dp))
            .background(StudioBackground)
            .border(1.dp, StudioPanelBorder, RoundedCornerShape(16.dp)),
        contentAlignment = Alignment.Center
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(36.dp),
            color = themeColor,
            strokeWidth = 3.dp
        )
    }
}

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
            .clip(RoundedCornerShape(16.dp))
            .background(StudioBackground)
            .border(1.dp, StudioPanelBorder, RoundedCornerShape(16.dp)),
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

@Composable
fun NativeUiErrorFallbackCard(
    plugin: PluginInformation,
    errorMessage: String,
    errorDetails: String?,
    isProcessDead: Boolean,
    onRetry: () -> Unit,
    onSwitchToParameters: () -> Unit,
    onReloadPlugin: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(FALLBACK_CARD_CORNER_RADIUS))
            .background(StudioBackground)
            .border(1.dp, BerryRose.copy(alpha = FALLBACK_BORDER_ALPHA), RoundedCornerShape(FALLBACK_CARD_CORNER_RADIUS)),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = FALLBACK_CARD_MAX_WIDTH)
                .padding(FALLBACK_CARD_PADDING),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Box(
                modifier = Modifier
                    .size(FALLBACK_ICON_CONTAINER_SIZE)
                    .clip(CircleShape)
                    .background(BerryRose.copy(alpha = FALLBACK_ICON_BG_ALPHA))
                    .border(1.dp, BerryRose.copy(alpha = FALLBACK_BORDER_ALPHA), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.WarningAmber,
                    contentDescription = null,
                    tint = BerryRose,
                    modifier = Modifier.size(FALLBACK_ICON_SIZE)
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = errorMessage,
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold,
                color = TextPrimary,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(8.dp))

            val descriptionText = if (isProcessDead) {
                "The remote plugin process crashed or terminated unexpectedly."
            } else {
                "The native editor could not be connected. You can retry the UI connection or switch to generic parameter controls."
            }

            Text(
                text = descriptionText,
                fontSize = 12.sp,
                color = TextSecondary,
                textAlign = TextAlign.Start,
                modifier = Modifier.fillMaxWidth(),
                lineHeight = 18.sp
            )

            if (!errorDetails.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(12.dp))

                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = StudioSurfaceVariant,
                    border = androidx.compose.foundation.BorderStroke(1.dp, StudioPanelBorder)
                ) {
                    Text(
                        text = errorDetails,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        color = BlossomCoralSoft,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            if (isProcessDead) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(FALLBACK_ACTION_SPACING)
                ) {
                    Button(
                        onClick = onReloadPlugin,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = SproutGreen,
                            contentColor = StudioBackground
                        ),
                        shape = RoundedCornerShape(FALLBACK_ACTION_CORNER_RADIUS),
                        contentPadding = PaddingValues(
                            horizontal = FALLBACK_BUTTON_HORIZONTAL_PADDING,
                            vertical = FALLBACK_BUTTON_VERTICAL_PADDING
                        ),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(
                            imageVector = Icons.Default.RestartAlt,
                            contentDescription = null,
                            modifier = Modifier.size(FALLBACK_BUTTON_ICON_SIZE)
                        )

                        Spacer(modifier = Modifier.width(FALLBACK_BUTTON_ICON_SPACING))

                        Text(
                            text = "Reload Plugin",
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 12.sp,
                            maxLines = 1
                        )
                    }

                    OutlinedButton(
                        onClick = onSwitchToParameters,
                        shape = RoundedCornerShape(FALLBACK_ACTION_CORNER_RADIUS),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = TextPrimary
                        ),
                        border = androidx.compose.foundation.BorderStroke(1.dp, StudioPanelBorder),
                        contentPadding = PaddingValues(
                            horizontal = FALLBACK_BUTTON_HORIZONTAL_PADDING,
                            vertical = FALLBACK_BUTTON_VERTICAL_PADDING
                        ),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Tune,
                            contentDescription = null,
                            modifier = Modifier.size(FALLBACK_BUTTON_ICON_SIZE)
                        )

                        Spacer(modifier = Modifier.width(FALLBACK_BUTTON_ICON_SPACING))

                        Text(
                            text = "Parameters",
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 12.sp,
                            maxLines = 1
                        )
                    }
                }
            } else {
                Button(
                    onClick = onSwitchToParameters,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = SproutGreen,
                        contentColor = StudioBackground
                    ),
                    shape = RoundedCornerShape(FALLBACK_ACTION_CORNER_RADIUS),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(
                        imageVector = Icons.Default.Tune,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )

                    Spacer(modifier = Modifier.width(8.dp))

                    Text(
                        text = "Switch to Parameters",
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 13.sp
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(FALLBACK_ACTION_SPACING)
                ) {
                    OutlinedButton(
                        onClick = onRetry,
                        shape = RoundedCornerShape(FALLBACK_ACTION_CORNER_RADIUS),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = TextPrimary
                        ),
                        border = androidx.compose.foundation.BorderStroke(1.dp, StudioPanelBorder),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )

                        Spacer(modifier = Modifier.width(6.dp))

                        Text(
                            text = "Retry UI",
                            fontSize = 12.sp
                        )
                    }

                    OutlinedButton(
                        onClick = onReloadPlugin,
                        shape = RoundedCornerShape(FALLBACK_ACTION_CORNER_RADIUS),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = BerryRose
                        ),
                        border = androidx.compose.foundation.BorderStroke(1.dp, BerryRose.copy(alpha = FALLBACK_BORDER_ALPHA)),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(
                            imageVector = Icons.Default.RestartAlt,
                            contentDescription = null,
                            tint = BerryRose,
                            modifier = Modifier.size(16.dp)
                        )

                        Spacer(modifier = Modifier.width(6.dp))

                        Text(
                            text = "Reload Plugin",
                            fontSize = 12.sp
                        )
                    }
                }
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

        val zoomState = viewModel.slotNativeUiZoomStates[slot.index]
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
                    message = "Plugin Process Crashed",
                    details = reason,
                    isProcessDead = true
                )
            }

            host.onError = { error ->
                uiStatus = NativeUiStatus.Error(
                    message = "Plugin Process Error",
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

                    delay(NATIVE_UI_STABILIZATION_DELAY_MS)

                    if (uiStatus is NativeUiStatus.Loading) {
                        uiStatus = NativeUiStatus.Ready
                    }
                } catch (e: Throwable) {
                    uiStatus = NativeUiStatus.Error(
                        message = "Failed to Connect Editor",
                        details = e.localizedMessage ?: e.javaClass.simpleName,
                        isProcessDead = false
                    )
                }
            }

            onDispose {
                viewModel.syncParametersForSlot(slot.index, ignoreCooldown = true)

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
                onSwitchToParameters = {
                    viewModel.updateViewMode(StudioRackViewMode.PARAMETERS)
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
                            .padding(bottom = 6.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
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
