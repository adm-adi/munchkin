package com.munchkin.app.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.PaintingStyle
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.random.Random

@Composable
fun Dice3D(
    modifier: Modifier = Modifier,
    size: Dp = 100.dp,
    onRollFinished: (Int) -> Unit = {}
) {
    val scope = rememberCoroutineScope()
    var isRolling by remember { mutableStateOf(false) }
    var currentNumber by remember { mutableIntStateOf(1) }
    
    // Animation States
    val animRotation = remember { Animatable(0f) }
    val animScale = remember { Animatable(1f) }
    
    fun rollDice() {
        if (isRolling) return
        isRolling = true
        
        scope.launch {
            // Animation sequence
            launch {
                animRotation.animateTo(
                    targetValue = 360f * 2,
                    animationSpec = tween(1000, easing = FastOutSlowInEasing)
                )
                animRotation.snapTo(0f)
            }
            launch {
                animScale.animateTo(
                    targetValue = 0.8f,
                    animationSpec = tween(200)
                )
                animScale.animateTo(
                    targetValue = 1.2f,
                    animationSpec = spring(dampingRatio = Spring.DampingRatioHighBouncy)
                )
                animScale.animateTo(1f)
            }
            
            // Rapid number changing
            val rollTime = 1000L
            val startTime = System.currentTimeMillis()
            var finalResult = 1
            
            while (System.currentTimeMillis() - startTime < rollTime) {
                currentNumber = Random.nextInt(1, 7)
                delay(80) 
            }
            
            // Final result
            finalResult = Random.nextInt(1, 7)
            currentNumber = finalResult
            isRolling = false
            onRollFinished(finalResult)
        }
    }

    Box(
        modifier = modifier
            .size(size)
            .graphicsLayer {
                rotationZ = animRotation.value
                scaleX = animScale.value
                scaleY = animScale.value
            }
            .clickable(enabled = !isRolling) { rollDice() },
        contentAlignment = Alignment.Center
    ) {
        NeonDiceFace(number = currentNumber)
    }
}

@Composable
fun NeonDiceFace(
    number: Int,
    primaryColor: Color = Color(0xFF00E5FF), // Neon Cyan
    secondaryColor: Color = Color(0xFFD500F9) // Neon Purple
) {
    Box(modifier = Modifier.fillMaxSize()) {
        Canvas(modifier = Modifier.matchParentSize()) {
            val strokeWidth = size.width * 0.05f
            val cornerRadius = size.width * 0.15f
            
            // 1. Glow Effect (Shadow)
            val paint = Paint().apply {
                this.color = primaryColor
                this.style = PaintingStyle.Stroke
                this.strokeWidth = strokeWidth
                
                val frameworkPaint = this.asFrameworkPaint()
                frameworkPaint.color = primaryColor.toArgb()
                frameworkPaint.setShadowLayer(
                    size.width * 0.1f, // Radius
                    0f, 0f, 
                    primaryColor.toArgb()
                )
            }
            
            // 2. Draw Box background
            drawRoundRect(
                color = Color(0xFF121212), // Dark background
                size = size,
                cornerRadius = CornerRadius(cornerRadius, cornerRadius)
            )
            
            // 3. Draw Border with Glow
            drawContext.canvas.drawRoundRect(
                left = 0f + strokeWidth/2,
                top = 0f + strokeWidth/2,
                right = size.width - strokeWidth/2,
                bottom = size.height - strokeWidth/2,
                radiusX = cornerRadius,
                radiusY = cornerRadius,
                paint = paint
            )

            // 4. Draw Dots
            drawDots(number, primaryColor, secondaryColor)
        }
    }
}

private fun DrawScope.drawDots(number: Int, color: Color, centerColor: Color) {
    val dotRadius = size.width * 0.08f
    val center = size.width / 2
    val left = size.width * 0.25f
    val right = size.width * 0.75f
    val top = size.width * 0.25f
    val bottom = size.width * 0.75f

    fun drawDot(x: Float, y: Float) {
        drawCircle(
            color = color,
            radius = dotRadius,
            center = Offset(x, y)
        )
        // Inner white/center core
        drawCircle(
            color = Color.White.copy(alpha = 0.8f),
            radius = dotRadius * 0.4f,
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
