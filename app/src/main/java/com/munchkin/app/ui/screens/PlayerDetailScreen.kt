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
    // labelMapper is a plain lambda, not a composable, so the class/race names
    // are resolved through the context rather than stringResource().
    val context = androidx.compose.ui.platform.LocalContext.current

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(if (isReadOnly) stringResource(R.string.player_details) else stringResource(R.string.edit_player)) },
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
            
            // Class and Race Selectors (Only if not ReadOnly)
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

                // Super Munchkin grants the abilities of two classes. There was no way
                // to turn it on from the UI at all, which is why the flag looked inert.
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

                // Half-Breed is the race equivalent of Super Munchkin.
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

                Spacer(modifier = Modifier.height(24.dp))

                // Same reminders as in combat: which abilities the app applies and
                // which the player has to resolve at the table.
                AbilityReminders(listOf(player))

                Spacer(modifier = Modifier.height(32.dp))
            }
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


