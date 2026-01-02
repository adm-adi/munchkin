package com.munchkin.app.ui.components

import android.content.Context
import android.media.SoundPool
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import com.munchkin.app.MunchkinApp
import kotlinx.coroutines.*

/**
 * Sound and vibration manager for game feedback.
 */
object SoundManager {
    
    private var soundPool: SoundPool? = null
    private var isInitialized = false
    private var soundsEnabled = false // Disabled by default until assets are provided
    private var vibrationEnabled = true
    
    // Coroutine scope for sound playback
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    enum class SoundType {
        DICE_ROLL,
        LEVEL_UP,
        LEVEL_DOWN,
        VICTORY,
        DEFEAT,
        TURN_START,
        CLICK
    }
    
    enum class VibrationPattern {
        DICE_ROLL,
        LEVEL_UP,
        VICTORY,
        DEFEAT,
        BUTTON_CLICK
    }

    // Public API
    fun playDiceRoll() {
        playTone(SoundType.DICE_ROLL)
        vibrate(VibrationPattern.DICE_ROLL)
    }

    fun playLevelUp() {
        playTone(SoundType.LEVEL_UP)
        vibrate(VibrationPattern.LEVEL_UP)
    }

    fun playLevelDown() {
        playTone(SoundType.LEVEL_DOWN)
        // No distinct vibration for down
        vibrate(VibrationPattern.BUTTON_CLICK) 
    }

    fun playVictory() {
        playTone(SoundType.VICTORY)
        vibrate(VibrationPattern.VICTORY)
    }

    fun playDefeat() {
        playTone(SoundType.DEFEAT)
        vibrate(VibrationPattern.DEFEAT)
    }

    fun playTurnStart() {
        playTone(SoundType.TURN_START)
        vibrate(VibrationPattern.BUTTON_CLICK)
    }

    fun playButtonClick() {
        playTone(SoundType.CLICK)
        vibrate(VibrationPattern.BUTTON_CLICK)
    }

    private fun playTone(type: SoundType) {
        if (!soundsEnabled) return
        // Placeholder for future sound implementation
    }
    
    private fun vibrate(pattern: VibrationPattern) {
        if (!vibrationEnabled) return
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
    
    fun release() {
        soundPool?.release()
        soundPool = null
        scope.cancel()
        isInitialized = false
    }
}
