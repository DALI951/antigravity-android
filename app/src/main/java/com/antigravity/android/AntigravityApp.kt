package com.antigravity.android

import android.app.Application

class AntigravityApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }

    fun getOrCreateContainer(): AppContainer {
        if (!::container.isInitialized) {
            container = AppContainer(null)
        }
        return container
    }
}
