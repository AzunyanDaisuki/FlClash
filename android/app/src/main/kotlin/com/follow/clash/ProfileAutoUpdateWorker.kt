package com.follow.clash

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.os.Build
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.follow.clash.common.GlobalState
import com.follow.clash.core.Core
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

class ProfileAutoUpdateWorker(
    appContext: Context,
    workerParams: WorkerParameters,
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        runCatching {
            updateDueProfiles()
        }.onFailure {
            GlobalState.log("Profile auto update failed: ${it.message}")
        }
        Result.success()
    }

    private suspend fun updateDueProfiles() {
        val databaseFile = File(applicationContext.filesDir, "database.sqlite")
        if (!databaseFile.exists()) return

        SQLiteDatabase.openDatabase(
            databaseFile.path,
            null,
            SQLiteDatabase.OPEN_READWRITE,
        ).use { database ->
            val profiles = queryProfiles(database)
            if (profiles.isEmpty()) return
            initCore()
            profiles.forEach { profile ->
                if (!profile.shouldUpdate()) return@forEach
                runCatching {
                    updateProfile(database, profile)
                }.onFailure {
                    GlobalState.log("Profile ${profile.id} auto update failed: ${it.message}")
                }
            }
        }
    }

    private fun queryProfiles(database: SQLiteDatabase): List<ProfileRecord> {
        val profiles = mutableListOf<ProfileRecord>()
        database.query(
            "profiles",
            arrayOf(
                "id",
                "label",
                "url",
                "last_update_date",
                "auto_update_duration_millis",
                "auto_update",
            ),
            "auto_update = 1 AND url != ''",
            null,
            null,
            null,
            null,
        ).use { cursor ->
            val idIndex = cursor.getColumnIndexOrThrow("id")
            val labelIndex = cursor.getColumnIndexOrThrow("label")
            val urlIndex = cursor.getColumnIndexOrThrow("url")
            val lastUpdateDateIndex = cursor.getColumnIndexOrThrow("last_update_date")
            val durationIndex = cursor.getColumnIndexOrThrow("auto_update_duration_millis")
            val autoUpdateIndex = cursor.getColumnIndexOrThrow("auto_update")
            while (cursor.moveToNext()) {
                if (cursor.getInt(autoUpdateIndex) != 1) continue
                val durationMillis = cursor.getLong(durationIndex)
                if (durationMillis <= 0) continue
                profiles.add(
                    ProfileRecord(
                        id = cursor.getLong(idIndex),
                        label = cursor.getString(labelIndex).orEmpty(),
                        url = cursor.getString(urlIndex).orEmpty(),
                        lastUpdateDate = when (cursor.isNull(lastUpdateDateIndex)) {
                            true -> null
                            false -> cursor.getLong(lastUpdateDateIndex)
                        },
                        autoUpdateDurationMillis = durationMillis,
                    )
                )
            }
        }
        return profiles
    }

    private suspend fun updateProfile(database: SQLiteDatabase, profile: ProfileRecord) {
        val downloadedProfile = downloadProfile(profile.url)
        val tempFile = File.createTempFile("profile_${profile.id}", ".yaml", applicationContext.cacheDir)
        try {
            tempFile.writeBytes(downloadedProfile.bytes)
            val validateMessage = validateConfig(tempFile)
            if (validateMessage.isNotEmpty()) {
                throw IllegalStateException(validateMessage)
            }
            val profileFile = File(applicationContext.filesDir, "profiles/${profile.id}.yaml")
            profileFile.parentFile?.mkdirs()
            tempFile.copyTo(profileFile, overwrite = true)
            val values = ContentValues().apply {
                put("label", profile.nextLabel(downloadedProfile.contentDisposition))
                put("subscription_info", subscriptionInfoJson(downloadedProfile.subscriptionUserInfo))
                put("last_update_date", System.currentTimeMillis() / 1000)
            }
            database.update("profiles", values, "id = ?", arrayOf(profile.id.toString()))
        } finally {
            tempFile.delete()
        }
    }

    private fun downloadProfile(url: String): DownloadedProfile {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 30_000
            readTimeout = 180_000
            requestMethod = "GET"
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", userAgent)
        }
        return try {
            val statusCode = connection.responseCode
            if (statusCode !in 200..299) {
                throw IllegalStateException("HTTP $statusCode")
            }
            DownloadedProfile(
                bytes = connection.inputStream.use { it.readBytes() },
                contentDisposition = connection.getHeaderField("content-disposition"),
                subscriptionUserInfo = connection.getHeaderField("subscription-userinfo"),
            )
        } finally {
            connection.disconnect()
        }
    }

    private suspend fun initCore() {
        val initParams = JSONObject()
            .put("homeDir", applicationContext.filesDir.path)
            .put("version", Build.VERSION.SDK_INT)
        invokeCoreAction("initClash", initParams.toString())
    }

    private suspend fun validateConfig(file: File): String {
        val result = invokeCoreAction("validateConfig", file.path)
        return result.optString("data", "")
    }

    private suspend fun invokeCoreAction(method: String, data: Any): JSONObject =
        suspendCancellableCoroutine { continuation ->
            val action = JSONObject()
                .put("id", "profileAutoUpdate#${UUID.randomUUID()}")
                .put("method", method)
                .put("data", data)
            Core.invokeAction(action.toString()) { result ->
                val json = runCatching {
                    JSONObject(result ?: "{}")
                }.getOrDefault(JSONObject())
                if (continuation.isActive) {
                    continuation.resume(json)
                }
            }
        }

    private fun subscriptionInfoJson(value: String?): String {
        val data = JSONObject()
            .put("upload", 0)
            .put("download", 0)
            .put("total", 0)
            .put("expire", 0)
        if (value == null) return data.toString()
        value.split(";").forEach { item ->
            val parts = item.trim().split("=", limit = 2)
            if (parts.size != 2) return@forEach
            val key = parts[0].trim()
            val number = parts[1].trim().toLongOrNull() ?: return@forEach
            if (key in setOf("upload", "download", "total", "expire")) {
                data.put(key, number)
            }
        }
        return data.toString()
    }

    private val userAgent: String
        get() {
            val packageInfo = applicationContext.packageManager.getPackageInfo(applicationContext.packageName, 0)
            return "FlClash/v${packageInfo.versionName} clash-verge Platform/android"
        }

    private data class ProfileRecord(
        val id: Long,
        val label: String,
        val url: String,
        val lastUpdateDate: Long?,
        val autoUpdateDurationMillis: Long,
    ) {
        fun shouldUpdate(): Boolean {
            val lastUpdateMillis = lastUpdateDate?.toEpochMillis() ?: return true
            return lastUpdateMillis + autoUpdateDurationMillis <= System.currentTimeMillis()
        }

        fun nextLabel(contentDisposition: String?): String {
            if (label.trim().isNotEmpty()) return label.trim()
            return contentDisposition.fileNameFromContentDisposition()?.takeIf { it.isNotBlank() }
                ?: id.toString()
        }
    }

    private data class DownloadedProfile(
        val bytes: ByteArray,
        val contentDisposition: String?,
        val subscriptionUserInfo: String?,
    )

    companion object {
        private const val UNIQUE_WORK_NAME = "profile_auto_update"
        private const val MIN_PERIODIC_INTERVAL_MILLIS = 15 * 60 * 1000L

        fun sync(context: Context, intervalMillis: Long?) {
            val workManager = WorkManager.getInstance(context)
            if (intervalMillis == null || intervalMillis <= 0) {
                workManager.cancelUniqueWork(UNIQUE_WORK_NAME)
                return
            }
            val repeatIntervalMillis = maxOf(intervalMillis, MIN_PERIODIC_INTERVAL_MILLIS)
            val request =
                PeriodicWorkRequestBuilder<ProfileAutoUpdateWorker>(
                    repeatIntervalMillis,
                    TimeUnit.MILLISECONDS,
                )
                    .setConstraints(
                        Constraints.Builder()
                            .setRequiredNetworkType(NetworkType.CONNECTED)
                            .build()
                    )
                    .addTag(UNIQUE_WORK_NAME)
                    .build()
            workManager.enqueueUniquePeriodicWork(
                UNIQUE_WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                request,
            )
        }
    }
}

private fun Long.toEpochMillis(): Long {
    return when {
        this > 100_000_000_000L -> this
        else -> this * 1000L
    }
}

private fun String?.fileNameFromContentDisposition(): String? {
    if (this == null) return null
    val starMatch = Regex("""filename\*=UTF-8''([^;]+)""", RegexOption.IGNORE_CASE).find(this)
    if (starMatch != null) {
        return URLDecoder.decode(starMatch.groupValues[1].trim('"'), "UTF-8")
    }
    val match = Regex("""filename="?([^";]+)"?""", RegexOption.IGNORE_CASE).find(this)
    return match?.groupValues?.getOrNull(1)?.trim()
}
