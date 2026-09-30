package org.androidaudioplugin.greenhouse.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import java.util.Locale
import kotlin.math.pow

private const val UNITY_LEVEL_DB = 0f
// Fader taper: 0 dB sits at this position, and the level moves slowly around it and faster towards
// the ends (dB grows with the distance from it raised to this exponent)
private const val LEVEL_FADER_UNITY_POSITION = 0.7f
private const val LEVEL_FADER_CURVE_EXPONENT = 2f

/**
 * A [HostFader] for a level in dB, from [minLevelDb] (muted) to [maxLevelDb]. Double-tap resets it
 * to [defaultLevelDb]. By default it has a mixing desk taper around 0 dB, for wide ranges that are
 * rarely boosted. [isLinearInDb]: every part of the travel is worth as many dB, for short ranges
 * used on both sides of 0 dB.
 */
@Composable
fun LevelFader(
    levelDb: Float,
    minLevelDb: Float,
    maxLevelDb: Float,
    onLevelChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    defaultLevelDb: Float = UNITY_LEVEL_DB,
    isLinearInDb: Boolean = false
) {
    fun toPosition(db: Float): Float {
        if (isLinearInDb) {
            return ((db - minLevelDb) / (maxLevelDb - minLevelDb)).coerceIn(0f, 1f)
        }

        return levelDbToFaderPosition(db, minLevelDb, maxLevelDb)
    }

    HostFader(
        position = toPosition(levelDb),
        defaultPosition = toPosition(defaultLevelDb),
        onPositionChange = { position ->
            val newLevelDb = if (isLinearInDb) {
                minLevelDb + (maxLevelDb - minLevelDb) * position
            } else {
                faderPositionToLevelDb(position, minLevelDb, maxLevelDb)
            }

            onLevelChange(newLevelDb)
        },
        modifier = modifier
    )
}

/** A level as a [LevelFader] shows it: [mutedLabel] when muted, signed dB with one decimal otherwise. */
fun formatLevelDb(levelDb: Float, minLevelDb: Float, mutedLabel: String = "-∞ dB"): String {
    if (levelDb <= minLevelDb) {
        return mutedLabel
    }

    return String.format(Locale.US, "%+.1f dB", levelDb)
}

private fun faderPositionToLevelDb(position: Float, minLevelDb: Float, maxLevelDb: Float): Float {
    val unity = LEVEL_FADER_UNITY_POSITION

    if (position >= unity) {
        val distance = (position - unity) / (1f - unity)
        return maxLevelDb * distance.pow(LEVEL_FADER_CURVE_EXPONENT)
    }

    val distance = (unity - position) / unity
    return minLevelDb * distance.pow(LEVEL_FADER_CURVE_EXPONENT)
}

private fun levelDbToFaderPosition(levelDb: Float, minLevelDb: Float, maxLevelDb: Float): Float {
    val unity = LEVEL_FADER_UNITY_POSITION
    val inverseExponent = 1f / LEVEL_FADER_CURVE_EXPONENT

    if (levelDb >= UNITY_LEVEL_DB) {
        val ratio = (levelDb / maxLevelDb).coerceIn(0f, 1f)
        return unity + (1f - unity) * ratio.pow(inverseExponent)
    }

    val ratio = (levelDb / minLevelDb).coerceIn(0f, 1f)
    return unity - unity * ratio.pow(inverseExponent)
}
