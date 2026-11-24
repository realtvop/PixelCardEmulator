package io.github.realtvop.pixelcardemulator

import android.content.Context
import android.nfc.NfcAdapter
import android.nfc.NfcManager
import android.os.Build
import java.lang.reflect.Method

open class NfcEmulationException(message: String, cause: Throwable? = null) : Exception(message, cause)
class ExperimentalRawConsentRequiredException(message: String) : NfcEmulationException(message)

private const val CoordinatorPreferences = "nfc_emulation_state"
private const val ActiveBackendKey = "active_backend"
private const val ExperimentalFingerprintKey = "experimental_raw_fingerprint"
private const val ForceRawKey = "force_raw"
private const val RestartDiscoveryAfterSwitchKey = "restart_discovery_after_switch"
internal const val NfcFieldResetDelayMillis = 250L

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
    private val adapter: NfcAdapter? = context.getSystemService(NfcManager::class.java)?.defaultAdapter
    private val pollingControl = PollingControl(context, adapter)

    override val isSupported: Boolean
        get() = runCatching { resolveApi(requireEnabled = false) }.isSuccess

    override fun activate(uid: ByteArray) {
        require(uid.size in SupportedUidSizes) { "Unsupported UID length" }
        val api = resolveApi()
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

    override fun deactivate() {
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
        }
    }

    fun setActivity(activity: android.app.Activity?) {
        pollingControl.setActivity(activity)
    }

    private fun <T> withOptionalDiscoveryRestart(block: () -> T): T =
        if (shouldRestartDiscovery()) {
            pollingControl.withBestEffortPause(NfcFieldResetDelayMillis, block)
        } else {
            block()
        }

    private fun resolveApi(requireEnabled: Boolean = true): Api {
        val nfcAdapter = adapter ?: throw NfcEmulationException(context.getString(R.string.nfc_unavailable))
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
        backend.deactivate()
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
