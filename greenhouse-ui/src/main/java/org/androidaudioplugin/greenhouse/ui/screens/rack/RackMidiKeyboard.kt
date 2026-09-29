package org.androidaudioplugin.greenhouse.ui.screens.rack

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.androidaudioplugin.greenhouse.core.TransportState
import org.androidaudioplugin.greenhouse.ui.HostViewModel
import org.androidaudioplugin.greenhouse.ui.host.SequencerController
import org.androidaudioplugin.greenhouse.ui.components.MidiDin5Icon
import org.androidaudioplugin.greenhouse.ui.components.StudioKeyboard
import org.androidaudioplugin.greenhouse.ui.theme.*

private val KEYBOARD_HEIGHT = 90.dp
private val KEYBOARD_BLACK_KEY_HEIGHT = 52.dp
private const val KEYBOARD_NUM_WHITE_KEYS = 14
private val KEYBOARD_PANEL_CORNER_RADIUS = 12.dp
private val KEYBOARD_CONTROL_BUTTON_SIZE = 26.dp
private val KEYBOARD_CONTROL_ICON_SIZE = 14.dp
private val LOOP_PROGRESS_HEIGHT = 2.5.dp
// Clear of the button's rounded corners
private val LOOP_PROGRESS_INSET = 6.dp
private val LOOP_PROGRESS_BOTTOM_MARGIN = 3.dp
// Tap feedback of the octave steps: a circle around the icon, clear of the octave label
private val OCTAVE_STEP_RIPPLE_RADIUS = 10.dp
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
    var isKeyboardFolded by remember { mutableStateOf(false) }
    var isSequencerVisible by remember { mutableStateOf(false) }
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
                // Left: Octave Stepper, then SEQUENCE
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    // Octave Stepper (- / lowest octave / +): the keys label their octaves, the lowest is enough here
                    Row(
                        modifier = Modifier
                            .height(KEYBOARD_CONTROL_BUTTON_SIZE)
                            .clip(RoundedCornerShape(6.dp))
                            .background(StudioSurfaceElevated)
                            .border(1.dp, StudioPanelBorder, RoundedCornerShape(6.dp)),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OctaveStepButton(Icons.Default.Remove, "Octave Down", isEnabled = octave > MIN_OCTAVE) {
                            viewModel.keyboard.octave = octave - 1
                        }

                        Text(
                            text = startNoteName,
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary,
                            letterSpacing = 0.5.sp
                        )

                        OctaveStepButton(Icons.Default.Add, "Octave Up", isEnabled = octave < MAX_OCTAVE) {
                            viewModel.keyboard.octave = octave + 1
                        }
                    }

                    // SEQUENCE Button: shows / hides the sequencer strip
                    SequenceToggleButton(sequencer = viewModel.sequencer, isOn = isSequencerVisible) {
                        isSequencerVisible = !isSequencerVisible
                    }
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
                            modifier = Modifier.size(16.dp)
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
            Modifier.loopProgress(positionProvider = { sequencer.positionTick }, lengthTicks = status.lengthTicks)
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
            fontSize = 9.5.sp,
            fontFamily = FontFamily.Monospace,
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

/** An octave step at one end of the octave stepper. */
@Composable
private fun OctaveStepButton(
    icon: ImageVector,
    contentDescription: String,
    isEnabled: Boolean,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(KEYBOARD_CONTROL_BUTTON_SIZE)
            .clickable(
                interactionSource = null,
                indication = ripple(bounded = false, radius = OCTAVE_STEP_RIPPLE_RADIUS),
                enabled = isEnabled,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = if (isEnabled) {
                SproutGreen
            } else {
                TextMuted
            },
            modifier = Modifier.size(KEYBOARD_CONTROL_ICON_SIZE)
        )
    }
}

/** Position in the loop, drawn as a rounded line near the bottom. The position is read while drawing, so it moves without recomposing. */
private fun Modifier.loopProgress(positionProvider: () -> Long, lengthTicks: Long): Modifier {
    return drawBehind {
        val thickness = LOOP_PROGRESS_HEIGHT.toPx()
        val y = size.height - LOOP_PROGRESS_BOTTOM_MARGIN.toPx() - thickness / 2
        val start = LOOP_PROGRESS_INSET.toPx()
        val end = size.width - start
        val played = start + (end - start) * positionProvider().coerceIn(0L, lengthTicks) / lengthTicks
        drawLine(StudioPanelBorder, Offset(start, y), Offset(end, y), thickness, StrokeCap.Round)
        drawLine(SproutGreen, Offset(start, y), Offset(played, y), thickness, StrokeCap.Round)
    }
}
