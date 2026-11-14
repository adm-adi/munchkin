package com.munchkin.app.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.munchkin.app.R
import com.munchkin.app.core.Abilities
import com.munchkin.app.ui.theme.DungeonBorder
import com.munchkin.app.ui.theme.DungeonDangerFill
import com.munchkin.app.ui.theme.DungeonSuccessFill
import com.munchkin.app.ui.theme.NeonError
import com.munchkin.app.ui.theme.NeonGray100
import com.munchkin.app.ui.theme.NeonGray200
import com.munchkin.app.ui.theme.NeonGray300
import com.munchkin.app.ui.theme.NeonPrimaryLight
import com.munchkin.app.ui.theme.NeonSecondary
import com.munchkin.app.ui.theme.NeonSuccess
import com.munchkin.app.ui.theme.NeonSurface
import com.munchkin.app.ui.theme.NeonSurfaceVariant
import com.munchkin.app.ui.theme.NeonWarning

/**
 * Dialog for rolling to Run Away in combat, either via the virtual 3D die
 * (followed by a modifier verification step) or directly logging a physical die 1..6.
 */
@Composable
fun RunAwayDialog(
    onDismiss: () -> Unit,
    onResult: (result: Int, success: Boolean) -> Unit,
    runAwayBonus: Int = 0,
    runAwayBonusLabel: String? = null
) {
    var step by remember { mutableStateOf(RunAwayStep.ROLL) }
    var rollResult by remember { mutableIntStateOf(0) }

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
                    text = if (step == RunAwayStep.ROLL) {
                        stringResource(R.string.run_away_title)
                    } else {
                        stringResource(R.string.run_away_confirm_title)
                    },
                    style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Black),
                    color = NeonGray100,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(bottom = 20.dp)
                )

                if (step == RunAwayStep.ROLL) {
                    Dice3D(
                        size = 144.dp,
                        onRollFinished = { result ->
                            rollResult = result
                            step = RunAwayStep.VERIFY
                        }
                    )

                    Spacer(modifier = Modifier.height(20.dp))

                    Text(
                        text = stringResource(R.string.run_away_tap_die),
                        style = MaterialTheme.typography.bodyMedium,
                        color = NeonGray200,
                        textAlign = TextAlign.Center
                    )

                    Spacer(modifier = Modifier.height(4.dp))

                    Text(
                        text = stringResource(
                            R.string.run_away_threshold_hint,
                            Abilities.RUN_AWAY_THRESHOLD
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = NeonGray300,
                        textAlign = TextAlign.Center
                    )

                    Spacer(modifier = Modifier.height(18.dp))
                    HorizontalDivider(color = DungeonBorder, thickness = 1.dp)
                    Spacer(modifier = Modifier.height(14.dp))

                    Text(
                        text = stringResource(R.string.run_away_physical_die),
                        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                        color = NeonGray200,
                        textAlign = TextAlign.Center
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    listOf(listOf(1, 2, 3), listOf(4, 5, 6)).forEach { rowValues ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 3.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            rowValues.forEach { die ->
                                val dieCd = stringResource(R.string.cd_physical_die_value, die)
                                FilledTonalButton(
                                    onClick = {
                                        onResult(
                                            die,
                                            (die + runAwayBonus) >= Abilities.RUN_AWAY_THRESHOLD
                                        )
                                    },
                                    modifier = Modifier
                                        .weight(1f)
                                        .heightIn(min = 48.dp)
                                        .semantics { contentDescription = dieCd },
                                    shape = RoundedCornerShape(12.dp),
                                    colors = ButtonDefaults.filledTonalButtonColors(
                                        containerColor = NeonSurfaceVariant,
                                        contentColor = NeonGray100
                                    ),
                                    border = BorderStroke(1.dp, DungeonBorder),
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 10.dp)
                                ) {
                                    Text(
                                        text = die.toString(),
                                        style = MaterialTheme.typography.titleMedium.copy(
                                            fontWeight = FontWeight.Bold,
                                            fontFeatureSettings = "tnum"
                                        )
                                    )
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    TextButton(
                        onClick = onDismiss,
                        modifier = Modifier.heightIn(min = 48.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.cancel),
                            color = NeonGray300,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                } else {
                    val total = rollResult + runAwayBonus

                    Text(
                        text = stringResource(R.string.run_away_result, total),
                        style = MaterialTheme.typography.headlineMedium.copy(
                            fontWeight = FontWeight.Black,
                            fontFeatureSettings = "tnum"
                        ),
                        color = NeonSecondary,
                        textAlign = TextAlign.Center
                    )

                    if (runAwayBonusLabel != null) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = stringResource(
                                R.string.run_away_breakdown,
                                rollResult,
                                runAwayBonusLabel
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = NeonGray300,
                            textAlign = TextAlign.Center
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // Flat inset callout (avoiding nested Card-inside-Card)
                    val calloutShape = RoundedCornerShape(12.dp)
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(calloutShape)
                            .background(NeonSurfaceVariant)
                            .border(1.dp, DungeonBorder, calloutShape)
                            .padding(12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.run_away_check_modifiers_title),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = NeonWarning,
                            textAlign = TextAlign.Center
                        )
                        Text(
                            text = stringResource(R.string.run_away_check_modifiers_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = NeonGray200,
                            textAlign = TextAlign.Center
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    val autoEscaped = total >= Abilities.RUN_AWAY_THRESHOLD
                    Text(
                        text = if (autoEscaped) {
                            stringResource(R.string.run_away_suggestion_escaped, total)
                        } else {
                            stringResource(R.string.run_away_suggestion_caught, total)
                        },
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = if (autoEscaped) NeonSuccess else NeonError,
                        textAlign = TextAlign.Center
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    Text(
                        text = stringResource(R.string.run_away_did_escape),
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = NeonGray100,
                        textAlign = TextAlign.Center
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Button(
                            onClick = { onResult(rollResult, false) },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = DungeonDangerFill,
                                contentColor = NeonGray100
                            ),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = 48.dp)
                        ) {
                            Text(
                                text = stringResource(R.string.run_away_caught),
                                fontWeight = FontWeight.Bold,
                                maxLines = 1
                            )
                        }

                        Button(
                            onClick = { onResult(rollResult, true) },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = DungeonSuccessFill,
                                contentColor = NeonGray100
                            ),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = 48.dp)
                        ) {
                            Text(
                                text = stringResource(R.string.run_away_escaped),
                                fontWeight = FontWeight.Bold,
                                maxLines = 1
                            )
                        }
                    }
                }
            }
        }
    }
}

private enum class RunAwayStep { ROLL, VERIFY }
