package com.munchkin.app

import android.app.Application
import com.munchkin.app.di.AppContainer
import com.munchkin.app.di.DefaultAppContainer
import com.munchkin.app.ui.components.SoundManager

/**
 * Application class for Munchkin Mesa Tracker.
 * Owns the explicit [AppContainer] dependency graph.
 */
class MunchkinApp : Application() {

    override fun onCreate() {
        super.onCreate()
        container = DefaultAppContainer(this)
        SoundManager.init(this)
    }

    companion object {
        lateinit var container: AppContainer
            private set
    }
}
