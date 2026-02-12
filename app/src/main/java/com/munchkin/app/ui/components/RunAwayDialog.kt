package com.munchkin.app.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog

@Composable
fun RunAwayDialog(
    onDismiss: () -> Unit,
    onResult: (result: Int, success: Boolean) -> Unit
) {
    var step by remember { mutableStateOf(RunAwayStep.ROLL) }
    var rollResult by remember { mutableIntStateOf(0) }

    Dialog(onDismissRequest = { if (step == RunAwayStep.ROLL) onDismiss() }) {
        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = Color(0xFF1E1E1E) // Dark background
            ),
            modifier = Modifier.padding(16.dp).fillMaxWidth()
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
                    
                    Spacer(modifier = Modifier.height(16.dp))
                    
                    TextButton(onClick = onDismiss) {
                        Text("Cancelar", color = MaterialTheme.colorScheme.primary)
                    }
                } else {
                    // Verification Step
                    Text(
                        text = "Resultado: $rollResult",
                        color = MaterialTheme.colorScheme.primary,
                        fontSize = 48.sp,
                        fontWeight = FontWeight.Bold
                    )
                    
                    Spacer(modifier = Modifier.height(16.dp))
                    
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(
                                text = "⚠️ Verifica tus modificadores",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "El resultado base es $rollResult. Si tienes objetos o habilidades que bonifiquen la huida, súmalos mentalmente.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    
                    Spacer(modifier = Modifier.height(24.dp))
                    
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
