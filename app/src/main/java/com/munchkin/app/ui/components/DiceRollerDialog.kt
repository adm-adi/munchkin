package com.munchkin.app.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.munchkin.app.R
import com.munchkin.app.ui.theme.DungeonBorder
import com.munchkin.app.ui.theme.NeonGray100
import com.munchkin.app.ui.theme.NeonGray300
import com.munchkin.app.ui.theme.NeonPrimary
import com.munchkin.app.ui.theme.NeonSurface
import com.munchkin.app.ui.theme.NeonSurfaceVariant

/**
 * Modal 3D die roller dialog with optional run-away modifier controls.
 */
@Composable
fun DiceRollerDialog(
    onDismiss: () -> Unit,
    showModifier: Boolean = false,
    onRollComplete: ((Int, Int) -> Unit)? = null
) {
    var diceModifier by remember { mutableIntStateOf(0) }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = NeonSurface,
            border = BorderStroke(1.dp, DungeonBorder),
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp)
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(24.dp)
            ) {
                Text(
                    text = if (showModifier) {
                        stringResource(R.string.dice_run_away_modifier)
                    } else {
                        stringResource(R.string.dice_roll_title)
                    },
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                    color = NeonGray100,
                    modifier = Modifier.padding(bottom = 20.dp)
                )

                if (showModifier) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        modifier = Modifier.padding(bottom = 20.dp)
                    ) {
                        FilledTonalIconButton(
                            onClick = { diceModifier-- },
                            modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp),
                            colors = IconButtonDefaults.filledTonalIconButtonColors(
                                containerColor = NeonSurfaceVariant,
                                contentColor = NeonGray100
                            )
                        ) {
                            Icon(
                                imageVector = Icons.Default.Remove,
                                contentDescription = stringResource(R.string.cd_decrement_modifier)
                            )
                        }

                        Text(
                            text = if (diceModifier > 0) "+$diceModifier" else diceModifier.toString(),
                            style = MaterialTheme.typography.headlineSmall.copy(
                                fontWeight = FontWeight.Bold,
                                fontFeatureSettings = "tnum"
                            ),
                            color = NeonGray100
                        )

                        FilledTonalIconButton(
                            onClick = { diceModifier++ },
                            modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp),
                            colors = IconButtonDefaults.filledTonalIconButtonColors(
                                containerColor = NeonSurfaceVariant,
                                contentColor = NeonGray100
                            )
                        ) {
                            Icon(
                                imageVector = Icons.Default.Add,
                                contentDescription = stringResource(R.string.cd_increment_modifier)
                            )
                        }
                    }
                }

                Dice3D(
                    size = 150.dp,
                    onRollFinished = { result ->
                        onRollComplete?.invoke(result, diceModifier)
                    }
                )

                Spacer(modifier = Modifier.height(28.dp))

                Text(
                    text = stringResource(R.string.dice_tap_to_roll),
                    style = MaterialTheme.typography.bodyMedium,
                    color = NeonGray300
                )

                Spacer(modifier = Modifier.height(20.dp))

                Button(
                    onClick = onDismiss,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = NeonPrimary,
                        contentColor = NeonGray100
                    )
                ) {
                    Text(
                        text = stringResource(R.string.close),
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}
