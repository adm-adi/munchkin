package com.munchkin.app.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.munchkin.app.R
import com.munchkin.app.ui.theme.NeonBackground
import com.munchkin.app.ui.theme.NeonGray100
import com.munchkin.app.ui.theme.NeonPrimary
import com.munchkin.app.ui.theme.NeonPrimaryLight
import com.munchkin.app.ui.theme.NeonSecondary
import com.munchkin.app.ui.theme.NeonSurface
import com.munchkin.app.ui.theme.NeonSurfaceVariant
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.random.Random

/**
 * Interactive tactile 6-sided Dungeon Edition die with 3D perspective tumble,
 * toss arc, dynamic ground shadow, and spring settle.
 */
@Composable
fun Dice3D(
    modifier: Modifier = Modifier,
    size: Dp = 112.dp,
    externalNumber: Int? = null,
    accentColor: Color = NeonSecondary,
    onRollFinished: (Int) -> Unit = {}
) {
    var currentNumber by remember { mutableIntStateOf((externalNumber ?: 6).coerceIn(1, 6)) }
    var isRolling by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current.density

    val animRotX = remember { Animatable(-8f) }
    val animRotY = remember { Animatable(10f) }
    val animRotZ = remember { Animatable(0f) }
    val animScale = remember { Animatable(1f) }
    val animLiftPx = remember { Animatable(0f) }
    val animImpactRing = remember { Animatable(0f) }

    LaunchedEffect(externalNumber) {
        if (externalNumber != null && !isRolling) {
            currentNumber = externalNumber.coerceIn(1, 6)
            animScale.animateTo(1.09f, tween(90))
            animScale.animateTo(
                1f,
                spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium)
            )
        }
    }

    fun rollDice() {
        if (isRolling) return
        isRolling = true
        SoundManager.playDiceRoll()

        val liftPeak = -26f * density
        val dirX = if (Random.nextBoolean()) 1f else -1f
        val dirY = if (Random.nextBoolean()) 1f else -1f

        scope.launch {
            animImpactRing.snapTo(0f)

            // 3D multi-axis tumble
            val rotXJob = launch {
                animRotX.animateTo(
                    targetValue = dirX * 720f - 8f,
                    animationSpec = tween(durationMillis = 820, easing = FastOutSlowInEasing)
                )
                animRotX.snapTo(-8f)
            }
            val rotYJob = launch {
                animRotY.animateTo(
                    targetValue = dirY * 540f + 10f,
                    animationSpec = tween(durationMillis = 820, easing = FastOutSlowInEasing)
                )
                animRotY.snapTo(10f)
            }
            val rotZJob = launch {
                animRotZ.animateTo(
                    targetValue = dirX * 360f,
                    animationSpec = tween(durationMillis = 820, easing = FastOutSlowInEasing)
                )
                animRotZ.snapTo(0f)
            }

            // Vertical toss arc + bounce settle
            val liftJob = launch {
                animLiftPx.animateTo(liftPeak, tween(240, easing = LinearOutSlowInEasing))
                animLiftPx.animateTo(0f, spring(dampingRatio = 0.42f, stiffness = Spring.StiffnessMediumLow))
            }

            // Squash & stretch
            val scaleJob = launch {
                animScale.animateTo(0.86f, tween(110))
                animScale.animateTo(1.14f, tween(240, easing = LinearOutSlowInEasing))
                animScale.animateTo(
                    1f,
                    spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium)
                )
            }

            // Decelerating face shuffle matching spin speed
            val shuffleDelays = longArrayOf(65L, 70L, 80L, 95L, 115L, 140L, 175L)
            for (stepDelay in shuffleDelays) {
                var nextFace = Random.nextInt(1, 7)
                if (nextFace == currentNumber) nextFace = (nextFace % 6) + 1
                currentNumber = nextFace
                delay(stepDelay)
            }

            val finalResult = Random.nextInt(1, 7)
            currentNumber = finalResult

            rotXJob.join()
            rotYJob.join()
            rotZJob.join()
            liftJob.join()
            scaleJob.join()

            // Crisp landing shockwave pulse
            launch {
                animImpactRing.snapTo(0.05f)
                animImpactRing.animateTo(1f, tween(340, easing = LinearOutSlowInEasing))
                animImpactRing.snapTo(0f)
            }

            isRolling = false
            onRollFinished(finalResult)
        }
    }

    val cdDiceRoll = stringResource(R.string.cd_dice_roll)

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(size)
                .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                .semantics { contentDescription = cdDiceRoll }
                .clickable(
                    enabled = !isRolling,
                    role = Role.Button,
                    onClick = ::rollDice
                ),
            contentAlignment = Alignment.Center
        ) {
            // Landing shockwave ring (pure GPU draw)
            val ringProgress = animImpactRing.value
            if (ringProgress > 0f) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val alpha = (1f - ringProgress) * 0.55f
                    val radius = (this.size.minDimension * 0.46f) * (0.95f + ringProgress * 0.32f)
                    drawCircle(
                        color = accentColor.copy(alpha = alpha),
                        radius = radius,
                        style = Stroke(width = 3.dp.toPx() * (1f - ringProgress * 0.5f))
                    )
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxSize(0.86f)
                    .graphicsLayer {
                        cameraDistance = 14f * density
                        rotationX = animRotX.value
                        rotationY = animRotY.value
                        rotationZ = animRotZ.value
                        translationY = animLiftPx.value
                        scaleX = animScale.value
                        scaleY = animScale.value
                    }
            ) {
                NeonDiceFace(
                    number = currentNumber,
                    primaryColor = accentColor,
                    secondaryColor = NeonPrimary
                )
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

        // Dynamic ground shadow that contracts while die is airborne
        Canvas(
            modifier = Modifier.size(width = size * 0.72f, height = 12.dp)
        ) {
            val liftRatio = ((-animLiftPx.value) / (26f * density)).coerceIn(0f, 1f)
            val shadowScale = 1f - liftRatio * 0.36f
            val shadowAlpha = 0.65f - liftRatio * 0.32f
            val ovalW = this.size.width * shadowScale
            val ovalH = this.size.height * shadowScale
            drawOval(
                brush = Brush.radialGradient(
                    colors = listOf(
                        Color.Black.copy(alpha = shadowAlpha),
                        Color.Transparent
                    ),
                    center = Offset(this.size.width / 2f, this.size.height / 2f),
                    radius = (ovalW / 2f).coerceAtLeast(1f)
                ),
                topLeft = Offset((this.size.width - ovalW) / 2f, (this.size.height - ovalH) / 2f),
                size = Size(ovalW, ovalH)
            )
        }
    }
}

/**
 * Custom Canvas die face rendered using Dungeon Edition forged obsidian,
 * bevelled gold frame, and recessed gold/crimson pips (zero Paint allocations).
 */
@Composable
fun NeonDiceFace(
    number: Int,
    primaryColor: Color = NeonSecondary,
    secondaryColor: Color = NeonPrimary
) {
    Box(modifier = Modifier.fillMaxSize()) {
        Canvas(modifier = Modifier.matchParentSize()) {
            val w = size.width
            val h = size.height
            val strokeWidth = w * 0.045f
            val cornerRadius = w * 0.18f
            val inset = strokeWidth / 2f

            // Forged obsidian body with directional torchlight gradient
            drawRoundRect(
                brush = Brush.linearGradient(
                    colors = listOf(NeonSurfaceVariant, NeonSurface, NeonBackground),
                    start = Offset(0f, 0f),
                    end = Offset(w, h)
                ),
                size = size,
                cornerRadius = CornerRadius(cornerRadius, cornerRadius)
            )

            // Subtle top-left torch sheen
            drawRoundRect(
                brush = Brush.radialGradient(
                    colors = listOf(NeonGray100.copy(alpha = 0.14f), Color.Transparent),
                    center = Offset(w * 0.28f, h * 0.24f),
                    radius = w * 0.65f
                ),
                size = size,
                cornerRadius = CornerRadius(cornerRadius, cornerRadius)
            )

            // Recessed inner bevel
            val bevelInset = strokeWidth * 2.1f
            drawRoundRect(
                color = NeonGray100.copy(alpha = 0.09f),
                topLeft = Offset(bevelInset, bevelInset),
                size = Size(w - bevelInset * 2f, h - bevelInset * 2f),
                cornerRadius = CornerRadius(cornerRadius * 0.72f, cornerRadius * 0.72f),
                style = Stroke(width = strokeWidth * 0.55f)
            )

            // Ambient frame halo (GPU stroke)
            drawRoundRect(
                color = primaryColor.copy(alpha = 0.28f),
                topLeft = Offset(inset, inset),
                size = Size(w - strokeWidth, h - strokeWidth),
                cornerRadius = CornerRadius(cornerRadius, cornerRadius),
                style = Stroke(width = strokeWidth * 2.1f)
            )

            // Crisp metallic frame border
            drawRoundRect(
                color = primaryColor,
                topLeft = Offset(inset, inset),
                size = Size(w - strokeWidth, h - strokeWidth),
                cornerRadius = CornerRadius(cornerRadius, cornerRadius),
                style = Stroke(width = strokeWidth)
            )

            drawDots(
                number = number,
                aceColor = secondaryColor,
                pipColor = primaryColor
            )
        }
    }
}

private fun DrawScope.drawDots(number: Int, aceColor: Color, pipColor: Color) {
    val isAce = number == 1
    val dotRadius = size.width * (if (isAce) 0.115f else 0.084f)
    val center = size.width / 2f
    val left = size.width * 0.26f
    val right = size.width * 0.74f
    val top = size.width * 0.26f
    val bottom = size.width * 0.74f

    fun drawDot(x: Float, y: Float) {
        val coreColor = if (isAce) aceColor else pipColor
        val highlightColor = if (isAce) NeonPrimaryLight else NeonGray100

        // Deep carved pit shadow
        drawCircle(
            color = Color.Black.copy(alpha = 0.65f),
            radius = dotRadius * 1.24f,
            center = Offset(x + dotRadius * 0.08f, y + dotRadius * 0.12f)
        )
        // Subtle glow halo
        drawCircle(
            color = coreColor.copy(alpha = 0.24f),
            radius = dotRadius * 1.48f,
            center = Offset(x, y)
        )
        // Metallic pip sphere
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(highlightColor, coreColor, coreColor.copy(alpha = 0.72f)),
                center = Offset(x - dotRadius * 0.28f, y - dotRadius * 0.28f),
                radius = dotRadius * 1.25f
            ),
            radius = dotRadius,
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
