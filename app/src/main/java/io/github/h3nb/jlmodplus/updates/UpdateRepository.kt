package io.github.h3nb.jlmodplus.updates

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.edit
import androidx.preference.PreferenceManager
import io.github.h3nb.jlmodplus.BuildConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

internal sealed interface UpdateStatus {
    data object NotChecked : UpdateStatus
    data object Checking : UpdateStatus
    data object Current : UpdateStatus
    data object NoRelease : UpdateStatus
    data class Available(val candidate: UpdateCandidate) : UpdateStatus
    data object NotInstallable : UpdateStatus
    data object Failed : UpdateStatus
}

internal class UpdateRepository private constructor(context: Context) {
    private val preferences: SharedPreferences =
        PreferenceManager.getDefaultSharedPreferences(context.applicationContext)
    private val mutex = Mutex()

    var channel by mutableStateOf(
        preferences.getString(KEY_CHANNEL, null)?.let { value ->
            UpdateChannel.entries.firstOrNull { it.name == value }
        } ?: if ('-' in BuildConfig.VERSION_NAME) UpdateChannel.Preview else UpdateChannel.Stable
    )
        private set

    var automaticChecks by mutableStateOf(preferences.getBoolean(KEY_ENABLED, true))
        private set

    // The last valid result survives a failed refresh; refresh activity is independent.
    var status by mutableStateOf<UpdateStatus>(restoredStatus())
        private set
    var checking by mutableStateOf(false)
        private set
    var refreshFailed by mutableStateOf(false)
        private set
    var lastChecked by mutableStateOf(preferences.getLong(KEY_SUCCESS, 0L))
        private set

    init {
        val identity = BuildConfig.VERSION_NAME + "#" + BuildConfig.VERSION_CODE
        if (preferences.getString(KEY_INSTALLED, null) != identity) {
            preferences.edit {
                putString(KEY_INSTALLED, identity)
                remove(KEY_SUCCESS).remove(KEY_ATTEMPT).remove(KEY_RESULT)
                remove(KEY_VERSION).remove(KEY_CODE).remove(KEY_URL)
                remove(KEY_HANDLED)
            }
            lastChecked = 0L
            status = UpdateStatus.NotChecked
        }
    }

    fun selectChannel(value: UpdateChannel) {
        if (channel == value) return
        channel = value
        preferences.edit {
            putString(KEY_CHANNEL, value.name)
            remove(KEY_SUCCESS).remove(KEY_ATTEMPT).remove(KEY_RESULT)
            remove(KEY_VERSION).remove(KEY_CODE).remove(KEY_URL)
        }
        lastChecked = 0L
        status = UpdateStatus.NotChecked
        refreshFailed = false
    }

    fun configureAutomaticChecks(enabled: Boolean) {
        automaticChecks = enabled
        preferences.edit { putBoolean(KEY_ENABLED, enabled) }
    }

    fun candidateKey(candidate: UpdateCandidate): String =
        channel.name + "|" + candidate.versionName + "|" + candidate.versionCode

    fun isHandled(candidate: UpdateCandidate): Boolean =
        preferences.getString(KEY_HANDLED, null) == candidateKey(candidate)

    fun handle(candidate: UpdateCandidate) {
        preferences.edit { putString(KEY_HANDLED, candidateKey(candidate)) }
    }

    /** Polling is active only while the Library is resumed. No background worker is needed. */
    fun nextAutomaticDelayMillis(): Long {
        val now = System.currentTimeMillis()
        fun remaining(last: Long, interval: Long): Long =
            if (last <= 0L || now < last) 0L else (interval - (now - last)).coerceAtLeast(0L)
        return maxOf(
            remaining(lastChecked, CHECK_INTERVAL_MS),
            remaining(preferences.getLong(KEY_ATTEMPT, 0L), RETRY_DELAY_MS),
            MIN_POLL_DELAY_MS,
        )
    }

    suspend fun check(manual: Boolean) = mutex.withLock {
        if (!manual && !automaticChecks) return@withLock
        val now = System.currentTimeMillis()
        if (!manual && now - lastChecked in 0 until CHECK_INTERVAL_MS) return@withLock
        val attempted = preferences.getLong(KEY_ATTEMPT, 0L)
        if (!manual && now - attempted in 0 until RETRY_DELAY_MS) return@withLock

        preferences.edit { putLong(KEY_ATTEMPT, now) }
        val requestedChannel = channel
        val previous = status
        checking = true
        refreshFailed = false
        if (previous == UpdateStatus.NotChecked || previous == UpdateStatus.Failed) {
            status = UpdateStatus.Checking
        }
        try {
            val result = withContext(Dispatchers.IO) { fetchLatest(requestedChannel) }
            if (channel != requestedChannel) return@withLock
            lastChecked = System.currentTimeMillis()
            status = result
            val editor = preferences.edit()
                .putLong(KEY_SUCCESS, lastChecked)
                .putString(KEY_RESULT, when (result) {
                    is UpdateStatus.Available -> "available"
                    UpdateStatus.Current -> "current"
                    UpdateStatus.NotInstallable -> "incompatible"
                    else -> "none"
                })
            if (result is UpdateStatus.Available) {
                editor.putString(KEY_VERSION, result.candidate.versionName)
                    .putInt(KEY_CODE, result.candidate.versionCode)
                    .putString(KEY_URL, result.candidate.releaseUrl)
                    .putString(KEY_DOWNLOAD_URL, result.candidate.downloadUrl)
            } else {
                editor.remove(KEY_VERSION).remove(KEY_CODE).remove(KEY_URL)
                    .remove(KEY_DOWNLOAD_URL)
            }
            editor.apply()
        } catch (cancelled: CancellationException) {
            if (channel == requestedChannel) status = previous
            throw cancelled
        } catch (_: IOException) {
            if (channel == requestedChannel) {
                status = if (previous == UpdateStatus.NotChecked || previous == UpdateStatus.Failed) {
                    UpdateStatus.Failed
                } else previous
                refreshFailed = true
            }
        } catch (_: JSONException) {
            if (channel == requestedChannel) {
                status = if (previous == UpdateStatus.NotChecked || previous == UpdateStatus.Failed) {
                    UpdateStatus.Failed
                } else previous
                refreshFailed = true
            }
        } finally {
            checking = false
        }
    }

    private fun restoredStatus(): UpdateStatus {
        return when (preferences.getString(KEY_RESULT, null)) {
            "available" -> {
                val version = preferences.getString(KEY_VERSION, null)
                val url = preferences.getString(KEY_URL, null)
                val download = preferences.getString(KEY_DOWNLOAD_URL, null)
                val code = preferences.getInt(KEY_CODE, 0)
                val candidate = if (version != null && url != null && code > 0) {
                    UpdateCandidate(version, code, url, download)
                } else null
                if (candidate != null && eligibleUpdate(
                        BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE, candidate)) {
                    UpdateStatus.Available(candidate)
                } else UpdateStatus.NotChecked
            }
            "current" -> UpdateStatus.Current
            "none" -> UpdateStatus.NoRelease
            "incompatible" -> UpdateStatus.NotInstallable
            else -> UpdateStatus.NotChecked
        }
    }

    private fun fetchLatest(requestedChannel: UpdateChannel): UpdateStatus {
        val connection = URL(MANIFEST_URL).openConnection() as HttpURLConnection
        connection.connectTimeout = 5000
        connection.readTimeout = 5000
        connection.setRequestProperty("Accept", "application/json")
        connection.setRequestProperty("User-Agent", "JL-Mod-Plus-Updater")
        val document = try {
            if (connection.responseCode != 200) {
                throw IOException("Update manifest HTTP " + connection.responseCode)
            }
            JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
        } finally {
            connection.disconnect()
        }
        if (document.getInt("schema") != 1 ||
            document.getString("applicationId") != "io.github.h3nb.jlmodplus") {
            throw IOException("Unsupported update manifest")
        }

        val installed = ReleaseVersion.parse(BuildConfig.VERSION_NAME)
            ?: return UpdateStatus.NoRelease
        val entries = document.getJSONArray("releases")
        var best: Pair<ReleaseVersion, UpdateCandidate>? = null
        var hasNewerIncompatible = false
        var hasPublished = false
        for (i in 0 until entries.length()) {
            val release = entries.getJSONObject(i)
            val version = release.getString("versionName")
            val parsed = ReleaseVersion.parse(version) ?: continue
            val prerelease = release.getBoolean("prerelease")
            if (prerelease != ('-' in version)) continue
            if (requestedChannel == UpdateChannel.Stable && prerelease) continue
            hasPublished = true
            if (parsed <= installed) continue
            val code = release.getInt("versionCode")
            val minSdk = release.getInt("minSdk")
            if (code <= 0 || minSdk < 23) throw IOException("Invalid release metadata")
            if (code <= BuildConfig.VERSION_CODE || minSdk > Build.VERSION.SDK_INT) {
                hasNewerIncompatible = true
                continue
            }

            val assets = release.getJSONArray("assets")
            val supportedUrls = mutableMapOf<String, String>()
            for (assetIndex in 0 until assets.length()) {
                val asset = assets.getJSONObject(assetIndex)
                val abi = asset.getString("abi")
                val url = asset.getString("url")
                val expected = "https://github.com/H3nb/JL-Mod-Plus/releases/download/v" +
                    version + "/JL-Mod-Plus-v" + version + "-" + abi + ".apk"
                if (abi !in SUPPORTED_ASSET_ABIS || url != expected) {
                    throw IOException("Invalid update asset URL")
                }
                supportedUrls[abi] = url
            }
            val download = Build.SUPPORTED_ABIS.firstNotNullOfOrNull { supportedUrls[it] }
                ?: supportedUrls["universal"]
            if (download == null) {
                hasNewerIncompatible = true
                continue
            }
            val candidate = UpdateCandidate(
                versionName = version,
                versionCode = code,
                releaseUrl = "https://github.com/H3nb/JL-Mod-Plus/releases/tag/v" + version,
                downloadUrl = download,
            )
            if (eligibleUpdate(BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE, candidate) &&
                (best == null || parsed > best.first)) {
                best = parsed to candidate
            }
        }
        return when {
            best != null -> UpdateStatus.Available(best.second)
            hasNewerIncompatible -> UpdateStatus.NotInstallable
            hasPublished -> UpdateStatus.Current
            else -> UpdateStatus.NoRelease
        }
    }

    companion object {
        private const val MANIFEST_URL = "https://h3nb.github.io/JL-Mod-Plus/updates.json"
        private val SUPPORTED_ASSET_ABIS =
            setOf("arm64-v8a", "armeabi-v7a", "x86", "x86_64", "universal")
        private const val KEY_CHANNEL = "jlmod_updates_channel"
        private const val KEY_INSTALLED = "jlmod_updates_installed_identity"
        private const val KEY_ENABLED = "jlmod_updates_enabled"
        private const val KEY_ATTEMPT = "jlmod_updates_last_attempt"
        private const val KEY_SUCCESS = "jlmod_updates_last_success"
        private const val KEY_RESULT = "jlmod_updates_result"
        private const val KEY_VERSION = "jlmod_updates_version"
        private const val KEY_CODE = "jlmod_updates_code"
        private const val KEY_URL = "jlmod_updates_url"
        private const val KEY_DOWNLOAD_URL = "jlmod_updates_download_url"
        private const val KEY_HANDLED = "jlmod_updates_handled_version"
        private const val CHECK_INTERVAL_MS = 60 * 60 * 1000L
        private const val RETRY_DELAY_MS = 30 * 60 * 1000L
        private const val MIN_POLL_DELAY_MS = 60_000L

        @Volatile private var instance: UpdateRepository? = null
        fun get(context: Context): UpdateRepository =
            instance ?: synchronized(this) {
                instance ?: UpdateRepository(context).also { instance = it }
            }
    }
}
