package io.github.realtvop.pixelcardemulator

import android.app.Application
import android.content.Context
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLDecoder

/**
 * GitHub redirects this to the page of the newest release that is neither a draft nor a pre-release. The update banner
 * opens it, and the check reads the tag from the redirect. The REST API would say the same, but it allows only 60
 * requests an hour per IP address without a token, which a shared proxy or VPN address can use up for everyone on it.
 */
const val LatestReleaseUrl = "https://github.com/realtvop/PixelCardEmulator/releases/latest"

/** Where the redirect points when there is a release. Without one, GitHub sends the release list instead. */
private const val ReleaseTagPath = "/releases/tag/"

/** Releases are weeks apart, so once a day is plenty. */
private const val CheckIntervalMillis = 24 * 60 * 60 * 1000L
private const val TimeoutMillis = 10_000
private const val LogTag = "UpdateChecker"

private const val PreferencesName = "update_check"
private const val CheckedAtKey = "checked_at"
private const val LatestVersionKey = "latest_version"
private const val SkippedVersionKey = "skipped_version"

/**
 * Holds the version for the update banner on the card list. It is only created by [MainActivity], so the card picker
 * never makes a request.
 */
class UpdateViewModel(application: Application) : AndroidViewModel(application) {
    private val checker = UpdateChecker(application)

    /** A release newer than this build that the user has not skipped, or null. */
    var availableVersion by mutableStateOf<String?>(null)
        private set

    init {
        if (BuildConfig.GITHUB_UPDATE_CHECK) {
            // What the last check found shows straight away. The request only runs once the interval has passed.
            availableVersion = checker.knownUpdate()
            viewModelScope.launch {
                withContext(Dispatchers.IO) { checker.refresh() }
                // Read again here rather than on the IO thread, so a skip made meanwhile is not undone.
                availableVersion = checker.knownUpdate()
            }
        }
    }

    /** Hides the banner until a release newer than this one comes out. */
    fun skip() {
        val version = availableVersion ?: return
        checker.skip(version)
        availableVersion = null
    }
}

private class UpdateChecker(context: Context) {
    private val preferences = context.getSharedPreferences(PreferencesName, Context.MODE_PRIVATE)

    fun knownUpdate(): String? {
        val latest = preferences.getString(LatestVersionKey, null) ?: return null
        val skipped = preferences.getString(SkippedVersionKey, null)
        return latest.takeIf {
            isNewerVersion(it, BuildConfig.VERSION_NAME) && (skipped == null || isNewerVersion(it, skipped))
        }
    }

    /** Blocks on the network. A failed request leaves the old answer in place and is tried again next launch. */
    fun refresh(now: Long = System.currentTimeMillis()) {
        // A clock set back makes the difference negative, which counts as due.
        if (now - preferences.getLong(CheckedAtKey, 0L) in 0 until CheckIntervalMillis) return
        val latest = try {
            fetchLatestVersion()
        } catch (e: Exception) {
            // Offline, blocked or anything else: an update check must never take the app down.
            Log.w(LogTag, "Could not check GitHub for a newer release", e)
            return
        }
        preferences.edit().putString(LatestVersionKey, latest).putLong(CheckedAtKey, now).apply()
    }

    fun skip(version: String) {
        preferences.edit().putString(SkippedVersionKey, version).apply()
    }

    private fun fetchLatestVersion(): String {
        val connection = URI(LatestReleaseUrl).toURL().openConnection() as HttpURLConnection
        try {
            // Only the redirect matters, so neither it nor the page body is fetched.
            connection.instanceFollowRedirects = false
            connection.requestMethod = "HEAD"
            connection.connectTimeout = TimeoutMillis
            connection.readTimeout = TimeoutMillis
            // Android's default user agent names the phone model, which GitHub does not need.
            connection.setRequestProperty("User-Agent", "PixelCardEmulator/${BuildConfig.VERSION_NAME}")
            val status = connection.responseCode
            val location = connection.getHeaderField("Location")
            if (status !in 300..399 || location == null) throw IOException("GitHub answered $status")
            val tag = location.substringAfter(ReleaseTagPath, missingDelimiterValue = "")
            if (tag.isEmpty()) throw IOException("GitHub has no published release")
            return URLDecoder.decode(tag, Charsets.UTF_8).trim().removePrefix("v").removePrefix("V")
        } finally {
            connection.disconnect()
        }
    }
}

/**
 * Whether [candidate] is a later version than [current]. Parts compare as numbers, so 0.10 is later than 0.9, and a
 * missing part counts as 0, so 0.1 and 0.1.0 are the same. A leading "v", as in release tags, is ignored. Anything
 * that is not dot-separated numbers is never later, so an odd tag can only hide the banner, not show a wrong one.
 */
internal fun isNewerVersion(candidate: String, current: String): Boolean {
    val candidateParts = versionParts(candidate) ?: return false
    val currentParts = versionParts(current) ?: return false
    for (index in 0 until maxOf(candidateParts.size, currentParts.size)) {
        val candidatePart = candidateParts.getOrElse(index) { 0 }
        val currentPart = currentParts.getOrElse(index) { 0 }
        if (candidatePart != currentPart) return candidatePart > currentPart
    }
    // Same numbers: a pre-release such as 0.2.0-beta1 comes before 0.2.0 itself.
    return isPreRelease(current) && !isPreRelease(candidate)
}

/** Build metadata after "+" never orders versions, and a pre-release suffix after "-" only breaks ties. */
private fun versionCore(version: String): String =
    version.trim().removePrefix("v").removePrefix("V").substringBefore('+').substringBefore('-')

private fun isPreRelease(version: String): Boolean = '-' in version.trim().substringBefore('+')

private fun versionParts(version: String): List<Int>? =
    versionCore(version).split('.').map { part ->
        if (part.isEmpty() || !part.all { it in '0'..'9' }) return null
        part.toIntOrNull() ?: return null
    }
