package org.androidaudioplugin.greenhouse.ui.screens.rack

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SaveAs
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.rounded.Eco
import androidx.compose.material.icons.rounded.Spa
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import org.androidaudioplugin.greenhouse.data.RackPreset
import org.androidaudioplugin.greenhouse.data.RackPresetHeader
import org.androidaudioplugin.greenhouse.ui.HostViewModel
import org.androidaudioplugin.greenhouse.ui.theme.DangerRed
import org.androidaudioplugin.greenhouse.ui.theme.SproutGreen
import org.androidaudioplugin.greenhouse.ui.theme.StudioBackground
import org.androidaudioplugin.greenhouse.ui.theme.StudioPanelBorder
import org.androidaudioplugin.greenhouse.ui.theme.StudioSurface
import org.androidaudioplugin.greenhouse.ui.theme.StudioSurfaceElevated
import org.androidaudioplugin.greenhouse.ui.theme.StudioSurfaceVariant
import org.androidaudioplugin.greenhouse.ui.theme.TextMuted
import org.androidaudioplugin.greenhouse.ui.theme.TextPrimary
import org.androidaudioplugin.greenhouse.ui.theme.TextSecondary
import org.androidaudioplugin.greenhouse.ui.theme.WarmSunbeam
import kotlinx.coroutines.delay
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// Dialog frame
private val DIALOG_CORNER_RADIUS = 24.dp
private val DIALOG_PADDING = 20.dp
private const val DIALOG_WIDTH_FRACTION = 0.94f
private const val DIALOG_MAX_HEIGHT_FRACTION = 0.85f
private val SECTION_SPACING = 16.dp
private val HEADER_TITLE_BUTTON_GAP = 12.dp
private val HEADER_TO_LIST_SPACING = 8.dp

// Cards & rows
private val CARD_CORNER_RADIUS = 14.dp
private val CURRENT_CARD_H_PADDING = 14.dp
private val CURRENT_CARD_V_PADDING = 12.dp
private val CURRENT_LABEL_SPACING = 2.dp
private const val CURRENT_CARD_TINT_ALPHA = 0.08f
private const val CURRENT_CARD_BORDER_ALPHA = 0.3f
private val CURRENT_LABEL_ICON_SIZE = 12.dp
private val CURRENT_LABEL_ICON_GAP = 4.dp

// Decorative leaves tucked into the current session card's top-right corner
private val LEAF_LARGE_SIZE = 96.dp
private val LEAF_LARGE_OFFSET_X = 22.dp
private val LEAF_LARGE_OFFSET_Y = (-18).dp
private const val LEAF_LARGE_ROTATION = -24f
private val LEAF_SMALL_SIZE = 40.dp
private val LEAF_SMALL_OFFSET_X = (-64).dp
private val LEAF_SMALL_OFFSET_Y = 10.dp
private const val LEAF_SMALL_ROTATION = 16f
private const val LEAF_ALPHA = 0.08f
private val ROW_START_PADDING = 14.dp
private val ROW_END_PADDING = 4.dp
private val ROW_VERTICAL_PADDING = 10.dp
private const val ROW_DIVIDER_ALPHA = 0.6f
private val INLINE_GAP = 8.dp
private val TIGHT_GAP = 4.dp
private const val ACTIVE_BORDER_ALPHA = 0.5f

// Name input bar
private val INPUT_BAR_HEIGHT = 44.dp
private val INPUT_CORNER_RADIUS = 10.dp
private val INPUT_START_PADDING = 12.dp
private val INPUT_END_PADDING = 5.dp
private val INPUT_WARNING_SPACING = 6.dp

// Icon buttons & menus
private val ICON_BUTTON_SIZE = 34.dp
private val SMALL_ICON_BUTTON_SIZE = 28.dp
private val ICON_SIZE = 16.dp
private val SMALL_ICON_SIZE = 14.dp
private val MENU_CORNER_RADIUS = 8.dp

// Text actions
private val TEXT_ACTION_CORNER_RADIUS = 8.dp
private val TEXT_ACTION_H_PADDING = 10.dp
private val TEXT_ACTION_V_PADDING = 6.dp

// Session action buttons (Save / Load)
private val SESSION_BUTTON_HEIGHT = 34.dp
private val SESSION_BUTTON_CORNER_RADIUS = 9.dp
private val SESSION_BUTTON_H_PADDING = 16.dp
private val SESSION_BUTTON_FONT_SIZE = 12.5.sp
private const val TONAL_BG_ALPHA = 0.16f
private const val DISABLED_BG_ALPHA = 0.6f
private val SESSION_BUTTON_ICON_SIZE = 15.dp
private val SESSION_BUTTON_ICON_GAP = 5.dp
private const val SAVE_FAILED_FEEDBACK_DURATION_MS = 2000L

// Empty state & confirmation dialogs
private val EMPTY_STATE_V_PADDING = 28.dp
private val CONFIRM_DIALOG_CORNER_RADIUS = 20.dp
private val CONFIRM_DIALOG_PADDING = 20.dp
private val CONFIRM_BUTTON_CORNER_RADIUS = 8.dp
private const val CONFIRM_DIALOG_WIDTH_FRACTION = 0.9f

private const val SESSION_DATE_PATTERN = "d MMM yyyy"
private const val DETAIL_SEPARATOR = " · "

@Composable
fun RackSessionDialog(
    viewModel: HostViewModel,
    onDismissRequest: () -> Unit,
    onImportRequested: () -> Unit
) {
    val context = LocalContext.current
    var sessionToDelete by remember { mutableStateOf<RackPresetHeader?>(null) }
    var showNewSessionConfirmation by remember { mutableStateOf(false) }
    val maxDialogHeight = (LocalConfiguration.current.screenHeightDp * DIALOG_MAX_HEIGHT_FRACTION).dp

    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        // Read inside Dialog: the dialog is a separate window with its own focus owner,
        // so the focus manager from the calling screen cannot clear focus in here.
        val focusManager = LocalFocusManager.current

        LaunchedEffect(Unit) {
            focusManager.clearFocus()
        }

        Column(
            modifier = Modifier
                .fillMaxWidth(DIALOG_WIDTH_FRACTION)
                .heightIn(max = maxDialogHeight)
                .clip(RoundedCornerShape(DIALOG_CORNER_RADIUS))
                .background(StudioSurface)
                .border(1.dp, StudioPanelBorder, RoundedCornerShape(DIALOG_CORNER_RADIUS))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) {
                    focusManager.clearFocus()
                }
                .padding(DIALOG_PADDING)
        ) {
            DialogHeader(
                onNewSession = {
                    focusManager.clearFocus()
                    showNewSessionConfirmation = true
                },
                onClose = onDismissRequest
            )

            Spacer(modifier = Modifier.height(SECTION_SPACING))

            CurrentSessionCard(viewModel = viewModel)

            Spacer(modifier = Modifier.height(SECTION_SPACING))

            SavedSessionsHeader(
                count = viewModel.sessions.savedSessions.size,
                onImport = {
                    focusManager.clearFocus()
                    onImportRequested()
                }
            )

            Spacer(modifier = Modifier.height(HEADER_TO_LIST_SPACING))

            if (viewModel.sessions.savedSessions.isEmpty()) {
                EmptySessionsHint()
            } else {
                val listShape = RoundedCornerShape(CARD_CORNER_RADIUS)

                // One grouped container with dividers, so it reads as a list rather than a stack of cards
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f, fill = false)
                        .clip(listShape)
                        .background(StudioSurfaceVariant)
                        .border(1.dp, StudioPanelBorder, listShape)
                ) {
                    itemsIndexed(
                        items = viewModel.sessions.savedSessions,
                        key = { _, header -> header.file.absolutePath }
                    ) { index, sessionHeader ->
                        Column {
                            if (index > 0) {
                                HorizontalDivider(
                                    modifier = Modifier.padding(start = ROW_START_PADDING),
                                    color = StudioPanelBorder.copy(alpha = ROW_DIVIDER_ALPHA),
                                    thickness = 1.dp
                                )
                            }

                            val isActive = sessionHeader.file == viewModel.sessions.currentSessionFile ||
                                sessionHeader.name.equals(viewModel.sessions.currentSessionName, ignoreCase = true)

                            SessionRow(
                                header = sessionHeader,
                                isActive = isActive,
                                onLoad = {
                                    focusManager.clearFocus()
                                    viewModel.sessions.loadSessionFromFile(sessionHeader.file) {
                                        onDismissRequest()
                                    }
                                },
                                onShare = {
                                    focusManager.clearFocus()
                                    shareSessionFile(context, sessionHeader.file, sessionHeader.name)
                                },
                                onDelete = {
                                    focusManager.clearFocus()
                                    sessionToDelete = sessionHeader
                                }
                            )
                        }
                    }
                }
            }
        }
    }

    // Delete Confirmation Dialog
    val target = sessionToDelete

    if (target != null) {
        ConfirmationDialog(
            title = "Delete \"${target.name}\"?",
            message = "It will be gone for good.",
            confirmLabel = "Delete",
            dismissLabel = "Keep it",
            isTitleDanger = true,
            onDismiss = { sessionToDelete = null },
            onConfirm = {
                viewModel.sessions.deleteSession(target)
                sessionToDelete = null
            }
        )
    }

    // New Session Confirmation Dialog
    if (showNewSessionConfirmation) {
        ConfirmationDialog(
            title = "New session?",
            message = "This empties the rack and unloads every plugin. Anything you haven't saved will be lost.",
            confirmLabel = "New session",
            dismissLabel = "Cancel",
            isTitleDanger = false,
            onDismiss = { showNewSessionConfirmation = false },
            onConfirm = {
                viewModel.sessions.startNewSession()
                showNewSessionConfirmation = false
                onDismissRequest()
            }
        )
    }
}

@Composable
private fun DialogHeader(
    onNewSession: () -> Unit,
    onClose: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "Sessions",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = TextPrimary
            )

            Spacer(modifier = Modifier.width(HEADER_TITLE_BUTTON_GAP))

            SessionActionButton(
                label = "New",
                style = SessionButtonStyle.Tonal,
                onClick = onNewSession
            )
        }

        GhostIconButton(
            icon = Icons.Default.Close,
            contentDescription = "Close",
            onClick = onClose
        )
    }
}

/**
 * The rack as it is right now: its name, a one-tap Save, and a menu for Save As.
 * When the rack has never been saved, the card asks for a name instead.
 */
@Composable
private fun CurrentSessionCard(viewModel: HostViewModel) {
    val focusManager = LocalFocusManager.current
    val activeSessionName = viewModel.sessions.currentSessionName
    val hasActiveSession = !activeSessionName.isNullOrBlank()
    val loadedPluginsCount = viewModel.rack.slots.count { it.isLoaded }
    var nameInput by remember { mutableStateOf(TextFieldValue()) }
    var isSaveAsExpanded by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }
    var saveFeedback by remember { mutableStateOf(SaveFeedback.Idle) }

    val onSaveFinished: (Boolean) -> Unit = { success ->
        saveFeedback = if (success) {
            SaveFeedback.Saved
        } else {
            SaveFeedback.Failed
        }
    }

    // "Saved" stays until the popup closes; "Couldn't save" goes back to "Save" so it can be retried
    LaunchedEffect(saveFeedback) {
        if (saveFeedback == SaveFeedback.Failed) {
            delay(SAVE_FAILED_FEEDBACK_DURATION_MS)
            saveFeedback = SaveFeedback.Idle
        }
    }

    val pluginSummary = when (loadedPluginsCount) {
        0 -> "No plugins"
        1 -> "1 plugin"
        else -> "$loadedPluginsCount plugins"
    }

    val details = if (hasActiveSession) {
        "$DETAIL_SEPARATOR$pluginSummary"
    } else {
        "$DETAIL_SEPARATOR$pluginSummary${DETAIL_SEPARATOR}not saved yet"
    }

    val showNameInput = !hasActiveSession || isSaveAsExpanded
    val shape = RoundedCornerShape(CARD_CORNER_RADIUS)

    val isNameTaken = viewModel.sessions.isSessionNameTaken(nameInput.text)

    val nameWarning = if (isNameTaken) {
        "There's already one called \"${nameInput.text.trim()}\". Try another name."
    } else {
        null
    }

    // Opens the name field pre-filled and fully selected, so typing replaces it
    val openSaveAs = { initialName: String ->
        nameInput = TextFieldValue(
            text = initialName,
            selection = TextRange(0, initialName.length)
        )
        isSaveAsExpanded = true
    }

    val submitName = {
        if (nameInput.text.isNotBlank() && !isNameTaken) {
            saveFeedback = SaveFeedback.Saving
            viewModel.sessions.saveSession(nameInput.text, onSaveFinished)
            nameInput = TextFieldValue()
            isSaveAsExpanded = false
            focusManager.clearFocus()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(SproutGreen.copy(alpha = CURRENT_CARD_TINT_ALPHA))
            .border(1.dp, SproutGreen.copy(alpha = CURRENT_CARD_BORDER_ALPHA), shape)
    ) {
        // Decorative leaves, drawn first so they sit behind the content; the card's clip crops them.
        // matchParentSize: this layer takes the card's size without affecting it, and
        // wrapContentSize(unbounded) lets the leaves overflow it instead of stretching the card.
        Box(modifier = Modifier.matchParentSize()) {
            Icon(
                imageVector = Icons.Rounded.Eco,
                contentDescription = null,
                tint = SproutGreen.copy(alpha = LEAF_ALPHA),
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .wrapContentSize(align = Alignment.TopEnd, unbounded = true)
                    .offset(x = LEAF_LARGE_OFFSET_X, y = LEAF_LARGE_OFFSET_Y)
                    .rotate(LEAF_LARGE_ROTATION)
                    .size(LEAF_LARGE_SIZE)
            )

            Icon(
                imageVector = Icons.Rounded.Spa,
                contentDescription = null,
                tint = SproutGreen.copy(alpha = LEAF_ALPHA),
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .wrapContentSize(align = Alignment.TopEnd, unbounded = true)
                    .offset(x = LEAF_SMALL_OFFSET_X, y = LEAF_SMALL_OFFSET_Y)
                    .rotate(LEAF_SMALL_ROTATION)
                    .size(LEAF_SMALL_SIZE)
            )
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = CURRENT_CARD_H_PADDING, vertical = CURRENT_CARD_V_PADDING)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Rounded.Eco,
                            contentDescription = null,
                            tint = SproutGreen,
                            modifier = Modifier.size(CURRENT_LABEL_ICON_SIZE)
                        )

                        Spacer(modifier = Modifier.width(CURRENT_LABEL_ICON_GAP))

                        Text(
                            text = "Current session",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = SproutGreen
                        )

                        Text(
                            text = details,
                            fontSize = 11.sp,
                            color = TextMuted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    Spacer(modifier = Modifier.height(CURRENT_LABEL_SPACING))

                    Text(
                        text = activeSessionName ?: "Untitled",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Spacer(modifier = Modifier.width(INLINE_GAP))

                if (hasActiveSession && !isSaveAsExpanded) {
                    SessionActionButton(
                        label = when (saveFeedback) {
                            SaveFeedback.Idle -> "Save"
                            SaveFeedback.Saving -> "Saving…"
                            SaveFeedback.Saved -> "Saved"
                            SaveFeedback.Failed -> "Couldn't save"
                        },
                        style = if (saveFeedback == SaveFeedback.Failed) {
                            SessionButtonStyle.Warning
                        } else {
                            SessionButtonStyle.Primary
                        },
                        leadingIcon = if (saveFeedback == SaveFeedback.Saved) {
                            Icons.Default.Check
                        } else {
                            null
                        },
                        // Nothing to do while saving or once saved: taps are ignored with no ripple
                        interactive = saveFeedback == SaveFeedback.Idle || saveFeedback == SaveFeedback.Failed,
                        onClick = {
                            focusManager.clearFocus()

                            // An imported session can share its name with one already saved:
                            // ask for a new name instead of overwriting the other one
                            if (viewModel.sessions.canSaveActiveSessionInPlace()) {
                                saveFeedback = SaveFeedback.Saving
                                viewModel.sessions.saveActiveSession(onSaveFinished)
                            } else {
                                openSaveAs(activeSessionName ?: "")
                            }
                        }
                    )

                    Spacer(modifier = Modifier.width(TIGHT_GAP))
                }

                if (hasActiveSession) {
                    Box {
                        GhostIconButton(
                            icon = Icons.Default.MoreVert,
                            contentDescription = "More options",
                            onClick = { showMenu = true }
                        )

                        SessionMenu(
                            expanded = showMenu,
                            onDismiss = { showMenu = false }
                        ) {
                            SessionMenuItem(
                                icon = Icons.Default.SaveAs,
                                label = "Save as",
                                onClick = {
                                    showMenu = false
                                    openSaveAs("$activeSessionName copy")
                                }
                            )
                        }
                    }
                }
            }

            if (showNameInput) {
                Spacer(modifier = Modifier.height(INLINE_GAP))

                // Same field for a first save and for Save as; Save as also gets a cancel button
                SessionNameField(
                    value = nameInput,
                    onValueChange = { nameInput = it },
                    onSubmit = submitName,
                    warning = nameWarning,
                    requestFocusOnShow = hasActiveSession,
                    onCancel = if (hasActiveSession) {
                        {
                            nameInput = TextFieldValue()
                            isSaveAsExpanded = false
                            focusManager.clearFocus()
                        }
                    } else {
                        null
                    }
                )
            }
        }
    }
}

@Composable
private fun SessionNameField(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    onSubmit: () -> Unit,
    warning: String?,
    requestFocusOnShow: Boolean,
    onCancel: (() -> Unit)?
) {
    var isFocused by remember { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current
    val shape = RoundedCornerShape(INPUT_CORNER_RADIUS)

    LaunchedEffect(Unit) {
        if (requestFocusOnShow) {
            focusRequester.requestFocus()
            keyboardController?.show()
        }
    }

    val borderColor = when {
        warning != null -> WarmSunbeam
        isFocused -> SproutGreen
        else -> StudioPanelBorder
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(INPUT_BAR_HEIGHT)
                .clip(shape)
                .background(StudioSurface)
                .border(1.dp, borderColor, shape)
                .padding(start = INPUT_START_PADDING, end = INPUT_END_PADDING),
            verticalAlignment = Alignment.CenterVertically
        ) {
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = TextStyle(
                    fontSize = 13.sp,
                    color = TextPrimary,
                    fontWeight = FontWeight.Medium
                ),
                cursorBrush = SolidColor(SproutGreen),
                modifier = Modifier
                    .weight(1f)
                    .focusRequester(focusRequester)
                    .onFocusChanged { isFocused = it.isFocused },
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Sentences,
                    imeAction = ImeAction.Done
                ),
                keyboardActions = KeyboardActions(onDone = { onSubmit() }),
                decorationBox = { innerTextField ->
                    Box(contentAlignment = Alignment.CenterStart) {
                        if (value.text.isEmpty()) {
                            Text(
                                text = "e.g. Sunday jam",
                                fontSize = 13.sp,
                                color = TextMuted,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }

                        innerTextField()
                    }
                }
            )

            Spacer(modifier = Modifier.width(INLINE_GAP))

            SessionActionButton(
                label = "Save",
                style = SessionButtonStyle.Primary,
                enabled = value.text.isNotBlank() && warning == null,
                onClick = onSubmit
            )

            if (onCancel != null) {
                GhostIconButton(
                    icon = Icons.Default.Close,
                    contentDescription = "Cancel",
                    onClick = onCancel,
                    size = SMALL_ICON_BUTTON_SIZE,
                    iconSize = SMALL_ICON_SIZE
                )
            }
        }

        if (warning != null) {
            Spacer(modifier = Modifier.height(INPUT_WARNING_SPACING))

            Text(
                text = warning,
                fontSize = 11.sp,
                color = WarmSunbeam
            )
        }
    }
}

@Composable
private fun SavedSessionsHeader(
    count: Int,
    onImport: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "Saved",
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = TextSecondary
            )

            Spacer(modifier = Modifier.width(INLINE_GAP))

            Text(
                text = count.toString(),
                fontSize = 12.sp,
                color = TextMuted
            )
        }

        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(TEXT_ACTION_CORNER_RADIUS))
                .clickable(onClick = onImport)
                .padding(horizontal = TEXT_ACTION_H_PADDING, vertical = TEXT_ACTION_V_PADDING)
        ) {
            Text(
                text = "Import",
                fontSize = 12.5.sp,
                fontWeight = FontWeight.SemiBold,
                color = SproutGreen
            )
        }
    }
}

@Composable
private fun EmptySessionsHint() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = EMPTY_STATE_V_PADDING),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "Nothing saved yet",
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            color = TextSecondary
        )

        Spacer(modifier = Modifier.height(TIGHT_GAP))

        Text(
            text = "Type a name above and tap Save.",
            fontSize = 12.sp,
            color = TextMuted
        )
    }
}

@Composable
private fun SessionRow(
    header: RackPresetHeader,
    isActive: Boolean,
    onLoad: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit
) {
    val dateFormatter = remember { SimpleDateFormat(SESSION_DATE_PATTERN, Locale.getDefault()) }
    val formattedDate = remember(header.modifiedAt) { dateFormatter.format(Date(header.modifiedAt)) }
    var showMenu by remember { mutableStateOf(false) }

    // Date first so a long plugin list is what gets ellipsized
    val pluginsText = if (header.pluginNames.isEmpty()) {
        "No plugins"
    } else {
        header.pluginNames.joinToString(", ")
    }
    val detailText = "$formattedDate$DETAIL_SEPARATOR$pluginsText"

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                start = ROW_START_PADDING,
                end = ROW_END_PADDING,
                top = ROW_VERTICAL_PADDING,
                bottom = ROW_VERTICAL_PADDING
            ),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = header.name,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )

                if (isActive) {
                    Spacer(modifier = Modifier.width(INLINE_GAP))

                    Text(
                        text = "Loaded",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = SproutGreen
                    )
                }
            }

            Text(
                text = detailText,
                fontSize = 11.sp,
                color = TextMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        Spacer(modifier = Modifier.width(INLINE_GAP))

        // The active session has no Load button; Reload lives in its menu since it is rarely needed
        if (!isActive) {
            SessionActionButton(
                label = "Load",
                style = SessionButtonStyle.Tonal,
                onClick = onLoad
            )
        }

        Box {
            GhostIconButton(
                icon = Icons.Default.MoreVert,
                contentDescription = "More options",
                onClick = { showMenu = true }
            )

            SessionMenu(
                expanded = showMenu,
                onDismiss = { showMenu = false }
            ) {
                if (isActive) {
                    SessionMenuItem(
                        icon = Icons.Default.Refresh,
                        label = "Reload",
                        onClick = {
                            showMenu = false
                            onLoad()
                        }
                    )
                }

                SessionMenuItem(
                    icon = Icons.Default.Share,
                    label = "Share",
                    onClick = {
                        showMenu = false
                        onShare()
                    }
                )

                SessionMenuItem(
                    icon = Icons.Default.Delete,
                    label = "Delete",
                    tint = DangerRed,
                    onClick = {
                        showMenu = false
                        onDelete()
                    }
                )
            }
        }
    }
}

/** Visual weight of a session action: Primary (solid), Tonal (soft green) or Warning (soft amber). */
private enum class SessionButtonStyle {
    Primary,
    Tonal,
    Warning
}

/** What the Save button is currently telling the user. */
private enum class SaveFeedback {
    Idle,
    Saving,
    Saved,
    Failed
}

@Composable
private fun SessionActionButton(
    label: String,
    style: SessionButtonStyle,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    leadingIcon: ImageVector? = null,
    // false keeps the normal look but ignores taps (no ripple), unlike enabled = false which greys it out
    interactive: Boolean = true
) {
    val shape = RoundedCornerShape(SESSION_BUTTON_CORNER_RADIUS)

    val backgroundColor = when {
        !enabled -> StudioSurfaceVariant.copy(alpha = DISABLED_BG_ALPHA)
        style == SessionButtonStyle.Primary -> SproutGreen
        style == SessionButtonStyle.Warning -> WarmSunbeam.copy(alpha = TONAL_BG_ALPHA)
        else -> SproutGreen.copy(alpha = TONAL_BG_ALPHA)
    }

    val contentColor = when {
        !enabled -> TextMuted
        style == SessionButtonStyle.Primary -> StudioBackground
        style == SessionButtonStyle.Warning -> WarmSunbeam
        else -> SproutGreen
    }

    Row(
        modifier = modifier
            .height(SESSION_BUTTON_HEIGHT)
            .clip(shape)
            .background(backgroundColor)
            .clickable(enabled = enabled && interactive, onClick = onClick)
            .padding(horizontal = SESSION_BUTTON_H_PADDING),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(SESSION_BUTTON_ICON_GAP, Alignment.CenterHorizontally)
    ) {
        if (leadingIcon != null) {
            Icon(
                imageVector = leadingIcon,
                contentDescription = null,
                tint = contentColor,
                modifier = Modifier.size(SESSION_BUTTON_ICON_SIZE)
            )
        }

        Text(
            text = label,
            fontSize = SESSION_BUTTON_FONT_SIZE,
            fontWeight = FontWeight.SemiBold,
            color = contentColor,
            maxLines = 1
        )
    }
}

/** Borderless round icon button, used for close, cancel and the 3-dots menus. */
@Composable
private fun GhostIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    size: androidx.compose.ui.unit.Dp = ICON_BUTTON_SIZE,
    iconSize: androidx.compose.ui.unit.Dp = ICON_SIZE
) {
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = TextSecondary,
            modifier = Modifier.size(iconSize)
        )
    }
}

@Composable
private fun SessionMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    content: @Composable () -> Unit
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        modifier = Modifier
            .background(StudioSurfaceElevated)
            .border(1.dp, StudioPanelBorder, RoundedCornerShape(MENU_CORNER_RADIUS))
    ) {
        content()
    }
}

@Composable
private fun SessionMenuItem(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    tint: Color = SproutGreen
) {
    val textColor = if (tint == DangerRed) {
        DangerRed
    } else {
        TextPrimary
    }

    DropdownMenuItem(
        text = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(INLINE_GAP)
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = tint,
                    modifier = Modifier.size(ICON_SIZE)
                )

                Text(
                    text = label,
                    color = textColor,
                    fontSize = 13.sp
                )
            }
        },
        onClick = onClick,
        colors = MenuDefaults.itemColors()
    )
}

@Composable
private fun ConfirmationDialog(
    title: String,
    message: String,
    confirmLabel: String,
    dismissLabel: String,
    isTitleDanger: Boolean,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    val borderColor = if (isTitleDanger) {
        DangerRed.copy(alpha = ACTIVE_BORDER_ALPHA)
    } else {
        StudioPanelBorder
    }

    val titleColor = if (isTitleDanger) {
        DangerRed
    } else {
        TextPrimary
    }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier
                .fillMaxWidth(CONFIRM_DIALOG_WIDTH_FRACTION)
                .clip(RoundedCornerShape(CONFIRM_DIALOG_CORNER_RADIUS))
                .background(StudioSurface)
                .border(1.dp, borderColor, RoundedCornerShape(CONFIRM_DIALOG_CORNER_RADIUS))
                .padding(CONFIRM_DIALOG_PADDING),
            colors = CardDefaults.cardColors(containerColor = StudioSurface)
        ) {
            Column {
                Text(
                    text = title,
                    color = titleColor,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold
                )

                Spacer(modifier = Modifier.height(INLINE_GAP))

                Text(
                    text = message,
                    color = TextSecondary,
                    fontSize = 13.sp
                )

                Spacer(modifier = Modifier.height(SECTION_SPACING))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Button(
                        onClick = onDismiss,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = StudioSurfaceElevated,
                            contentColor = TextPrimary
                        ),
                        shape = RoundedCornerShape(CONFIRM_BUTTON_CORNER_RADIUS)
                    ) {
                        Text(dismissLabel)
                    }

                    Spacer(modifier = Modifier.width(INLINE_GAP))

                    Button(
                        onClick = onConfirm,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = DangerRed,
                            contentColor = TextPrimary
                        ),
                        shape = RoundedCornerShape(CONFIRM_BUTTON_CORNER_RADIUS)
                    ) {
                        Text(confirmLabel)
                    }
                }
            }
        }
    }
}

private fun shareSessionFile(context: Context, file: File, sessionName: String) {
    try {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )

        val intent = Intent(Intent.ACTION_SEND).apply {
            type = RackPreset.MIME_TYPE
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "Greenhouse: $sessionName")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        context.startActivity(Intent.createChooser(intent, "Share"))
    } catch (e: Throwable) {
        val fallbackIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, file.readText())
            putExtra(Intent.EXTRA_SUBJECT, "Greenhouse: $sessionName")
        }
        context.startActivity(Intent.createChooser(fallbackIntent, "Share"))
    }
}
