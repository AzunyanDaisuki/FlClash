package com.follow.clash.common


import android.app.Application
import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers

object GlobalState : CoroutineScope by CoroutineScope(Dispatchers.Default) {

    const val NOTIFICATION_CHANNEL = "FlClash"

    const val NOTIFICATION_ID = 1

    val packageName: String
        get() = application.packageName

    val RECEIVE_BROADCASTS_PERMISSIONS: String
        get() = "${packageName}.permission.RECEIVE_BROADCASTS"


    private var _application: Application? = null

    val application: Application
        get() = _application!!


    fun log(text: String) {
        Log.d("[FlClash]", text)
    }

    fun init(application: Application) {
        _application = application
    }

    fun setCrashlytics(enable: Boolean) {
        _application?.let {
            runCatching {
                initCrashlytics(it, enable)
                if (enable) {
                    log("init crashlytics ${it.processName}")
                }
            }.onFailure { e ->
                log("skip crashlytics: ${e.message}")
            }
        }
    }

    private fun initCrashlytics(context: Context, enable: Boolean) {
        Class.forName("com.google.firebase.FirebaseApp")
            .getMethod("initializeApp", Context::class.java)
            .invoke(null, context)
        val crashlytics = Class.forName("com.google.firebase.crashlytics.FirebaseCrashlytics")
            .getMethod("getInstance")
            .invoke(null)
        crashlytics.javaClass
            .getMethod("setCrashlyticsCollectionEnabled", Boolean::class.javaPrimitiveType)
            .invoke(crashlytics, enable)
    }
}
