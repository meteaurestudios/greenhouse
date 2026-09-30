package org.androidaudioplugin.greenhouse.ui.screens.rack

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.androidaudioplugin.greenhouse.core.TransportState
import org.androidaudioplugin.greenhouse.ui.HostViewModel
import org.androidaudioplugin.greenhouse.ui.host.SequencerController
import org.androidaudioplugin.greenhouse.ui.components.MidiDin5Icon
import org.androidaudioplugin.greenhouse.ui.components.StepperControl
import org.androidaudioplugin.greenhouse.ui.components.StudioKeyboard
import org.androidaudioplugin.greenhouse.ui.theme.*

private val KEYBOARD_HEIGHT = 90.dp
private val KEYBOARD_BLACK_KEY_HEIGHT = 52.dp
private const val KEYBOARD_NUM_WHITE_KEYS = 14
private val KEYBOARD_PANEL_CORNER_RADIUS = 12.dp
// Same height as the sequencer controls under it
private val KEYBOARD_CONTROL_BUTTON_SIZE = 32.dp
private val KEYBOARD_CONTROL_ICON_SIZE = 16.dp
private val KEYBOARD_FOLD_ICON_SIZE = 20.dp
private val KEYBOARD_CONTROL_TEXT_SIZE = 10.5.sp
private val LOOP_PROGRESS_HEIGHT = 2.5.dp
// Clear of the button's rounded corners
private val LOOP_PROGRESS_INSET = 6.dp
private val LOOP_PROGRESS_BOTTOM_MARGIN = 3.dp
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

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(KEYBOARD_PANEL_CORNER_RADIUS),
        colors = CardDefaults.cardColors(containerColor = StudioSurfaceVariant),
        border = androidx.compose.foundation.BorderStroke(1.dp, StudioPanelBorder)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth()
        ) {
            // Attached Control Header Strip
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Left: SEQUENCE Button, shows / hides the sequencer strip
                SequenceToggleButton(sequencer = viewModel.sequencer, isOn = isSequencerVisible) {
                    isSequencerVisible = !isSequencerVisible
                }

                // Center / Right Controls
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val hasMidiDeviceAttached = viewModel.midi.availableDevices.isNotEmpty() || viewModel.midi.isDeviceConnected
                    val isMidiInUse = viewModel.midi.isDeviceConnected && viewModel.midi.activeDevice != null

                    // MIDI Controller Icon Button (Always visible if hardware controller is connected to the device)
                    if (hasMidiDeviceAttached) {
                        Box(
                            modifier = Modifier
                                .size(KEYBOARD_CONTROL_BUTTON_SIZE)
                                .clip(RoundedCornerShape(6.dp))
                                .background(
                                    if (isMidiInUse) {
                                        SproutGreen.copy(alpha = 0.15f)
                                    } else {
                                        StudioSurfaceElevated
                                    }
                                )
                                .border(
                                    width = 1.dp,
                                    color = if (isMidiInUse) {
                                        SproutGreen.copy(alpha = 0.5f)
                                    } else {
                                        StudioPanelBorder
                                    },
                                    shape = RoundedCornerShape(6.dp)
                                )
                                .clickable { showMidiMenuDialog = true },
                            contentAlignment = Alignment.Center
                        ) {
                            MidiDin5Icon(
                                tint = if (isMidiInUse) {
                                    SproutGreen
                                } else {
                                    TextMuted
                                },
                                modifier = Modifier.size(KEYBOARD_CONTROL_ICON_SIZE)
                            )
                        }
                    }

                    // HOLD Button
                    KeyboardToggleButton(label = "HOLD", isOn = isHoldEnabled) {
                        viewModel.keyboard.toggleHold()
                    }

                    // Octave Stepper (- / lowest octave / +): the keys label their octaves, the lowest is enough here
                    StepperControl(
                        label = startNoteName,
                        canDecrement = octave > MIN_OCTAVE,
                        canIncrement = octave < MAX_OCTAVE,
                        decrementDescription = "Octave Down",
                        incrementDescription = "Octave Up",
                        onDecrement = { viewModel.keyboard.octave = octave - 1 },
                        onIncrement = { viewModel.keyboard.octave = octave + 1 },
                        height = KEYBOARD_CONTROL_BUTTON_SIZE,
                        iconSize = KEYBOARD_CONTROL_ICON_SIZE,
                        fontSize = KEYBOARD_CONTROL_TEXT_SIZE
                    )

                    // Hide / Fold Toggle Button
                    Box(
                        modifier = Modifier
                            .size(KEYBOARD_CONTROL_BUTTON_SIZE)
                            .clip(RoundedCornerShape(6.dp))
                            .background(StudioSurfaceElevated)
                            .border(1.dp, StudioPanelBorder, RoundedCornerShape(6.dp))
                            .clickable {
                                isKeyboardFolded = !isKeyboardFolded
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = if (isKeyboardFolded) {
                                Icons.Default.KeyboardArrowUp
                            } else {
                                Icons.Default.KeyboardArrowDown
                            },
                            contentDescription = if (isKeyboardFolded) {
                                "Show Keyboard"
                            } else {
                                "Hide Keyboard"
                            },
                            tint = TextSecondary,
                            modifier = Modifier.size(KEYBOARD_FOLD_ICON_SIZE)
                        )
                    }
                }
            }

            // Sequencer transport and lane: stays visible when the keys are folded
            if (isSequencerVisible) {
                HorizontalDivider(
                    thickness = 1.dp,
                    color = StudioPanelBorder
                )

                SequencerStrip(viewModel = viewModel)
            }

            // Attached Keyboard Surface
            if (!isKeyboardFolded) {
                HorizontalDivider(
                    thickness = 1.dp,
                    color = StudioPanelBorder
                )

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
                )
            }
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
 * Shows / hides the sequencer strip. Hiding it ends recording, which it could no longer show. While
 * playing, a bar along its bottom shows the position in the loop. It reads the sequencer on its own,
 * so the rest of the keyboard does not recompose with it.
 */
@Composable
private fun SequenceToggleButton(
    sequencer: SequencerController,
    isOn: Boolean,
    onToggle: () -> Unit
) {
    val status = sequencer.status

    KeyboardToggleButton(
        label = "SEQUENCE",
        isOn = isOn,
        modifier = if (status.state == TransportState.PLAYING) {
            val playheadTick = rememberPlayheadTick(sequencer)
            Modifier.loopProgress(positionProvider = { playheadTick.doubleValue }, lengthTicks = status.lengthTicks)
        } else {
            Modifier
        }
    ) {
        onToggle()

        if (isOn && status.isRecording) {
            sequencer.toggleRecording()
        }
    }
}

/** A text toggle of the keyboard header, highlighted when it is on. */
@Composable
private fun KeyboardToggleButton(
    label: String,
    isOn: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .height(KEYBOARD_CONTROL_BUTTON_SIZE)
            .clip(RoundedCornerShape(6.dp))
            .background(
                if (isOn) {
                    SproutGreen.copy(alpha = 0.15f)
                } else {
                    StudioSurfaceElevated
                }
            )
            .border(
                width = 1.dp,
                color = if (isOn) {
                    SproutGreen
                } else {
                    StudioPanelBorder
                },
                shape = RoundedCornerShape(6.dp)
            )
            .then(modifier)
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            fontSize = KEYBOARD_CONTROL_TEXT_SIZE,
            fontWeight = FontWeight.Bold,
            color = if (isOn) {
                SproutGreen
            } else {
                TextSecondary
            },
            letterSpacing = 0.5.sp
        )
    }
}

/** Position in the loop, drawn as a rounded line near the bottom. The position is read while drawing, so it moves without recomposing. */
private fun Modifier.loopProgress(positionProvider: () -> Double, lengthTicks: Long): Modifier {
    return drawBehind {
        val thickness = LOOP_PROGRESS_HEIGHT.toPx()
        val y = size.height - LOOP_PROGRESS_BOTTOM_MARGIN.toPx() - thickness / 2
        val start = LOOP_PROGRESS_INSET.toPx()
        val end = size.width - start
        val played = start + (end - start) * (positionProvider() / lengthTicks).toFloat().coerceIn(0f, 1f)
        drawLine(StudioPanelBorder, Offset(start, y), Offset(end, y), thickness, StrokeCap.Round)
        drawLine(SproutGreen, Offset(start, y), Offset(played, y), thickness, StrokeCap.Round)
    }
}
