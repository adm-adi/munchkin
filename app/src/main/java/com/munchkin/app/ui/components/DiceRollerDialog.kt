package com.munchkin.app.ui.components
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog

@Composable
fun DiceRollerDialog(
    onDismiss: () -> Unit,
    showModifier: Boolean = false,
    onRollComplete: ((Int, Int) -> Unit)? = null // roll, modifier
) {
    var modifier by remember { mutableIntStateOf(0) }

    Dialog(onDismissRequest = onDismiss) {
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
                    text = if (showModifier) "Huida (Modificador)" else "Lanzar Dado",
                    color = Color.White,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(bottom = 24.dp)
                )

                // Modifier UI
                if (showModifier) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center,
                        modifier = Modifier.padding(bottom = 16.dp)
                    ) {
                        OutlinedButton(onClick = { modifier-- }) {
                            Text("-", style = MaterialTheme.typography.titleLarge)
                        }
                        Text(
                            text = (if (modifier > 0) "+" else "") + modifier.toString(),
                            color = Color.White,
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 16.dp)
                        )
                        OutlinedButton(onClick = { modifier++ }) {
                            Text("+", style = MaterialTheme.typography.titleLarge)
                        }
                    }
                }

                // 3D Dice Component
                Dice3D(
                    size = 150.dp,
                    onRollFinished = { result ->
                        SoundManager.playDiceRoll()
                        onRollComplete?.invoke(result, modifier)
                    }
                )

                Spacer(modifier = Modifier.height(32.dp))

                Text(
                    text = "¡Toca el dado para lanzar!",
                    color = Color.Gray,
                    fontSize = 14.sp
                )
                
                Spacer(modifier = Modifier.height(16.dp))

                Button(
                    onClick = onDismiss,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                ) {
                    Text("Cerrar")
                }
            }
        }
    }
}
