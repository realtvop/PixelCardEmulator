package io.github.realtvop.pixelcardemulator

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Build
import android.provider.Settings
import androidx.activity.BackEventCompat
import androidx.activity.ComponentActivity
import androidx.activity.compose.PredictiveBackHandler
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.animate
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Editor target for a card that does not exist yet. Never collides with an id, which is a UUID. */
private const val NewCard = "new"

/** Corner radius the editor reaches at the end of a back swipe, where it has become a card. */
private val SwipeCornerRadius = 32.dp

/** How dark the list gets behind the editor at the end of a back swipe. */
private const val ScrimAlpha = 0.4f

class MainActivity : ComponentActivity() {
    private val viewModel: CardsViewModel by viewModels()
    private val updates: UpdateViewModel by viewModels()
    private var automaticPickerEnabled by mutableStateOf(false)
    private val wirelessPermissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        if (requiredWirelessPermissions().all { result[it] == true || checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }) {
            beginWirelessDebuggingSetup()
        } else {
            viewModel.reportPermissionSetupError(R.string.wireless_permissions_denied)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        automaticPickerEnabled = Settings.canDrawOverlays(this)
        enableEdgeToEdge()
        setContent {
            PixelCardTheme {
                // Two screens only, so one value stands in for a navigation library: null shows the
                // list, anything else opens the editor on the card with that id, or on a new card.
                var editorTarget by rememberSaveable { mutableStateOf<String?>(null) }
                // A back swipe moves the editor by hand, over the list. Progress runs from 0 at rest to 1 at the
                // full swipe. Dismiss runs from 0 to 1 once a swipe is committed and carries the editor off the edge.
                var swipeProgress by remember { mutableFloatStateOf(0f) }
                var swipeDismiss by remember { mutableFloatStateOf(0f) }
                var swipeEdge by remember { mutableIntStateOf(BackEventCompat.EDGE_LEFT) }
                // The list is drawn beneath the editor only while a swipe is underway.
                var swiping by remember { mutableStateOf(false) }
                // A committed swipe has already carried the editor away, so the list needs no transition of its own.
                var swipeCommitted by remember { mutableStateOf(false) }

                val slide = MaterialTheme.motionScheme.defaultEffectsSpec<IntOffset>()
                val fade = MaterialTheme.motionScheme.defaultEffectsSpec<Float>()

                PredictiveBackHandler(enabled = editorTarget != null) { progress ->
                    var swiped = false
                    try {
                        progress.collect { backEvent ->
                            swiped = true
                            swiping = true
                            swipeEdge = backEvent.swipeEdge
                            swipeProgress = backEvent.progress
                        }
                        // Only a swipe the system committed carries the editor off. A plain back press goes as before.
                        if (swiped) {
                            animate(swipeDismiss, 1f, animationSpec = fade) { value, _ -> swipeDismiss = value }
                            swipeCommitted = true
                        }
                        editorTarget = null
                    } catch (e: CancellationException) {
                        // Cancelling the gesture cancels this coroutine too, so the spring back needs NonCancellable.
                        // A commit that was still carrying the editor off comes back too.
                        withContext(NonCancellable) {
                            launch { animate(swipeProgress, 0f, animationSpec = fade) { value, _ -> swipeProgress = value } }
                            launch { animate(swipeDismiss, 0f, animationSpec = fade) { value, _ -> swipeDismiss = value } }
                        }
                        throw e
                    } finally {
                        swiping = false
                    }
                }

                // Opening always starts the editor at rest, whatever the last swipe left behind.
                fun openEditor(target: String) {
                    swipeProgress = 0f
                    swipeDismiss = 0f
                    swipeCommitted = false
                    editorTarget = target
                }

                val list: @Composable () -> Unit = {
                    CardListScreen(
                        cards = viewModel.cards,
                        activeId = viewModel.activeId,
                        busyCardId = viewModel.emulatorUiState.busyCardId,
                        defaultId = viewModel.defaultId,
                        onToggleActive = viewModel::toggleActive,
                        onToggleDefault = viewModel::toggleDefault,
                        onAddCard = { openEditor(NewCard) },
                        onEditCard = { openEditor(it) },
                        forceRaw = viewModel.forceRaw,
                        onToggleForceRaw = viewModel::toggleForceRaw,
                        restartDiscoveryAfterSwitch = viewModel.restartDiscoveryAfterSwitch,
                        onToggleRestartDiscoveryAfterSwitch = viewModel::toggleRestartDiscoveryAfterSwitch,
                        language = viewModel.language,
                        onLanguageSelected = viewModel::setLanguage,
                        showPermissionBanner = !viewModel.secureSettingsGranted && !viewModel.forceRaw,
                        onPermissionBannerClick = viewModel::showPermissionPrompt,
                        automaticPickerEnabled = automaticPickerEnabled,
                        onAutomaticPickerClick = ::openAutomaticPickerSettings,
                        updateVersion = updates.availableVersion,
                        onSkipUpdate = updates::skip,
                    )
                }

                Box(Modifier.fillMaxSize()) {
                    if (swiping) {
                        Box(Modifier.fillMaxSize()) {
                            list()
                            // The list dims as the editor pulls away from it.
                            Box(
                                Modifier.fillMaxSize().drawBehind {
                                    val alpha = ScrimAlpha * swipeProgress * (1f - swipeDismiss)
                                    drawRect(Color.Black, alpha = alpha.coerceIn(0f, 1f))
                                },
                            )
                        }
                    }
                    AnimatedContent(
                        targetState = editorTarget,
                        modifier = Modifier.fillMaxSize(),
                        transitionSpec = {
                            if (targetState == null && swipeCommitted) {
                                // The editor has already gone off the edge, so the list simply appears.
                                EnterTransition.None togetherWith ExitTransition.None
                            } else {
                                // The editor enters from the end edge and leaves the way it came.
                                val direction = if (targetState != null) 1 else -1
                                (slideInHorizontally(slide) { direction * it / 6 } + fadeIn(fade)) togetherWith
                                    (slideOutHorizontally(slide) { -direction * it / 6 } + fadeOut(fade))
                            }
                        },
                        label = "screen",
                    ) { target ->
                        if (target == null) {
                            list()
                        } else {
                            // Remembered so the screen still shows the card while it animates out after a delete.
                            val card = remember { viewModel.cards.find { it.id == target } }
                            Box(
                                Modifier.fillMaxSize().graphicsLayer {
                                    // The editor shrinks and rounds off as the swipe goes on, then slides off toward
                                    // the edge the swipe started from once the swipe is committed.
                                    val scale = 1f - 0.1f * swipeProgress
                                    scaleX = scale
                                    scaleY = scale
                                    val side = if (swipeEdge == BackEventCompat.EDGE_RIGHT) -1 else 1
                                    translationX = side * size.width * swipeDismiss
                                    val radius = SwipeCornerRadius * swipeProgress
                                    shape = RoundedCornerShape(radius)
                                    clip = radius > 0.dp
                                },
                            ) {
                                CardEditorScreen(
                                    card = card,
                                    onBack = { editorTarget = null },
                                    onSave = { name, uid ->
                                        if (card == null) viewModel.add(name, uid) else viewModel.update(card.id, name, uid)
                                        editorTarget = null
                                    },
                                    onDelete = {
                                        card?.let { viewModel.delete(it.id) }
                                        editorTarget = null
                                    },
                                )
                            }
                        }
                    }
                }

                val emulatorState = viewModel.emulatorUiState
                if (emulatorState.permissionRequired) {
                    SecureSettingsPermissionDialog(
                        command = viewModel.grantCommand,
                        busy = emulatorState.permissionGrantBusy,
                        status = emulatorState.permissionGrantStatus,
                        error = emulatorState.permissionGrantError,
                        onShizuku = viewModel::grantPermissionWithShizuku,
                        onWirelessDebugging = ::requestWirelessDebuggingSetup,
                        onDismiss = viewModel::dismissPermissionPrompt,
                    )
                    LaunchedEffect(Unit) {
                        while (true) {
                            delay(1000)
                            viewModel.refreshPermission()
                        }
                    }
                }
                emulatorState.error?.let { message ->
                    AlertDialog(
                        onDismissRequest = viewModel::dismissError,
                        title = { Text(getString(R.string.nfc_operation_failed)) },
                        text = { Text(message) },
                        confirmButton = {
                            TextButton(onClick = viewModel::dismissError) { Text(getString(R.string.ok)) }
                        },
                    )
                }
                if (emulatorState.experimentalRawConfirmationRequired) {
                    AlertDialog(
                        onDismissRequest = viewModel::dismissExperimentalRawConfirmation,
                        title = { Text(getString(R.string.experimental_raw_title)) },
                        text = { Text(getString(R.string.experimental_raw_message)) },
                        confirmButton = {
                            TextButton(onClick = viewModel::confirmExperimentalRaw) {
                                Text(getString(R.string.continue_action))
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = viewModel::dismissExperimentalRawConfirmation) {
                                Text(getString(R.string.cancel))
                            }
                        },
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        automaticPickerEnabled = Settings.canDrawOverlays(this)
        viewModel.attachActivity(this)
        viewModel.refreshPermission()
        viewModel.refreshActiveCard()
    }

    override fun onPause() {
        viewModel.detachActivity(this)
        super.onPause()
    }

    override fun onStart() {
        super.onStart()
        AppVisibility.activityStarted()
    }

    override fun onStop() {
        AppVisibility.activityStopped()
        super.onStop()
    }

    private fun openAutomaticPickerSettings() {
        startActivity(
            Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName"),
            ),
        )
    }

    private fun requestWirelessDebuggingSetup() {
        val missing = requiredWirelessPermissions().filter {
            checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) {
            beginWirelessDebuggingSetup()
        } else {
            wirelessPermissions.launch(missing.toTypedArray())
        }
    }

    private fun requiredWirelessPermissions(): List<String> = buildList {
        add(Manifest.permission.POST_NOTIFICATIONS)
        if (Build.VERSION.SDK_INT >= 37) add(Manifest.permission.ACCESS_LOCAL_NETWORK)
    }

    private fun beginWirelessDebuggingSetup() {
        WirelessDebuggingService.begin(this)
        startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))
    }
}

@androidx.compose.runtime.Composable
private fun SecureSettingsPermissionDialog(
    command: String,
    busy: Boolean,
    status: String?,
    error: String?,
    onShizuku: () -> Unit,
    onWirelessDebugging: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.permission_required)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.permission_explanation))
                FilledTonalButton(
                    onClick = onShizuku,
                    enabled = !busy,
                    modifier = androidx.compose.ui.Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.grant_with_shizuku))
                }
                FilledTonalButton(
                    onClick = onWirelessDebugging,
                    enabled = !busy,
                    modifier = androidx.compose.ui.Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.grant_with_wireless_debugging))
                }
                status?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                HorizontalDivider(modifier = androidx.compose.ui.Modifier.padding(top = 4.dp))
                Text(stringResource(R.string.computer_adb_explanation, command))
                TextButton(
                    onClick = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("ADB command", command))
                    },
                    enabled = !busy,
                    modifier = androidx.compose.ui.Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.copy_command))
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !busy) { Text(stringResource(R.string.cancel)) }
        },
    )
}
