package io.github.realtvop.pixelcardemulator

import android.app.Activity
import android.content.Context
import android.nfc.NfcAdapter
import android.nfc.NfcManager
import android.os.Build
import androidx.annotation.StringRes
import java.lang.ref.WeakReference
import java.lang.reflect.Proxy
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

private const val RawPreferences = "raw_nfc_state"
private const val RawRestorePendingKey = "restore_pending"
private const val RawSnapshotKey = "original_configuration"
private const val ResponseTimeoutSeconds = 4L
private const val PollingPauseMillis = 40_000L

private const val CoreGroup = 0
private const val MessageTypeCommand = 1
private const val CoreSetConfig = 0x02
private const val CoreGetConfig = 0x03

private const val NfccConfigControl = 0x85
private const val LaBitFrameSdd = 0x30
private const val LaPlatformConfig = 0x31
private const val LaSelInfo = 0x32
private const val LaNfcid1 = 0x33
internal const val LiARatsTb1 = 0x58
internal const val LiAHistoricalBytes = 0x59

private val SnapshotParameters = listOf(
    NfccConfigControl,
    LaBitFrameSdd,
    LaPlatformConfig,
    LaSelInfo,
    LaNfcid1,
    LiARatsTb1,
    LiAHistoricalBytes,
)
private val RequiredSnapshotParameters = SnapshotParameters - LiAHistoricalBytes

internal object RawNfcDeviceCatalog {
    private val manufacturer = Build.MANUFACTURER.lowercase()
    private val model = Build.MODEL.uppercase()

    val isAllowlisted: Boolean
        get() = manufacturer in setOf("google", "samsung", "oneplus") ||
            (manufacturer == "sony" && model == "XQ-EC72")

    val profile: RawProfile
        get() = if (manufacturer == "google") RawProfile.Pixel else RawProfile.CtsType4A

    val prefersDiscoveryTechnology: Boolean
        get() = Build.VERSION.SDK_INT >= 35 && manufacturer == "oneplus" && model == "CPH2747"

    val prefersUidOnly: Boolean
        get() = manufacturer == "oneplus" && model == "CPH2747"

    val allowsDiscoveryTechnologyFallback: Boolean
        get() = when (manufacturer) {
            "samsung" -> Build.VERSION.SDK_INT == 35
            "oneplus" -> Build.VERSION.SDK_INT >= 35
            "sony" -> model == "XQ-EC72" && Build.VERSION.SDK_INT >= 35
            else -> false
        }
}

internal enum class RawProfile(
    val bitFrameSdd: Byte,
    val platformConfig: Byte,
    val selInfo: Byte,
    val rats: Byte,
) {
    Pixel(0x04, 0x00, 0x00, 0x00),
    CtsType4A(0x06, 0x0C, 0x20, 0x40),
}

internal class RawNciCardEmulator(
    private val context: Context,
    private val shouldRestartDiscovery: () -> Boolean = { true },
) : NfcCardEmulationBackend {
    private val preferences = context.getSharedPreferences(RawPreferences, Context.MODE_PRIVATE)
    private val adapter = context.getSystemService(NfcManager::class.java)?.defaultAdapter
    private val transport = RawNciTransport(context, adapter)
    private val pollingControl = PollingControl(context, adapter)

    override val isSupported: Boolean
        get() = transport.isSupported

    val hasPendingRestore: Boolean
        get() = preferences.getBoolean(RawRestorePendingKey, false)

    fun setActivity(activity: Activity?) {
        pollingControl.setActivity(activity)
    }

    override fun activate(uid: ByteArray) {
        require(uid.size in SupportedUidSizes) { "Unsupported UID length" }
        ensureNfcEnabled()

        val original = if (hasPendingRestore) {
            loadSnapshot()
        } else {
            pollingControl.withPaused { readCompleteSnapshot() }.also(::savePendingSnapshot)
        }
        check(original.keys.containsAll(RequiredSnapshotParameters)) {
            context.getString(R.string.raw_snapshot_incomplete)
        }

        val profile = RawNfcDeviceCatalog.profile
        pollingControl.withPaused(fieldResetDelayMillis()) {
            setPreferredConfiguration(uid, profile)
            requireUid(uid, R.string.raw_uid_not_retained)
        }

        if (!uidMatches(uid)) {
            pollingControl.withPaused(fieldResetDelayMillis()) {
                setPreferredConfiguration(uid, profile)
                Thread.sleep(120)
                requireUid(uid, R.string.raw_uid_overwritten_after_resume)
            }
            requireUid(uid, R.string.raw_uid_overwritten_after_replay)
        }
    }

    override fun deactivate() {
        ensureNfcEnabled()
        val snapshot = loadSnapshot().takeIf { hasPendingRestore }
        pollingControl.withPaused {
            if (snapshot != null) {
                transport.setConfig(snapshot.entries.map { it.key to it.value })
                requireUid(snapshot.getValue(LaNfcid1), R.string.raw_uid_not_restored)
            } else {
                transport.setConfig(
                    listOf(
                        NfccConfigControl to byteArrayOf(0x01),
                        LaNfcid1 to ByteArray(0),
                        LiARatsTb1 to byteArrayOf(0x00),
                        LiAHistoricalBytes to ByteArray(0),
                    ),
                )
            }
        }
        if (snapshot != null && !uidMatches(snapshot.getValue(LaNfcid1))) {
            pollingControl.withPaused {
                transport.setConfig(snapshot.entries.map { it.key to it.value })
                Thread.sleep(120)
                requireUid(snapshot.getValue(LaNfcid1), R.string.raw_original_uid_lost_after_resume)
            }
            requireUid(snapshot.getValue(LaNfcid1), R.string.raw_original_uid_not_retained_after_replay)
        }
        clearPendingSnapshot()
    }

    private fun setProfile(uid: ByteArray, profile: RawProfile) {
        transport.setConfig(
            listOf(
                NfccConfigControl to byteArrayOf(0x00),
                LaBitFrameSdd to byteArrayOf(profile.bitFrameSdd),
                LaPlatformConfig to byteArrayOf(profile.platformConfig),
                LaSelInfo to byteArrayOf(profile.selInfo),
                LaNfcid1 to uid,
                LiARatsTb1 to byteArrayOf(profile.rats),
            ),
        )
    }

    private fun setPreferredConfiguration(uid: ByteArray, profile: RawProfile) {
        if (RawNfcDeviceCatalog.prefersUidOnly) {
            val uidOnlyWorked = runCatching {
                transport.setConfig(listOf(LaNfcid1 to uid))
                requireUid(uid, R.string.raw_uid_only_not_retained)
            }.isSuccess
            if (uidOnlyWorked) return
        }
        setProfile(uid, profile)
    }

    private fun readCompleteSnapshot(): Map<Int, ByteArray> {
        val values = transport.readConfig(SnapshotParameters)
        val missing = RequiredSnapshotParameters.filterNot(values::containsKey)
        if (missing.isNotEmpty()) {
            val parameters = missing.joinToString { "0x%02X".format(it) }
            throw NfcEmulationException(context.getString(R.string.raw_snapshot_parameters_missing, parameters))
        }
        return values
    }

    private fun uidMatches(uid: ByteArray): Boolean =
        runCatching { transport.getConfig(listOf(LaNfcid1))[LaNfcid1]?.contentEquals(uid) == true }.getOrDefault(false)

    private fun requireUid(uid: ByteArray, @StringRes messageRes: Int) {
        if (!uidMatches(uid)) throw NfcEmulationException(context.getString(messageRes))
    }

    private fun fieldResetDelayMillis(): Long =
        if (shouldRestartDiscovery()) NfcFieldResetDelayMillis else 0L

    private fun savePendingSnapshot(snapshot: Map<Int, ByteArray>) {
        val serialized = snapshot.entries.joinToString(";") { (id, value) ->
            "%02X=%s".format(id, value.toHex())
        }
        check(
            preferences.edit()
                .putString(RawSnapshotKey, serialized)
                .putBoolean(RawRestorePendingKey, true)
                .commit(),
        ) { context.getString(R.string.raw_snapshot_save_failed) }
    }

    private fun loadSnapshot(): Map<Int, ByteArray> {
        val serialized = preferences.getString(RawSnapshotKey, null) ?: return emptyMap()
        return serialized.split(';').filter(String::isNotBlank).associate { item ->
            val (id, value) = item.split('=', limit = 2)
            id.toInt(16) to value.hexToBytes()
        }
    }

    private fun clearPendingSnapshot() {
        check(
            preferences.edit()
                .remove(RawSnapshotKey)
                .putBoolean(RawRestorePendingKey, false)
                .commit(),
        ) { context.getString(R.string.raw_snapshot_clear_failed) }
    }

    private fun ensureNfcEnabled() {
        if (adapter == null) throw NfcEmulationException(context.getString(R.string.nfc_unavailable))
        if (!adapter.isEnabled) throw NfcEmulationException(context.getString(R.string.nfc_turn_on_to_activate))
    }
}

internal class RawNciTransport(private val context: Context, private val adapter: NfcAdapter?) {
    private val callbackType by lazy { Class.forName("android.nfc.NfcAdapter\$NfcVendorNciCallback") }

    val isSupported: Boolean
        get() = adapter != null && runCatching {
            callbackType
            findMethod(NfcAdapter::class.java, "sendVendorNciMessage",
                Int::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!,
                Int::class.javaPrimitiveType!!, ByteArray::class.java)
            findMethod(NfcAdapter::class.java, "registerNfcVendorNciCallback", Executor::class.java, callbackType)
            findMethod(NfcAdapter::class.java, "unregisterNfcVendorNciCallback", callbackType)
        }.isSuccess

    fun readConfig(ids: List<Int>): Map<Int, ByteArray> {
        val values = linkedMapOf<Int, ByteArray>()
        runCatching { values.putAll(getConfig(ids)) }
        for (parameter in ids) {
            if (parameter !in values) {
                runCatching { values.putAll(getConfig(listOf(parameter))) }
            }
        }
        return values
    }

    fun setConfig(parameters: List<Pair<Int, ByteArray>>) {
        val size = 1 + parameters.sumOf { 2 + it.second.size }
        val payload = ByteArray(size)
        payload[0] = parameters.size.toByte()
        var offset = 1
        parameters.forEach { (id, value) ->
            payload[offset++] = id.toByte()
            payload[offset++] = value.size.toByte()
            value.copyInto(payload, offset)
            offset += value.size
        }
        val response = command(CoreSetConfig, payload)
        val status = response.firstOrNull()?.toInt()?.and(0xFF)
            ?: throw NfcEmulationException(context.getString(R.string.raw_set_config_empty))
        if (status != 0) {
            val rejected = if (response.size >= 2) {
                response.drop(2).joinToString { "0x%02X".format(it.toInt() and 0xFF) }
            } else {
                context.getString(R.string.raw_unknown_parameters)
            }
            throw NfcEmulationException(context.getString(R.string.raw_set_config_failed, status, rejected))
        }
    }

    fun getConfig(ids: List<Int>): Map<Int, ByteArray> {
        val request = byteArrayOf(ids.size.toByte(), *ids.map(Int::toByte).toByteArray())
        val response = command(CoreGetConfig, request)
        if (response.size < 2) throw NfcEmulationException(context.getString(R.string.raw_get_config_malformed))
        val status = response[0].toInt() and 0xFF
        if (status != 0) throw NfcEmulationException(context.getString(R.string.raw_get_config_failed, status))

        val count = response[1].toInt() and 0xFF
        var offset = 2
        return buildMap {
            repeat(count) {
                if (offset + 2 > response.size) {
                    throw NfcEmulationException(context.getString(R.string.raw_config_truncated))
                }
                val id = response[offset++].toInt() and 0xFF
                val length = response[offset++].toInt() and 0xFF
                if (offset + length > response.size) {
                    throw NfcEmulationException(context.getString(R.string.raw_parameter_truncated, id))
                }
                put(id, response.copyOfRange(offset, offset + length))
                offset += length
            }
        }
    }

    private fun command(oid: Int, payload: ByteArray): ByteArray {
        val nfcAdapter = adapter ?: throw NfcEmulationException(context.getString(R.string.nfc_unavailable))
        try {
            val register = findMethod(
                NfcAdapter::class.java,
                "registerNfcVendorNciCallback",
                Executor::class.java,
                callbackType,
            )
            val unregister = findMethod(NfcAdapter::class.java, "unregisterNfcVendorNciCallback", callbackType)
            val send = findMethod(
                NfcAdapter::class.java,
                "sendVendorNciMessage",
                Int::class.javaPrimitiveType!!,
                Int::class.javaPrimitiveType!!,
                Int::class.javaPrimitiveType!!,
                ByteArray::class.java,
            )
            val response = AtomicReference<ByteArray>()
            val latch = CountDownLatch(1)
            val callback = Proxy.newProxyInstance(callbackType.classLoader, arrayOf(callbackType)) { proxy, method, args ->
                when (method.name) {
                    "onVendorNciResponse" -> {
                        val gid = args?.getOrNull(0) as? Int
                        val responseOid = args?.getOrNull(1) as? Int
                        val bytes = args?.getOrNull(2) as? ByteArray
                        if (gid == CoreGroup && responseOid == oid && bytes != null) {
                            response.set(bytes.copyOf())
                            latch.countDown()
                        }
                        null
                    }
                    "onVendorNciNotification" -> null
                    "toString" -> "PixelCardEmulatorRawNciCallback"
                    "hashCode" -> System.identityHashCode(proxy)
                    "equals" -> proxy === args?.getOrNull(0)
                    else -> null
                }
            }

            register.invoke(nfcAdapter, Executor(Runnable::run), callback)
            try {
                val frameworkStatus = send.invoke(
                    nfcAdapter,
                    MessageTypeCommand,
                    CoreGroup,
                    oid,
                    payload,
                ) as? Int
                if (frameworkStatus != 0) {
                    throw NfcEmulationException(context.getString(R.string.raw_framework_rejected, frameworkStatus))
                }
                if (!latch.await(ResponseTimeoutSeconds, TimeUnit.SECONDS)) {
                    throw NfcEmulationException(context.getString(R.string.raw_timeout))
                }
                return response.get() ?: throw NfcEmulationException(context.getString(R.string.raw_no_response))
            } finally {
                runCatching { unregister.invoke(nfcAdapter, callback) }
            }
        } catch (error: NfcEmulationException) {
            throw error
        } catch (error: Throwable) {
            throw NfcEmulationException(context.getString(R.string.raw_communication_failed), error)
        }
    }
}

internal class PollingControl(private val context: Context, private val adapter: NfcAdapter?) {
    private var activity = WeakReference<Activity>(null)

    fun setActivity(value: Activity?) {
        activity = WeakReference(value)
    }

    fun <T> withPaused(minimumPauseMillis: Long = 0, block: () -> T): T =
        runPaused(pause(), minimumPauseMillis, block)

    fun <T> withBestEffortPause(minimumPauseMillis: Long = 0, block: () -> T): T {
        val resume = runCatching(::pause).getOrNull() ?: return block()
        return runPaused(resume, minimumPauseMillis, block)
    }

    private fun <T> runPaused(resume: () -> Unit, minimumPauseMillis: Long, block: () -> T): T {
        val pausedAt = System.nanoTime()
        return try {
            block()
        } finally {
            val elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - pausedAt)
            val remainingMillis = minimumPauseMillis - elapsedMillis
            try {
                if (remainingMillis > 0) Thread.sleep(remainingMillis)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            } finally {
                resume()
            }
        }
    }

    private fun pause(): () -> Unit {
        val nfcAdapter = adapter ?: throw NfcEmulationException(context.getString(R.string.nfc_unavailable))
        if (RawNfcDeviceCatalog.prefersDiscoveryTechnology) return pauseWithDiscoveryTechnology(nfcAdapter)

        runCatching {
            val extension = findMethod(NfcAdapter::class.java, "getNfcOemExtension").invoke(nfcAdapter)
                ?: error("No NFC OEM extension")
            val pause = findMethod(extension.javaClass, "pausePolling", Long::class.javaPrimitiveType!!)
            val resume = findMethod(extension.javaClass, "resumePolling")
            checkPauseStatus(pause.invoke(extension, PollingPauseMillis))
            return { checkPauseStatus(resume.invoke(extension)) }
        }

        if (RawNfcDeviceCatalog.allowsDiscoveryTechnologyFallback) {
            return pauseWithDiscoveryTechnology(nfcAdapter)
        }
        throw NfcEmulationException(context.getString(R.string.raw_no_pause_api))
    }

    private fun pauseWithDiscoveryTechnology(nfcAdapter: NfcAdapter): () -> Unit {
        val foregroundActivity = activity.get()
            ?: throw NfcEmulationException(context.getString(R.string.raw_foreground_activity_required))
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        while (!foregroundActivity.hasWindowFocus() && System.nanoTime() < deadline) Thread.sleep(50)
        if (!foregroundActivity.hasWindowFocus()) {
            throw NfcEmulationException(context.getString(R.string.raw_app_not_foreground))
        }
        val set = findMethod(
            NfcAdapter::class.java,
            "setDiscoveryTechnology",
            Activity::class.java,
            Int::class.javaPrimitiveType!!,
            Int::class.javaPrimitiveType!!,
        )
        val reset = findMethod(NfcAdapter::class.java, "resetDiscoveryTechnology", Activity::class.java)
        set.invoke(nfcAdapter, foregroundActivity, 0, 0)
        return { reset.invoke(nfcAdapter, foregroundActivity) }
    }

    private fun checkPauseStatus(value: Any?) {
        if (value is Int && value !in setOf(1, 2)) {
            throw NfcEmulationException(context.getString(R.string.raw_polling_state_failed, value))
        }
    }
}
