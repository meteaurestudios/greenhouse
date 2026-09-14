package org.androidaudioplugin.greenhouse.ui.screens.rack

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.androidaudioplugin.greenhouse.ui.RackSlotData
import org.androidaudioplugin.greenhouse.ui.components.GreenhouseMascot
import org.androidaudioplugin.greenhouse.ui.theme.*
import java.util.Locale

private val PLACEHOLDER_CARD_RADIUS = 18.dp
private val MASCOT_SIZE_EMPTY = 72.dp
private val MASCOT_SIZE_LOADING = 64.dp
private val LOADING_INDICATOR_SIZE = 24.dp
private val LOADING_STROKE_WIDTH = 2.5.dp

@Composable
fun NoPluginInSlotView(
    slot: RackSlotData,
    isProcessing: Boolean,
    onOpenBrowser: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(PLACEHOLDER_CARD_RADIUS))
            .background(StudioBackground),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp, vertical = 12.dp)
        ) {
            GreenhouseMascot(
                size = MASCOT_SIZE_EMPTY,
                isAnimated = true,
                isListening = isProcessing
            )

            Spacer(modifier = Modifier.height(14.dp))

            Text(
                text = "${slot.title} is ready",
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                color = TextPrimary
            )

            Spacer(modifier = Modifier.height(4.dp))

            val placeholderSubtitle = if (slot.index == 0) {
                "Plant an instrument plugin to start playing."
            } else {
                "Give your melody room to breathe and expand."
            }

            Text(
                text = placeholderSubtitle,
                fontSize = 12.sp,
                color = TextSecondary,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )

            Spacer(modifier = Modifier.height(16.dp))

            Button(
                onClick = onOpenBrowser,
                colors = ButtonDefaults.buttonColors(containerColor = SproutGreen, contentColor = StudioBackground),
                shape = RoundedCornerShape(12.dp),
                contentPadding = PaddingValues(horizontal = 24.dp, vertical = 10.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp)
                )

                Spacer(modifier = Modifier.width(6.dp))

                Text(
                    text = "BROWSE PLUGINS",
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 12.sp,
                    letterSpacing = 0.5.sp
                )
            }
        }
    }
}

@Composable
fun PluginLoadingView(
    slot: RackSlotData,
    pluginName: String?
) {
    val themeColor = if (slot.index == 0) {
        BlossomCoral
    } else {
        PeriwinkleBlue
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(PLACEHOLDER_CARD_RADIUS))
            .background(StudioBackground),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp, vertical = 12.dp)
        ) {
            GreenhouseMascot(
                size = MASCOT_SIZE_LOADING,
                isAnimated = true,
                isListening = true
            )

            Spacer(modifier = Modifier.height(14.dp))

            CircularProgressIndicator(
                modifier = Modifier.size(LOADING_INDICATOR_SIZE),
                color = themeColor,
                strokeWidth = LOADING_STROKE_WIDTH
            )

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = "Nurturing ${pluginName ?: "Plugin"}...",
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                color = TextPrimary,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = "Initializing ${slot.title} (${slot.slotType.lowercase(Locale.US)})...",
                fontSize = 11.sp,
                color = TextSecondary,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
