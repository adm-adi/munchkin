package com.munchkin.app.ui.components

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.SoundPool
import android.media.ToneGenerator
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import com.munchkin.app.MunchkinApp
import kotlinx.coroutines.*

/**
 * Sound and vibration manager for game feedback.
 * Uses ToneGenerator for audio feedback without requiring external sound files.
 */
object SoundManager {
    
    private var soundPool: SoundPool? = null
    private var isInitialized = false
    private var soundsEnabled = true
    private var vibrationEnabled = true
    
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    
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
        
        isInitialized = true
    }
    
    fun setSoundsEnabled(enabled: Boolean) {
        soundsEnabled = enabled
    }
    
    fun setVibrationEnabled(enabled: Boolean) {
        vibrationEnabled = enabled
    }
    
    fun playDiceRoll() {
        if (vibrationEnabled) vibrate(VibrationPattern.DICE_ROLL)
        if (soundsEnabled) playTone(SoundType.DICE_ROLL)
    }
    
    fun playLevelUp() {
        if (vibrationEnabled) vibrate(VibrationPattern.LEVEL_UP)
        if (soundsEnabled) playTone(SoundType.LEVEL_UP)
    }
    
    fun playLevelDown() {
        if (vibrationEnabled) vibrate(VibrationPattern.DEFEAT)
        if (soundsEnabled) playTone(SoundType.LEVEL_DOWN)
    }
    
    fun playVictory() {
        if (vibrationEnabled) vibrate(VibrationPattern.VICTORY)
        if (soundsEnabled) playTone(SoundType.VICTORY)
    }
    
    fun playDefeat() {
        if (vibrationEnabled) vibrate(VibrationPattern.DEFEAT)
        if (soundsEnabled) playTone(SoundType.DEFEAT)
    }
    
    fun playButtonClick() {
        if (vibrationEnabled) vibrate(VibrationPattern.BUTTON_CLICK)
    }
    
    fun playTurnStart() {
        if (vibrationEnabled) vibrate(VibrationPattern.LEVEL_UP)
        if (soundsEnabled) playTone(SoundType.TURN_START)
    }
    
    private enum class SoundType {
        DICE_ROLL,
        LEVEL_UP,
        LEVEL_DOWN,
        VICTORY,
        DEFEAT,
        TURN_START
    }
    
    private fun playTone(type: SoundType) {
        scope.launch {
            try {
                when (type) {
                    SoundType.DICE_ROLL -> {
                        // Quick succession of tones to simulate rolling
                        val toneGen = ToneGenerator(AudioManager.STREAM_MUSIC, 50)
                        repeat(4) {
                            toneGen.startTone(ToneGenerator.TONE_PROP_BEEP, 50)
                            delay(80)
                        }
                        toneGen.release()
                    }
                    SoundType.LEVEL_UP -> {
                        // Ascending tones for level up celebration
                        val toneGen = ToneGenerator(AudioManager.STREAM_MUSIC, 80)
                        toneGen.startTone(ToneGenerator.TONE_DTMF_1, 100)
                        delay(120)
                        toneGen.startTone(ToneGenerator.TONE_DTMF_3, 100)
                        delay(120)
                        toneGen.startTone(ToneGenerator.TONE_DTMF_6, 150)
                        delay(200)
                        toneGen.release()
                    }
                    SoundType.LEVEL_DOWN -> {
                        // Descending sad tones
                        val toneGen = ToneGenerator(AudioManager.STREAM_MUSIC, 60)
                        toneGen.startTone(ToneGenerator.TONE_DTMF_6, 100)
                        delay(120)
                        toneGen.startTone(ToneGenerator.TONE_DTMF_3, 100)
                        delay(120)
                        toneGen.startTone(ToneGenerator.TONE_DTMF_1, 200)
                        delay(250)
                        toneGen.release()
                    }
                    SoundType.VICTORY -> {
                        // Fanfare for victory
                        val toneGen = ToneGenerator(AudioManager.STREAM_MUSIC, 100)
                        toneGen.startTone(ToneGenerator.TONE_DTMF_1, 100)
                        delay(100)
                        toneGen.startTone(ToneGenerator.TONE_DTMF_3, 100)
                        delay(100)
                        toneGen.startTone(ToneGenerator.TONE_DTMF_6, 100)
                        delay(100)
                        toneGen.startTone(ToneGenerator.TONE_DTMF_9, 300)
                        delay(400)
                        toneGen.release()
                    }
                    SoundType.DEFEAT -> {
                        // Sad descending tone
                        val toneGen = ToneGenerator(AudioManager.STREAM_MUSIC, 60)
                        toneGen.startTone(ToneGenerator.TONE_SUP_ERROR, 400)
                        delay(500)
                        toneGen.release()
                    }
                    SoundType.TURN_START -> {
                        // Short notification for turn start
                        val toneGen = ToneGenerator(AudioManager.STREAM_MUSIC, 70)
                        toneGen.startTone(ToneGenerator.TONE_PROP_BEEP2, 150)
                        delay(200)
                        toneGen.release()
                    }
                }
            } catch (e: Exception) {
                // Silently fail if audio not available
            }
        }
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
        scope.cancel()
        isInitialized = false
    }
}
