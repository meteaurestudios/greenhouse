package org.androidaudioplugin.greenhouse.ui.screens.rack

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
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
private const val PARAMETER_ENUM_MATCH_EPSILON = 0.0001
private const val PARAMETER_INT_DISCRETE_STEP = 1.0
private val PARAMETER_CARD_HEIGHT = 112.dp
private val PARAMETER_KNOB_SIZE = 54.dp
private val PARAMETER_CARD_CORNER_RADIUS = 12.dp

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

@Composable
fun ParameterControlRack(
    slotIndex: Int,
    pluginId: String,
    parameters: List<ParameterInformation>,
    parameterValues: Map<Int, Double>,
    gridState: LazyGridState,
    onValueChange: (ParameterInformation, Double) -> Unit,
    modifier: Modifier = Modifier
) {
    if (parameters.isEmpty()) {
        Box(
            modifier = modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "This plugin exposes no adjustable parameters.",
                color = TextSecondary,
                fontSize = 14.sp
            )
        }
    } else {
        var isSearchVisible by remember(pluginId) { mutableStateOf(false) }
        var searchQuery by remember(pluginId) { mutableStateOf("") }

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
            if (parameters.size > PARAMETER_SEARCH_THRESHOLD) {
                if (isSearchVisible || searchQuery.isNotEmpty()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(36.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(StudioSurface)
                                .border(1.dp, StudioPanelBorder, RoundedCornerShape(8.dp))
                                .padding(horizontal = 8.dp),
                            contentAlignment = Alignment.CenterStart
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Search,
                                    contentDescription = "Search Parameters",
                                    tint = TextSecondary,
                                    modifier = Modifier.size(16.dp)
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
                                            fontSize = 12.sp,
                                            fontFamily = FontFamily.Monospace
                                        ),
                                        modifier = Modifier.fillMaxWidth(),
                                        decorationBox = { innerTextField ->
                                            Box(
                                                modifier = Modifier.fillMaxWidth(),
                                                contentAlignment = Alignment.CenterStart
                                            ) {
                                                if (searchQuery.isEmpty()) {
                                                    Text(
                                                        text = "Search parameters...",
                                                        color = TextMuted,
                                                        fontSize = 12.sp,
                                                        fontFamily = FontFamily.Monospace
                                                    )
                                                }

                                                innerTextField()
                                            }
                                        }
                                    )
                                }

                                if (searchQuery.isNotEmpty()) {
                                    Box(
                                        modifier = Modifier
                                            .size(20.dp)
                                            .clip(CircleShape)
                                            .clickable { searchQuery = "" },
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            imageVector = Icons.Filled.Close,
                                            contentDescription = "Clear Search",
                                            tint = TextSecondary,
                                            modifier = Modifier.size(14.dp)
                                        )
                                    }
                                }
                            }
                        }

                        Spacer(modifier = Modifier.width(6.dp))

                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(StudioSurface)
                                .border(1.dp, StudioPanelBorder, RoundedCornerShape(8.dp))
                                .clickable {
                                    searchQuery = ""
                                    isSearchVisible = false
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Close,
                                contentDescription = "Close Search",
                                tint = TextSecondary,
                                modifier = Modifier.size(16.dp)
                            )
                        }

                        Spacer(modifier = Modifier.width(6.dp))

                        Text(
                            text = "${filteredParameters.size}/${parameters.size}",
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            color = TextMuted
                        )
                    }
                } else {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 6.dp),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .height(28.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(StudioSurface)
                                .border(1.dp, StudioPanelBorder, RoundedCornerShape(8.dp))
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

                                Text(
                                    text = "Filter (${parameters.size})",
                                    fontSize = 10.sp,
                                    fontFamily = FontFamily.Monospace,
                                    color = TextSecondary
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
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(end = 8.dp)
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
                    estimatedRowHeightDp = PARAMETER_CARD_HEIGHT + 8.dp,
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

            // 3. Combined Value & Range Row: [MIN] [CURRENT VALUE / BADGE] [MAX]
            if (paramType !is ParameterType.BoolType) {
                val valueText = when (paramType) {
                    is ParameterType.EnumType -> {
                        val currentOpt = paramType.options.firstOrNull { option ->
                            kotlin.math.abs(option.value - value) < PARAMETER_ENUM_MATCH_EPSILON
                        }
                        currentOpt?.name ?: formatFastDecimal(value, 1)
                    }

                    is ParameterType.IntType -> "${value.toInt()}"
                    is ParameterType.FloatType -> formatFastDecimal(value, 2)
                    is ParameterType.BoolType -> ""
                }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 2.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = paramType.minText,
                        fontSize = 7.5.sp,
                        fontFamily = FontFamily.Monospace,
                        color = TextMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.align(Alignment.CenterStart)
                    )

                    Box(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .clip(RoundedCornerShape(4.dp))
                            .background(activeAccent.copy(alpha = 0.12f))
                            .border(1.dp, activeAccent.copy(alpha = 0.35f), RoundedCornerShape(4.dp))
                            .padding(horizontal = 4.dp, vertical = 1.dp),
                        contentAlignment = Alignment.Center
                    ) {
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

                    Text(
                        text = paramType.maxText,
                        fontSize = 7.5.sp,
                        fontFamily = FontFamily.Monospace,
                        color = TextMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.align(Alignment.CenterEnd)
                    )
                }
            }
        }
    }
}
