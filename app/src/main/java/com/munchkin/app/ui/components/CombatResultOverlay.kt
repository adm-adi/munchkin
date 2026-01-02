package com.munchkin.app.ui.components

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

enum class CombatAnimationType {
    VICTORY,
    DEFEAT,
    ESCAPE_SUCCESS,
    ESCAPE_FAIL
}

@Composable
fun CombatResultOverlay(
    type: CombatAnimationType,
    onAnimationFinished: () -> Unit
) {
    var isVisible by remember { mutableStateOf(false) }
    
    LaunchedEffect(type) {
        // Play vibration based on result type
        when (type) {
            CombatAnimationType.VICTORY, CombatAnimationType.ESCAPE_SUCCESS -> SoundManager.playVictory()
            CombatAnimationType.DEFEAT, CombatAnimationType.ESCAPE_FAIL -> SoundManager.playDefeat()
        }
        isVisible = true
        // Show for 2.5 seconds then dismiss
        delay(2500)
        isVisible = false
        delay(500) // Wait for exit animation
        onAnimationFinished()
    }

    AnimatedVisibility(
        visible = isVisible,
        enter = scaleIn(initialScale = 0.5f) + fadeIn(),
        exit = scaleOut(targetScale = 1.5f) + fadeOut()
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    brush = Brush.radialGradient(
                        colors = when (type) {
                            CombatAnimationType.VICTORY -> listOf(Color(0xFFFFD700).copy(alpha = 0.6f), Color.Transparent)
                            CombatAnimationType.DEFEAT -> listOf(Color(0xFFB00020).copy(alpha = 0.6f), Color.Transparent)
                            CombatAnimationType.ESCAPE_SUCCESS -> listOf(Color(0xFF4CAF50).copy(alpha = 0.6f), Color.Transparent)
                            CombatAnimationType.ESCAPE_FAIL -> listOf(Color(0xFFD32F2F).copy(alpha = 0.6f), Color.Transparent)
                        }
                    )
                ),
            contentAlignment = Alignment.Center
        ) {
            // Particles for Victory
            if (type == CombatAnimationType.VICTORY) {
                VictoryConfetti()
            }

            // Main Text
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
                modifier = Modifier.graphicsLayer {
                    // Slight shake for defeat/fail
                    if (type == CombatAnimationType.DEFEAT || type == CombatAnimationType.ESCAPE_FAIL) {
                        this.rotationZ = (Math.random() * 4 - 2).toFloat()
                    }
                }
            ) {
                Text(
                    text = when (type) {
                        CombatAnimationType.VICTORY -> "¡VICTORIA!"
                        CombatAnimationType.DEFEAT -> "¡DERROTA!"
                        CombatAnimationType.ESCAPE_SUCCESS -> "¡ESCAPASTE!"
                        CombatAnimationType.ESCAPE_FAIL -> "¡ATRAPADO!"
                    },
                    style = MaterialTheme.typography.displayLarge.copy(
                        fontWeight = FontWeight.Black,
                        fontSize = 56.sp
                    ),
                    color = Color.White
                )
                
                Text(
                    text = when (type) {
                        CombatAnimationType.VICTORY -> "Combate ganado"
                        CombatAnimationType.DEFEAT -> "¡A sufrir las consecuencias!"
                        CombatAnimationType.ESCAPE_SUCCESS -> "Vives para luchar otro día"
                        CombatAnimationType.ESCAPE_FAIL -> "Cosas malas van a pasar..."
                    },
                    style = MaterialTheme.typography.titleLarge,
                    color = Color.White.copy(alpha = 0.9f)
                )
            }
        }
    }
}

@Composable
fun VictoryConfetti() {
    val particles = remember { List(50) { ConfettiParticle() } }
    val infiniteTransition = rememberInfiniteTransition(label = "confetti")
    
    // Animate time to drive particle motion
    val time by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(2000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "time"
    )

    Canvas(modifier = Modifier.fillMaxSize()) {
        particles.forEach { particle ->
            // Update position based on time (gravity + spread)
            val currentY = particle.initialY + (time * 1500f * particle.speed)
            val currentX = particle.initialX + (sin(time * 10f + particle.offset) * 50f)
            
            drawCircle(
                color = particle.color,
                radius = 10f,
                center = Offset(currentX, currentY % size.height) // Loop vertically
            )
        }
    }
}

data class ConfettiParticle(
    val initialX: Float = Random.nextFloat() * 1000f, // Approx screen width range
    val initialY: Float = Random.nextFloat() * -1000f, // Start above screen
    val speed: Float = Random.nextFloat() * 0.5f + 0.5f,
    val offset: Float = Random.nextFloat() * 6.28f,
    val color: Color = listOf(Color.Yellow, Color.Red, Color.Blue, Color.Green, Color.Magenta).random()
)
