package org.androidaudioplugin.greenhouse.ui.screens.rack

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.androidaudioplugin.greenhouse.data.SlotHostSettings
import org.androidaudioplugin.greenhouse.ui.RackSlotData
import org.androidaudioplugin.greenhouse.ui.components.HostFader
import org.androidaudioplugin.greenhouse.ui.components.LevelFader
import org.androidaudioplugin.greenhouse.ui.components.formatLevelDb
import org.androidaudioplugin.greenhouse.ui.host.RackController
import org.androidaudioplugin.greenhouse.ui.theme.tabularTextStyle
import org.androidaudioplugin.greenhouse.ui.theme.SproutGreen
import org.androidaudioplugin.greenhouse.ui.theme.StudioPanelBorder
import org.androidaudioplugin.greenhouse.ui.theme.StudioSurface
import org.androidaudioplugin.greenhouse.ui.theme.TextSecondary
import kotlin.math.roundToInt

private const val PERCENT_SCALE = 100f
private val HOST_CONTROL_HORIZONTAL_PADDING = 10.dp
private val COMPACT_HOST_CONTROL_HORIZONTAL_PADDING = 8.dp
// Room for the widest value of each control ("+12.0 dB", "100%"), so the unit is never cut off and
// the slider does not move while the value changes
private val LEVEL_VALUE_WIDTH = 54.dp
private val MIX_VALUE_WIDTH = 30.dp

/**
 * The slot's host control (the instrument's output LEVEL, an effect's dry / wet MIX), spanning three
 * parameter cards and styled like the filter button beside it. Its slider and green value set it
 * apart from the plugin's own parameters (knobs). [isCompact] shows the slider alone, one card
 * wide, while the search field shares the line.
 */
@Composable
fun HostControl(
    slot: RackSlotData,
    onLevelChange: (Float) -> Unit,
    onMixChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    isCompact: Boolean = false
) {
    val shape = RoundedCornerShape(PARAMETER_TOOLBAR_CORNER_RADIUS)
    val isInstrument = slot.index == RackController.INSTRUMENT_SLOT_INDEX

    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(PARAMETER_TOOLBAR_HEIGHT)
            .clip(shape)
            .background(StudioSurface)
            .border(1.dp, StudioPanelBorder, shape)
            .padding(
                horizontal = if (isCompact) {
                    COMPACT_HOST_CONTROL_HORIZONTAL_PADDING
                } else {
                    HOST_CONTROL_HORIZONTAL_PADDING
                }
            ),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Compact: the slider takes the whole control, without label or value
        if (!isCompact) {
            val label = if (isInstrument) {
                "LEVEL"
            } else {
                "MIX"
            }

            Text(
                text = label,
                fontSize = 10.sp,
                color = TextSecondary
            )

            Spacer(modifier = Modifier.width(8.dp))
        }

        // Takes whatever the label and value leave
        val faderModifier = Modifier
            .weight(1f)
            .fillMaxHeight()

        if (isInstrument) {
            LevelFader(
                levelDb = slot.levelDb,
                minLevelDb = SlotHostSettings.MIN_LEVEL_DB,
                maxLevelDb = SlotHostSettings.MAX_LEVEL_DB,
                onLevelChange = onLevelChange,
                modifier = faderModifier
            )
        } else {
            HostFader(
                position = slot.mix,
                defaultPosition = SlotHostSettings.DEFAULT_MIX,
                onPositionChange = onMixChange,
                modifier = faderModifier
            )
        }

        if (!isCompact) {
            val valueText = if (isInstrument) {
                formatLevelDb(slot.levelDb, SlotHostSettings.MIN_LEVEL_DB)
            } else {
                "${(slot.mix * PERCENT_SCALE).roundToInt()}%"
            }

            Text(
                text = valueText,
                fontSize = 10.sp,
                style = tabularTextStyle,
                color = SproutGreen,
                textAlign = TextAlign.End,
                maxLines = 1,
                softWrap = false,
                modifier = Modifier.width(
                    if (isInstrument) {
                        LEVEL_VALUE_WIDTH
                    } else {
                        MIX_VALUE_WIDTH
                    }
                )
            )
        }
    }
}
