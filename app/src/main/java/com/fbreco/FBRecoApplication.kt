package com.fbreco

import android.app.Application
import com.mapbox.common.MapboxOptions
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class FBRecoApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        val token = BuildConfig.MAPBOX_ACCESS_TOKEN
        if (token.isNotBlank()) {
            MapboxOptions.accessToken = token
        }
    }
}
