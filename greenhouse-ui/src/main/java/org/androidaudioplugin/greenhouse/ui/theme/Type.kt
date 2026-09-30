package org.androidaudioplugin.greenhouse.ui.theme

import androidx.compose.material3.LocalTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.TextStyle

/**
 * Font feature for figures of equal width: a value that changes (tempo, level, parameter value)
 * does not shift the text around it. Buttons and controls use the system font with it; the
 * monospace font is kept for slot badges, status tags and diagnostic text.
 */
const val TABULAR_FIGURES = "tnum"

/** The current text style with [TABULAR_FIGURES], for a Text showing a changing value. */
val tabularTextStyle: TextStyle
    @Composable
    get() = LocalTextStyle.current.copy(fontFeatureSettings = TABULAR_FIGURES)
