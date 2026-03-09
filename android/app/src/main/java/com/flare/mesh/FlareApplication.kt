package com.flare.mesh

import android.app.Application
import timber.log.Timber

class FlareApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        }
        Timber.i("Flare application started")
    }
}
