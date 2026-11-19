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
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
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
import com.munchkin.app.core.GameEngine
import com.munchkin.app.ui.theme.DungeonBorder
import com.munchkin.app.ui.theme.DungeonDangerFill
import com.munchkin.app.ui.theme.DungeonSuccessFill
import com.munchkin.app.ui.theme.NeonBackground
import com.munchkin.app.ui.theme.NeonError
import com.munchkin.app.ui.theme.NeonGray100
import com.munchkin.app.ui.theme.NeonGray200
import com.munchkin.app.ui.theme.NeonGray300
import com.munchkin.app.ui.theme.NeonSecondary
import com.munchkin.app.ui.theme.NeonSuccess
import com.munchkin.app.ui.theme.NeonSurface
import com.munchkin.app.ui.theme.NeonSurfaceVariant

/**
 * Dialog for rolling to Run Away in combat using either the tactile 3D die or
 * a physical table die (1..6), with a real server-validated manual card/curse
 * modifier (-10..+10) combined with automatic race bonuses (Elf +1, Halfling -1).
 */
@Composable
fun RunAwayDialog(
    onDismiss: () -> Unit,
    onResult: (result: Int, success: Boolean, modifier: Int) -> Unit,
    runAwayBonus: Int = 0,
    runAwayBonusLabel: String? = null
) {
    var selectedDie by remember { mutableStateOf<Int?>(null) }
    var manualModifier by remember { mutableIntStateOf(0) }

    val total = selectedDie?.let { it + runAwayBonus + manualModifier }
    val escaped = total != null && total >= Abilities.RUN_AWAY_THRESHOLD
    val dieAccent = when {
        total == null -> NeonSecondary
        escaped -> NeonSuccess
        else -> NeonError
    }

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
                    .padding(20.dp)
            ) {
                Text(
                    text = stringResource(R.string.run_away_title),
                    style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Black),
                    color = NeonGray100,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(6.dp))

                Text(
                    text = stringResource(
                        R.string.run_away_threshold_hint,
                        Abilities.RUN_AWAY_THRESHOLD
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = NeonGray300,
                    textAlign = TextAlign.Center
                )

                if (runAwayBonusLabel != null) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = runAwayBonusLabel,
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                        color = NeonSecondary,
                        textAlign = TextAlign.Center
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                Dice3D(
                    size = 132.dp,
                    externalNumber = selectedDie,
                    accentColor = dieAccent,
                    onRollFinished = { result ->
                        selectedDie = result
                    }
                )

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = stringResource(R.string.run_away_tap_die),
                    style = MaterialTheme.typography.bodySmall,
                    color = NeonGray200,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(14.dp))
                HorizontalDivider(color = DungeonBorder, thickness = 1.dp)
                Spacer(modifier = Modifier.height(12.dp))

                // Real manual Run Away modifier stepper (-10..+10) for cards/curses
                val modShape = RoundedCornerShape(12.dp)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(modShape)
                        .background(NeonSurfaceVariant)
                        .border(1.dp, DungeonBorder, modShape)
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                        Text(
                            text = stringResource(R.string.run_away_manual_modifier),
                            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                            color = NeonGray100
                        )
                        Text(
                            text = stringResource(R.string.run_away_manual_modifier_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = NeonGray300
                        )
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        FilledTonalIconButton(
                            onClick = {
                                manualModifier = (manualModifier - 1).coerceAtLeast(GameEngine.RUN_AWAY_MODIFIER_MIN)
                            },
                            enabled = manualModifier > GameEngine.RUN_AWAY_MODIFIER_MIN,
                            modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp),
                            colors = IconButtonDefaults.filledTonalIconButtonColors(
                                containerColor = NeonSurface,
                                contentColor = NeonGray100
                            )
                        ) {
                            Icon(
                                imageVector = Icons.Default.Remove,
                                contentDescription = stringResource(R.string.cd_decrement_modifier)
                            )
                        }

                        Text(
                            text = if (manualModifier > 0) "+$manualModifier" else manualModifier.toString(),
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontWeight = FontWeight.Black,
                                fontFeatureSettings = "tnum"
                            ),
                            color = NeonSecondary,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.widthIn(min = 34.dp)
                        )

                        FilledTonalIconButton(
                            onClick = {
                                manualModifier = (manualModifier + 1).coerceAtMost(GameEngine.RUN_AWAY_MODIFIER_MAX)
                            },
                            enabled = manualModifier < GameEngine.RUN_AWAY_MODIFIER_MAX,
                            modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp),
                            colors = IconButtonDefaults.filledTonalIconButtonColors(
                                containerColor = NeonSurface,
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

                Spacer(modifier = Modifier.height(12.dp))

                Text(
                    text = stringResource(R.string.run_away_physical_die),
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = NeonGray200,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(8.dp))

                listOf(listOf(1, 2, 3), listOf(4, 5, 6)).forEach { rowValues ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 3.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        rowValues.forEach { die ->
                            val isSelected = selectedDie == die
                            val dieCd = stringResource(R.string.cd_physical_die_value, die)
                            FilledTonalButton(
                                onClick = { selectedDie = die },
                                modifier = Modifier
                                    .weight(1f)
                                    .heightIn(min = 48.dp)
                                    .semantics { contentDescription = dieCd },
                                shape = RoundedCornerShape(12.dp),
                                colors = ButtonDefaults.filledTonalButtonColors(
                                    containerColor = if (isSelected) NeonSecondary else NeonSurfaceVariant,
                                    contentColor = if (isSelected) NeonBackground else NeonGray100
                                ),
                                border = BorderStroke(1.dp, if (isSelected) NeonSecondary else DungeonBorder),
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

                if (selectedDie != null && total != null) {
                    Spacer(modifier = Modifier.height(14.dp))

                    val summaryShape = RoundedCornerShape(12.dp)
                    val breakdownParts = buildList {
                        if (runAwayBonusLabel != null) add(runAwayBonusLabel)
                        if (manualModifier != 0) {
                            val sign = if (manualModifier > 0) "+" else ""
                            add("$sign$manualModifier ${stringResource(R.string.run_away_manual_mod_short)}")
                        }
                    }

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(summaryShape)
                            .background(NeonSurfaceVariant)
                            .border(1.dp, if (escaped) NeonSuccess else NeonError, summaryShape)
                            .padding(12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.run_away_result, total),
                            style = MaterialTheme.typography.titleLarge.copy(
                                fontWeight = FontWeight.Black,
                                fontFeatureSettings = "tnum"
                            ),
                            color = NeonSecondary,
                            textAlign = TextAlign.Center
                        )

                        if (breakdownParts.isNotEmpty()) {
                            Text(
                                text = stringResource(
                                    R.string.run_away_breakdown,
                                    selectedDie!!,
                                    breakdownParts.joinToString(" · ")
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = NeonGray300,
                                textAlign = TextAlign.Center
                            )
                        }

                        Text(
                            text = if (escaped) {
                                stringResource(R.string.run_away_suggestion_escaped, total)
                            } else {
                                stringResource(R.string.run_away_suggestion_caught, total)
                            },
                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                            color = if (escaped) NeonSuccess else NeonError,
                            textAlign = TextAlign.Center
                        )
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    Button(
                        onClick = { onResult(selectedDie!!, escaped, manualModifier) },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (escaped) DungeonSuccessFill else DungeonDangerFill,
                            contentColor = NeonGray100
                        ),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 50.dp)
                    ) {
                        Text(
                            text = if (escaped) {
                                stringResource(R.string.run_away_escaped)
                            } else {
                                stringResource(R.string.run_away_caught)
                            },
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

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
            }
        }
    }
}
