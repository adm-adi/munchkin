package com.munchkin.app.ui.screens

import androidx.compose.animation.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.munchkin.app.R
import com.munchkin.app.core.*
import com.munchkin.app.ui.components.CounterButton
import com.munchkin.app.ui.components.QuickModifierButtons
import com.munchkin.app.ui.components.TraitChip
import com.munchkin.app.ui.components.labelRes

/**
 * Player detail screen for editing own stats.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerDetailScreen(
    player: PlayerState,
    onIncrementLevel: () -> Unit,
    onDecrementLevel: () -> Unit,
    onModifyGear: (Int) -> Unit,
    onApplyDeath: (() -> Unit)? = null,
    onSetClass: (CharacterClass) -> Unit = {},
    onSetRace: (CharacterRace) -> Unit = {},
    onSetSecondaryClass: (CharacterClass) -> Unit = {},
    onSetSecondaryRace: (CharacterRace) -> Unit = {},
    onSetSuperMunchkin: (Boolean) -> Unit = {},
    onSetHalfBreed: (Boolean) -> Unit = {},
    onBack: () -> Unit,
    isReadOnly: Boolean = false,
    maxLevel: Int = 10,
    modifier: Modifier = Modifier
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var showDeathConfirm by remember { mutableStateOf(false) }

    if (showDeathConfirm && onApplyDeath != null) {
        AlertDialog(
            onDismissRequest = { showDeathConfirm = false },
            title = { Text(stringResource(R.string.death_confirm_title)) },
            text = { Text(stringResource(R.string.death_confirm_message)) },
            confirmButton = {
                Button(
                    onClick = {
                        showDeathConfirm = false
                        onApplyDeath()
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError
                    )
                ) {
                    Text(stringResource(R.string.confirm_action))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeathConfirm = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(if (isReadOnly) "${stringResource(R.string.player_details)}: ${player.name}" else stringResource(R.string.edit_player)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 24.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(16.dp))
            
            // Level counter
            CounterButton(
                value = player.level,
                label = stringResource(R.string.level),
                onIncrement = onIncrementLevel,
                onDecrement = onDecrementLevel,
                minValue = 1,
                maxValue = maxLevel,
                enabled = !isReadOnly
            )
            
            Spacer(modifier = Modifier.height(32.dp))
            
            // Gear counter
            CounterButton(
                value = player.gearBonus,
                label = stringResource(R.string.gear),
                onIncrement = { onModifyGear(1) },
                onDecrement = { onModifyGear(-1) },
                showSign = true,
                enabled = !isReadOnly
            )
            
            if (!isReadOnly) {
                Spacer(modifier = Modifier.height(16.dp))
                QuickModifierButtons(onModify = onModifyGear)

                if (onApplyDeath != null) {
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedButton(
                        onClick = { showDeathConfirm = true },
                        enabled = player.gearBonus != 0,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.error
                        )
                    ) {
                        Text("💀 ${stringResource(R.string.death_reset_gear)}")
                    }
                }
            }
            
            Spacer(modifier = Modifier.height(32.dp))
            
            // Combat power display
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.power),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        text = "${player.combatPower}",
                        style = MaterialTheme.typography.headlineMedium
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(32.dp))
            
            if (!isReadOnly) {
                // Class Selector
                Text(text = stringResource(R.string.label_class), style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.height(8.dp))
                AppDropdown(
                    options = CharacterClass.values().toList(),
                    selectedOption = player.characterClass,
                    onOptionSelected = onSetClass,
                    labelMapper = { context.getString(it.labelRes()) }
                )

                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = stringResource(R.string.super_munchkin_two_classes),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f)
                    )
                    Switch(
                        checked = player.hasSuperMunchkin,
                        onCheckedChange = onSetSuperMunchkin
                    )
                }

                if (player.hasSuperMunchkin) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(text = stringResource(R.string.label_secondary_class), style = MaterialTheme.typography.labelLarge)
                    Spacer(modifier = Modifier.height(4.dp))
                    AppDropdown(
                        options = CharacterClass.values().toList(),
                        selectedOption = player.secondaryClass,
                        onOptionSelected = onSetSecondaryClass,
                        labelMapper = { context.getString(it.labelRes()) }
                    )
                }

                Spacer(modifier = Modifier.height(24.dp))

                // Race Selector
                Text(text = stringResource(R.string.label_race), style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.height(8.dp))
                AppDropdown(
                    options = CharacterRace.values().toList(),
                    selectedOption = player.characterRace,
                    onOptionSelected = onSetRace,
                    labelMapper = { context.getString(it.labelRes()) }
                )

                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = stringResource(R.string.half_breed_two_races),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f)
                    )
                    Switch(
                        checked = player.hasHalfBreed,
                        onCheckedChange = onSetHalfBreed
                    )
                }

                if (player.hasHalfBreed) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(text = stringResource(R.string.label_secondary_race), style = MaterialTheme.typography.labelLarge)
                    Spacer(modifier = Modifier.height(4.dp))
                    AppDropdown(
                        options = CharacterRace.values().toList(),
                        selectedOption = player.secondaryRace,
                        onOptionSelected = onSetSecondaryRace,
                        labelMapper = { context.getString(it.labelRes()) }
                    )
                }
            } else {
                // Read-only view of player's Classes and Races
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    ),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        val classesText = buildList {
                            add(context.getString(player.characterClass.labelRes()))
                            if (player.hasSuperMunchkin && player.secondaryClass != CharacterClass.NONE) {
                                add(context.getString(player.secondaryClass.labelRes()))
                            }
                        }.joinToString(" + ")

                        val racesText = buildList {
                            add(context.getString(player.characterRace.labelRes()))
                            if (player.hasHalfBreed && player.secondaryRace != CharacterRace.HUMAN) {
                                add(context.getString(player.secondaryRace.labelRes()))
                            }
                        }.joinToString(" + ")

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = stringResource(R.string.classes),
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = classesText + if (player.hasSuperMunchkin) " (${stringResource(R.string.super_munchkin)})" else "",
                                style = MaterialTheme.typography.bodyLarge
                            )
                        }
                        HorizontalDivider()
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = stringResource(R.string.races),
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = racesText + if (player.hasHalfBreed) " (${stringResource(R.string.half_breed)})" else "",
                                style = MaterialTheme.typography.bodyLarge
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Same reminders as in combat: shown for both owner and read-only inspection
            AbilityReminders(listOf(player))

            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

@Composable
fun <T> AppDropdown(
    options: List<T>,
    selectedOption: T,
    onOptionSelected: (T) -> Unit,
    labelMapper: (T) -> String
) {
    var expanded by remember { mutableStateOf(false) }
    
    Box(modifier = Modifier.fillMaxWidth()) {
        OutlinedButton(
            onClick = { expanded = true },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(labelMapper(selectedOption))
            Spacer(modifier = Modifier.weight(1f))
            Icon(Icons.Default.ArrowDropDown, contentDescription = null)
        }
        
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.fillMaxWidth()
        ) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(labelMapper(option)) },
                    onClick = {
                        onOptionSelected(option)
                        expanded = false
                    }
                )
            }
        }
    }
}


