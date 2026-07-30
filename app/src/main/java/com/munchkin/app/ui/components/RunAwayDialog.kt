package com.munchkin.app.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.munchkin.app.R

@Composable
fun RunAwayDialog(
    onDismiss: () -> Unit,
    onResult: (result: Int, success: Boolean) -> Unit,
    /**
     * Net automatic modifier from the player's races: an Elf is +1, a Halfling
     * -1, and a Half-Breed holding both nets zero. The player still decides the
     * outcome at the table, so this is shown rather than enforced.
     */
    runAwayBonus: Int = 0,
    /**
     * Where [runAwayBonus] comes from, already localized and signed, e.g.
     * "+1 Elfo". Shown whenever any race modifier applies — including when they
     * cancel out, since "+1 Elfo, -1 Mediano" is exactly what the player needs to
     * see to trust the total.
     */
    runAwayBonusLabel: String? = null
) {
    var step by remember { mutableStateOf(RunAwayStep.ROLL) }
    var rollResult by remember { mutableIntStateOf(0) }

    Dialog(onDismissRequest = { if (step == RunAwayStep.ROLL) onDismiss() }) {
        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = Color(0xFF1E1E1E) // Dark background
            ),
            modifier = Modifier.padding(16.dp).width(320.dp)
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(24.dp)
            ) {
                Text(
                    text = if (step == RunAwayStep.ROLL) "¡HUIDA!" else "Confirma el Resultado",
                    color = Color.White,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(bottom = 24.dp)
                )

                if (step == RunAwayStep.ROLL) {
                    // 3D Dice Component
                    Dice3D(
                        size = 150.dp,
                        onRollFinished = { result ->
                            SoundManager.playDiceRoll()
                            rollResult = result
                            step = RunAwayStep.VERIFY
                        }
                    )

                    Spacer(modifier = Modifier.height(32.dp))

                    Text(
                        text = "¡Toca el dado para intentar huir!",
                        color = Color.Gray,
                        fontSize = 14.sp
                    )

                    // The threshold was never stated anywhere, so a player who did
                    // not already know the rule got no help from the one screen
                    // whose job is to help.
                    Text(
                        text = stringResource(R.string.run_away_threshold_hint, RUN_AWAY_THRESHOLD),
                        color = Color.Gray,
                        fontSize = 12.sp,
                        textAlign = TextAlign.Center
                    )
                    
                    Spacer(modifier = Modifier.height(16.dp))
                    
                    TextButton(onClick = onDismiss) {
                        Text("Cancelar", color = MaterialTheme.colorScheme.primary)
                    }
                } else {
                    // Verification Step
                    Text(
                        text = stringResource(R.string.run_away_result, rollResult + runAwayBonus),
                        color = MaterialTheme.colorScheme.primary,
                        fontSize = 48.sp,
                        fontWeight = FontWeight.Bold
                    )

                    if (runAwayBonusLabel != null) {
                        Text(
                            text = stringResource(
                                R.string.run_away_breakdown,
                                rollResult,
                                runAwayBonusLabel
                            ),
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.8f),
                            fontSize = 14.sp,
                            textAlign = TextAlign.Center
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))
                    
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp).fillMaxWidth(),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = "⚠️ Verifica tus modificadores",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "Ten en cuenta los objetos y/o habilidades que modifiquen el resultado base de la huida",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                    
                    Spacer(modifier = Modifier.height(24.dp))

                    // The app knows the roll and the automatic modifiers, so it can
                    // say what that total means. It stays a suggestion: cards played
                    // at the table can still change the outcome, which is why the
                    // player confirms.
                    val total = rollResult + runAwayBonus
                    Text(
                        text = if (total >= RUN_AWAY_THRESHOLD) {
                            stringResource(R.string.run_away_suggestion_escaped, total)
                        } else {
                            stringResource(R.string.run_away_suggestion_caught, total)
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (total >= RUN_AWAY_THRESHOLD) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.error
                        },
                        textAlign = TextAlign.Center
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    Text(
                        text = "¿Has conseguido huir?",
                        style = MaterialTheme.typography.titleMedium,
                        color = Color.White
                    )
                    
                    Spacer(modifier = Modifier.height(16.dp))
                    
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        Button(
                            onClick = { onResult(rollResult, false) },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.error
                            ),
                            modifier = Modifier.weight(1f).padding(end = 8.dp)
                        ) {
                            Text("ATRAPADO 💀")
                        }
                        
                        Button(
                            onClick = { onResult(rollResult, true) },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primary
                            ),
                            modifier = Modifier.weight(1f).padding(start = 8.dp)
                        ) {
                            Text("ESCAPÉ 🏃💨")
                        }
                    }
                }
            }
        }
    }
}

private enum class RunAwayStep { ROLL, VERIFY }

/** A run-away attempt succeeds on 5 or more, after race and card modifiers. */
const val RUN_AWAY_THRESHOLD = 5
