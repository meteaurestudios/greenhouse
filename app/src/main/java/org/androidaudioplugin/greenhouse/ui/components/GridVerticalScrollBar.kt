package org.androidaudioplugin.greenhouse.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.androidaudioplugin.greenhouse.ui.theme.StudioSurfaceVariant
import org.androidaudioplugin.greenhouse.ui.theme.TextSecondary

val SCROLLBAR_WIDTH = 3.5.dp
val SCROLLBAR_HORIZONTAL_OFFSET = 5.dp
val SCROLLBAR_TRACK_PADDING = 4.dp
private val SCROLLBAR_CORNER_RADIUS = 2.dp
private const val SCROLLBAR_MIN_THUMB_RATIO = 0.08f
private const val SCROLLBAR_MAX_THUMB_RATIO = 0.7f
private const val SCROLLBAR_TRACK_ALPHA = 0.2f
private const val SCROLLBAR_THUMB_ALPHA = 0.38f
private const val DEFAULT_GRID_COLUMN_COUNT = 3

@Composable
fun GridVerticalScrollBar(
    gridState: LazyGridState,
    totalItems: Int,
    estimatedRowHeightDp: Dp,
    defaultColumnCount: Int = DEFAULT_GRID_COLUMN_COUNT,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    val rowHeightPx = with(density) { estimatedRowHeightDp.toPx() }

    Canvas(modifier = modifier) {
        val viewportHeightPx = size.height
        val visibleItems = gridState.layoutInfo.visibleItemsInfo

        val actualCols = if (visibleItems.isNotEmpty()) {
            val firstY = visibleItems[0].offset.y
            val countInFirstRow = visibleItems.count { it.offset.y == firstY }
            countInFirstRow.coerceAtLeast(1)
        } else {
            defaultColumnCount.coerceAtLeast(1)
        }

        val totalRows = kotlin.math.ceil(totalItems.toDouble() / actualCols).toFloat()
        val totalContentHeightPx = totalRows * rowHeightPx

        if (totalContentHeightPx > viewportHeightPx && viewportHeightPx > 0f) {
            // 1. Draw subtle background track
            drawRoundRect(
                color = StudioSurfaceVariant.copy(alpha = SCROLLBAR_TRACK_ALPHA),
                cornerRadius = CornerRadius(SCROLLBAR_CORNER_RADIUS.toPx())
            )

            // 2. Read scroll state only during draw phase (zero composable body recompositions)
            val firstVisibleIndex = gridState.firstVisibleItemIndex
            val firstVisibleOffset = gridState.firstVisibleItemScrollOffset

            val firstVisibleRow = firstVisibleIndex / actualCols
            val currentScrollOffsetPx = (firstVisibleRow * rowHeightPx) + firstVisibleOffset
            val totalScrollDistancePx = (totalContentHeightPx - viewportHeightPx).coerceAtLeast(1f)

            val scrollProgress = (currentScrollOffsetPx / totalScrollDistancePx).coerceIn(0f, 1f)
            val thumbHeightRatio = (viewportHeightPx / totalContentHeightPx)
                .coerceIn(SCROLLBAR_MIN_THUMB_RATIO, SCROLLBAR_MAX_THUMB_RATIO)

            val thumbHeightPx = viewportHeightPx * thumbHeightRatio
            val maxTravelPx = viewportHeightPx - thumbHeightPx
            val thumbTopY = scrollProgress * maxTravelPx

            drawRoundRect(
                color = TextSecondary.copy(alpha = SCROLLBAR_THUMB_ALPHA),
                topLeft = Offset(0f, thumbTopY),
                size = Size(size.width, thumbHeightPx),
                cornerRadius = CornerRadius(SCROLLBAR_CORNER_RADIUS.toPx())
            )
        }
    }
}
