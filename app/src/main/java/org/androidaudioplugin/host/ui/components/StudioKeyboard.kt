package org.androidaudioplugin.host.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.changedToDown
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

private val WHITE_KEY_TO_SEMITONES = intArrayOf(0, 2, 4, 5, 7, 9, 11)
private val BLACK_KEY_OFFSETS = floatArrayOf(0.05f, 0.1f, 0f, 0.05f, 0.15f, 0.25f, 0f)
private val HAS_BLACK_KEY = booleanArrayOf(true, true, false, true, true, true, false)

@Composable
fun StudioKeyboard(
    noteOnStates: List<Long>,
    octaveZeroBased: Int,
    numWhiteKeys: Int = 14,
    whiteKeyWidth: Dp? = null,
    blackKeyHeight: Dp = 52.dp,
    totalHeight: Dp = 90.dp,
    whiteKeyColor: Color = Color(0xFFF1F5F9),
    blackKeyColor: Color = Color(0xFF171A21),
    whiteNoteOnColor: Color = Color(0xFF38BDF8),
    blackNoteOnColor: Color = Color(0xFF38BDF8),
    onNoteOn: (note: Int) -> Unit,
    onNoteOff: (note: Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current

    BoxWithConstraints(modifier = modifier) {
        val totalWidthPx = constraints.maxWidth.toFloat()
        val computedWhiteKeyWidthPx = if (whiteKeyWidth != null) {
            with(density) { whiteKeyWidth.toPx() }
        } else {
            totalWidthPx / numWhiteKeys.toFloat()
        }

        val totalHeightPx = if (constraints.hasFixedHeight) {
            constraints.maxHeight.toFloat()
        } else {
            with(density) { totalHeight.toPx() }
        }

        val blackKeyHeightPx = with(density) { blackKeyHeight.toPx() }
        val blackKeyWidthPx = computedWhiteKeyWidthPx * 0.72f

        // Precompute key rects for hit testing and drawing
        val whiteKeyRects = remember(numWhiteKeys, computedWhiteKeyWidthPx, totalHeightPx, octaveZeroBased) {
            Array(numWhiteKeys) { i ->
                val x = i * computedWhiteKeyWidthPx
                val note = (octaveZeroBased + (i / 7)) * 12 + WHITE_KEY_TO_SEMITONES[i % 7]
                note to Rect(Offset(x, 0f), Size(computedWhiteKeyWidthPx, totalHeightPx))
            }
        }

        val blackKeyRects = remember(numWhiteKeys, computedWhiteKeyWidthPx, blackKeyHeightPx, blackKeyWidthPx, octaveZeroBased) {
            val list = mutableListOf<Pair<Int, Rect>>()

            for (i in 0 until numWhiteKeys) {
                val keyInOctave = i % 7

                if (HAS_BLACK_KEY[keyInOctave]) {
                    val x = i * computedWhiteKeyWidthPx
                    val bkOffset = BLACK_KEY_OFFSETS[keyInOctave] * computedWhiteKeyWidthPx
                    val note = (octaveZeroBased + (i / 7)) * 12 + WHITE_KEY_TO_SEMITONES[keyInOctave] + 1

                    if (note in 0..127) {
                        val rect = Rect(
                            Offset(x + bkOffset + (computedWhiteKeyWidthPx / 2f), 0f),
                            Size(blackKeyWidthPx, blackKeyHeightPx)
                        )
                        list.add(note to rect)
                    }
                }
            }

            list.toTypedArray()
        }

        fun findNoteAtPosition(position: Offset): Int? {
            if (position.x < 0f || position.x > (numWhiteKeys * computedWhiteKeyWidthPx) || position.y < 0f || position.y > totalHeightPx) {
                return null
            }

            // Check black keys first with comfortable hit target in the upper region
            if (position.y <= (blackKeyHeightPx * 1.05f)) {
                for (pair in blackKeyRects) {
                    val rect = pair.second
                    // Add slight horizontal touch margin for black keys
                    val touchMargin = 3f

                    if (position.x >= (rect.left - touchMargin) && position.x <= (rect.right + touchMargin) && position.y <= rect.bottom) {
                        return pair.first
                    }
                }
            }

            // Otherwise, match white key
            val whiteIndex = (position.x / computedWhiteKeyWidthPx).toInt().coerceIn(0, numWhiteKeys - 1)
            val note = whiteKeyRects[whiteIndex].first

            if (note in 0..127) {
                return note
            }

            return null
        }

        val pointerIdToNote = remember { mutableMapOf<PointerId, Int>() }

        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(octaveZeroBased, numWhiteKeys, computedWhiteKeyWidthPx, totalHeightPx) {
                    awaitPointerEventScope {
                        try {
                            while (true) {
                                val event = awaitPointerEvent()
                                val changes = event.changes

                                for (change in changes) {
                                    val pointerId = change.id

                                    if (change.changedToDown()) {
                                        val note = findNoteAtPosition(change.position)

                                        if (note != null) {
                                            pointerIdToNote[pointerId] = note
                                            val count = pointerIdToNote.values.count { it == note }

                                            if (count == 1) {
                                                onNoteOn(note)
                                            }
                                        }
                                    } else if (change.changedToUp() || !change.pressed) {
                                        val oldNote = pointerIdToNote.remove(pointerId)

                                        if (oldNote != null) {
                                            val count = pointerIdToNote.values.count { it == oldNote }

                                            if (count == 0) {
                                                onNoteOff(oldNote)
                                            }
                                        }
                                    } else if (change.pressed) {
                                        val currentNote = findNoteAtPosition(change.position)
                                        val previousNote = pointerIdToNote[pointerId]

                                        if (currentNote != null && currentNote != previousNote) {
                                            pointerIdToNote[pointerId] = currentNote

                                            if (previousNote != null) {
                                                val oldCount = pointerIdToNote.values.count { it == previousNote }

                                                if (oldCount == 0) {
                                                    onNoteOff(previousNote)
                                                }
                                            }

                                            val newCount = pointerIdToNote.values.count { it == currentNote }

                                            if (newCount == 1) {
                                                onNoteOn(currentNote)
                                            }
                                        }
                                    }
                                }
                            }
                        } finally {
                            val notesToRelease = pointerIdToNote.values.distinct()
                            pointerIdToNote.clear()

                            for (note in notesToRelease) {
                                onNoteOff(note)
                            }
                        }
                    }
                }
        ) {
            // Draw background frame
            drawRect(
                color = Color.Black,
                size = size,
                style = Stroke(width = 1.dp.toPx())
            )

            // 1. Draw White Keys
            for (pair in whiteKeyRects) {
                val note = pair.first
                val rect = pair.second
                val isNoteOn = note in 0..127 && (noteOnStates.getOrNull(note) ?: 0L) > 0L
                val keyFillColor = if (isNoteOn) whiteNoteOnColor else whiteKeyColor

                drawRoundRect(
                    color = keyFillColor,
                    topLeft = Offset(rect.left, rect.top),
                    size = Size(rect.width - 1.5f, rect.height),
                    cornerRadius = CornerRadius(4.dp.toPx(), 4.dp.toPx())
                )

                // Divider line between white keys
                drawLine(
                    color = Color(0xFF1E2430),
                    start = Offset(rect.right - 1f, 0f),
                    end = Offset(rect.right - 1f, rect.bottom),
                    strokeWidth = 1.dp.toPx()
                )
            }

            // 2. Draw Black Keys
            for (pair in blackKeyRects) {
                val note = pair.first
                val rect = pair.second
                val isNoteOn = note in 0..127 && (noteOnStates.getOrNull(note) ?: 0L) > 0L
                val keyFillColor = if (isNoteOn) blackNoteOnColor else blackKeyColor

                drawRoundRect(
                    color = keyFillColor,
                    topLeft = rect.topLeft,
                    size = rect.size,
                    cornerRadius = CornerRadius(3.dp.toPx(), 3.dp.toPx())
                )

                // Subtle edge highlight for black keys
                drawRoundRect(
                    color = if (isNoteOn) Color.White.copy(alpha = 0.4f) else Color(0xFF333846),
                    topLeft = rect.topLeft,
                    size = rect.size,
                    cornerRadius = CornerRadius(3.dp.toPx(), 3.dp.toPx()),
                    style = Stroke(width = 1.dp.toPx())
                )
            }
        }
    }
}
