package org.androidaudioplugin.greenhouse.ui.screens.rack

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.androidaudioplugin.greenhouse.core.MidiSequencer
import org.androidaudioplugin.greenhouse.core.TransportState
import org.androidaudioplugin.greenhouse.ui.HostViewModel
import org.androidaudioplugin.greenhouse.ui.host.SequenceNote
import org.androidaudioplugin.greenhouse.ui.host.SequencerController
import org.androidaudioplugin.greenhouse.ui.host.SequencerController.Companion.formatBpm
import org.androidaudioplugin.greenhouse.ui.theme.*
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

private val SEQUENCER_SHAPE = RoundedCornerShape(6.dp)
private val SEQUENCER_CONTROL_SIZE = 26.dp
private val SEQUENCER_ICON_SIZE = 15.dp
private val SEQUENCER_RECORD_DOT_SIZE = 10.dp
private const val ACTIVE_BACKGROUND_ALPHA = 0.15f
private const val IDLE_RECORD_DOT_ALPHA = 0.7f
// A disabled control fades as a whole, not only its content
private const val DISABLED_ALPHA = 0.35f
private const val BLINK_PERIOD_MS = 500
private const val BLINK_MIN_ALPHA = 0.25f
private val LANE_NOTE_MIN_WIDTH = 2.dp
private val LANE_NOTE_HEIGHT = 2.dp
private val LANE_VERTICAL_PADDING = 3.dp
private val LANE_PLAYHEAD_WIDTH = 1.5.dp
private val LANE_BAR_LINE_WIDTH = 1.dp
// First beat of each bar, then the other beats, dimmed
private val LANE_HINT_FONT_SIZE = 8.5.sp
private val LANE_HINT_LETTER_SPACING = 0.5.sp
private val LANE_HINT_PADDING = 4.dp
private const val BAR_LINE_ALPHA = 0.5f
private const val BEAT_LINE_ALPHA = 0.5f
// Beat lines are drawn only while the lane shows this many bars or fewer
private const val MAX_BARS_WITH_BEAT_LINES = 4
// Note opacity follows the velocity, down to this for the softest notes so they stay visible
private const val LANE_NOTE_MIN_ALPHA = 0.3f
private val SHEET_HORIZONTAL_PADDING = 20.dp
private val SHEET_BOTTOM_PADDING = 16.dp
private val SHEET_ROW_SPACING = 14.dp
private val SHEET_DRAG_HANDLE_PADDING = 10.dp
private val SETTINGS_LABEL_WIDTH = 76.dp
private val SETTINGS_PILL_SPACING = 6.dp
private val SETTINGS_CONTROL_HEIGHT = 34.dp
private val SETTINGS_ACTION_ICON_SIZE = 16.dp
private val SETTINGS_DROPDOWN_MIN_WIDTH = 104.dp
// Dragging the tempo field this far sideways changes the tempo by 1 BPM
private val BPM_DRAG_DISTANCE_PER_BPM = 6.dp
private val BPM_FIELD_WIDTH = 136.dp
private const val BPM_TEXT_MAX_LENGTH = 5
private const val MIDI_FILE_EXTENSION = ".mid"
private val MIDI_IMPORT_MIME_TYPES = arrayOf(SequencerController.MIDI_FILE_MIME_TYPE, "audio/x-midi", "*/*")

private fun formatLength(lengthBars: Int): String {
    if (lengthBars == 1) {
        return "1 bar"
    }

    return "$lengthBars bars"
}

/** Rounded, bordered surface of the sequencer controls. */
private fun Modifier.sequencerControl(
    background: Color = StudioSurfaceElevated,
    border: Color = StudioPanelBorder
): Modifier {
    return clip(SEQUENCER_SHAPE)
        .background(background)
        .border(1.dp, border, SEQUENCER_SHAPE)
}

/**
 * Transport strip of the sequencer, under the keyboard header: record, play / stop, a read-only
 * lane of the recorded notes with the playhead, undo, and the tempo chip that opens the settings.
 */
@Composable
fun SequencerStrip(viewModel: HostViewModel) {
    val sequencer = viewModel.sequencer
    val status = sequencer.status
    val isRunning = sequencer.isRunning
    val isArmed = status.state == TransportState.ARMED
    val blinkAlpha = blinkAlpha(isArmed)
    var showSettings by remember { mutableStateOf(false) }
    // Undo removes the highest take, also while it is being recorded
    val undoTake = remember(sequencer.notes) { sequencer.notes.maxOfOrNull { it.take } }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        // Record: arm / punch in, punch out
        ToggleBox(
            isActive = status.isRecording,
            activeColor = BerryRose,
            onClickLabel = "Record",
            onClick = { sequencer.toggleRecording() },
            modifier = Modifier.size(SEQUENCER_CONTROL_SIZE)
        ) {
            Box(
                modifier = Modifier
                    .size(SEQUENCER_RECORD_DOT_SIZE)
                    .graphicsLayer { alpha = blinkAlpha.value }
                    .clip(CircleShape)
                    .background(
                        if (status.isRecording) {
                            BerryRose
                        } else {
                            BerryRose.copy(alpha = IDLE_RECORD_DOT_ALPHA)
                        }
                    )
            )
        }

        // Play from the start / stop
        ToggleBox(
            isActive = isRunning,
            onClickLabel = "Play or Stop Sequence",
            onClick = { sequencer.togglePlayback() },
            modifier = Modifier.size(SEQUENCER_CONTROL_SIZE)
        ) {
            Icon(
                imageVector = if (isRunning) {
                    Icons.Default.Stop
                } else {
                    Icons.Default.PlayArrow
                },
                contentDescription = null,
                modifier = Modifier.size(SEQUENCER_ICON_SIZE)
            )
        }

        // Read-only lane: tap it for the settings too
        Box(
            modifier = Modifier
                .weight(1f)
                .height(SEQUENCER_CONTROL_SIZE)
                .sequencerControl(background = MeterTrackBackground)
                .clickable { showSettings = true },
            contentAlignment = Alignment.Center
        ) {
            // Hidden while armed, under the text
            if (!isArmed) {
                SequenceLane(
                    notes = sequencer.notes,
                    undoTake = undoTake,
                    lengthTicks = status.lengthTicks,
                    positionProvider = { sequencer.positionTick },
                    modifier = Modifier.fillMaxSize()
                )
            }

            if (status.state == TransportState.PLAYING) {
                Playhead(
                    positionProvider = { sequencer.positionTick },
                    lengthTicks = status.lengthTicks,
                    color = if (status.isRecording) {
                        BerryRose
                    } else {
                        TextPrimary
                    },
                    modifier = Modifier.fillMaxSize()
                )
            }

            if (isArmed || (!sequencer.hasEvents && !isRunning)) {
                LaneHint(
                    text = if (isArmed) {
                        "PLAY A NOTE TO START"
                    } else {
                        "PRESS RECORD, THEN PLAY NOTES"
                    },
                    color = if (isArmed) {
                        BerryRose
                    } else {
                        TextMuted
                    },
                    alpha = blinkAlpha
                )
            }
        }

        // Undo: always at hand, right after the lane. While recording, it discards what the take recorded so far.
        // In the color of the take it removes while the lane highlights one.
        ToggleBox(
            isActive = undoTake != null,
            activeColor = TangerineGlow,
            isEnabled = sequencer.hasEvents || status.isRecording,
            onClickLabel = "Undo Last Take",
            onClick = { sequencer.undoLastTake() },
            modifier = Modifier.size(SEQUENCER_CONTROL_SIZE)
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.Undo,
                contentDescription = null,
                modifier = Modifier.size(SEQUENCER_ICON_SIZE)
            )
        }

        // Tempo: opens the settings
        Row(
            modifier = Modifier
                .height(SEQUENCER_CONTROL_SIZE)
                .sequencerControl()
                .clickable { showSettings = true }
                .padding(horizontal = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = "${formatBpm(sequencer.settings.bpm)} bpm",
                fontSize = 9.5.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                color = TextPrimary,
                maxLines = 1
            )

            Icon(
                imageVector = Icons.Default.Tune,
                contentDescription = "Sequencer Settings",
                tint = TextSecondary,
                modifier = Modifier.size(12.dp)
            )
        }
    }

    if (showSettings) {
        SequencerSettingsSheet(
            viewModel = viewModel,
            onDismiss = { showSettings = false }
        )
    }
}

/** A hint on one line over the lane, its font shrunk to fit narrow screens. */
@Composable
private fun LaneHint(
    text: String,
    color: Color,
    alpha: State<Float>
) {
    val textMeasurer = rememberTextMeasurer()

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = LANE_HINT_PADDING),
        contentAlignment = Alignment.Center
    ) {
        val style = TextStyle(
            fontSize = LANE_HINT_FONT_SIZE,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            letterSpacing = LANE_HINT_LETTER_SPACING
        )
        val textWidth = remember(text, textMeasurer) { textMeasurer.measure(text, style).size.width }
        val scale = min(1f, constraints.maxWidth.toFloat() / max(textWidth, 1))

        Text(
            text = text,
            color = color,
            style = style.copy(fontSize = LANE_HINT_FONT_SIZE * scale, letterSpacing = LANE_HINT_LETTER_SPACING * scale),
            maxLines = 1,
            softWrap = false,
            modifier = Modifier.graphicsLayer { this.alpha = alpha.value }
        )
    }
}

/** Blinks while isBlinking. Read it while drawing (graphicsLayer) so it does not recompose. */
@Composable
private fun blinkAlpha(isBlinking: Boolean): State<Float> {
    if (!isBlinking) {
        return remember { mutableFloatStateOf(1f) }
    }

    return rememberInfiniteTransition(label = "sequencerBlink").animateFloat(
        initialValue = 1f,
        targetValue = BLINK_MIN_ALPHA,
        animationSpec = infiniteRepeatable(tween(BLINK_PERIOD_MS, easing = LinearEasing), RepeatMode.Reverse),
        label = "sequencerBlinkAlpha"
    )
}

/** A control that is highlighted while active. Its content takes the matching colour. */
@Composable
private fun ToggleBox(
    isActive: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    activeColor: Color = SproutGreen,
    isEnabled: Boolean = true,
    onClickLabel: String? = null,
    content: @Composable () -> Unit
) {
    val contentColor = when {
        !isEnabled -> TextMuted
        isActive -> activeColor
        else -> TextSecondary
    }

    Box(
        modifier = modifier
            .alpha(
                if (isEnabled) {
                    1f
                } else {
                    DISABLED_ALPHA
                }
            )
            .sequencerControl(
                background = if (isActive) {
                    activeColor.copy(alpha = ACTIVE_BACKGROUND_ALPHA)
                } else {
                    StudioSurfaceElevated
                },
                border = if (isActive) {
                    activeColor
                } else {
                    StudioPanelBorder
                }
            )
            .clickable(enabled = isEnabled, onClickLabel = onClickLabel, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        CompositionLocalProvider(LocalContentColor provides contentColor) {
            content()
        }
    }
}

/**
 * Notes over the loop, highest at the top, with bar lines. Notes still held reach the playhead. The
 * softer a note was played, the fainter it is. The last take (the one undo removes) stands out in
 * its own color.
 */
@Composable
private fun SequenceLane(
    notes: List<SequenceNote>,
    /** Take drawn in the undo color, or null. */
    undoTake: Int?,
    lengthTicks: Long,
    positionProvider: () -> Long,
    modifier: Modifier = Modifier
) {
    val lowestNote = remember(notes) { notes.minOfOrNull { it.note } ?: 0 }
    val highestNote = remember(notes) { notes.maxOfOrNull { it.note } ?: 0 }

    Canvas(modifier = modifier) {
        val bars = (lengthTicks / MidiSequencer.TICKS_PER_BAR).toInt()

        for (beat in 1 until bars * MidiSequencer.BEATS_PER_BAR) {
            val isBarLine = beat % MidiSequencer.BEATS_PER_BAR == 0

            if (!isBarLine && bars > MAX_BARS_WITH_BEAT_LINES) {
                continue
            }

            val x = size.width * (beat.toLong() * MidiSequencer.PPQ) / lengthTicks
            val color = if (isBarLine) {
                TextSecondary.copy(alpha = BAR_LINE_ALPHA)
            } else {
                StudioPanelBorder.copy(alpha = BEAT_LINE_ALPHA)
            }

            drawLine(color, Offset(x, 0f), Offset(x, size.height), LANE_BAR_LINE_WIDTH.toPx())
        }

        val padding = LANE_VERTICAL_PADDING.toPx()
        val noteHeight = LANE_NOTE_HEIGHT.toPx()
        val noteRange = max(highestNote - lowestNote, 1)
        val usableHeight = size.height - 2 * padding - noteHeight

        for (note in notes) {
            val y = padding + usableHeight * (highestNote - note.note) / noteRange
            val baseColor = when {
                note.take == undoTake -> TangerineGlow
                note.slot == 0 -> SproutGreen
                else -> PeriwinkleBlue
            }
            val color = baseColor.copy(alpha = LANE_NOTE_MIN_ALPHA + (1f - LANE_NOTE_MIN_ALPHA) * note.velocity)

            fun drawNote(startTick: Long, endTick: Long) {
                val x0 = size.width * startTick / lengthTicks
                val x1 = size.width * endTick / lengthTicks
                drawRect(color, Offset(x0, y), Size(max(x1 - x0, LANE_NOTE_MIN_WIDTH.toPx()), noteHeight))
            }

            // Read while drawing, only for held notes: the lane redraws with the playhead while one is held
            val endTick = if (note.endTick == SequenceNote.HELD) {
                positionProvider()
            } else {
                note.endTick
            }

            // Held across the loop end: to the end, and from the start again
            if (endTick < note.startTick) {
                drawNote(note.startTick, lengthTicks)
                drawNote(0L, endTick)
            } else {
                drawNote(note.startTick, endTick)
            }
        }
    }
}

/** Drawn on its own and read while drawing, so it moves without redrawing the notes or recomposing. */
@Composable
private fun Playhead(
    positionProvider: () -> Long,
    lengthTicks: Long,
    color: Color,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier) {
        val x = size.width * positionProvider().coerceIn(0L, lengthTicks) / lengthTicks
        drawLine(color, Offset(x, 0f), Offset(x, size.height), LANE_PLAYHEAD_WIDTH.toPx())
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SequencerSettingsSheet(
    viewModel: HostViewModel,
    onDismiss: () -> Unit
) {
    val sequencer = viewModel.sequencer
    val settings = sequencer.settings
    val status = sequencer.status
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var isClearArmed by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    var sheetCoordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
    var bpmFieldBounds by remember { mutableStateOf(Rect.Zero) }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            sequencer.importMidiFile(uri)
        }
    }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(SequencerController.MIDI_FILE_MIME_TYPE)
    ) { uri ->
        if (uri != null) {
            sequencer.exportMidiFile(uri)
        }
    }

    // Sized to its content. It stops below the status bar and camera cutout; its content clears the navigation bar.
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        modifier = Modifier.windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top)),
        sheetState = sheetState,
        containerColor = StudioSurface,
        dragHandle = { BottomSheetDefaults.DragHandle(modifier = Modifier.padding(vertical = SHEET_DRAG_HANDLE_PADDING)) },
        contentWindowInsets = { WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal) }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .onGloballyPositioned { sheetCoordinates = it }
                // A touch anywhere but on the tempo field ends typing in it. Observed, not consumed:
                // the touched control still gets it.
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                        val coordinates = sheetCoordinates

                        if (coordinates != null && coordinates.isAttached &&
                            !bpmFieldBounds.contains(coordinates.localToRoot(down.position))) {
                            focusManager.clearFocus()
                        }
                    }
                }
                .verticalScroll(rememberScrollState())
                .padding(horizontal = SHEET_HORIZONTAL_PADDING)
                .padding(bottom = SHEET_BOTTOM_PADDING),
            verticalArrangement = Arrangement.spacedBy(SHEET_ROW_SPACING)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "SEQUENCER",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = SproutGreen,
                    letterSpacing = 0.5.sp
                )

                Text(
                    text = if (sequencer.hasEvents) {
                        "${status.eventCount} events"
                    } else {
                        "Nothing recorded"
                    },
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    color = TextSecondary
                )
            }

            SettingsRow(title = "TEMPO") {
                BpmField(
                    bpm = settings.bpm,
                    onBpmChange = { bpm -> sequencer.updateSettings { it.copy(bpm = bpm) } },
                    modifier = Modifier.onGloballyPositioned { bpmFieldBounds = it.boundsInRoot() }
                )

                SettingsPill(label = "TAP", isSelected = false) {
                    sequencer.tapTempo()
                }
            }

            SettingsRow(title = "LENGTH") {
                val isAuto = settings.lengthBars == MidiSequencer.AUTO_LENGTH_BARS
                val bars = (status.lengthTicks / MidiSequencer.TICKS_PER_BAR).toInt()

                SettingsPill(
                    label = if (isAuto) {
                        "AUTO ($bars)"
                    } else {
                        "AUTO"
                    },
                    isSelected = isAuto
                ) {
                    // Turning AUTO off keeps the current length
                    sequencer.updateSettings {
                        it.copy(
                            lengthBars = if (isAuto) {
                                bars
                            } else {
                                MidiSequencer.AUTO_LENGTH_BARS
                            }
                        )
                    }
                }

                // Dimmed while AUTO. The current length is offered even if it is not a preset.
                SettingsDropdown(
                    options = (SequencerController.LENGTH_OPTIONS + bars).distinct().sorted(),
                    selected = bars,
                    label = { option -> formatLength(option).uppercase() },
                    isDimmed = isAuto,
                    onSelect = { option -> sequencer.updateSettings { it.copy(lengthBars = option) } }
                )
            }

            SettingsRow(title = "QUANTIZE") {
                SettingsPill(
                    label = if (settings.isQuantizing) {
                        "ON"
                    } else {
                        "OFF"
                    },
                    isSelected = settings.isQuantizing
                ) {
                    sequencer.updateSettings { it.copy(isQuantizing = !it.isQuantizing) }
                }

                // Selectable while quantize is off, dimmed
                SettingsDropdown(
                    options = SequencerController.QUANTIZE_OPTIONS,
                    selected = sequencer.quantizeOption,
                    label = { option -> option.label.uppercase() },
                    isDimmed = !settings.isQuantizing,
                    onSelect = { option -> sequencer.updateSettings { it.copy(quantizeTicks = option.ticks) } }
                )
            }

            Text(
                text = "Records the notes you play, in a loop. Recording starts with the first note, and the metronome " +
                    "clicks while recording. AUTO: the first take sets the number of bars. Lengthening the loop adds empty bars. " +
                    "Quantize applies on playback: what you played is kept.",
                fontSize = 11.sp,
                lineHeight = 15.sp,
                color = TextMuted
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                SettingsActionButton(
                    icon = Icons.Default.DeleteOutline,
                    label = if (isClearArmed) {
                        "CONFIRM"
                    } else {
                        "CLEAR"
                    },
                    isEnabled = sequencer.hasEvents && !status.isRecording,
                    color = BerryRose,
                    modifier = Modifier.weight(1f)
                ) {
                    if (isClearArmed) {
                        sequencer.clear()
                    }

                    isClearArmed = !isClearArmed
                }

                SettingsActionButton(
                    icon = Icons.Default.FileDownload,
                    label = "IMPORT .MID",
                    isEnabled = !status.isRecording,
                    modifier = Modifier.weight(1f)
                ) {
                    importLauncher.launch(MIDI_IMPORT_MIME_TYPES)
                }

                SettingsActionButton(
                    icon = Icons.Default.FileUpload,
                    label = "EXPORT .MID",
                    isEnabled = sequencer.hasEvents,
                    modifier = Modifier.weight(1f)
                ) {
                    val sessionName = viewModel.sessions.currentSessionName
                    exportLauncher.launch(sessionName?.ifBlank { null }?.plus(MIDI_FILE_EXTENSION) ?: SequencerController.DEFAULT_EXPORT_FILE_NAME)
                }
            }
        }
    }
}

/** A setting on one line: its name, then its choices (wrapping if they do not fit). */
@Composable
private fun SettingsRow(
    title: String,
    content: @Composable () -> Unit
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = title,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            color = TextSecondary,
            letterSpacing = 0.5.sp,
            modifier = Modifier.width(SETTINGS_LABEL_WIDTH)
        )

        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(SETTINGS_PILL_SPACING),
            verticalArrangement = Arrangement.spacedBy(SETTINGS_PILL_SPACING),
            itemVerticalAlignment = Alignment.CenterVertically
        ) {
            content()
        }
    }
}

@Composable
private fun SettingsPill(
    label: String,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    ToggleBox(
        isActive = isSelected,
        onClick = onClick,
        modifier = Modifier.height(SETTINGS_CONTROL_HEIGHT)
    ) {
        Text(
            text = label,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 10.dp)
        )
    }
}

@Composable
private fun SettingsActionButton(
    icon: ImageVector,
    label: String,
    isEnabled: Boolean,
    modifier: Modifier = Modifier,
    color: Color = TextPrimary,
    onClick: () -> Unit
) {
    val contentColor = if (isEnabled) {
        color
    } else {
        TextMuted
    }

    Row(
        modifier = modifier
            .height(SETTINGS_CONTROL_HEIGHT)
            .sequencerControl(background = Color.Transparent)
            .clickable(enabled = isEnabled, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = contentColor,
            modifier = Modifier.size(SETTINGS_ACTION_ICON_SIZE)
        )

        Spacer(modifier = Modifier.width(4.dp))

        Text(
            text = label,
            fontSize = 10.5.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            color = contentColor,
            maxLines = 1
        )
    }
}

/**
 * Tempo as a text box: drag it sideways to change the tempo in whole BPM, or tap it to type a
 * value, applied when done or when it loses focus. The engine clamps the value.
 */
@Composable
private fun BpmField(
    bpm: Double,
    onBpmChange: (Double) -> Unit,
    modifier: Modifier = Modifier
) {
    var isEditing by remember { mutableStateOf(false) }
    // onFocusChanged also reports the initial, unfocused state: only a loss after gaining focus ends editing
    var hasEditorFocus by remember { mutableStateOf(false) }
    var text by remember { mutableStateOf(TextFieldValue()) }
    val focusRequester = remember { FocusRequester() }
    val currentBpm by rememberUpdatedState(bpm)
    val dragDistancePerBpm = with(LocalDensity.current) { BPM_DRAG_DISTANCE_PER_BPM.toPx() }

    fun commitText() {
        text.text.replace(',', '.').toDoubleOrNull()?.let(onBpmChange)
        isEditing = false
        hasEditorFocus = false
    }

    val gestures = if (isEditing) {
        Modifier
    } else {
        Modifier
            .pointerInput(Unit) {
                var dragStartBpm = 0.0
                var dragDistance = 0f

                detectHorizontalDragGestures(
                    onDragStart = {
                        dragStartBpm = currentBpm.roundToInt().toDouble()
                        dragDistance = 0f
                    }
                ) { change, dragAmount ->
                    change.consume()
                    dragDistance += dragAmount
                    val dragged = (dragStartBpm + (dragDistance / dragDistancePerBpm).roundToInt())
                        .coerceIn(MidiSequencer.MIN_BPM, MidiSequencer.MAX_BPM)

                    if (dragged != currentBpm) {
                        onBpmChange(dragged)
                    }
                }
            }
            .pointerInput(Unit) {
                detectTapGestures {
                    val current = formatBpm(currentBpm)
                    text = TextFieldValue(current, selection = TextRange(0, current.length))
                    isEditing = true
                }
            }
    }

    Box(
        modifier = modifier
            .width(BPM_FIELD_WIDTH)
            .height(SETTINGS_CONTROL_HEIGHT)
            .sequencerControl(
                border = if (isEditing) {
                    SproutGreen
                } else {
                    StudioPanelBorder
                }
            )
            .then(gestures),
        contentAlignment = Alignment.Center
    ) {
        if (isEditing) {
            LaunchedEffect(Unit) {
                focusRequester.requestFocus()
            }

            // Closing the sheet while typing applies the value instead of losing it
            DisposableEffect(Unit) {
                onDispose {
                    if (isEditing) {
                        commitText()
                    }
                }
            }

            BasicTextField(
                value = text,
                onValueChange = { value ->
                    if (value.text.length <= BPM_TEXT_MAX_LENGTH) {
                        text = value
                    }
                },
                singleLine = true,
                textStyle = TextStyle(
                    color = TextPrimary,
                    fontSize = 13.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center
                ),
                cursorBrush = SolidColor(SproutGreen),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { commitText() }),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp)
                    .focusRequester(focusRequester)
                    .onFocusChanged { focus ->
                        if (focus.isFocused) {
                            hasEditorFocus = true
                        } else if (hasEditorFocus && isEditing) {
                            commitText()
                        }
                    }
            )
        } else {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                    contentDescription = null,
                    tint = TextMuted,
                    modifier = Modifier.size(SEQUENCER_ICON_SIZE)
                )

                Text(
                    text = "${formatBpm(bpm)} BPM",
                    fontSize = 13.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )

                Icon(
                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = TextMuted,
                    modifier = Modifier.size(SEQUENCER_ICON_SIZE)
                )
            }
        }
    }
}

/** Picks one of options. Dimmed options are still selectable. */
@Composable
private fun <T> SettingsDropdown(
    options: List<T>,
    selected: T,
    label: (T) -> String,
    onSelect: (T) -> Unit,
    isDimmed: Boolean = false
) {
    var isExpanded by remember { mutableStateOf(false) }

    Box {
        Row(
            modifier = Modifier
                .height(SETTINGS_CONTROL_HEIGHT)
                .widthIn(min = SETTINGS_DROPDOWN_MIN_WIDTH)
                .sequencerControl()
                .clickable { isExpanded = true }
                .padding(start = 10.dp, end = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = label(selected),
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                color = if (isDimmed) {
                    TextSecondary
                } else {
                    TextPrimary
                }
            )

            Icon(
                imageVector = Icons.Default.ArrowDropDown,
                contentDescription = null,
                tint = TextSecondary,
                modifier = Modifier.size(SETTINGS_ACTION_ICON_SIZE)
            )
        }

        DropdownMenu(
            expanded = isExpanded,
            onDismissRequest = { isExpanded = false },
            containerColor = StudioSurfaceElevated
        ) {
            for (option in options) {
                DropdownMenuItem(
                    text = {
                        Text(
                            text = label(option),
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            color = if (option == selected) {
                                SproutGreen
                            } else {
                                TextPrimary
                            }
                        )
                    },
                    onClick = {
                        onSelect(option)
                        isExpanded = false
                    }
                )
            }
        }
    }
}
