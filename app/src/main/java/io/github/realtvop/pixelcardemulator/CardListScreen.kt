package io.github.realtvop.pixelcardemulator

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.AnchoredDraggableDefaults
import androidx.compose.foundation.gestures.AnchoredDraggableState
import androidx.compose.foundation.gestures.DraggableAnchors
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.anchoredDraggable
import androidx.compose.foundation.gestures.animateTo
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.plus
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilledTonalIconToggleButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.offset
import kotlin.math.roundToInt

/** Opened from the overflow menu so that people can star the project. */
private const val GitHubRepoUrl = "https://github.com/realtvop/PixelCardEmulator"

@Composable
fun CardListScreen(
    cards: List<SavedCard>,
    activeId: String?,
    busyCardId: String? = null,
    defaultId: String?,
    onToggleActive: (String) -> Unit,
    onToggleDefault: (String) -> Unit,
    onAddCard: () -> Unit,
    onEditCard: (String) -> Unit,
    forceRaw: Boolean = false,
    onToggleForceRaw: () -> Unit = {},
    restartDiscoveryAfterSwitch: Boolean = true,
    onToggleRestartDiscoveryAfterSwitch: () -> Unit = {},
    language: AppLanguage = AppLanguage.SystemDefault,
    onLanguageSelected: (AppLanguage) -> Unit = {},
    showPermissionBanner: Boolean = false,
    onPermissionBannerClick: () -> Unit = {},
    automaticPickerEnabled: Boolean = false,
    onAutomaticPickerClick: () -> Unit = {},
    updateVersion: String? = null,
    onSkipUpdate: () -> Unit = {},
    managementEnabled: Boolean = true,
) {
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    // The card whose swipe menu is open. Held here so that opening one closes any other.
    var swipedCardId by remember { mutableStateOf<String?>(null) }
    var appMenuOpen by remember { mutableStateOf(false) }
    var languageDialogOpen by remember { mutableStateOf(false) }
    val uriHandler = LocalUriHandler.current
    // The banner stays composed while it animates away, after updateVersion has gone back to null.
    var shownUpdateVersion by remember { mutableStateOf(updateVersion.orEmpty()) }
    if (updateVersion != null) shownUpdateVersion = updateVersion

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            Column {
                TopAppBar(
                    title = { Text(stringResource(R.string.cards)) },
                    actions = {
                        if (managementEnabled) {
                            FilledTonalIconButton(
                                onClick = onAddCard,
                                shapes = IconButtonDefaults.shapes(),
                                modifier = Modifier
                                    .padding(end = 8.dp)
                                    .size(
                                        IconButtonDefaults.smallContainerSize(
                                            IconButtonDefaults.IconButtonWidthOption.Wide,
                                        ),
                                    ),
                            ) {
                                Icon(Icons.Rounded.Add, contentDescription = stringResource(R.string.add_card))
                            }
                            Box {
                                IconButton(onClick = { appMenuOpen = true }) {
                                    Icon(Icons.Rounded.MoreVert, contentDescription = stringResource(R.string.more_options))
                                }
                                DropdownMenu(expanded = appMenuOpen, onDismissRequest = { appMenuOpen = false }) {
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.automatic_card_picker)) },
                                        onClick = {
                                            appMenuOpen = false
                                            onAutomaticPickerClick()
                                        },
                                        trailingIcon = {
                                            if (automaticPickerEnabled) {
                                                Icon(
                                                    Icons.Rounded.Check,
                                                    contentDescription = stringResource(R.string.enabled),
                                                )
                                            } else {
                                                Text(
                                                    stringResource(R.string.set_up),
                                                    style = MaterialTheme.typography.labelLarge,
                                                )
                                            }
                                        },
                                    )
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.raw_nci_mode)) },
                                        onClick = {
                                            appMenuOpen = false
                                            onToggleForceRaw()
                                        },
                                        trailingIcon = {
                                            if (forceRaw) {
                                                Icon(
                                                    Icons.Rounded.Check,
                                                    contentDescription = stringResource(R.string.enabled),
                                                )
                                            }
                                        },
                                    )
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.brief_disconnect_on_card_switch)) },
                                        onClick = {
                                            appMenuOpen = false
                                            onToggleRestartDiscoveryAfterSwitch()
                                        },
                                        trailingIcon = {
                                            if (restartDiscoveryAfterSwitch) {
                                                Icon(
                                                    Icons.Rounded.Check,
                                                    contentDescription = stringResource(R.string.enabled),
                                                )
                                            }
                                        },
                                    )
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.language)) },
                                        trailingIcon = {
                                            Text(
                                                stringResource(language.label),
                                                style = MaterialTheme.typography.labelLarge,
                                            )
                                        },
                                        onClick = {
                                            appMenuOpen = false
                                            languageDialogOpen = true
                                        },
                                    )
                                    HorizontalDivider()
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.star_on_github)) },
                                        onClick = {
                                            appMenuOpen = false
                                            uriHandler.openUri(GitHubRepoUrl)
                                        },
                                    )
                                }
                            }
                        }
                    },
                    scrollBehavior = scrollBehavior,
                )
                AnimatedVisibility(visible = showPermissionBanner) {
                    PermissionBanner(onClick = onPermissionBannerClick)
                }
                AnimatedVisibility(visible = updateVersion != null) {
                    UpdateBanner(
                        version = shownUpdateVersion,
                        onDownload = { uriHandler.openUri(LatestReleaseUrl) },
                        onSkip = onSkipUpdate,
                    )
                }
            }
        },
    ) { innerPadding ->
        if (cards.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(horizontal = 24.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(R.string.card_tip),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        } else {
            // One column on a phone, more on unfolded or landscape screens.
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 320.dp),
                contentPadding = innerPadding + PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(cards, key = { it.id }) { card ->
                    CardRow(
                        card = card,
                        active = card.id == activeId,
                        busy = card.id == busyCardId,
                        isDefault = card.id == defaultId,
                        swipeMenuOpen = card.id == swipedCardId,
                        onSwipeMenuOpenChange = { open ->
                            if (open) swipedCardId = card.id else if (swipedCardId == card.id) swipedCardId = null
                        },
                        onToggleActive = { onToggleActive(card.id) },
                        onToggleDefault = { onToggleDefault(card.id) },
                        onEdit = { onEditCard(card.id) },
                        swipeActionsEnabled = managementEnabled,
                        modifier = Modifier.animateItem(),
                    )
                }
            }
        }
    }

    if (languageDialogOpen) {
        LanguageDialog(
            selected = language,
            onSelect = {
                languageDialogOpen = false
                onLanguageSelected(it)
            },
            onDismiss = { languageDialogOpen = false },
        )
    }
}

@Composable
private fun PermissionBanner(onClick: () -> Unit) {
    ListItem(
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        leadingContent = {
            Icon(painterResource(R.drawable.ic_contactless), contentDescription = null)
        },
        trailingContent = {
            TextButton(onClick = onClick) { Text(stringResource(R.string.set_up)) }
        },
        shapes = ListItemDefaults.shapes(shape = MaterialTheme.shapes.large),
        colors = ListItemDefaults.colors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
            leadingContentColor = MaterialTheme.colorScheme.onErrorContainer,
            trailingContentColor = MaterialTheme.colorScheme.onErrorContainer,
        ),
    ) {
        Text(stringResource(R.string.permission_required))
    }
}

/** Download opens the release page in the browser. Closing it skips this version, so it only returns for a newer one. */
@Composable
private fun UpdateBanner(version: String, onDownload: () -> Unit, onSkip: () -> Unit) {
    ListItem(
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        leadingContent = {
            Icon(painterResource(R.drawable.ic_download), contentDescription = null)
        },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onDownload) { Text(stringResource(R.string.download)) }
                IconButton(onClick = onSkip) {
                    Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.skip_this_version))
                }
            }
        },
        shapes = ListItemDefaults.shapes(shape = MaterialTheme.shapes.large),
        colors = ListItemDefaults.colors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            leadingContentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            trailingContentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        ),
    ) {
        Text(stringResource(R.string.update_available, version))
    }
}

/** Choosing a language applies it straight away, which is why there is no confirm button. */
@Composable
private fun LanguageDialog(
    selected: AppLanguage,
    onSelect: (AppLanguage) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.language)) },
        text = {
            Column(Modifier.selectableGroup()) {
                AppLanguage.entries.forEach { option ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 56.dp)
                            .selectable(
                                selected = option == selected,
                                onClick = { onSelect(option) },
                                role = Role.RadioButton,
                            ),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = option == selected, onClick = null)
                        Text(stringResource(option.label), modifier = Modifier.padding(start = 8.dp))
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

private enum class SwipeMenu { Closed, Open }

private val SwipeActionSize = 56.dp
private val SwipeActionGap = 12.dp

/** How far a swiped card gives way: its two action buttons, each with a gap before it. */
private val SwipeMenuWidth = (SwipeActionSize + SwipeActionGap) * 2

/**
 * Shows the name only; the UID stays hidden. Tap toggles active. The default toggle and Edit are
 * reached by swiping the card aside.
 */
@Composable
private fun CardRow(
    card: SavedCard,
    active: Boolean,
    busy: Boolean,
    isDefault: Boolean,
    swipeMenuOpen: Boolean,
    onSwipeMenuOpenChange: (Boolean) -> Unit,
    onToggleActive: () -> Unit,
    onToggleDefault: () -> Unit,
    onEdit: () -> Unit,
    swipeActionsEnabled: Boolean,
    modifier: Modifier = Modifier,
) {
    val haptics = LocalHapticFeedback.current
    val colorScheme = MaterialTheme.colorScheme

    val swipeWidthPx = with(LocalDensity.current) { SwipeMenuWidth.toPx() }
    val swipe = remember(swipeWidthPx) {
        AnchoredDraggableState(
            initialValue = SwipeMenu.Closed,
            anchors = DraggableAnchors {
                SwipeMenu.Closed at 0f
                SwipeMenu.Open at -swipeWidthPx
            },
        )
    }
    val swiped by remember(swipe, swipeActionsEnabled) {
        derivedStateOf { swipeActionsEnabled && -swipe.offset >= 1f }
    }
    // Report where a swipe settles, and close again when told to (another card opened, or a tap).
    if (swipeActionsEnabled) {
        LaunchedEffect(swipe.settledValue) { onSwipeMenuOpenChange(swipe.settledValue == SwipeMenu.Open) }
        LaunchedEffect(swipeMenuOpen) { if (!swipeMenuOpen) swipe.animateTo(SwipeMenu.Closed) }
    }

    Box(
        if (swipeActionsEnabled) {
            modifier.anchoredDraggable(
                state = swipe,
                orientation = Orientation.Horizontal,
                flingBehavior = AnchoredDraggableDefaults.flingBehavior(
                    state = swipe,
                    animationSpec = MaterialTheme.motionScheme.fastSpatialSpec(),
                ),
            )
        } else {
            modifier
        },
    ) {
        // Only composed while swiped, so the actions are never reachable underneath a closed card.
        if (swiped) {
            Row(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .graphicsLayer {
                        // Grows into the space the card gives up.
                        val progress = (-swipe.offset / swipeWidthPx).coerceIn(0f, 1f)
                        alpha = progress
                        scaleX = 0.5f + progress / 2
                        scaleY = 0.5f + progress / 2
                    },
                horizontalArrangement = Arrangement.spacedBy(SwipeActionGap),
            ) {
                FilledTonalIconToggleButton(
                    checked = isDefault,
                    onCheckedChange = {
                        onToggleDefault()
                        onSwipeMenuOpenChange(false)
                    },
                    shapes = IconButtonDefaults.toggleableShapes(),
                    modifier = Modifier.size(SwipeActionSize),
                ) {
                    Icon(Icons.Rounded.Star, contentDescription = stringResource(R.string.default_card))
                }
                FilledTonalIconButton(
                    onClick = {
                        onSwipeMenuOpenChange(false)
                        onEdit()
                    },
                    shapes = IconButtonDefaults.shapes(),
                    modifier = Modifier.size(SwipeActionSize),
                ) {
                    Icon(Icons.Rounded.Edit, contentDescription = stringResource(R.string.edit))
                }
            }
        }

        ListItem(
            checked = active,
            onCheckedChange = { nowActive ->
                if (swipeMenuOpen) {
                    // A tap on a swiped card only puts it back.
                    onSwipeMenuOpenChange(false)
                } else {
                    haptics.performHapticFeedback(
                        if (!swipeActionsEnabled || nowActive) HapticFeedbackType.ToggleOn else HapticFeedbackType.ToggleOff,
                    )
                    onToggleActive()
                }
            },
            modifier = Modifier
                // Swiping squeezes the card from its end edge rather than sliding it out of the row.
                .layout { measurable, constraints ->
                    val given = if (swipeActionsEnabled) {
                        (-swipe.offset).roundToInt().coerceIn(0, constraints.maxWidth)
                    } else {
                        0
                    }
                    val placeable = measurable.measure(constraints.offset(horizontal = -given))
                    layout(constraints.maxWidth, placeable.height) { placeable.placeRelative(0, 0) }
                }
                // Tall enough that showing or hiding the "Default" line never changes the row height.
                .heightIn(min = 88.dp),
            leadingContent = { CardBadge(active) },
            supportingContent = {
                AnimatedVisibility(isDefault) { Text(stringResource(R.string.default_card)) }
            },
            trailingContent = {
                if (busy) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        strokeWidth = 3.dp,
                    )
                } else {
                    AnimatedVisibility(active, enter = fadeIn() + scaleIn(), exit = fadeOut() + scaleOut()) {
                        Text(stringResource(R.string.active), style = MaterialTheme.typography.labelLarge)
                    }
                }
            },
            verticalAlignment = Alignment.CenterVertically,
            // The active card also rounds into a pill, so the state never depends on color alone.
            shapes = ListItemDefaults.shapes(
                shape = MaterialTheme.shapes.extraLarge,
                selectedShape = MaterialTheme.shapes.extraExtraLarge,
                pressedShape = MaterialTheme.shapes.large,
            ),
            colors = ListItemDefaults.colors(
                containerColor = colorScheme.surfaceContainer,
                selectedContainerColor = colorScheme.primaryContainer,
                selectedContentColor = colorScheme.onPrimaryContainer,
                selectedSupportingContentColor = colorScheme.onPrimaryContainer,
                selectedTrailingContentColor = colorScheme.onPrimaryContainer,
            ),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
        ) {
            Text(
                text = card.name,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun CardBadge(active: Boolean) {
    val colorScheme = MaterialTheme.colorScheme
    val effects = MaterialTheme.motionScheme.defaultEffectsSpec<Color>()
    val container by animateColorAsState(
        if (active) colorScheme.primary else colorScheme.surfaceContainerHighest,
        effects,
        label = "badgeContainer",
    )
    val content by animateColorAsState(
        if (active) colorScheme.onPrimary else colorScheme.onSurfaceVariant,
        effects,
        label = "badgeContent",
    )

    Box(
        modifier = Modifier
            .size(48.dp)
            .background(container, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(painterResource(R.drawable.ic_contactless), contentDescription = null, tint = content)
    }
}

@PreviewLightDark
@Composable
private fun CardListScreenPreview() {
    PixelCardTheme {
        CardListScreen(
            cards = MockCards,
            activeId = MockCards[0].id,
            defaultId = MockCards[1].id,
            onToggleActive = {},
            onToggleDefault = {},
            onAddCard = {},
            onEditCard = {},
            forceRaw = false,
            onToggleForceRaw = {},
            language = AppLanguage.SystemDefault,
            onLanguageSelected = {},
            showPermissionBanner = true,
            onPermissionBannerClick = {},
            updateVersion = "0.2.0",
        )
    }
}

@PreviewLightDark
@Composable
private fun EmptyCardListScreenPreview() {
    PixelCardTheme {
        CardListScreen(
            cards = emptyList(),
            activeId = null,
            defaultId = null,
            onToggleActive = {},
            onToggleDefault = {},
            onAddCard = {},
            onEditCard = {},
            forceRaw = false,
            onToggleForceRaw = {},
            language = AppLanguage.SystemDefault,
            onLanguageSelected = {},
            showPermissionBanner = false,
            onPermissionBannerClick = {},
        )
    }
}
