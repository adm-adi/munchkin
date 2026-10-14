package com.munchkin.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.munchkin.app.R
import com.munchkin.app.core.MonsterInstance

/**
 * Shown after losing a fight or failing to escape one.
 *
 * Includes quick actions for the two most common Bad Stuff penalties:
 * - Lose 1 Level
 * - Death (reset Gear Bonus to 0 while keeping Level, Race, and Class)
 */
@Composable
fun BadStuffDialog(
    monsters: List<MonsterInstance>,
    onDismiss: () -> Unit,
    onDecrementLevel: (() -> Unit)? = null,
    onApplyDeath: (() -> Unit)? = null
) {
    if (monsters.isEmpty()) return

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Text(text = "💀", style = MaterialTheme.typography.displaySmall) },
        title = { Text(stringResource(R.string.bad_stuff_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = stringResource(R.string.bad_stuff_intro),
                    style = MaterialTheme.typography.bodyMedium
                )
                monsters.forEach { monster ->
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(
                                text = monster.name,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = monster.badStuff.ifBlank {
                                    stringResource(R.string.bad_stuff_unknown)
                                },
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }
                if (onDecrementLevel != null || onApplyDeath != null) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (onDecrementLevel != null) {
                            OutlinedButton(
                                onClick = onDecrementLevel,
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("-1 ${stringResource(R.string.level)}", fontWeight = FontWeight.Bold)
                            }
                        }
                        if (onApplyDeath != null) {
                            OutlinedButton(
                                onClick = {
                                    onApplyDeath()
                                    onDismiss()
                                },
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.outlinedButtonColors(
                                    contentColor = MaterialTheme.colorScheme.error
                                )
                            ) {
                                Text("💀 ${stringResource(R.string.death_reset_gear)}", fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.bad_stuff_done))
            }
        }
    )
}
