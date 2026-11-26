package io.github.realtvop.pixelcardemulator

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.nfc.NfcAdapter
import android.nfc.NfcManager
import android.os.Build
import android.os.SystemClock
import androidx.annotation.WorkerThread
import java.lang.reflect.Method
import java.util.concurrent.atomic.AtomicBoolean

open class NfcEmulationException(message: String, cause: Throwable? = null) : Exception(message, cause)
class ExperimentalRawConsentRequiredException(message: String) : NfcEmulationException(message)
class NfcRestartRequiredException(message: String, cause: Throwable? = null) : NfcEmulationException(message, cause)

private const val CoordinatorPreferences = "nfc_emulation_state"
private const val ActiveBackendKey = "active_backend"
private const val ExperimentalFingerprintKey = "experimental_raw_fingerprint"
private const val ForceRawKey = "force_raw"
private const val RestartDiscoveryAfterSwitchKey = "restart_discovery_after_switch"
internal const val NfcFieldResetDelayMillis = 250L

private const val OemPreferences = "oem_nfc_state"
private const val OemTb1SnapshotKey = "original_tb1"
private const val OemHistoricalBytesSnapshotKey = "original_historical_bytes"
private const val OemRestartPendingKey = "restart_pending"
private const val NfcOffTimeoutMillis = 10_000L
private const val NfcOnTimeoutMillis = 15_000L
private val OemSnapshotParameters = listOf(LiARatsTb1, LiAHistoricalBytes)
private val OemOperationLock = Any()

internal interface NfcCardEmulationBackend {
    val isSupported: Boolean
    fun activate(uid: ByteArray)
    fun deactivate()
}

/** Android 15+'s system-service implementation of NFC-A identity emulation. */
private class OemNfcCardEmulator(
    private val context: Context,
    private val shouldRestartDiscovery: () -> Boolean,
) : NfcCardEmulationBackend {
    private val preferences = context.getSharedPreferences(OemPreferences, Context.MODE_PRIVATE)
    private val cardStore = CardStore(context)
    private val adapter: NfcAdapter? = context.getSystemService(NfcManager::class.java)?.defaultAdapter
    private val transport = RawNciTransport(context, adapter)
    private val pollingControl = PollingControl(context, adapter)

    override val isSupported: Boolean
        get() = runCatching { resolveApi(requireEnabled = false) }.isSuccess

    @WorkerThread
    override fun activate(uid: ByteArray) = synchronized(OemOperationLock) {
        require(uid.size in SupportedUidSizes) { "Unsupported UID length" }
        recoverPendingRestart()
        val api = resolveApi()
        saveSnapshotIfNeeded()
        // Google's NFC stack needs the minimal Pixel profile. Other vendors such as Samsung use
        // the CTS Type-4A profile, same as the raw NCI catalog.
        val profile = RawNfcDeviceCatalog.profile
        withOptionalDiscoveryRestart {
            invoke(
                api,
                enabled = true,
                bitFrameSdd = profile.bitFrameSdd.toInt(),
                platformConfig = profile.platformConfig.toInt(),
                selInfo = profile.selInfo.toInt(),
                nfcid1 = uid,
                rats = profile.rats.toInt(),
            )
        }
    }

    @WorkerThread
    override fun deactivate() = synchronized(OemOperationLock) {
        var emulationDisabled = false
        try {
            emulationDisabled = preferences.getBoolean(OemRestartPendingKey, false)
            if (recoverPendingRestart()) return@synchronized
            emulationDisabled = false
            val api = resolveApi()
            withOptionalDiscoveryRestart {
                invoke(
                    api,
                    enabled = false,
                    bitFrameSdd = 0x00,
                    platformConfig = 0x00,
                    selInfo = 0x00,
                    nfcid1 = ByteArray(0),
                    rats = 0x00,
                )
                emulationDisabled = true
            }
            if (!restoreSnapshot()) restartNfc()
            clearRecoveryState()
        } catch (error: Exception) {
            if (emulationDisabled) throw restartRequiredError(error)
            if (error is NfcEmulationException) throw error
            throw NfcEmulationException(context.getString(R.string.nfc_operation_failed), error)
        }
    }

    fun setActivity(activity: android.app.Activity?) {
        pollingControl.setActivity(activity)
    }

    private fun clearRecoveryState(restartPending: Boolean = false) {
        val editor = preferences.edit()
            .remove(OemTb1SnapshotKey)
            .remove(OemHistoricalBytesSnapshotKey)
        if (restartPending) {
            editor.putBoolean(OemRestartPendingKey, true)
        } else {
            editor.remove(OemRestartPendingKey)
        }
        check(editor.commit()) { context.getString(R.string.raw_snapshot_clear_failed) }
    }

    private fun restartRequiredError(cause: Exception): NfcRestartRequiredException {
        val nfcEnabled = adapter?.isEnabled == true
        val message = context.getString(
            if (nfcEnabled) R.string.oem_nfc_restart_failed else R.string.oem_nfc_restart_enable_failed,
        )
        val error = NfcRestartRequiredException(message, cause)
        runCatching { clearRecoveryState(restartPending = !nfcEnabled) }
            .exceptionOrNull()?.let(error::addSuppressed)
        return error
    }

    private fun saveSnapshotIfNeeded() {
        if (preferences.contains(OemTb1SnapshotKey)) return
        // An active card without a snapshot may have been activated by an older build.
        val values = if (cardStore.loadActiveCardId() == null && transport.isSupported) {
            transport.readConfig(OemSnapshotParameters)
        } else {
            emptyMap()
        }
        val tb1 = values[LiARatsTb1]?.takeIf { it.size == 1 }
        // An empty TB(1) marks an attempted snapshot for this session.
        check(
            preferences.edit()
                .putString(OemTb1SnapshotKey, tb1?.toHex().orEmpty())
                .putString(OemHistoricalBytesSnapshotKey, tb1?.let { values[LiAHistoricalBytes]?.toHex() })
                .commit(),
        ) { context.getString(R.string.raw_snapshot_save_failed) }
    }

    private fun restoreSnapshot(): Boolean = runCatching {
        val tb1 = preferences.getString(OemTb1SnapshotKey, null)?.hexToBytes()
        if (tb1?.size != 1 || tb1[0] == 0.toByte() || !transport.isSupported) return@runCatching false

        val parameters = buildList {
            add(LiARatsTb1 to tb1)
            preferences.getString(OemHistoricalBytesSnapshotKey, null)?.let {
                add(LiAHistoricalBytes to it.hexToBytes())
            }
        }
        pollingControl.withPaused { transport.setConfig(parameters) }
        transport.getConfig(listOf(LiARatsTb1))[LiARatsTb1]?.contentEquals(tb1) == true
    }.getOrDefault(false)

    private fun recoverPendingRestart(): Boolean {
        if (!preferences.getBoolean(OemRestartPendingKey, false)) return false
        val nfcAdapter = requireAdapter()
        if (nfcAdapter.isEnabled) {
            setRestartPending(false)
            return false
        }
        // The OEM disable succeeded before the adapter restart was requested.
        enableNfc(nfcAdapter)
        clearRecoveryState()
        return true
    }

    private fun restartNfc() {
        val nfcAdapter = requireAdapter()
        val nfcOff = AtomicBoolean(false)
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action == NfcAdapter.ACTION_ADAPTER_STATE_CHANGED &&
                    intent.getIntExtra(NfcAdapter.EXTRA_ADAPTER_STATE, -1) == NfcAdapter.STATE_OFF
                ) {
                    nfcOff.set(true)
                }
            }
        }
        val appContext = context.applicationContext
        appContext.registerReceiver(
            receiver,
            IntentFilter(NfcAdapter.ACTION_ADAPTER_STATE_CHANGED),
            Context.RECEIVER_EXPORTED,
        )
        try {
            // OEM emulation is off. Clearing the card ID lets startup resume this recovery.
            cardStore.saveActiveCardId(null)
            setRestartPending(true)
            val disable = runCatching { disableNfc(nfcAdapter) }
            if (disable.getOrNull() == true || !nfcAdapter.isEnabled) {
                waitUntil(NfcOffTimeoutMillis, nfcOff::get)
            }
            // The service queues enable behind a disable that is still running.
            val enable = runCatching { requestEnable(nfcAdapter) }
            if (!waitUntil(NfcOnTimeoutMillis) { nfcOff.get() && nfcAdapter.isEnabled }) {
                throw NfcEmulationException(
                    context.getString(R.string.oem_nfc_restart_failed),
                    enable.exceptionOrNull() ?: disable.exceptionOrNull(),
                )
            }
        } finally {
            appContext.unregisterReceiver(receiver)
        }
    }

    private fun disableNfc(nfcAdapter: NfcAdapter): Boolean {
        val disable = try {
            findMethod(NfcAdapter::class.java, "disable", Boolean::class.javaPrimitiveType!!)
        } catch (_: NoSuchMethodException) {
            return findMethod(NfcAdapter::class.java, "disable").invoke(nfcAdapter) == true
        }
        return disable.invoke(nfcAdapter, false) == true
    }

    private fun enableNfc(nfcAdapter: NfcAdapter) {
        val message = context.getString(R.string.oem_nfc_enable_failed)
        val enable = runCatching { requestEnable(nfcAdapter) }
        if (enable.getOrNull() != true || !waitUntil(NfcOnTimeoutMillis) { nfcAdapter.isEnabled }) {
            throw NfcEmulationException(message, enable.exceptionOrNull())
        }
    }

    private fun requestEnable(nfcAdapter: NfcAdapter): Boolean =
        findMethod(NfcAdapter::class.java, "enable").invoke(nfcAdapter) == true

    private fun setRestartPending(pending: Boolean) {
        check(preferences.edit().putBoolean(OemRestartPendingKey, pending).commit()) {
            context.getString(R.string.oem_restart_state_save_failed)
        }
    }

    private fun waitUntil(timeoutMillis: Long, condition: () -> Boolean): Boolean {
        val deadline = SystemClock.elapsedRealtime() + timeoutMillis
        while (!condition()) {
            val remainingMillis = deadline - SystemClock.elapsedRealtime()
            if (remainingMillis <= 0) return false
            SystemClock.sleep(minOf(100L, remainingMillis))
        }
        return true
    }

    private fun <T> withOptionalDiscoveryRestart(block: () -> T): T =
        if (shouldRestartDiscovery()) {
            pollingControl.withBestEffortPause(NfcFieldResetDelayMillis, block)
        } else {
            block()
        }

    private fun requireAdapter(): NfcAdapter =
        adapter ?: throw NfcEmulationException(context.getString(R.string.nfc_unavailable))

    private fun resolveApi(requireEnabled: Boolean = true): Api {
        val nfcAdapter = requireAdapter()
        if (requireEnabled && !nfcAdapter.isEnabled) {
            throw NfcEmulationException(context.getString(R.string.nfc_turn_on_to_activate))
        }

        try {
            val getExtension = findMethod(NfcAdapter::class.java, "getNfcOemExtension")
            val extension = getExtension.invoke(nfcAdapter)
                ?: throw NfcEmulationException(context.getString(R.string.oem_extension_unavailable))
            val emulate = findMethod(
                extension.javaClass,
                "emulateNfcTechnologyATag",
                Boolean::class.javaPrimitiveType!!,
                Byte::class.javaPrimitiveType!!,
                Byte::class.javaPrimitiveType!!,
                Byte::class.javaPrimitiveType!!,
                ByteArray::class.java,
                Byte::class.javaPrimitiveType!!,
                ByteArray::class.java,
            )
            return Api(extension, emulate)
        } catch (error: NfcEmulationException) {
            throw error
        } catch (error: Throwable) {
            throw NfcEmulationException(context.getString(R.string.controller_no_uid_emulation), error)
        }
    }

    private fun invoke(
        api: Api,
        enabled: Boolean,
        bitFrameSdd: Int,
        platformConfig: Int,
        selInfo: Int,
        nfcid1: ByteArray,
        rats: Int,
    ) {
        val status = try {
            api.emulate.invoke(
                api.extension,
                enabled,
                bitFrameSdd.toByte(),
                platformConfig.toByte(),
                selInfo.toByte(),
                nfcid1.copyOf(),
                rats.toByte(),
                null,
            ) as? Int
        } catch (error: Throwable) {
            throw NfcEmulationException(context.getString(R.string.oem_service_rejected), error)
        }

        when (status) {
            0 -> Unit
            1 -> throw NfcEmulationException(context.getString(R.string.nfc_turn_on_to_activate))
            2 -> throw NfcEmulationException(context.getString(R.string.controller_rejected_profile))
            null -> throw NfcEmulationException(context.getString(R.string.oem_service_no_status))
            else -> throw NfcEmulationException(context.getString(R.string.oem_unknown_status, status))
        }
    }

    private data class Api(val extension: Any, val emulate: Method)
}

/** Chooses the official system route first and retains the chosen backend until restore. */
class NfcEmulationCoordinator(private val context: Context) {
    private val preferences = context.getSharedPreferences(CoordinatorPreferences, Context.MODE_PRIVATE)
    private val oem = OemNfcCardEmulator(context) { restartDiscoveryAfterSwitch }
    private val raw = RawNciCardEmulator(context) { restartDiscoveryAfterSwitch }

    val isSupported: Boolean
        get() = oem.isSupported || raw.isSupported

    val hasPendingRestore: Boolean
        get() = preferences.contains(ActiveBackendKey) || raw.hasPendingRestore

    var forceRaw: Boolean
        get() = preferences.getBoolean(ForceRawKey, false)
        set(value) {
            check(preferences.edit().putBoolean(ForceRawKey, value).commit()) {
                "Could not save raw NFC preference"
            }
        }

    var restartDiscoveryAfterSwitch: Boolean
        get() = preferences.getBoolean(RestartDiscoveryAfterSwitchKey, true)
        set(value) {
            check(preferences.edit().putBoolean(RestartDiscoveryAfterSwitchKey, value).commit()) {
                "Could not save NFC discovery restart preference"
            }
        }

    val requiresExperimentalRawConsent: Boolean
        get() = raw.isSupported && (!oem.isSupported || forceRaw) && !RawNfcDeviceCatalog.isAllowlisted &&
            preferences.getString(ExperimentalFingerprintKey, null) != Build.FINGERPRINT

    fun setActivity(activity: android.app.Activity?) {
        oem.setActivity(activity)
        raw.setActivity(activity)
    }

    fun allowExperimentalRawForCurrentFirmware() {
        check(
            preferences.edit()
                .putString(ExperimentalFingerprintKey, Build.FINGERPRINT)
                .commit(),
        ) { "Could not save raw NFC consent" }
    }

    fun activate(uid: ByteArray) {
        val backendName = preferences.getString(ActiveBackendKey, null)
        val (name, backend) = when (backendName) {
            "oem" -> "oem" to oem
            "raw" -> "raw" to raw
            else -> selectBackend()
        }
        check(preferences.edit().putString(ActiveBackendKey, name).commit()) {
            context.getString(R.string.recovery_route_save_failed)
        }
        backend.activate(uid)
    }

    fun deactivate() {
        val backend = when (preferences.getString(ActiveBackendKey, null)) {
            "raw" -> raw
            "oem" -> oem
            else -> if (raw.hasPendingRestore) raw else oem
        }
        try {
            backend.deactivate()
        } catch (error: NfcRestartRequiredException) {
            runCatching { clearActiveBackend() }.exceptionOrNull()?.let(error::addSuppressed)
            throw error
        }
        clearActiveBackend()
    }

    private fun clearActiveBackend() {
        check(preferences.edit().remove(ActiveBackendKey).commit()) {
            context.getString(R.string.recovery_route_clear_failed)
        }
    }

    private fun selectBackend(): Pair<String, NfcCardEmulationBackend> {
        if (raw.isSupported && RawNfcDeviceCatalog.isAllowlisted && (!oem.isSupported || forceRaw)) {
            return "raw" to raw
        }
        if (oem.isSupported && !forceRaw) return "oem" to oem
        if (!raw.isSupported) throw NfcEmulationException(context.getString(R.string.uid_emulation_unsupported))
        if (!RawNfcDeviceCatalog.isAllowlisted && requiresExperimentalRawConsent) {
            throw ExperimentalRawConsentRequiredException(context.getString(R.string.raw_consent_required))
        }
        return "raw" to raw
    }
}

internal fun findMethod(owner: Class<*>, name: String, vararg parameters: Class<*>): Method {
    return try {
        owner.getMethod(name, *parameters)
    } catch (_: NoSuchMethodException) {
        owner.getDeclaredMethod(name, *parameters).apply { isAccessible = true }
    }
}
