package com.taxipro.data.ads

import android.content.Context
import android.os.Bundle
import com.google.firebase.FirebaseApp
import com.google.firebase.analytics.FirebaseAnalytics

class AdsAnalytics(context: Context) {
    private val appContext = context.applicationContext
    private val analytics: FirebaseAnalytics? = runCatching {
        if (FirebaseApp.getApps(appContext).isEmpty()) {
            FirebaseApp.initializeApp(appContext) ?: return@runCatching null
        }
        FirebaseAnalytics.getInstance(appContext)
    }.getOrNull()

    fun log(name: String, vararg params: Pair<String, Any>) {
        val firebaseAnalytics = analytics ?: return
        val bundle = Bundle().apply {
            params.forEach { (key, value) ->
                when (value) {
                    is String -> putString(key, value)
                    is Int -> putInt(key, value)
                    is Long -> putLong(key, value)
                    is Boolean -> putBoolean(key, value)
                    is Double -> putDouble(key, value)
                    is Float -> putFloat(key, value)
                }
            }
        }
        firebaseAnalytics.logEvent(name, bundle)
    }
}
