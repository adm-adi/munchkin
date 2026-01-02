package com.munchkin.app.ui.components

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import com.munchkin.app.MunchkinApp
import com.munchkin.app.R

/**
 * Sound and vibration manager for game feedback.
 */
object SoundManager {
    
    private var soundPool: SoundPool? = null
    private var diceRollSound: Int = 0
    private var levelUpSound: Int = 0
    private var victorySound: Int = 0
    private var defeatSound: Int = 0
    private var buttonClickSound: Int = 0
    
    private var isInitialized = false
    
    fun init(context: Context) {
        if (isInitialized) return
        
        val audioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_GAME)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        
        soundPool = SoundPool.Builder()
            .setMaxStreams(5)
            .setAudioAttributes(audioAttributes)
            .build()
        
        // Load sounds (using system sounds as fallback)
        soundPool?.let { pool ->
            // We'll use simple beeps for now - can be replaced with actual sound files
            // For now, we focus on vibration which works without audio files
        }
        
        isInitialized = true
    }
    
    fun playDiceRoll() {
        vibrate(VibrationPattern.DICE_ROLL)
    }
    
    fun playLevelUp() {
        vibrate(VibrationPattern.LEVEL_UP)
    }
    
    fun playVictory() {
        vibrate(VibrationPattern.VICTORY)
    }
    
    fun playDefeat() {
        vibrate(VibrationPattern.DEFEAT)
    }
    
    fun playButtonClick() {
        vibrate(VibrationPattern.BUTTON_CLICK)
    }
    
    private fun vibrate(pattern: VibrationPattern) {
        try {
            val context = MunchkinApp.context
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vibratorManager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
                vibratorManager.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            }
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                when (pattern) {
                    VibrationPattern.DICE_ROLL -> {
                        // Simulate dice rolling with multiple short pulses
                        val timings = longArrayOf(0, 50, 30, 50, 30, 50, 30, 100)
                        val amplitudes = intArrayOf(0, 100, 0, 150, 0, 200, 0, 255)
                        vibrator.vibrate(VibrationEffect.createWaveform(timings, amplitudes, -1))
                    }
                    VibrationPattern.LEVEL_UP -> {
                        // Ascending pattern for level up
                        val timings = longArrayOf(0, 50, 50, 100)
                        val amplitudes = intArrayOf(0, 100, 0, 255)
                        vibrator.vibrate(VibrationEffect.createWaveform(timings, amplitudes, -1))
                    }
                    VibrationPattern.VICTORY -> {
                        // Strong celebration pattern
                        val timings = longArrayOf(0, 100, 50, 100, 50, 200)
                        val amplitudes = intArrayOf(0, 255, 0, 255, 0, 255)
                        vibrator.vibrate(VibrationEffect.createWaveform(timings, amplitudes, -1))
                    }
                    VibrationPattern.DEFEAT -> {
                        // Single long sad vibration
                        vibrator.vibrate(VibrationEffect.createOneShot(300, 128))
                    }
                    VibrationPattern.BUTTON_CLICK -> {
                        // Quick tap feedback
                        vibrator.vibrate(VibrationEffect.createOneShot(30, 64))
                    }
                }
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(50)
            }
        } catch (e: Exception) {
            // Silently fail if vibration not available
        }
    }
    
    private enum class VibrationPattern {
        DICE_ROLL,
        LEVEL_UP,
        VICTORY,
        DEFEAT,
        BUTTON_CLICK
    }
    
    fun release() {
        soundPool?.release()
        soundPool = null
        isInitialized = false
    }
}
