package com.follow.clash

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.os.Build
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.follow.clash.common.GlobalState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.util.Calendar
import java.util.UUID
import java.util.concurrent.TimeUnit

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
        runCatching {
            enqueueNext(applicationContext, null)
        }.onFailure {
            GlobalState.log("Profile auto update schedule failed: ${it.message}")
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
            val now = System.currentTimeMillis()
            profiles.forEach { profile ->
                if (!profile.shouldUpdate(now)) return@forEach
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
            .put("home-dir", applicationContext.filesDir.path)
            .put("version", Build.VERSION.SDK_INT)
        invokeCoreAction("initClash", initParams.toString())
    }

    private suspend fun validateConfig(file: File): String {
        val result = invokeCoreAction("validateConfig", file.path)
        return result.optString("data", "")
    }

    private suspend fun invokeCoreAction(method: String, data: Any): JSONObject {
        val result = CompletableDeferred<String>()
        val action = JSONObject()
            .put("id", "profileAutoUpdate#${UUID.randomUUID()}")
            .put("method", method)
            .put("data", data)
        Service.bind()
        Service.invokeAction(action.toString()) {
            if (!result.isCompleted) {
                result.complete(it)
            }
        }.onFailure {
            if (!result.isCompleted) {
                result.completeExceptionally(it)
            }
        }
        val raw = withTimeout(CORE_ACTION_TIMEOUT_MILLIS) { result.await() }
        return runCatching {
            JSONObject(raw)
        }.getOrDefault(JSONObject())
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
        fun shouldUpdate(nowMillis: Long): Boolean {
            val lastUpdateMillis = lastUpdateDate?.toEpochMillis() ?: return true
            return lastUpdateMillis < previousFixedIntervalMillis(autoUpdateDurationMillis, nowMillis)
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
        private const val CORE_ACTION_TIMEOUT_MILLIS = 60_000L

        fun sync(context: Context, intervalMillis: Long?) {
            runCatching {
                val workManager = WorkManager.getInstance(context)
                if (intervalMillis == null || intervalMillis <= 0) {
                    workManager.cancelUniqueWork(UNIQUE_WORK_NAME)
                    return@runCatching
                }
                val delayMillis = nextFixedIntervalDelayMillis(
                    intervalMillis = intervalMillis,
                    includeCurrentBoundary = true,
                )
                enqueue(context, delayMillis, ExistingWorkPolicy.REPLACE)
            }.onFailure {
                GlobalState.log("Profile auto update sync failed: ${it.message}")
            }
        }

        private fun enqueueNext(context: Context, intervalMillis: Long?) {
            val shortestIntervalMillis = intervalMillis ?: readAutoUpdateIntervalMillis(context)
            if (shortestIntervalMillis == null || shortestIntervalMillis <= 0) return
            val delayMillis = nextFixedIntervalDelayMillis(
                intervalMillis = shortestIntervalMillis,
                includeCurrentBoundary = false,
            )
            enqueue(context, delayMillis, ExistingWorkPolicy.APPEND_OR_REPLACE)
        }

        private fun enqueue(
            context: Context,
            delayMillis: Long,
            policy: ExistingWorkPolicy,
        ) {
            val request = OneTimeWorkRequestBuilder<ProfileAutoUpdateWorker>()
                .setInitialDelay(delayMillis, TimeUnit.MILLISECONDS)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .addTag(UNIQUE_WORK_NAME)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                UNIQUE_WORK_NAME,
                policy,
                request,
            )
        }

        private fun readAutoUpdateIntervalMillis(context: Context): Long? {
            val databaseFile = databaseFile(context)
            if (!databaseFile.exists()) return null
            return runCatching {
                SQLiteDatabase.openDatabase(
                    databaseFile.path,
                    null,
                    SQLiteDatabase.OPEN_READONLY,
                ).use { database ->
                    database.rawQuery(
                        """
                        SELECT MIN(auto_update_duration_millis)
                        FROM profiles
                        WHERE auto_update = 1
                          AND url != ''
                          AND auto_update_duration_millis > 0
                        """.trimIndent(),
                        null,
                    ).use { cursor ->
                        if (cursor.moveToFirst() && !cursor.isNull(0)) {
                            cursor.getLong(0)
                        } else {
                            null
                        }
                    }
                }
            }.onFailure {
                GlobalState.log("Profile auto update interval read failed: ${it.message}")
            }.getOrNull()
        }

        private fun databaseFile(context: Context): File {
            return File(context.filesDir, "database.sqlite")
        }
    }
}

private fun nextFixedIntervalDelayMillis(
    intervalMillis: Long,
    includeCurrentBoundary: Boolean,
    nowMillis: Long = System.currentTimeMillis(),
): Long {
    val elapsedMillis = nowMillis - localDayStartMillis(nowMillis)
    val remainderMillis = positiveMod(elapsedMillis, intervalMillis)
    return when {
        remainderMillis == 0L && includeCurrentBoundary -> 0L
        remainderMillis == 0L -> intervalMillis
        else -> intervalMillis - remainderMillis
    }
}

private fun previousFixedIntervalMillis(intervalMillis: Long, nowMillis: Long): Long {
    val elapsedMillis = nowMillis - localDayStartMillis(nowMillis)
    return nowMillis - positiveMod(elapsedMillis, intervalMillis)
}

private fun localDayStartMillis(nowMillis: Long): Long {
    return Calendar.getInstance().apply {
        timeInMillis = nowMillis
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis
}

private fun positiveMod(value: Long, divisor: Long): Long {
    return ((value % divisor) + divisor) % divisor
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
