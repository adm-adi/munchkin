package com.munchkin.app.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.munchkin.app.R
import com.munchkin.app.ui.theme.GlassBase
import com.munchkin.app.ui.theme.NeonError
import com.munchkin.app.ui.theme.NeonGray100
import com.munchkin.app.ui.theme.NeonGray300
import com.munchkin.app.ui.theme.NeonGray500
import com.munchkin.app.ui.theme.NeonPrimary
import com.munchkin.app.ui.theme.NeonWarning
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlin.math.ceil

private const val LOW_TIME_WARNING_SECONDS = 10

@Composable
fun TurnStatusBanner(
    turnPlayerName: String,
    isMyTurn: Boolean,
    timerSeconds: Int,
    turnEndsAt: Long?,
    turnKey: Any?,
    modifier: Modifier = Modifier
) {
    val remainingSeconds = rememberTurnRemainingSeconds(timerSeconds, turnEndsAt, turnKey)
    val isLowTime = timerSeconds > 0 && remainingSeconds in 1..LOW_TIME_WARNING_SECONDS
    val progress = if (timerSeconds > 0) {
        (remainingSeconds.toFloat() / timerSeconds.toFloat()).coerceIn(0f, 1f)
    } else {
        0f
    }

    val pulseAlpha = if (isLowTime) {
        val transition = rememberInfiniteTransition(label = "turnTimerWarningPulse")
        transition.animateFloat(
            initialValue = 0.10f,
            targetValue = 0.28f,
            animationSpec = infiniteRepeatable(
                animation = tween(520, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "turnTimerWarningAlpha"
        ).value
    } else {
        0f
    }

    var warningPlayed by remember(turnKey, turnEndsAt) { mutableStateOf(false) }
    LaunchedEffect(isMyTurn, remainingSeconds, timerSeconds, turnKey, turnEndsAt) {
        if (
            isMyTurn &&
            timerSeconds > 0 &&
            remainingSeconds in 1..LOW_TIME_WARNING_SECONDS &&
            !warningPlayed
        ) {
            warningPlayed = true
            SoundManager.playTurnWarning()
        }
    }

    val turnAccent = when {
        isLowTime -> NeonError
        isMyTurn -> NeonPrimary
        else -> NeonGray500
    }
    val timerColor = when {
        isLowTime -> NeonError
        timerSeconds > 0 && remainingSeconds <= 30 -> NeonWarning
        else -> NeonPrimary
    }

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(
                when {
                    isLowTime -> NeonError.copy(alpha = pulseAlpha)
                    isMyTurn -> NeonPrimary.copy(alpha = 0.09f)
                    else -> GlassBase
                }
            )
            .border(
                width = if (isMyTurn || isLowTime) 1.5.dp else 1.dp,
                brush = Brush.linearGradient(
                    listOf(
                        turnAccent.copy(alpha = if (isMyTurn || isLowTime) 0.75f else 0.25f),
                        Color.Transparent
                    )
                ),
                shape = RoundedCornerShape(16.dp)
            )
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.Person,
                        contentDescription = null,
                        tint = turnAccent,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (isMyTurn) {
                            stringResource(R.string.your_turn)
                        } else {
                            stringResource(R.string.player_turn, turnPlayerName)
                        },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = if (isMyTurn) FontWeight.Bold else FontWeight.Normal,
                        color = if (isMyTurn) NeonGray100 else NeonGray300,
                        maxLines = 1
                    )
                }

                if (timerSeconds > 0) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.Timer,
                            contentDescription = null,
                            tint = timerColor,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = formatTurnTime(remainingSeconds),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = timerColor
                        )
                    }
                }
            }

            if (timerSeconds > 0) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(3.dp)
                        .background(NeonGray500.copy(alpha = 0.20f))
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(progress)
                            .height(3.dp)
                            .background(timerColor.copy(alpha = 0.85f))
                    )
                }
            }
        }
    }
}

@Composable
private fun rememberTurnRemainingSeconds(
    timerSeconds: Int,
    turnEndsAt: Long?,
    turnKey: Any?
): Int {
    val fallbackStart = remember(turnKey, turnEndsAt, timerSeconds) { System.currentTimeMillis() }
    var now by remember(turnKey, turnEndsAt, timerSeconds) {
        mutableLongStateOf(System.currentTimeMillis())
    }

    LaunchedEffect(turnKey, turnEndsAt, timerSeconds) {
        if (timerSeconds <= 0) return@LaunchedEffect
        while (isActive) {
            now = System.currentTimeMillis()
            val deadline = turnEndsAt ?: fallbackStart + timerSeconds * 1000L
            if (now >= deadline) break
            delay(250L)
        }
    }

    if (timerSeconds <= 0) return 0
    val deadline = turnEndsAt ?: fallbackStart + timerSeconds * 1000L
    return ceil((deadline - now).coerceAtLeast(0L) / 1000.0)
        .toInt()
        .coerceIn(0, timerSeconds)
}

private fun formatTurnTime(seconds: Int): String {
    val safeSeconds = seconds.coerceAtLeast(0)
    return "%d:%02d".format(safeSeconds / 60, safeSeconds % 60)
}
