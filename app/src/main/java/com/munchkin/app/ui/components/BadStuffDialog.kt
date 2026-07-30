package com.munchkin.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
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
 * The app used to dock exactly one level for a failed escape, which is not a rule
 * Munchkin has: the consequence is the monster's own Bad Stuff, and only the card
 * knows what that is. So the app names the monsters and steps back — the same
 * split the ability reminders already use between what it can apply and what the
 * table has to resolve.
 */
@Composable
fun BadStuffDialog(
    monsters: List<MonsterInstance>,
    onDismiss: () -> Unit
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
                                // Most monsters reach a combat without their Bad
                                // Stuff text (typed by hand, or from a catalog
                                // entry that has none), so say so rather than
                                // showing an empty card.
                                text = monster.badStuff.ifBlank {
                                    stringResource(R.string.bad_stuff_unknown)
                                },
                                style = MaterialTheme.typography.bodySmall
                            )
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
