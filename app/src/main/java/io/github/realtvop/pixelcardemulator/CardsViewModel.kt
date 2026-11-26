package io.github.realtvop.pixelcardemulator

import android.app.Application
import android.Manifest
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import androidx.annotation.StringRes
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import java.util.UUID
import java.util.concurrent.Executors

data class EmulatorUiState(
    val busy: Boolean = false,
    val busyCardId: String? = null,
    val status: String? = null,
    val permissionRequired: Boolean = false,
    val permissionGrantBusy: Boolean = false,
    val permissionGrantStatus: String? = null,
    val permissionGrantError: String? = null,
    val experimentalRawConfirmationRequired: Boolean = false,
    val error: String? = null,
)

private sealed interface PendingAction {
    data class Activate(val card: SavedCard, val previousCard: SavedCard?) : PendingAction
    data class Deactivate(val deleteCardId: String? = null) : PendingAction
    data object RestoreAfterRestart : PendingAction
}

/** A saved card. [uid] is uppercase hex without separators. */
data class SavedCard(
    val name: String,
    val uid: String,
    val id: String = UUID.randomUUID().toString(),
) {
    init {
        require(uid.length / 2 in SupportedUidSizes && uid.length % 2 == 0) { "Unsupported UID length" }
        require(uid.all { it in "0123456789ABCDEF" }) { "UID must be uppercase hexadecimal" }
    }

    /** UID length in bytes: 4, 7 or 10. */
    val uidSize: Int get() = uid.length / 2
}

val MockCards = listOf(
    SavedCard("Office", "9C4E21B7"),
    SavedCard("Home", "04A2247B915E80"),
    SavedCard("Gym", "5A17C3F0"),
    SavedCard("Parking garage", "08F13D6A22B94C7E0151"),
)

class CardsViewModel(application: Application) : AndroidViewModel(application) {
    private val store = CardStore(application)
    private val emulator = NfcEmulationCoordinator(application)
    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val shizukuPermissionGranter = ShizukuPermissionGranter(
        application,
        onProgress = {
            emulatorUiState = emulatorUiState.copy(
                permissionGrantBusy = true,
                permissionGrantStatus = localized(R.string.shizuku_grant_in_progress),
                permissionGrantError = null,
            )
        },
        onGranted = ::refreshPermission,
        onError = { message ->
            emulatorUiState = emulatorUiState.copy(
                permissionGrantBusy = false,
                permissionGrantStatus = null,
                permissionGrantError = localized(message),
            )
        },
    )
    private var pendingAction: PendingAction? = null

    var cards by mutableStateOf(store.loadCards())
        private set

    /** Held as a single id so that at most one card can be active. */
    var activeId by mutableStateOf(
        store.loadActiveCardId().takeIf { id -> emulator.hasPendingRestore && cards.any { it.id == id } },
    )
        private set

    /** Independent of [activeId]: the default card does not have to be the active one. */
    var defaultId by mutableStateOf(store.loadDefaultCardId().takeIf { id -> cards.any { it.id == id } })
        private set

    var secureSettingsGranted by mutableStateOf(
        application.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) ==
            PackageManager.PERMISSION_GRANTED,
    )
        private set

    var emulatorUiState by mutableStateOf(
        EmulatorUiState(
            permissionRequired = !secureSettingsGranted,
        ),
    )
        private set

    var forceRaw by mutableStateOf(emulator.forceRaw)
        private set

    var restartDiscoveryAfterSwitch by mutableStateOf(emulator.restartDiscoveryAfterSwitch)
        private set

    val grantCommand: String
        get() = "adb shell pm grant ${getApplication<Application>().packageName} " +
            "android.permission.WRITE_SECURE_SETTINGS"

    init {
        val hasPendingEmulation = emulator.hasPendingRestore
        if (hasPendingEmulation) {
            if (activeId == null) pendingAction = PendingAction.RestoreAfterRestart
        } else {
            store.saveActiveCardId(null)
            pendingAction = defaultId?.let { id -> cards.find { it.id == id } }
                ?.let { PendingAction.Activate(it, null) }
        }
    }

    fun setActive(id: String?) {
        activeId = id
        store.saveActiveCardId(id)
    }

    fun refreshActiveCard() {
        if (emulatorUiState.busy) return
        activeId = store.loadActiveCardId().takeIf { id -> cards.any { it.id == id } }
    }

    fun toggleActive(id: String) {
        if (emulatorUiState.busy) return
        if (activeId == id) {
            runAction(PendingAction.Deactivate())
        } else {
            val card = cards.find { it.id == id } ?: return
            runAction(PendingAction.Activate(card, cards.find { it.id == activeId }))
        }
    }

    fun toggleDefault(id: String) {
        defaultId = if (defaultId == id) null else id
        store.saveDefaultCardId(defaultId)
    }

    fun toggleForceRaw() {
        forceRaw = !forceRaw
        emulator.forceRaw = forceRaw
    }

    fun toggleRestartDiscoveryAfterSwitch() {
        restartDiscoveryAfterSwitch = !restartDiscoveryAfterSwitch
        emulator.restartDiscoveryAfterSwitch = restartDiscoveryAfterSwitch
    }

    /** Read from the system each time, so a language picked in system settings shows here too. */
    val language: AppLanguage
        get() = getApplication<Application>().currentAppLanguage()

    /** Android recreates the activity in the new language, so nothing else needs updating here. */
    fun setLanguage(language: AppLanguage) {
        getApplication<Application>().setAppLanguage(language)
    }

    fun add(name: String, uid: String) {
        cards = cards + SavedCard(name, uid)
        store.saveCards(cards)
    }

    fun update(id: String, name: String, uid: String) {
        val previous = cards.find { it.id == id }
        cards = cards.map { if (it.id == id) it.copy(name = name, uid = uid) else it }
        store.saveCards(cards)
        if (activeId == id && !emulatorUiState.busy) {
            cards.find { it.id == id }?.let { runAction(PendingAction.Activate(it, previous)) }
        }
    }

    fun delete(id: String) {
        if (activeId == id) {
            if (!emulatorUiState.busy) runAction(PendingAction.Deactivate(deleteCardId = id))
            return
        }
        deleteStoredCard(id)
    }

    fun refreshPermission() {
        secureSettingsGranted = hasSecureSettingsPermission()
        if (!secureSettingsGranted) return
        if (emulatorUiState.permissionRequired) {
            emulatorUiState = emulatorUiState.copy(
                permissionRequired = false,
                permissionGrantBusy = false,
                permissionGrantStatus = null,
                permissionGrantError = null,
            )
        }
        if (pendingAction != null && !emulatorUiState.busy) pendingAction?.let(::runAction)
    }

    fun attachActivity(activity: android.app.Activity) {
        emulator.setActivity(activity)
        if (pendingAction != null && !emulatorUiState.busy && emulatorUiState.error == null) {
            pendingAction?.let(::runAction)
        }
    }

    fun detachActivity(activity: android.app.Activity) {
        emulator.setActivity(null)
    }

    fun dismissPermissionPrompt() {
        emulatorUiState = emulatorUiState.copy(permissionRequired = false)
    }

    fun showPermissionPrompt() {
        if (!secureSettingsGranted) {
            emulatorUiState = emulatorUiState.copy(permissionRequired = true)
        }
    }

    fun grantPermissionWithShizuku() {
        shizukuPermissionGranter.grant()
    }

    fun reportPermissionSetupError(@StringRes message: Int) {
        emulatorUiState = emulatorUiState.copy(
            permissionGrantBusy = false,
            permissionGrantStatus = null,
            permissionGrantError = localized(message),
        )
    }

    fun confirmExperimentalRaw() {
        emulator.allowExperimentalRawForCurrentFirmware()
        emulatorUiState = emulatorUiState.copy(experimentalRawConfirmationRequired = false)
        pendingAction?.let(::runAction)
    }

    fun dismissExperimentalRawConfirmation() {
        emulatorUiState = emulatorUiState.copy(experimentalRawConfirmationRequired = false)
        pendingAction = null
    }

    fun dismissError() {
        emulatorUiState = emulatorUiState.copy(error = null)
    }

    override fun onCleared() {
        shizukuPermissionGranter.close()
        executor.shutdownNow()
        super.onCleared()
    }

    private fun deleteStoredCard(id: String) {
        cards = cards.filterNot { it.id == id }
        if (activeId == id) activeId = null
        if (defaultId == id) {
            defaultId = null
            store.saveDefaultCardId(null)
        }
        store.saveCards(cards)
    }

    private fun runAction(action: PendingAction) {
        pendingAction = action
        if (!hasSecureSettingsPermission()) {
            secureSettingsGranted = false
            emulatorUiState = EmulatorUiState(permissionRequired = true)
            return
        }
        if (!emulator.isSupported) {
            emulatorUiState = EmulatorUiState(error = localized(R.string.uid_emulation_unsupported))
            pendingAction = null
            return
        }
        if (action is PendingAction.Activate && emulator.requiresExperimentalRawConsent) {
            emulatorUiState = EmulatorUiState(experimentalRawConfirmationRequired = true)
            return
        }

        emulatorUiState = EmulatorUiState(
            busy = true,
            busyCardId = (action as? PendingAction.Activate)?.card?.id,
            status = when (action) {
                is PendingAction.Activate -> localized(R.string.status_activating, action.card.name)
                is PendingAction.Deactivate -> localized(R.string.status_restoring)
                PendingAction.RestoreAfterRestart -> localized(R.string.status_recovering)
            },
        )
        executor.execute { executeAction(action) }
    }

    private fun executeAction(action: PendingAction) {
        try {
            when (action) {
                is PendingAction.Activate -> executeActivation(action)
                is PendingAction.Deactivate -> {
                    emulator.deactivate()
                    mainHandler.post {
                        setActive(null)
                        action.deleteCardId?.let(::deleteStoredCard)
                        finishAction()
                    }
                }
                PendingAction.RestoreAfterRestart -> {
                    emulator.deactivate()
                    mainHandler.post {
                        setActive(null)
                        finishAction()
                        defaultId?.let { id -> cards.find { it.id == id } }?.let {
                            runAction(PendingAction.Activate(it, null))
                        }
                    }
                }
            }
        } catch (error: Throwable) {
            mainHandler.post {
                if (error is ExperimentalRawConsentRequiredException) {
                    emulatorUiState = EmulatorUiState(experimentalRawConfirmationRequired = true)
                } else {
                    if (error is NfcRestartRequiredException && action !is PendingAction.Activate) {
                        setActive(null)
                        if (action is PendingAction.Deactivate) {
                            action.deleteCardId?.let(::deleteStoredCard)
                        }
                    }
                    emulatorUiState = EmulatorUiState(error = error.message ?: localized(R.string.nfc_operation_failed))
                    pendingAction = null
                }
            }
        }
    }

    private fun executeActivation(action: PendingAction.Activate) {
        try {
            emulator.activate(action.card.uid.hexToBytes())
            mainHandler.post {
                setActive(action.card.id)
                finishAction()
            }
        } catch (activationError: Throwable) {
            val rollbackError = runCatching {
                if (action.previousCard != null) {
                    emulator.activate(action.previousCard.uid.hexToBytes())
                    if (action.previousCard.id == action.card.id) {
                        mainHandler.post {
                            cards = cards.map { card ->
                                if (card.id == action.previousCard.id) action.previousCard else card
                            }
                            store.saveCards(cards)
                        }
                    }
                } else {
                    emulator.deactivate()
                }
            }.exceptionOrNull()
            if (rollbackError != null) {
                activationError.addSuppressed(rollbackError)
            }
            throw activationError
        }
    }

    private fun finishAction() {
        emulatorUiState = EmulatorUiState()
        pendingAction = null
    }

    private fun hasSecureSettingsPermission(): Boolean =
        getApplication<Application>().checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) ==
            PackageManager.PERMISSION_GRANTED

    private fun localized(@StringRes id: Int, vararg args: Any): String =
        getApplication<Application>().getString(id, *args)
}
