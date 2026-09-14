package org.androidaudioplugin.greenhouse.ui.screens.rack

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.androidaudioplugin.greenhouse.ui.HostViewModel
import org.androidaudioplugin.greenhouse.ui.components.MidiDin5Icon
import org.androidaudioplugin.greenhouse.ui.components.StudioKeyboard
import org.androidaudioplugin.greenhouse.ui.theme.*

private val KEYBOARD_HEIGHT = 90.dp
private val KEYBOARD_BLACK_KEY_HEIGHT = 52.dp
private const val KEYBOARD_NUM_WHITE_KEYS = 14
private val KEYBOARD_PANEL_CORNER_RADIUS = 12.dp
private val KEYBOARD_CONTROL_BUTTON_SIZE = 26.dp
private val KEYBOARD_CONTROL_ICON_SIZE = 14.dp
private const val NOTES_PER_OCTAVE = 12
private const val MIDI_MAX_NOTE = 127
private const val OCTAVE_SPAN = 2
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
    val noteOnStates = viewModel.keyboardNoteOnStates
    val octave = viewModel.keyboardOctave
    val isHoldEnabled = viewModel.isKeyboardHoldActive
    var isKeyboardFolded by remember { mutableStateOf(false) }
    var showMidiMenuDialog by remember { mutableStateOf(false) }

    // Start & End notes covering full MIDI range 0..127 across octaves 0..9
    val startNote = octave * NOTES_PER_OCTAVE
    val endNote = minOf(MIDI_MAX_NOTE, (octave + OCTAVE_SPAN) * NOTES_PER_OCTAVE - 1)
    val startNoteName = getNoteName(startNote)
    val endNoteName = getNoteName(endNote)

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
                // Left: Octave Stepper (- / OCT RANGE / +)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    val isOctaveDownEnabled = octave > MIN_OCTAVE
                    val isOctaveUpEnabled = octave < MAX_OCTAVE

                    // Octave Down (-)
                    Box(
                        modifier = Modifier
                            .size(KEYBOARD_CONTROL_BUTTON_SIZE)
                            .clip(RoundedCornerShape(6.dp))
                            .background(
                                if (isOctaveDownEnabled) {
                                    StudioSurfaceElevated
                                } else {
                                    StudioSurfaceElevated.copy(alpha = 0.4f)
                                }
                            )
                            .border(
                                width = 1.dp,
                                color = if (isOctaveDownEnabled) {
                                    StudioPanelBorder
                                } else {
                                    Color.Transparent
                                },
                                shape = RoundedCornerShape(6.dp)
                            )
                            .clickable(enabled = isOctaveDownEnabled) {
                                viewModel.keyboardOctave = octave - 1
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Remove,
                            contentDescription = "Octave Down",
                            tint = if (isOctaveDownEnabled) {
                                SproutGreen
                            } else {
                                TextMuted
                            },
                            modifier = Modifier.size(KEYBOARD_CONTROL_ICON_SIZE)
                        )
                    }

                    // Note Range Display Badge (e.g. C3 – B4)
                    Box(
                        modifier = Modifier
                            .height(KEYBOARD_CONTROL_BUTTON_SIZE)
                            .clip(RoundedCornerShape(6.dp))
                            .background(StudioSurfaceElevated)
                            .border(1.dp, StudioPanelBorder, RoundedCornerShape(6.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(5.dp),
                            modifier = Modifier.padding(horizontal = 8.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(5.dp)
                                    .clip(CircleShape)
                                    .background(SproutGreen)
                            )

                            Text(
                                text = "$startNoteName – $endNoteName",
                                fontSize = 10.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                color = TextPrimary,
                                letterSpacing = 0.5.sp
                            )
                        }
                    }

                    // Octave Up (+)
                    Box(
                        modifier = Modifier
                            .size(KEYBOARD_CONTROL_BUTTON_SIZE)
                            .clip(RoundedCornerShape(6.dp))
                            .background(
                                if (isOctaveUpEnabled) {
                                    StudioSurfaceElevated
                                } else {
                                    StudioSurfaceElevated.copy(alpha = 0.4f)
                                }
                            )
                            .border(
                                width = 1.dp,
                                color = if (isOctaveUpEnabled) {
                                    StudioPanelBorder
                                } else {
                                    Color.Transparent
                                },
                                shape = RoundedCornerShape(6.dp)
                            )
                            .clickable(enabled = isOctaveUpEnabled) {
                                viewModel.keyboardOctave = octave + 1
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = "Octave Up",
                            tint = if (isOctaveUpEnabled) {
                                SproutGreen
                            } else {
                                TextMuted
                            },
                            modifier = Modifier.size(KEYBOARD_CONTROL_ICON_SIZE)
                        )
                    }
                }

                // Center / Right Controls
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val hasMidiDeviceAttached = viewModel.availableMidiDevices.isNotEmpty() || viewModel.isMidiDeviceConnected
                    val isMidiInUse = viewModel.isMidiDeviceConnected && viewModel.activeMidiDevice != null

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
                    Box(
                        modifier = Modifier
                            .height(KEYBOARD_CONTROL_BUTTON_SIZE)
                            .clip(RoundedCornerShape(6.dp))
                            .background(
                                if (isHoldEnabled) {
                                    SproutGreen.copy(alpha = 0.15f)
                                } else {
                                    StudioSurfaceElevated
                                }
                            )
                            .border(
                                width = 1.dp,
                                color = if (isHoldEnabled) {
                                    SproutGreen
                                } else {
                                    StudioPanelBorder
                                },
                                shape = RoundedCornerShape(6.dp)
                            )
                            .clickable {
                                viewModel.toggleKeyboardHold()
                            }
                            .padding(horizontal = 8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(5.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(5.dp)
                                    .clip(CircleShape)
                                    .background(
                                        if (isHoldEnabled) {
                                            SproutGreen
                                        } else {
                                            TextMuted
                                        }
                                    )
                            )

                            Text(
                                text = "HOLD",
                                fontSize = 9.5.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                color = if (isHoldEnabled) {
                                    SproutGreen
                                } else {
                                    TextSecondary
                                },
                                letterSpacing = 0.5.sp
                            )
                        }
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
                        viewModel.onKeyboardNoteOn(note)
                    },
                    onNoteOff = { note ->
                        viewModel.onKeyboardNoteOff(note)
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
