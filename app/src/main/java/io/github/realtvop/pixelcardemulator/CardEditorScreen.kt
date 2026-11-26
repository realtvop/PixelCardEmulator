package io.github.realtvop.pixelcardemulator

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.nfc.NfcAdapter
import android.nfc.NfcManager
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.OutputTransformation
import androidx.compose.foundation.text.input.TextFieldBuffer
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.delete
import androidx.compose.foundation.text.input.insert
import androidx.compose.foundation.text.input.maxLength
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.unit.dp
import kotlin.random.Random

private val UidSizes = listOf(4, 7, 10)
private const val MaxNameLength = 32
private const val NfcACascadeTag: Byte = 0x88.toByte()

/** Keeps the form from stretching across unfolded or landscape screens. */
private val FormMaxWidth = 560.dp

/** Adds a new card when [card] is null; otherwise shows that saved card so it can be changed or deleted. */
@Composable
fun CardEditorScreen(
    card: SavedCard?,
    onBack: () -> Unit,
    onSave: (name: String, uid: String) -> Unit,
    onDelete: () -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    val focusManager = LocalFocusManager.current
    val name = rememberTextFieldState(card?.name.orEmpty())
    val typedUid = rememberTextFieldState(card?.uid.orEmpty())
    var uidSize by rememberSaveable { mutableIntStateOf(card?.uidSize ?: UidSizes.first()) }
    // A saved UID stays hidden until it is asked for; a new card starts shown. A hidden UID cannot be
    // typed into, so nothing is typed blind; scanning still replaces it without revealing it.
    var uidShown by rememberSaveable { mutableStateOf(card == null) }
    val uidInteraction = remember { MutableInteractionSource() }
    val uidFocused by uidInteraction.collectIsFocusedAsState()
    var scanning by rememberSaveable { mutableStateOf(false) }
    var scanError by rememberSaveable { mutableStateOf<String?>(null) }
    var confirmingDelete by rememberSaveable { mutableStateOf(false) }

    // Null until the UID has as many bytes as the chosen size.
    val uid = typedUid.text.toString().takeIf { it.length == uidSize * 2 }

    NfcUidReader(
        enabled = scanning,
        onUid = { uidHex ->
            uidSize = uidHex.length / 2
            typedUid.edit { replace(0, length, uidHex) }
            // The filled-in form is the result, so the dialog closes on its own.
            scanning = false
            haptics.performHapticFeedback(HapticFeedbackType.Confirm)
        },
        onError = { scanError = it },
    )

    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    Scaffold(
        modifier = Modifier
            .nestedScroll(scrollBehavior.nestedScrollConnection)
            .imePadding(),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(if (card == null) R.string.add_card else R.string.edit_card)) },
                navigationIcon = {
                    IconButton(onClick = onBack, shapes = IconButtonDefaults.shapes()) {
                        Icon(
                            Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = stringResource(R.string.back),
                        )
                    }
                },
                actions = {
                    if (card != null) {
                        IconButton(onClick = { confirmingDelete = true }, shapes = IconButtonDefaults.shapes()) {
                            Icon(Icons.Rounded.Delete, contentDescription = stringResource(R.string.delete_card))
                        }
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        },
        bottomBar = {
            val height = ButtonDefaults.MediumContainerHeight
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(16.dp),
                contentAlignment = Alignment.Center,
            ) {
                Button(
                    onClick = { uid?.let { onSave(name.text.toString().trim(), it) } },
                    shapes = ButtonDefaults.shapesFor(height),
                    modifier = Modifier
                        .widthIn(max = FormMaxWidth)
                        .fillMaxWidth()
                        .heightIn(min = height),
                    enabled = name.text.isNotBlank() && uid != null,
                    contentPadding = ButtonDefaults.contentPaddingFor(height),
                ) {
                    Text(
                        text = stringResource(if (card == null) R.string.add_card else R.string.save),
                        style = ButtonDefaults.textStyleFor(height),
                    )
                }
            }
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                // Inside the bars, so the form never scrolls behind the save button.
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(Modifier.widthIn(max = FormMaxWidth)) {
                TextField(
                    state = name,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.name)) },
                    inputTransformation = InputTransformation.maxLength(MaxNameLength),
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Sentences,
                        imeAction = ImeAction.Done,
                    ),
                    lineLimits = TextFieldLineLimits.SingleLine,
                    shape = TextFieldDefaults.roundedShape,
                    colors = TextFieldDefaults.tonalColors(),
                )

                Spacer(Modifier.height(24.dp))
                UidSizeChoice(
                    selected = uidSize,
                    onSelect = { newSize ->
                        uidSize = newSize
                        typedUid.edit { if (length > newSize * 2) delete(newSize * 2, length) }
                    },
                )

                Spacer(Modifier.height(8.dp))
                TextField(
                    state = typedUid,
                    modifier = Modifier.fillMaxWidth(),
                    readOnly = !uidShown,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
                    label = { Text(stringResource(R.string.uid_hex)) },
                    trailingIcon = {
                        Row {
                            if (card == null) {
                                IconButton(
                                    onClick = {
                                        typedUid.setTextAndPlaceCursorAtEnd(generateRandomUid(uidSize))
                                        uidShown = true
                                        haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                                    },
                                    shapes = IconButtonDefaults.shapes(),
                                ) {
                                    Icon(
                                        painterResource(R.drawable.ic_dice),
                                        contentDescription = stringResource(R.string.generate_random_uid),
                                    )
                                }
                            }
                            IconButton(
                                onClick = {
                                    // Hiding a UID that is being typed also puts the keyboard away.
                                    if (uidShown && uidFocused) focusManager.clearFocus()
                                    uidShown = !uidShown
                                },
                                shapes = IconButtonDefaults.shapes(),
                            ) {
                                Icon(
                                    painterResource(if (uidShown) R.drawable.ic_visibility_off else R.drawable.ic_visibility),
                                    contentDescription = stringResource(if (uidShown) R.string.hide_uid else R.string.show_uid),
                                )
                            }
                        }
                    },
                    supportingText = {
                        Text(pluralStringResource(R.plurals.uid_progress, uidSize, typedUid.text.length / 2, uidSize))
                    },
                    inputTransformation = hexDigits(max = uidSize * 2),
                    outputTransformation = if (uidShown) BytePairs else MaskedBytePairs,
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Characters,
                        autoCorrectEnabled = false,
                        keyboardType = KeyboardType.Ascii,
                        imeAction = ImeAction.Done,
                    ),
                    lineLimits = TextFieldLineLimits.SingleLine,
                    shape = TextFieldDefaults.roundedShape,
                    colors = TextFieldDefaults.tonalColors(),
                    interactionSource = uidInteraction,
                )

                Spacer(Modifier.height(16.dp))
                FilledTonalButton(
                    onClick = {
                        scanError = null
                        scanning = true
                    },
                    shapes = ButtonDefaults.shapes(),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(
                        painterResource(R.drawable.ic_contactless),
                        contentDescription = null,
                        modifier = Modifier.size(ButtonDefaults.IconSize),
                    )
                    Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                    Text(stringResource(R.string.scan_card))
                }
            }
        }
    }

    if (scanning) {
        ScanDialog(error = scanError, onDismiss = { scanning = false })
    }

    if (card != null && confirmingDelete) {
        AlertDialog(
            onDismissRequest = { confirmingDelete = false },
            title = { Text(stringResource(R.string.delete_card_title, card.name)) },
            text = { Text(stringResource(R.string.delete_card_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmingDelete = false
                        onDelete()
                    },
                    shapes = ButtonDefaults.shapes(),
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) {
                    Text(stringResource(R.string.delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmingDelete = false }, shapes = ButtonDefaults.shapes()) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}

/** A connected button group for the UID size, which sets how many digits the UID field takes. */
@Composable
private fun UidSizeChoice(
    selected: Int,
    onSelect: (Int) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
    ) {
        UidSizes.forEachIndexed { index, size ->
            ToggleButton(
                checked = size == selected,
                onCheckedChange = { onSelect(size) },
                modifier = Modifier
                    .weight(1f)
                    .semantics { role = Role.RadioButton },
                shapes = when (index) {
                    0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                    UidSizes.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                    else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
                },
            ) {
                Text(pluralStringResource(R.plurals.bytes, size, size), maxLines = 1)
            }
        }
    }
}

/** Shown while waiting for an NFC card. The read UID is never displayed here. */
@Composable
private fun ScanDialog(
    error: String?,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(painterResource(R.drawable.ic_contactless), contentDescription = null) },
        title = { Text(stringResource(R.string.scan_card)) },
        text = {
            Text(
                text = error ?: stringResource(R.string.scan_instruction),
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { liveRegion = LiveRegionMode.Polite },
                color = if (error == null) Color.Unspecified else MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center,
            )
        },
        confirmButton = {
            TextButton(onClick = onDismiss, shapes = ButtonDefaults.shapes()) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}

/** Enables Android's NFC-A reader mode only while [enabled]. */
@Composable
private fun NfcUidReader(
    enabled: Boolean,
    onUid: (String) -> Unit,
    onError: (String) -> Unit,
) {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    val adapter = remember(context) { context.getSystemService(NfcManager::class.java)?.defaultAdapter }
    val unavailableMessage = stringResource(R.string.nfc_unavailable)
    val disabledMessage = stringResource(R.string.nfc_disabled)
    val invalidUidMessage = stringResource(R.string.unsupported_uid_length)

    DisposableEffect(enabled, activity, adapter) {
        if (!enabled) return@DisposableEffect onDispose {}
        if (activity == null || adapter == null) {
            onError(unavailableMessage)
            return@DisposableEffect onDispose {}
        }
        if (!adapter.isEnabled) {
            onError(disabledMessage)
            return@DisposableEffect onDispose {}
        }

        val callback = NfcAdapter.ReaderCallback { tag ->
            val uid = tag.id
            activity.runOnUiThread {
                if (uid.size in SupportedUidSizes) onUid(uid.toHex()) else onError(invalidUidMessage)
            }
        }
        adapter.enableReaderMode(
            activity,
            callback,
            NfcAdapter.FLAG_READER_NFC_A or
                NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK or
                NfcAdapter.FLAG_READER_NO_PLATFORM_SOUNDS,
            null,
        )
        onDispose { runCatching { adapter.disableReaderMode(activity) } }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

private fun generateRandomUid(size: Int): String {
    val bytes = ByteArray(size)
    // NFC-A reserves the cascade tag in UID0 for 4-byte UIDs and UID3 for longer UIDs.
    val cascadeTagIndex = if (size == 4) 0 else 3
    do {
        Random.nextBytes(bytes)
    } while (bytes[cascadeTagIndex] == NfcACascadeTag)
    return bytes.toHex()
}

/** Keeps only hex digits, so a pasted "04:A2:24" still works, uppercased and capped at [max] digits. */
private fun hexDigits(max: Int) = InputTransformation {
    val typed = asCharSequence().toString()
    val hex = typed.toHex().take(max)
    if (hex != typed) {
        // Replacing the text moves the cursor to the end, so put it back where the user was editing.
        val cursor = typed.take(selection.end).toHex().length.coerceAtMost(hex.length)
        replace(0, length, hex)
        selection = TextRange(cursor)
    }
}

private fun String.toHex() = uppercase().filter { it in "0123456789ABCDEF" }

/** Displays the digits as space-separated bytes without changing the stored text. */
private val BytePairs = OutputTransformation { spaceBytes() }

/** Like [BytePairs], with every digit masked, so the size still shows but the value does not. */
private val MaskedBytePairs = OutputTransformation {
    // One character at a time, so positions in the masked text still map to the real digits.
    for (index in 0 until length) replace(index, index + 1, "•")
    spaceBytes()
}

private fun TextFieldBuffer.spaceBytes() {
    for (index in (length - 1) / 2 * 2 downTo 2 step 2) insert(index, " ")
}

@PreviewLightDark
@Composable
private fun AddCardPreview() {
    PixelCardTheme {
        CardEditorScreen(card = null, onBack = {}, onSave = { _, _ -> }, onDelete = {})
    }
}

@PreviewLightDark
@Composable
private fun EditCardPreview() {
    PixelCardTheme {
        CardEditorScreen(card = MockCards[1], onBack = {}, onSave = { _, _ -> }, onDelete = {})
    }
}
