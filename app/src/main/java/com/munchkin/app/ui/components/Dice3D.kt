package com.munchkin.app.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.munchkin.app.R
import com.munchkin.app.ui.theme.NeonGray100
import com.munchkin.app.ui.theme.NeonPrimary
import com.munchkin.app.ui.theme.NeonSecondary
import com.munchkin.app.ui.theme.NeonSurface
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.random.Random

/**
 * Interactive 6-sided dungeon die with rotation/bounce animation and accessible button semantics.
 */
@Composable
fun Dice3D(
    modifier: Modifier = Modifier,
    size: Dp = 100.dp,
    onRollFinished: (Int) -> Unit = {}
) {
    var currentNumber by remember { mutableIntStateOf(6) }
    var isRolling by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    val animRotation = remember { Animatable(0f) }
    val animScale = remember { Animatable(1f) }

    fun rollDice() {
        if (isRolling) return
        isRolling = true
        SoundManager.playDiceRoll()

        scope.launch {
            launch {
                animRotation.animateTo(
                    targetValue = 720f,
                    animationSpec = tween(durationMillis = 1000, easing = FastOutSlowInEasing)
                )
                animRotation.snapTo(0f)
            }
            launch {
                animScale.animateTo(
                    targetValue = 0.8f,
                    animationSpec = tween(durationMillis = 200)
                )
                animScale.animateTo(
                    targetValue = 1.2f,
                    animationSpec = spring(dampingRatio = Spring.DampingRatioHighBouncy)
                )
                animScale.animateTo(1f)
            }

            val rollTime = 1000L
            val startTime = System.currentTimeMillis()
            while (System.currentTimeMillis() - startTime < rollTime) {
                currentNumber = Random.nextInt(1, 7)
                delay(80L)
            }

            val finalResult = Random.nextInt(1, 7)
            currentNumber = finalResult
            isRolling = false
            onRollFinished(finalResult)
        }
    }

    val cdDiceRoll = stringResource(R.string.cd_dice_roll)

    Box(
        modifier = modifier
            .size(size)
            .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
            .graphicsLayer {
                rotationZ = animRotation.value
                scaleX = animScale.value
                scaleY = animScale.value
            }
            .semantics { contentDescription = cdDiceRoll }
            .clickable(
                enabled = !isRolling,
                role = Role.Button,
                onClick = ::rollDice
            ),
        contentAlignment = Alignment.Center
    ) {
        NeonDiceFace(number = currentNumber)
    }
}

/**
 * Custom Canvas die face rendered using Dungeon Edition gold & crimson accents
 * without per-frame Paint shadow allocations.
 */
@Composable
fun NeonDiceFace(
    number: Int,
    primaryColor: Color = NeonSecondary,
    secondaryColor: Color = NeonPrimary
) {
    Box(modifier = Modifier.fillMaxSize()) {
        Canvas(modifier = Modifier.matchParentSize()) {
            val strokeWidth = size.width * 0.05f
            val cornerRadius = size.width * 0.15f
            val inset = strokeWidth / 2f

            drawRoundRect(
                color = NeonSurface,
                size = size,
                cornerRadius = CornerRadius(cornerRadius, cornerRadius)
            )

            // Outer ambient ring (GPU stroke, zero Paint allocations)
            drawRoundRect(
                color = primaryColor.copy(alpha = 0.25f),
                topLeft = Offset(inset, inset),
                size = Size(size.width - strokeWidth, size.height - strokeWidth),
                cornerRadius = CornerRadius(cornerRadius, cornerRadius),
                style = Stroke(width = strokeWidth * 2.2f)
            )

            // Crisp border
            drawRoundRect(
                color = primaryColor,
                topLeft = Offset(inset, inset),
                size = Size(size.width - strokeWidth, size.height - strokeWidth),
                cornerRadius = CornerRadius(cornerRadius, cornerRadius),
                style = Stroke(width = strokeWidth)
            )

            drawDots(number = number, dotColor = secondaryColor, haloColor = primaryColor)
        }
    }
}

private fun DrawScope.drawDots(number: Int, dotColor: Color, haloColor: Color) {
    val dotRadius = size.width * 0.08f
    val center = size.width / 2f
    val left = size.width * 0.25f
    val right = size.width * 0.75f
    val top = size.width * 0.25f
    val bottom = size.width * 0.75f

    fun drawDot(x: Float, y: Float) {
        drawCircle(
            color = haloColor.copy(alpha = 0.28f),
            radius = dotRadius * 1.45f,
            center = Offset(x, y)
        )
        drawCircle(
            color = dotColor,
            radius = dotRadius,
            center = Offset(x, y)
        )
        drawCircle(
            color = NeonGray100.copy(alpha = 0.85f),
            radius = dotRadius * 0.38f,
            center = Offset(x, y)
        )
    }

    when (number) {
        1 -> drawDot(center, center)
        2 -> {
            drawDot(left, top)
            drawDot(right, bottom)
        }
        3 -> {
            drawDot(left, top)
            drawDot(center, center)
            drawDot(right, bottom)
        }
        4 -> {
            drawDot(left, top)
            drawDot(right, top)
            drawDot(left, bottom)
            drawDot(right, bottom)
        }
        5 -> {
            drawDot(left, top)
            drawDot(right, top)
            drawDot(center, center)
            drawDot(left, bottom)
            drawDot(right, bottom)
        }
        6 -> {
            drawDot(left, top)
            drawDot(right, top)
            drawDot(left, center)
            drawDot(right, center)
            drawDot(left, bottom)
            drawDot(right, bottom)
        }
    }
}
