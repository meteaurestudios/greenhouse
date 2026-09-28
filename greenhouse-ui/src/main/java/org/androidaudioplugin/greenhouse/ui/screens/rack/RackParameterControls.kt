package org.androidaudioplugin.greenhouse.ui.screens.rack

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.androidaudioplugin.ParameterInformation
import org.androidaudioplugin.greenhouse.ui.components.BooleanParameterToggle
import org.androidaudioplugin.greenhouse.ui.components.EnumParameterSelector
import org.androidaudioplugin.greenhouse.ui.components.FlatRotaryKnob
import org.androidaudioplugin.greenhouse.ui.components.GridVerticalScrollBar
import org.androidaudioplugin.greenhouse.ui.components.SCROLLBAR_HORIZONTAL_OFFSET
import org.androidaudioplugin.greenhouse.ui.components.SCROLLBAR_TRACK_PADDING
import org.androidaudioplugin.greenhouse.ui.components.SCROLLBAR_WIDTH
import org.androidaudioplugin.greenhouse.ui.model.ParameterType
import org.androidaudioplugin.greenhouse.ui.model.inferredType
import org.androidaudioplugin.greenhouse.ui.theme.BlossomCoral
import org.androidaudioplugin.greenhouse.ui.theme.PeriwinkleBlue
import org.androidaudioplugin.greenhouse.ui.theme.SproutGreen
import org.androidaudioplugin.greenhouse.ui.theme.StudioPanelBorder
import org.androidaudioplugin.greenhouse.ui.theme.StudioSurface
import org.androidaudioplugin.greenhouse.ui.theme.TextMuted
import org.androidaudioplugin.greenhouse.ui.theme.TextPrimary
import org.androidaudioplugin.greenhouse.ui.theme.TextSecondary

private const val PARAMETER_SEARCH_THRESHOLD = 12
private const val GRID_COLUMN_COUNT = 3
private const val PARAMETER_BOOLEAN_ACTIVE_THRESHOLD = 0.5
private const val PARAMETER_INT_DISCRETE_STEP = 1.0
private val PARAMETER_CARD_HEIGHT = 112.dp
private val PARAMETER_KNOB_SIZE = 54.dp
private val PARAMETER_CARD_CORNER_RADIUS = 12.dp
// Shared by the host control, the filter button and the search field on the panel's first line
internal val PARAMETER_TOOLBAR_HEIGHT = 34.dp
internal val PARAMETER_TOOLBAR_CORNER_RADIUS = 8.dp
// Room for the grid scroll bar; the first line uses it too so its right edge lines up with the cards
private val GRID_END_PADDING = 8.dp
private val GRID_SPACING = 8.dp
// The host control spans this many parameter cards, or one while the search field shares its line
private const val HOST_CONTROL_CARD_SPAN = 2
private const val COMPACT_HOST_CONTROL_CARD_SPAN = 1

private fun formatFastDecimal(value: Double, decimals: Int): String {
    if (decimals == 1) {
        val rounded = kotlin.math.round(value * 10.0).toLong()
        val whole = rounded / 10
        val frac = kotlin.math.abs(rounded % 10)
        return "$whole.$frac"
    }

    val rounded = kotlin.math.round(value * 100.0).toLong()
    val whole = rounded / 100
    val frac = kotlin.math.abs(rounded % 100)

    if (frac < 10) {
        return "$whole.0$frac"
    } else {
        return "$whole.$frac"
    }
}

/** Width of [span] parameter cards (and the spacing between them) in a grid [gridWidth] wide. */
private fun gridSpanWidth(gridWidth: Dp, span: Int): Dp {
    val cardWidth = (gridWidth - GRID_SPACING * (GRID_COLUMN_COUNT - 1)) / GRID_COLUMN_COUNT
    return cardWidth * span + GRID_SPACING * (span - 1)
}

@Composable
fun ParameterControlRack(
    slotIndex: Int,
    pluginId: String,
    parameters: List<ParameterInformation>,
    parameterValues: Map<Int, Double>,
    gridState: LazyGridState,
    onValueChange: (ParameterInformation, Double) -> Unit,
    /** Host control of the slot, filling the width it is given; compact while the search field shares its line. */
    hostControl: @Composable (isCompact: Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    if (parameters.isEmpty()) {
        Column(modifier = modifier.fillMaxSize()) {
            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(end = GRID_END_PADDING)
            ) {
                Box(modifier = Modifier.width(gridSpanWidth(maxWidth, HOST_CONTROL_CARD_SPAN))) {
                    hostControl(false)
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "This plugin exposes no adjustable parameters.",
                    color = TextSecondary,
                    fontSize = 14.sp
                )
            }
        }
    } else {
        var isSearchVisible by remember(pluginId) { mutableStateOf(false) }
        var searchQuery by remember(pluginId) { mutableStateOf("") }
        val searchFocusRequester = remember { FocusRequester() }
        val keyboardController = LocalSoftwareKeyboardController.current

        val filteredParameters = remember(parameters, searchQuery) {
            if (searchQuery.isBlank()) {
                parameters
            } else {
                val query = searchQuery.trim().lowercase()
                parameters.filter { param ->
                    param.name.lowercase().contains(query)
                }
            }
        }

        Column(modifier = modifier.fillMaxSize()) {
            val isSearchable = parameters.size > PARAMETER_SEARCH_THRESHOLD
            val isSearchOpen = isSearchable && (isSearchVisible || searchQuery.isNotEmpty())
            val toolbarShape = RoundedCornerShape(PARAMETER_TOOLBAR_CORNER_RADIUS)

            // Host control pinned above the plugin's parameters, sharing the line with the filter
            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(end = GRID_END_PADDING, bottom = 8.dp)
            ) {
                val gridWidth = maxWidth

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val hostSpan = if (isSearchOpen) {
                        COMPACT_HOST_CONTROL_CARD_SPAN
                    } else {
                        HOST_CONTROL_CARD_SPAN
                    }

                    // Lines up with the parameter cards below
                    Box(modifier = Modifier.width(gridSpanWidth(gridWidth, hostSpan))) {
                        hostControl(isSearchOpen)
                    }

                    if (isSearchOpen) {
                        // Opening the filter goes straight to typing
                        LaunchedEffect(Unit) {
                            searchFocusRequester.requestFocus()
                            keyboardController?.show()
                        }

                        Spacer(modifier = Modifier.width(GRID_SPACING))

                        Row(
                            modifier = Modifier
                                .weight(1f)
                                .height(PARAMETER_TOOLBAR_HEIGHT)
                                .clip(toolbarShape)
                                .background(StudioSurface)
                                .border(1.dp, StudioPanelBorder, toolbarShape)
                                .padding(horizontal = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Search,
                                contentDescription = "Search Parameters",
                                tint = TextSecondary,
                                modifier = Modifier.size(13.dp)
                            )

                            Spacer(modifier = Modifier.width(6.dp))

                            Box(
                                modifier = Modifier.weight(1f),
                                contentAlignment = Alignment.CenterStart
                            ) {
                                BasicTextField(
                                    value = searchQuery,
                                    onValueChange = { searchQuery = it },
                                    singleLine = true,
                                    cursorBrush = SolidColor(SproutGreen),
                                    textStyle = TextStyle(
                                        color = TextPrimary,
                                        fontSize = 10.sp,
                                        fontFamily = FontFamily.Monospace
                                    ),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .focusRequester(searchFocusRequester),
                                    decorationBox = { innerTextField ->
                                        Box(
                                            modifier = Modifier.fillMaxWidth(),
                                            contentAlignment = Alignment.CenterStart
                                        ) {
                                            if (searchQuery.isEmpty()) {
                                                Text(
                                                    text = "Search...",
                                                    color = TextMuted,
                                                    fontSize = 10.sp,
                                                    fontFamily = FontFamily.Monospace,
                                                    maxLines = 1
                                                )
                                            }

                                            innerTextField()
                                        }
                                    }
                                )
                            }

                            if (searchQuery.isNotEmpty()) {
                                Spacer(modifier = Modifier.width(4.dp))

                                Text(
                                    text = "${filteredParameters.size}/${parameters.size}",
                                    fontSize = 9.sp,
                                    fontFamily = FontFamily.Monospace,
                                    color = TextMuted,
                                    maxLines = 1
                                )
                            }

                            Spacer(modifier = Modifier.width(4.dp))

                            // Clears the query, or closes the search once it is empty
                            Box(
                                modifier = Modifier
                                    .size(20.dp)
                                    .clip(CircleShape)
                                    .clickable {
                                        if (searchQuery.isNotEmpty()) {
                                            searchQuery = ""
                                        } else {
                                            keyboardController?.hide()
                                            isSearchVisible = false
                                        }
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Close,
                                    contentDescription = "Close Search",
                                    tint = TextSecondary,
                                    modifier = Modifier.size(14.dp)
                                )
                            }
                        }
                    } else if (isSearchable) {
                        Spacer(modifier = Modifier.width(GRID_SPACING))

                        Box(
                            modifier = Modifier
                                .height(PARAMETER_TOOLBAR_HEIGHT)
                                .clip(toolbarShape)
                                .background(StudioSurface)
                                .border(1.dp, StudioPanelBorder, toolbarShape)
                                .clickable { isSearchVisible = true }
                                .padding(horizontal = 8.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Search,
                                    contentDescription = "Filter Parameters",
                                    tint = TextSecondary,
                                    modifier = Modifier.size(13.dp)
                                )

                                Spacer(modifier = Modifier.width(4.dp))

                                // Narrow screens drop the word and keep the count on one line
                                var isLabelCompact by remember(parameters.size) { mutableStateOf(false) }

                                Text(
                                    text = if (isLabelCompact) {
                                        "${parameters.size}"
                                    } else {
                                        "Filter (${parameters.size})"
                                    },
                                    fontSize = 10.sp,
                                    fontFamily = FontFamily.Monospace,
                                    color = TextSecondary,
                                    maxLines = 1,
                                    softWrap = false,
                                    onTextLayout = { layout ->
                                        if (layout.didOverflowWidth) {
                                            isLabelCompact = true
                                        }
                                    }
                                )
                            }
                        }
                    }
                }
            }

            Box(modifier = Modifier.fillMaxSize()) {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(GRID_COLUMN_COUNT),
                    state = gridState,
                    horizontalArrangement = Arrangement.spacedBy(GRID_SPACING),
                    verticalArrangement = Arrangement.spacedBy(GRID_SPACING),
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(end = GRID_END_PADDING)
                ) {
                    items(
                        items = filteredParameters,
                        key = { param -> "${slotIndex}_${pluginId}_${param.id}" },
                        contentType = { param -> param.inferredType.javaClass.simpleName }
                    ) { param ->
                        val currentValue = parameterValues[param.id] ?: param.defaultValue
                        val paramType = param.inferredType
                        ParameterCard(
                            paramName = param.name,
                            paramType = paramType,
                            defaultValue = param.defaultValue,
                            minimumValue = param.minimumValue,
                            maximumValue = param.maximumValue,
                            value = currentValue,
                            slotIndex = slotIndex,
                            onValueChange = { newValue -> onValueChange(param, newValue) }
                        )
                    }
                }

                GridVerticalScrollBar(
                    gridState = gridState,
                    totalItems = filteredParameters.size,
                    estimatedRowHeightDp = PARAMETER_CARD_HEIGHT + GRID_SPACING,
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .offset(x = SCROLLBAR_HORIZONTAL_OFFSET)
                        .fillMaxHeight()
                        .width(SCROLLBAR_WIDTH)
                        .padding(vertical = SCROLLBAR_TRACK_PADDING)
                )
            }
        }
    }
}

@Composable
fun ParameterCard(
    paramName: String,
    paramType: ParameterType,
    defaultValue: Double,
    minimumValue: Double,
    maximumValue: Double,
    value: Double,
    slotIndex: Int = 0,
    onValueChange: (Double) -> Unit,
    modifier: Modifier = Modifier
) {
    val activeAccent = when (slotIndex) {
        0 -> BlossomCoral
        else -> PeriwinkleBlue
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(PARAMETER_CARD_HEIGHT)
            .clip(RoundedCornerShape(PARAMETER_CARD_CORNER_RADIUS))
            .background(StudioSurface)
            .border(1.dp, StudioPanelBorder, RoundedCornerShape(PARAMETER_CARD_CORNER_RADIUS))
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 6.dp, vertical = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // 1. Parameter Name Header
            Text(
                text = paramName,
                fontSize = 9.5.sp,
                fontWeight = FontWeight.Bold,
                color = TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )

            // 2. Dedicated Type Control Area
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                when (paramType) {
                    is ParameterType.BoolType -> {
                        BooleanParameterToggle(
                            isOn = value >= PARAMETER_BOOLEAN_ACTIVE_THRESHOLD,
                            offLabel = paramType.offLabel,
                            onLabel = paramType.onLabel,
                            activeColor = activeAccent,
                            onToggle = onValueChange
                        )
                    }

                    is ParameterType.EnumType -> {
                        EnumParameterSelector(
                            currentValue = value,
                            options = paramType.options,
                            activeColor = activeAccent,
                            onSelect = onValueChange
                        )
                    }

                    is ParameterType.IntType -> {
                        FlatRotaryKnob(
                            value = value,
                            minimumValue = paramType.min.toDouble(),
                            maximumValue = paramType.max.toDouble(),
                            defaultValue = defaultValue,
                            step = PARAMETER_INT_DISCRETE_STEP,
                            activeColor = activeAccent,
                            size = PARAMETER_KNOB_SIZE,
                            onValueChange = onValueChange
                        )
                    }

                    is ParameterType.FloatType -> {
                        FlatRotaryKnob(
                            value = value,
                            minimumValue = minimumValue,
                            maximumValue = maximumValue,
                            defaultValue = defaultValue,
                            step = null,
                            activeColor = activeAccent,
                            size = PARAMETER_KNOB_SIZE,
                            onValueChange = onValueChange
                        )
                    }
                }
            }

            // 3. Current Value (toggles and dropdowns already show their current label in the control itself)
            if (paramType !is ParameterType.BoolType && paramType !is ParameterType.EnumType) {
                val valueText = when (paramType) {
                    is ParameterType.IntType -> "${value.toInt()}"
                    is ParameterType.FloatType -> formatFastDecimal(value, 2)
                    is ParameterType.BoolType, is ParameterType.EnumType -> ""
                }

                Text(
                    text = valueText,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    letterSpacing = 0.2.sp,
                    color = activeAccent,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}
