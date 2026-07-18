package com.rf.airmedradar

import android.app.Application
import android.content.pm.PackageManager
import com.google.android.libraries.places.api.Places

class AirMedRadarApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        if (!Places.isInitialized()) {
            val apiKey = readMapsApiKey()
            if (apiKey.isNotBlank()) {
                Places.initialize(applicationContext, apiKey)
            }
        }
    }

    // Reuses the same key already injected into the manifest's geo.API_KEY meta-data
    // (sourced from local.properties) rather than plumbing the secret a second way.
    private fun readMapsApiKey(): String {
        val appInfo = packageManager.getApplicationInfo(packageName, PackageManager.GET_META_DATA)
        return appInfo.metaData?.getString("com.google.android.geo.API_KEY").orEmpty()
    }
}
