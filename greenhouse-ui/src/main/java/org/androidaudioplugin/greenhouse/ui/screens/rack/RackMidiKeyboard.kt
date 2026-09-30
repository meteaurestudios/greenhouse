package org.androidaudioplugin.greenhouse.ui.screens.rack

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.androidaudioplugin.greenhouse.core.TransportState
import org.androidaudioplugin.greenhouse.ui.HostViewModel
import org.androidaudioplugin.greenhouse.ui.host.SequencerController
import org.androidaudioplugin.greenhouse.ui.components.CONTROL_ACTIVE_FILL_ALPHA
import org.androidaudioplugin.greenhouse.ui.components.CONTROL_CORNER_RADIUS
import org.androidaudioplugin.greenhouse.ui.components.CONTROL_HEIGHT
import org.androidaudioplugin.greenhouse.ui.components.CONTROL_HORIZONTAL_PADDING
import org.androidaudioplugin.greenhouse.ui.components.CONTROL_ICON_SIZE
import org.androidaudioplugin.greenhouse.ui.components.CONTROL_LABEL_FONT_SIZE
import org.androidaudioplugin.greenhouse.ui.components.CONTROL_SPACING
import org.androidaudioplugin.greenhouse.ui.components.ControlButton
import org.androidaudioplugin.greenhouse.ui.components.MidiDin5Icon
import org.androidaudioplugin.greenhouse.ui.components.controlContentColor
import org.androidaudioplugin.greenhouse.ui.components.controlSurface
import org.androidaudioplugin.greenhouse.ui.components.panelSurface
import org.androidaudioplugin.greenhouse.ui.components.toggleClickable
import org.androidaudioplugin.greenhouse.ui.components.StepperControl
import org.androidaudioplugin.greenhouse.ui.components.StudioKeyboard
import org.androidaudioplugin.greenhouse.ui.theme.*

private val KEYBOARD_HEIGHT = 90.dp
private val KEYBOARD_BLACK_KEY_HEIGHT = 52.dp
private const val KEYBOARD_NUM_WHITE_KEYS = 14
private val KEYBOARD_PANEL_PADDING = 10.dp
private val KEYBOARD_PANEL_SPACING = 10.dp
private val KEYBOARD_KEYS_CORNER_RADIUS = 10.dp
// The open sequencer: a recessed tray, and its tab in the header joined to it (square where they meet)
private val SEQUENCER_TRAY_COLOR = StudioBackground
private val SEQUENCER_TRAY_PADDING = 8.dp
// How far the open tab reaches below the header down to the tray: also the other controls' gap above it
private val SEQUENCER_TAB_JOIN_HEIGHT = 6.dp
private const val QUARTER_TURN_DEGREES = 90f
private val SEQUENCER_TAB_SHAPE = RoundedCornerShape(topStart = CONTROL_CORNER_RADIUS, topEnd = CONTROL_CORNER_RADIUS)
private val SEQUENCER_TRAY_SHAPE = RoundedCornerShape(
    topStart = 0.dp,
    topEnd = CONTROL_CORNER_RADIUS,
    bottomStart = CONTROL_CORNER_RADIUS,
    bottomEnd = CONTROL_CORNER_RADIUS
)
// Room for the widest octave name ("C-1"), so the steps do not move
private val OCTAVE_LABEL_WIDTH = 30.dp
private const val NOTES_PER_OCTAVE = 12
private const val MIN_OCTAVE = 0
private const val MAX_OCTAVE = 9

private fun getNoteName(note: Int): String {
    val noteNames = arrayOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")
    val name = noteNames[note % NOTES_PER_OCTAVE]
    val oct = (note / NOTES_PER_OCTAVE) - 1
    return "$name$oct"
}

@Composable
fun MidiKeyboardSection(
    viewModel: HostViewModel
) {
    val noteOnStates = viewModel.keyboard.noteOnStates
    val octave = viewModel.keyboard.octave
    val isHoldEnabled = viewModel.keyboard.isHoldActive
    // Saveable: kept while the plugin browser is open, as the rack screen leaves the composition
    var isKeyboardFolded by rememberSaveable { mutableStateOf(false) }
    var isSequencerVisible by rememberSaveable { mutableStateOf(false) }
    var showMidiMenuDialog by remember { mutableStateOf(false) }

    // Lowest note of the keys, covering octaves 0..9
    val startNoteName = getNoteName(octave * NOTES_PER_OCTAVE)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .panelSurface()
            .padding(KEYBOARD_PANEL_PADDING),
        verticalArrangement = Arrangement.spacedBy(KEYBOARD_PANEL_SPACING)
    ) {
        // The header, and the sequencer under it while open, joined to its tab
        Column(modifier = Modifier.fillMaxWidth()) {
            // Control header: the sequencer tab, then the keyboard's own controls. Top-aligned: the
            // open tab reaches down to the sequencer, the other controls keep their gap above it.
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(CONTROL_SPACING),
                verticalAlignment = Alignment.Top
            ) {
                // Shows / hides the sequencer strip
                SequenceToggleButton(sequencer = viewModel.sequencer, isOn = isSequencerVisible) {
                    isSequencerVisible = !isSequencerVisible
                }

                Spacer(modifier = Modifier.weight(1f))

                val hasMidiDeviceAttached = viewModel.midi.availableDevices.isNotEmpty() || viewModel.midi.isDeviceConnected
                val isMidiInUse = viewModel.midi.isDeviceConnected && viewModel.midi.activeDevice != null

                // MIDI controller button, while a hardware controller is connected to the device
                if (hasMidiDeviceAttached) {
                    Box(
                        modifier = Modifier
                            .size(CONTROL_HEIGHT)
                            .controlSurface(isActive = isMidiInUse)
                            .clickable { showMidiMenuDialog = true },
                        contentAlignment = Alignment.Center
                    ) {
                        MidiDin5Icon(
                            tint = controlContentColor(isActive = isMidiInUse),
                            modifier = Modifier.size(CONTROL_ICON_SIZE)
                        )
                    }
                }

                KeyboardToggleButton(label = "Hold", isOn = isHoldEnabled) {
                    viewModel.keyboard.toggleHold()
                }

                // Octave stepper (- / lowest octave / +): the keys label their octaves, the lowest is enough here
                StepperControl(
                    label = startNoteName,
                    canDecrement = octave > MIN_OCTAVE,
                    canIncrement = octave < MAX_OCTAVE,
                    decrementDescription = "Octave Down",
                    incrementDescription = "Octave Up",
                    onDecrement = { viewModel.keyboard.octave = octave - 1 },
                    onIncrement = { viewModel.keyboard.octave = octave + 1 },
                    labelWidth = OCTAVE_LABEL_WIDTH
                )

                ControlButton(
                    label = null,
                    icon = if (isKeyboardFolded) {
                        Icons.Default.KeyboardArrowUp
                    } else {
                        Icons.Default.KeyboardArrowDown
                    },
                    contentDescription = if (isKeyboardFolded) {
                        "Show Keyboard"
                    } else {
                        "Hide Keyboard"
                    },
                    onClick = { isKeyboardFolded = !isKeyboardFolded }
                )
            }

            // Sequencer transport and lane, in a recessed tray under its tab: stays visible when the keys are folded
            if (isSequencerVisible) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(SEQUENCER_TRAY_SHAPE)
                        .background(SEQUENCER_TRAY_COLOR)
                        .padding(SEQUENCER_TRAY_PADDING)
                ) {
                    SequencerStrip(viewModel = viewModel)
                }
            }
        }

        if (!isKeyboardFolded) {
            StudioKeyboard(
                noteOnStates = noteOnStates.toList(),
                octaveZeroBased = octave,
                numWhiteKeys = KEYBOARD_NUM_WHITE_KEYS,
                totalHeight = KEYBOARD_HEIGHT,
                blackKeyHeight = KEYBOARD_BLACK_KEY_HEIGHT,
                whiteKeyColor = KeyboardWhiteKey,
                blackKeyColor = KeyboardBlackKey,
                whiteNoteOnColor = SproutGreen,
                blackNoteOnColor = SproutGreen,
                onNoteOn = { note ->
                    viewModel.keyboard.noteOn(note)
                },
                onNoteOff = { note ->
                    viewModel.keyboard.noteOff(note)
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(KEYBOARD_HEIGHT)
                    .clip(RoundedCornerShape(KEYBOARD_KEYS_CORNER_RADIUS))
            )
        }
    }

    if (showMidiMenuDialog) {
        MidiDeviceSelectionDialog(
            viewModel = viewModel,
            onDismiss = { showMidiMenuDialog = false }
        )
    }
}

/**
 * Shows / hides the sequencer strip. Open, it is the tab of the sequencer tray: in the tray's
 * colour, reaching down to join it. Hiding it ends recording, which it could no longer show. While
 * playing with the strip hidden, it fills up with the position in the loop. It reads the
 * sequencer on its own, so the rest of the keyboard does not recompose with it.
 */
@Composable
private fun SequenceToggleButton(
    sequencer: SequencerController,
    isOn: Boolean,
    onToggle: () -> Unit
) {
    val status = sequencer.status
    val progressModifier = if (!isOn && status.state == TransportState.PLAYING) {
        val playheadTick = rememberPlayheadTick(sequencer)
        Modifier.loopProgress(positionProvider = { playheadTick.doubleValue }, lengthTicks = status.lengthTicks)
    } else {
        Modifier
    }

    val surfaceModifier = if (isOn) {
        Modifier
            .height(CONTROL_HEIGHT + SEQUENCER_TAB_JOIN_HEIGHT)
            .trayJoinCorner()
            .clip(SEQUENCER_TAB_SHAPE)
            .background(SEQUENCER_TRAY_COLOR)
    } else {
        Modifier
            .height(CONTROL_HEIGHT)
            .controlSurface()
    }

    Box(
        modifier = surfaceModifier
            // After the fill: the loop progress is drawn over it
            .then(progressModifier)
            .toggleClickable {
                onToggle()

                if (isOn && status.isRecording) {
                    sequencer.toggleRecording()
                }
            }
            .padding(horizontal = CONTROL_HORIZONTAL_PADDING),
        // Centered in the whole tab: the short join keeps it close to level with the other controls
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "Sequencer",
            fontSize = CONTROL_LABEL_FONT_SIZE,
            fontWeight = if (isOn) {
                FontWeight.SemiBold
            } else {
                FontWeight.Medium
            },
            color = controlContentColor(isActive = isOn),
            maxLines = 1,
            softWrap = false
        )
    }
}

/** A text toggle of the keyboard header, highlighted when it is on. */
@Composable
private fun KeyboardToggleButton(
    label: String,
    isOn: Boolean,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .height(CONTROL_HEIGHT)
            .controlSurface(isActive = isOn)
            .toggleClickable(onClick = onClick)
            .padding(horizontal = CONTROL_HORIZONTAL_PADDING),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            fontSize = CONTROL_LABEL_FONT_SIZE,
            fontWeight = FontWeight.Medium,
            color = controlContentColor(isActive = isOn),
            maxLines = 1,
            softWrap = false
        )
    }
}

/**
 * The rounded inner corner where the open tab meets the tray, drawn just outside the tab's bottom
 * end: the tray's colour, less a quarter circle.
 */
private fun Modifier.trayJoinCorner(): Modifier {
    return drawBehind {
        val radius = CONTROL_CORNER_RADIUS.toPx()
        val corner = Path().apply {
            moveTo(size.width, size.height - radius)
            // Along the tab's edge down to the tray, along the tray, then the curve back up
            lineTo(size.width, size.height)
            lineTo(size.width + radius, size.height)
            arcTo(
                rect = Rect(size.width, size.height - 2 * radius, size.width + 2 * radius, size.height),
                startAngleDegrees = QUARTER_TURN_DEGREES,
                sweepAngleDegrees = QUARTER_TURN_DEGREES,
                forceMoveTo = false
            )
            close()
        }

        drawPath(corner, SEQUENCER_TRAY_COLOR)
    }
}

/**
 * Position in the loop, as the button filling up with a tint from the left. The position is read
 * while drawing, so it moves without recomposing. Drawn over the button's fill, inside its shape.
 */
private fun Modifier.loopProgress(positionProvider: () -> Double, lengthTicks: Long): Modifier {
    return drawBehind {
        val played = size.width * (positionProvider() / lengthTicks).toFloat().coerceIn(0f, 1f)
        drawRect(SproutGreen.copy(alpha = CONTROL_ACTIVE_FILL_ALPHA), size = Size(played, size.height))
    }
}
