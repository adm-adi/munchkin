package com.munchkin.app

import android.app.Application
import android.content.Context

/**
 * Application class for Munchkin Mesa Tracker.
 * Provides global access to application context.
 */
class MunchkinApp : Application() {
    
    companion object {
        private lateinit var instance: MunchkinApp
        
        val context: Context
            get() = instance.applicationContext
    }
    
    override fun onCreate() {
        super.onCreate()
        instance = this
        // SoundManager auto-initializes
    }
}
