package com.munchkin.app.ui.components

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/**
 * Haptic feedback manager for game actions and combat events.
 */
object SoundManager {

    private var vibrationEnabled = true

    @Volatile
    private var vibrator: Vibrator? = null

    fun init(appContext: Context) {
        if (vibrator != null) return
        val context = appContext.applicationContext
        vibrator = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vibratorManager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                vibratorManager?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }
        } catch (_: Exception) {
            null
        }
    }

    enum class VibrationPattern {
        DICE_ROLL,
        LEVEL_UP,
        VICTORY,
        DEFEAT,
        BUTTON_CLICK
    }

    fun playDiceRoll() {
        vibrate(VibrationPattern.DICE_ROLL)
    }

    fun playLevelUp() {
        vibrate(VibrationPattern.LEVEL_UP)
    }

    fun playLevelDown() {
        vibrate(VibrationPattern.BUTTON_CLICK)
    }

    fun playVictory() {
        vibrate(VibrationPattern.VICTORY)
    }

    fun playDefeat() {
        vibrate(VibrationPattern.DEFEAT)
    }

    fun playTurnStart() {
        vibrate(VibrationPattern.BUTTON_CLICK)
    }

    fun playButtonClick() {
        vibrate(VibrationPattern.BUTTON_CLICK)
    }

    private fun vibrate(pattern: VibrationPattern) {
        if (!vibrationEnabled) return
        try {
            val activeVibrator = vibrator ?: return
            val effect = when (pattern) {
                VibrationPattern.DICE_ROLL -> {
                    val timings = longArrayOf(0, 50, 30, 50, 30, 50, 30, 100)
                    val amplitudes = intArrayOf(0, 100, 0, 150, 0, 200, 0, 255)
                    VibrationEffect.createWaveform(timings, amplitudes, -1)
                }
                VibrationPattern.LEVEL_UP -> {
                    val timings = longArrayOf(0, 50, 50, 100)
                    val amplitudes = intArrayOf(0, 100, 0, 255)
                    VibrationEffect.createWaveform(timings, amplitudes, -1)
                }
                VibrationPattern.VICTORY -> {
                    val timings = longArrayOf(0, 100, 50, 100, 50, 200)
                    val amplitudes = intArrayOf(0, 255, 0, 255, 0, 255)
                    VibrationEffect.createWaveform(timings, amplitudes, -1)
                }
                VibrationPattern.DEFEAT -> {
                    VibrationEffect.createOneShot(300, 128)
                }
                VibrationPattern.BUTTON_CLICK -> {
                    VibrationEffect.createOneShot(30, 64)
                }
            }
            activeVibrator.vibrate(effect)
        } catch (_: Exception) {
            // Silently ignore if vibration hardware/permission is unavailable.
        }
    }
}
