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
import org.json.JSONArray
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

    var status by mutableStateOf<UpdateStatus>(restoredStatus())
        private set

    var lastChecked by mutableStateOf(preferences.getLong(KEY_SUCCESS, 0L))
        private set

    init {
        val identity = BuildConfig.VERSION_NAME + "#" + BuildConfig.VERSION_CODE
        if (preferences.getString(KEY_INSTALLED, null) != identity) {
            preferences.edit {
                putString(KEY_INSTALLED, identity)
                remove(KEY_SUCCESS).remove(KEY_RESULT).remove(KEY_VERSION)
                remove(KEY_CODE).remove(KEY_URL)
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
            remove(KEY_SUCCESS).remove(KEY_RESULT).remove(KEY_VERSION)
            remove(KEY_CODE).remove(KEY_URL)
        }
        lastChecked = 0L
        status = UpdateStatus.NotChecked
    }

    fun configureAutomaticChecks(enabled: Boolean) {
        automaticChecks = enabled
        preferences.edit { putBoolean(KEY_ENABLED, enabled) }
    }

    fun takeUnnotifiedVersion(): String? {
        val candidate = (status as? UpdateStatus.Available)?.candidate ?: return null
        if (preferences.getString(KEY_NOTIFIED, null) == candidate.versionName) return null
        preferences.edit { putString(KEY_NOTIFIED, candidate.versionName) }
        return candidate.versionName
    }

    suspend fun check(manual: Boolean) = mutex.withLock {
        if (!manual && !automaticChecks) return@withLock
        val now = System.currentTimeMillis()
        if (!manual && now - lastChecked in 0 until CHECK_INTERVAL_MS) return@withLock
        val attempted = preferences.getLong(KEY_ATTEMPT, 0L)
        if (!manual && now - attempted in 0 until RETRY_DELAY_MS) return@withLock

        preferences.edit { putLong(KEY_ATTEMPT, now) }
        val requestedChannel = channel
        status = UpdateStatus.Checking
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
            } else {
                editor.remove(KEY_VERSION).remove(KEY_CODE).remove(KEY_URL)
            }
            editor.apply()
        } catch (cancelled: CancellationException) {
            if (channel == requestedChannel) status = UpdateStatus.NotChecked
            throw cancelled
        } catch (_: IOException) {
            if (channel == requestedChannel) status = UpdateStatus.Failed
        } catch (_: org.json.JSONException) {
            if (channel == requestedChannel) status = UpdateStatus.Failed
        }
    }

    private fun restoredStatus(): UpdateStatus {
        return when (preferences.getString(KEY_RESULT, null)) {
            "available" -> {
                val version = preferences.getString(KEY_VERSION, null)
                val url = preferences.getString(KEY_URL, null)
                val code = preferences.getInt(KEY_CODE, 0)
                val candidate = if (version != null && url != null && code > 0) {
                    UpdateCandidate(version, code, url)
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

    private fun fetchLatest(channel: UpdateChannel): UpdateStatus {
        val releases = mutableListOf<JSONObject>()
        if (channel == UpdateChannel.Preview) {
            val recent = loadJson(RELEASES_API) as? JSONArray
                ?: throw IOException("Invalid releases response")
            for (index in 0 until recent.length()) {
                releases.add(recent.getJSONObject(index))
            }
        }
        val stable = loadJson(LATEST_API, missingAllowed = true) as? JSONObject
        if (stable != null && releases.none { it.optString("tag_name") == stable.optString("tag_name") }) {
            releases.add(stable)
        }
        val installed = ReleaseVersion.parse(BuildConfig.VERSION_NAME)
            ?: return UpdateStatus.NoRelease
        val ranked = releases.mapNotNull { release ->
            if (release.optBoolean("draft", false)) return@mapNotNull null
            if (channel == UpdateChannel.Stable && release.optBoolean("prerelease", false)) {
                return@mapNotNull null
            }
            val tag = release.optString("tag_name")
            if (!tag.startsWith("v")) return@mapNotNull null
            val version = tag.drop(1)
            val parsed = ReleaseVersion.parse(version) ?: return@mapNotNull null
            if (release.optBoolean("prerelease", false) != ('-' in version)) return@mapNotNull null
            Triple(release, version, parsed)
        }.sortedWith(compareByDescending { it.third })

        var incompatible = false
        val newer = ranked.filter { it.third > installed }.take(MAX_METADATA_CHECKS)
        for ((release, version) in newer) {
            val assets = release.optJSONArray("assets") ?: continue
            var metadataUrl: String? = null
            var hasApk = false
            for (i in 0 until assets.length()) {
                val asset = assets.getJSONObject(i)
                val name = asset.optString("name")
                if (name == "release-info.json") metadataUrl = asset.optString("browser_download_url")
                if (name.endsWith(".apk") && (name.endsWith("-universal.apk") ||
                            Build.SUPPORTED_ABIS.any { abi -> name.endsWith("-" + abi + ".apk") })) {
                    hasApk = true
                }
            }
            val expected = "https://github.com/H3nb/JL-Mod-Plus/releases/download/v" +
                version + "/release-info.json"
            if (!hasApk || metadataUrl != expected) continue
            val meta = loadJson(expected) as? JSONObject ?: continue
            val minimumSdk = meta.optInt("minSdk", 0)
            if (meta.optInt("schema") != 1 || meta.optString("versionName") != version ||
                meta.optString("applicationId") != "io.github.h3nb.jlmodplus" ||
                minimumSdk < 23 || minimumSdk > Build.VERSION.SDK_INT) {
                continue
            }
            val code = meta.optInt("versionCode", 0)
            if (code < 1) continue
            val candidate = UpdateCandidate(
                versionName = version,
                versionCode = code,
                releaseUrl = "https://github.com/H3nb/JL-Mod-Plus/releases/tag/v" + version,
            )
            if (eligibleUpdate(BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE, candidate)) {
                return UpdateStatus.Available(candidate)
            }
            incompatible = true
        }
        return when {
            incompatible -> UpdateStatus.NotInstallable
            newer.isNotEmpty() -> UpdateStatus.NoRelease
            ranked.isNotEmpty() -> UpdateStatus.Current
            else -> UpdateStatus.NoRelease
        }
    }

    private fun loadJson(url: String, missingAllowed: Boolean = false): Any? {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 5000
        connection.readTimeout = 5000
        connection.setRequestProperty("Accept", "application/vnd.github+json")
        connection.setRequestProperty("User-Agent", "JL-Mod-Plus-Updater")
        try {
            val response = connection.responseCode
            if (missingAllowed && response == 404) return null
            if (response != 200) throw IOException("GitHub returned HTTP " + response)
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            return if (body.trimStart().startsWith("[")) JSONArray(body) else JSONObject(body)
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        private const val RELEASES_API =
            "https://api.github.com/repos/H3nb/JL-Mod-Plus/releases?per_page=30"
        private const val LATEST_API =
            "https://api.github.com/repos/H3nb/JL-Mod-Plus/releases/latest"
        private const val KEY_CHANNEL = "jlmod_updates_channel"
        private const val KEY_INSTALLED = "jlmod_updates_installed_identity"
        private const val KEY_ENABLED = "jlmod_updates_enabled"
        private const val KEY_ATTEMPT = "jlmod_updates_last_attempt"
        private const val KEY_SUCCESS = "jlmod_updates_last_success"
        private const val KEY_RESULT = "jlmod_updates_result"
        private const val KEY_VERSION = "jlmod_updates_version"
        private const val KEY_CODE = "jlmod_updates_code"
        private const val KEY_URL = "jlmod_updates_url"
        private const val KEY_NOTIFIED = "jlmod_updates_notified"
        private const val CHECK_INTERVAL_MS = 24 * 60 * 60 * 1000L
        private const val RETRY_DELAY_MS = 30 * 60 * 1000L
        private const val MAX_METADATA_CHECKS = 8

        @Volatile private var instance: UpdateRepository? = null
        fun get(context: Context): UpdateRepository =
            instance ?: synchronized(this) {
                instance ?: UpdateRepository(context).also { instance = it }
            }
    }
}
