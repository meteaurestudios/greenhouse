package org.androidaudioplugin.greenhouse.ui.screens.rack

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PriorityHigh
import androidx.compose.material3.Icon
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.androidaudioplugin.greenhouse.ui.theme.BlossomCoral
import org.androidaudioplugin.greenhouse.ui.theme.StudioSurfaceVariant
import org.androidaudioplugin.greenhouse.ui.theme.TextPrimary

private val SNACKBAR_BORDER_WIDTH = 1.dp
private val SNACKBAR_ELEVATION = 8.dp
private val SNACKBAR_ICON_SIZE = 18.dp
private val COMPACT_SNACKBAR_ICON_SIZE = 16.dp
// The exclamation mark glyph has wide side bearings, so a small gap already reads as normal spacing
private val SNACKBAR_ICON_SPACING = 2.dp
private val SNACKBAR_TEXT_SIZE = 13.sp
private const val SNACKBAR_MAX_LINES = 2

/**
 * Error messages drawn as a stand-in for the rack's header banner: same shape, padding and
 * [bannerHeight], with an exclamation mark and the message centered in it.
 * [bannerHeight] is the measured banner height; unspecified lets the snackbar size to its content.
 */
@Composable
fun BannerSnackbarHost(
    hostState: SnackbarHostState,
    bannerHeight: Dp,
    modifier: Modifier = Modifier
) {
    val isCompact = LocalConfiguration.current.screenWidthDp < BANNER_COMPACT_SCREEN_WIDTH_DP

    val iconSize = if (isCompact) {
        COMPACT_SNACKBAR_ICON_SIZE
    } else {
        SNACKBAR_ICON_SIZE
    }

    SnackbarHost(hostState = hostState, modifier = modifier) { data ->
        val shape = RoundedCornerShape(BANNER_CORNER_RADIUS)

        val heightModifier = if (bannerHeight != Dp.Unspecified) {
            Modifier.height(bannerHeight)
        } else {
            Modifier
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .then(heightModifier)
                .shadow(SNACKBAR_ELEVATION, shape)
                .clip(shape)
                .background(StudioSurfaceVariant)
                .border(SNACKBAR_BORDER_WIDTH, BlossomCoral, shape)
                .padding(horizontal = BANNER_PADDING_HORIZONTAL),
            horizontalArrangement = Arrangement.spacedBy(SNACKBAR_ICON_SPACING, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Filled.PriorityHigh,
                contentDescription = null,
                tint = BlossomCoral,
                modifier = Modifier.size(iconSize)
            )

            Text(
                text = data.visuals.message,
                color = TextPrimary,
                fontSize = SNACKBAR_TEXT_SIZE,
                fontWeight = FontWeight.SemiBold,
                maxLines = SNACKBAR_MAX_LINES,
                overflow = TextOverflow.Ellipsis,
                // A long message wraps instead of pushing the icon out
                modifier = Modifier.weight(1f, fill = false)
            )
        }
    }
}
