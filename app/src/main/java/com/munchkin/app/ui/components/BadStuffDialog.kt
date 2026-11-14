package com.munchkin.app.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.munchkin.app.R
import com.munchkin.app.core.MonsterInstance
import com.munchkin.app.ui.theme.DungeonBorder
import com.munchkin.app.ui.theme.NeonError
import com.munchkin.app.ui.theme.NeonGray100
import com.munchkin.app.ui.theme.NeonGray200
import com.munchkin.app.ui.theme.NeonGray300
import com.munchkin.app.ui.theme.NeonPrimaryLight
import com.munchkin.app.ui.theme.NeonSurface
import com.munchkin.app.ui.theme.NeonSurfaceVariant

/**
 * Dialog reminding the player to apply Bad Stuff from monsters they failed to
 * defeat or flee from.
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
        containerColor = NeonSurface,
        titleContentColor = NeonGray100,
        textContentColor = NeonGray200,
        icon = {
            Text(
                text = "💀",
                style = MaterialTheme.typography.displaySmall
            )
        },
        title = {
            Text(
                text = stringResource(R.string.bad_stuff_title),
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = stringResource(R.string.bad_stuff_intro),
                    style = MaterialTheme.typography.bodyMedium,
                    color = NeonGray200
                )

                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = NeonSurfaceVariant,
                    border = BorderStroke(1.dp, DungeonBorder),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        monsters.forEachIndexed { index, monster ->
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 14.dp, vertical = 12.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Text(
                                    text = monster.name,
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = NeonGray100
                                )
                                Text(
                                    text = monster.badStuff.ifBlank {
                                        stringResource(R.string.bad_stuff_unknown)
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = NeonGray300
                                )
                            }
                            if (index < monsters.lastIndex) {
                                HorizontalDivider(color = DungeonBorder, thickness = 1.dp)
                            }
                        }
                    }
                }

                if (onDecrementLevel != null || onApplyDeath != null) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (onDecrementLevel != null) {
                            OutlinedButton(
                                onClick = onDecrementLevel,
                                modifier = Modifier
                                    .weight(1f)
                                    .heightIn(min = 48.dp),
                                border = BorderStroke(1.dp, DungeonBorder),
                                colors = ButtonDefaults.outlinedButtonColors(
                                    contentColor = NeonGray100
                                )
                            ) {
                                Text(
                                    text = "-1 ${stringResource(R.string.level)}",
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                        if (onApplyDeath != null) {
                            OutlinedButton(
                                onClick = {
                                    onApplyDeath()
                                    onDismiss()
                                },
                                modifier = Modifier
                                    .weight(1f)
                                    .heightIn(min = 48.dp),
                                border = BorderStroke(1.dp, NeonError.copy(alpha = 0.6f)),
                                colors = ButtonDefaults.outlinedButtonColors(
                                    contentColor = NeonError
                                )
                            ) {
                                Text(
                                    text = "💀 ${stringResource(R.string.death_reset_gear)}",
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.heightIn(min = 48.dp)
            ) {
                Text(
                    text = stringResource(R.string.bad_stuff_done),
                    color = NeonPrimaryLight,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    )
}
