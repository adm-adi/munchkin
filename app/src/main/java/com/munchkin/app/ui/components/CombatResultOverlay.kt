package com.munchkin.app.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.munchkin.app.R
import com.munchkin.app.ui.theme.DungeonDangerFill
import com.munchkin.app.ui.theme.DungeonSuccessFill
import com.munchkin.app.ui.theme.NeonBackground
import com.munchkin.app.ui.theme.NeonCyan
import com.munchkin.app.ui.theme.NeonGold
import com.munchkin.app.ui.theme.NeonGray100
import com.munchkin.app.ui.theme.NeonGray200
import com.munchkin.app.ui.theme.NeonPrimary
import com.munchkin.app.ui.theme.NeonPrimaryLight
import com.munchkin.app.ui.theme.NeonSecondary
import com.munchkin.app.ui.theme.NeonSuccess
import kotlinx.coroutines.delay
import kotlin.math.sin
import kotlin.random.Random

enum class CombatAnimationType {
    VICTORY,
    DEFEAT,
    ESCAPE_SUCCESS,
    ESCAPE_FAIL
}

/**
 * Full-screen combat outcome overlay. Consumes all pointer events while visible
 * so underlying combat controls cannot be tapped accidentally.
 */
@Composable
fun CombatResultOverlay(
    type: CombatAnimationType,
    onAnimationFinished: () -> Unit
) {
    var isVisible by remember { mutableStateOf(false) }
    val shakeRotation = remember(type) { Animatable(0f) }

    LaunchedEffect(type) {
        when (type) {
            CombatAnimationType.VICTORY, CombatAnimationType.ESCAPE_SUCCESS -> SoundManager.playVictory()
            CombatAnimationType.DEFEAT, CombatAnimationType.ESCAPE_FAIL -> SoundManager.playDefeat()
        }
        isVisible = true
        if (type == CombatAnimationType.DEFEAT || type == CombatAnimationType.ESCAPE_FAIL) {
            repeat(3) {
                shakeRotation.animateTo(2.5f, animationSpec = tween(70))
                shakeRotation.animateTo(-2.5f, animationSpec = tween(70))
            }
            shakeRotation.animateTo(0f, animationSpec = tween(90))
        }
        delay(2500L)
        isVisible = false
        delay(500L)
        onAnimationFinished()
    }

    AnimatedVisibility(
        visible = isVisible,
        enter = scaleIn(initialScale = 0.6f) + fadeIn(),
        exit = scaleOut(targetScale = 1.3f) + fadeOut()
    ) {
        val accentGlow = when (type) {
            CombatAnimationType.VICTORY -> NeonSecondary
            CombatAnimationType.DEFEAT -> NeonPrimary
            CombatAnimationType.ESCAPE_SUCCESS -> DungeonSuccessFill
            CombatAnimationType.ESCAPE_FAIL -> DungeonDangerFill
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(NeonBackground.copy(alpha = 0.72f))
                .background(
                    brush = Brush.radialGradient(
                        colors = listOf(accentGlow.copy(alpha = 0.58f), Color.Transparent)
                    )
                )
                .pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) {
                            awaitPointerEvent().changes.forEach { it.consume() }
                        }
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            if (type == CombatAnimationType.VICTORY) {
                VictoryConfetti()
            }

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
                modifier = Modifier
                    .padding(horizontal = 24.dp)
                    .graphicsLayer {
                        rotationZ = shakeRotation.value
                    }
            ) {
                Text(
                    text = when (type) {
                        CombatAnimationType.VICTORY -> stringResource(R.string.result_win)
                        CombatAnimationType.DEFEAT -> stringResource(R.string.result_lose)
                        CombatAnimationType.ESCAPE_SUCCESS -> stringResource(R.string.combat_escaped)
                        CombatAnimationType.ESCAPE_FAIL -> stringResource(R.string.combat_failed_escape)
                    },
                    style = MaterialTheme.typography.displayMedium.copy(
                        fontWeight = FontWeight.Black,
                        fontSize = 44.sp
                    ),
                    color = NeonGray100,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = when (type) {
                        CombatAnimationType.VICTORY -> stringResource(R.string.combat_overlay_victory_sub)
                        CombatAnimationType.DEFEAT -> stringResource(R.string.combat_overlay_defeat_sub)
                        CombatAnimationType.ESCAPE_SUCCESS -> stringResource(R.string.combat_overlay_escape_success_sub)
                        CombatAnimationType.ESCAPE_FAIL -> stringResource(R.string.combat_overlay_escape_fail_sub)
                    },
                    style = MaterialTheme.typography.titleLarge,
                    color = NeonGray200,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

@Composable
fun VictoryConfetti() {
    val particles = remember { List(50) { ConfettiParticle() } }
    val infiniteTransition = rememberInfiniteTransition(label = "confetti")

    val timeState = infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "confettiTime"
    )

    Canvas(modifier = Modifier.fillMaxSize()) {
        val time = timeState.value
        particles.forEach { particle ->
            val currentY = particle.initialY + (time * size.height * 1.4f * particle.speed)
            val wrappedY = ((currentY % size.height) + size.height) % size.height
            val currentX = (particle.initialXFraction * size.width) +
                (sin(time * 10f + particle.offset) * 40f)

            drawCircle(
                color = particle.color,
                radius = 9f,
                center = Offset(currentX, wrappedY)
            )
        }
    }
}

private val ConfettiPalette = listOf(
    NeonGold,
    NeonPrimaryLight,
    NeonSecondary,
    NeonSuccess,
    NeonCyan
)

data class ConfettiParticle(
    val initialXFraction: Float = Random.nextFloat(),
    val initialY: Float = Random.nextFloat() * -800f,
    val speed: Float = Random.nextFloat() * 0.5f + 0.5f,
    val offset: Float = Random.nextFloat() * 6.28f,
    val color: Color = ConfettiPalette.random()
)
