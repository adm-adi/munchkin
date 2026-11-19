package com.munchkin.app.ui.screens

import androidx.compose.animation.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.munchkin.app.R
import com.munchkin.app.core.*
import com.munchkin.app.ui.components.CounterButton
import com.munchkin.app.ui.components.QuickModifierButtons
import com.munchkin.app.ui.components.labelRes
import com.munchkin.app.ui.theme.*

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
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    onApplyDeath: (() -> Unit)? = null,
    onSetClass: (CharacterClass) -> Unit = {},
    onSetRace: (CharacterRace) -> Unit = {},
    onSetSecondaryClass: (CharacterClass) -> Unit = {},
    onSetSecondaryRace: (CharacterRace) -> Unit = {},
    onSetSuperMunchkin: (Boolean) -> Unit = {},
    onSetHalfBreed: (Boolean) -> Unit = {},
    onAddCurse: (name: String, combatPenalty: Int, runAwayPenalty: Int, combatsRemaining: Int) -> Unit = { _, _, _, _ -> },
    onRemoveCurse: (curseId: String) -> Unit = {},
    isReadOnly: Boolean = false,
    maxLevel: Int = 10,
    expansionsEnabled: Boolean = false
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var showDeathConfirm by remember { mutableStateOf(false) }
    var showAddCurseDialog by remember { mutableStateOf(false) }
    val availableClasses = remember(expansionsEnabled) { CharacterClass.availableFor(expansionsEnabled) }
    val availableRaces = remember(expansionsEnabled) { CharacterRace.availableFor(expansionsEnabled) }

    if (showDeathConfirm && onApplyDeath != null) {
        AlertDialog(
            onDismissRequest = { showDeathConfirm = false },
            containerColor = NeonSurface,
            title = { Text(stringResource(R.string.death_confirm_title), color = NeonGray100) },
            text = { Text(stringResource(R.string.death_confirm_message), color = NeonGray300) },
            confirmButton = {
                Button(
                    onClick = {
                        showDeathConfirm = false
                        onApplyDeath()
                    },
                    modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = DungeonDangerFill,
                        contentColor = NeonWhite
                    )
                ) {
                    Text(stringResource(R.string.confirm_action))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showDeathConfirm = false },
                    modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                ) {
                    Text(stringResource(R.string.cancel), color = NeonGray300)
                }
            }
        )
    }

    Scaffold(
        modifier = modifier,
        containerColor = NeonBackground,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        if (isReadOnly) "${stringResource(R.string.player_details)}: ${player.name}"
                        else stringResource(R.string.edit_player),
                        color = NeonGray100,
                        fontWeight = FontWeight.Bold
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = NeonSurface,
                    titleContentColor = NeonGray100,
                    navigationIconContentColor = NeonGray100
                ),
                navigationIcon = {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back)
                        )
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
            
            // Gear counter (official Munchkin rules allow negative combat modifiers)
            CounterButton(
                value = player.gearBonus,
                label = stringResource(R.string.gear),
                onIncrement = { onModifyGear(1) },
                onDecrement = { onModifyGear(-1) },
                minValue = -999,
                maxValue = 999,
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
                        modifier = Modifier
                            .fillMaxWidth()
                            .sizeIn(minHeight = 48.dp),
                        border = BorderStroke(
                            1.dp,
                            if (player.gearBonus != 0) NeonError.copy(alpha = 0.6f) else GlassBorderDim
                        ),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = NeonError,
                            disabledContentColor = NeonGray400
                        )
                    ) {
                        Icon(
                            Icons.Default.Refresh,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(R.string.death_reset_gear))
                    }
                }
            }
            
            Spacer(modifier = Modifier.height(32.dp))
            
            // Combat power display (flat surface, Dungeon Edition)
            Surface(
                color = NeonSurfaceVariant,
                shape = RoundedCornerShape(14.dp),
                border = BorderStroke(1.dp, NeonPrimary.copy(alpha = 0.45f)),
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
                        color = NeonGray100,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        text = "${player.combatPower}",
                        style = MaterialTheme.typography.headlineMedium,
                        color = NeonGold,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(32.dp))
            
            if (!isReadOnly) {
                // Class Selector
                Text(
                    text = stringResource(R.string.label_class),
                    style = MaterialTheme.typography.titleMedium,
                    color = NeonGray100
                )
                Spacer(modifier = Modifier.height(8.dp))
                AppDropdown(
                    options = availableClasses,
                    selectedOption = player.characterClass,
                    onOptionSelected = onSetClass,
                    labelMapper = { context.getString(it.labelRes()) }
                )

                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .sizeIn(minHeight = 48.dp)
                        .clickable(role = Role.Switch) {
                            onSetSuperMunchkin(!player.hasSuperMunchkin)
                        }
                ) {
                    Text(
                        text = stringResource(R.string.super_munchkin_two_classes),
                        style = MaterialTheme.typography.bodyMedium,
                        color = NeonGray100,
                        modifier = Modifier.weight(1f)
                    )
                    Switch(
                        checked = player.hasSuperMunchkin,
                        onCheckedChange = null
                    )
                }

                if (player.hasSuperMunchkin) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.label_secondary_class),
                        style = MaterialTheme.typography.labelLarge,
                        color = NeonGray300
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    AppDropdown(
                        options = availableClasses,
                        selectedOption = player.secondaryClass,
                        onOptionSelected = onSetSecondaryClass,
                        labelMapper = { context.getString(it.labelRes()) }
                    )
                }

                Spacer(modifier = Modifier.height(24.dp))

                // Race Selector
                Text(
                    text = stringResource(R.string.label_race),
                    style = MaterialTheme.typography.titleMedium,
                    color = NeonGray100
                )
                Spacer(modifier = Modifier.height(8.dp))
                AppDropdown(
                    options = availableRaces,
                    selectedOption = player.characterRace,
                    onOptionSelected = onSetRace,
                    labelMapper = { context.getString(it.labelRes()) }
                )

                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .sizeIn(minHeight = 48.dp)
                        .clickable(role = Role.Switch) {
                            onSetHalfBreed(!player.hasHalfBreed)
                        }
                ) {
                    Text(
                        text = stringResource(R.string.half_breed_two_races),
                        style = MaterialTheme.typography.bodyMedium,
                        color = NeonGray100,
                        modifier = Modifier.weight(1f)
                    )
                    Switch(
                        checked = player.hasHalfBreed,
                        onCheckedChange = null
                    )
                }

                if (player.hasHalfBreed) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.label_secondary_race),
                        style = MaterialTheme.typography.labelLarge,
                        color = NeonGray300
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    AppDropdown(
                        options = availableRaces,
                        selectedOption = player.secondaryRace,
                        onOptionSelected = onSetSecondaryRace,
                        labelMapper = { context.getString(it.labelRes()) }
                    )
                }
            } else {
                // Read-only view of player's Classes and Races (flat surface + hairline divider)
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = NeonSurfaceVariant,
                    shape = RoundedCornerShape(16.dp),
                    border = BorderStroke(1.dp, GlassBorderDim)
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
                                color = NeonGray300
                            )
                            Text(
                                text = classesText + if (player.hasSuperMunchkin) " (${stringResource(R.string.super_munchkin)})" else "",
                                style = MaterialTheme.typography.bodyLarge,
                                color = NeonGray100
                            )
                        }
                        HorizontalDivider(color = GlassBorderDim)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = stringResource(R.string.races),
                                style = MaterialTheme.typography.labelLarge,
                                color = NeonGray300
                            )
                            Text(
                                text = racesText + if (player.hasHalfBreed) " (${stringResource(R.string.half_breed)})" else "",
                                style = MaterialTheme.typography.bodyLarge,
                                color = NeonGray100
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Active Curses section
            ActiveCursesSection(
                curses = player.activeCurses,
                isReadOnly = isReadOnly,
                onAddClick = { showAddCurseDialog = true },
                onRemoveCurse = onRemoveCurse
            )

            Spacer(modifier = Modifier.height(24.dp))

            // Same reminders as in combat: shown for both owner and read-only inspection
            AbilityReminders(listOf(player))

            Spacer(modifier = Modifier.height(32.dp))
        }
    }

    if (showAddCurseDialog && !isReadOnly) {
        AddCurseDialog(
            onDismiss = { showAddCurseDialog = false },
            onConfirm = { name, combatPen, runPen, combats ->
                onAddCurse(name, combatPen, runPen, combats)
                showAddCurseDialog = false
            }
        )
    }
}

@Composable
private fun ActiveCursesSection(
    curses: List<ActiveCurse>,
    isReadOnly: Boolean,
    onAddClick: () -> Unit,
    onRemoveCurse: (String) -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = NeonSurfaceVariant,
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, if (curses.isNotEmpty()) NeonError.copy(alpha = 0.45f) else GlassBorderDim)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.curses_title),
                    style = MaterialTheme.typography.titleSmall,
                    color = NeonGray100,
                    fontWeight = FontWeight.Bold
                )
                if (!isReadOnly) {
                    TextButton(
                        onClick = onAddClick,
                        modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null, tint = NeonSecondary, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(stringResource(R.string.curse_add), color = NeonSecondary, fontWeight = FontWeight.SemiBold)
                    }
                }
            }

            if (curses.isEmpty()) {
                Text(
                    text = stringResource(R.string.curse_none),
                    style = MaterialTheme.typography.bodySmall,
                    color = NeonGray400
                )
            } else {
                curses.forEachIndexed { idx, curse ->
                    if (idx > 0) HorizontalDivider(color = GlassBorderDim)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = curse.name,
                                style = MaterialTheme.typography.bodyMedium,
                                color = NeonGray100,
                                fontWeight = FontWeight.SemiBold
                            )
                            val effects = buildList {
                                if (curse.combatPenalty != 0) {
                                    val s = if (curse.combatPenalty > 0) "+" else ""
                                    add("$s${curse.combatPenalty} ⚔️")
                                }
                                if (curse.runAwayPenalty != 0) {
                                    val s = if (curse.runAwayPenalty > 0) "+" else ""
                                    add("$s${curse.runAwayPenalty} 🏃")
                                }
                                if (curse.combatsRemaining > 0) {
                                    add("${curse.combatsRemaining}x")
                                } else {
                                    add(stringResource(R.string.curse_persistent))
                                }
                            }.joinToString(" • ")
                            Text(
                                text = effects,
                                style = MaterialTheme.typography.labelSmall,
                                color = NeonError
                            )
                        }
                        if (!isReadOnly) {
                            IconButton(
                                onClick = { onRemoveCurse(curse.id) },
                                modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                            ) {
                                Icon(
                                    Icons.Default.Close,
                                    contentDescription = stringResource(R.string.close),
                                    tint = NeonGray300
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AddCurseDialog(
    onDismiss: () -> Unit,
    onConfirm: (name: String, combatPenalty: Int, runAwayPenalty: Int, combatsRemaining: Int) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var combatPenalty by remember { mutableStateOf("0") }
    var runAwayPenalty by remember { mutableStateOf("0") }
    var combatsRemaining by remember { mutableStateOf("0") }

    val sexChangeLabel = stringResource(R.string.curse_preset_sex_change)
    val chickenLabel = stringResource(R.string.curse_preset_chicken)
    val noArmorLabel = stringResource(R.string.curse_preset_no_armor)

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = NeonSurface,
        title = { Text(stringResource(R.string.curse_add), color = NeonGray100) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .imePadding(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Quick Munchkin curse presets
                OutlinedButton(
                    onClick = { onConfirm(sexChangeLabel, -5, 0, 1) },
                    modifier = Modifier.fillMaxWidth().sizeIn(minHeight = 48.dp),
                    border = BorderStroke(1.dp, NeonError.copy(alpha = 0.5f)),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = NeonGray100)
                ) {
                    Text(sexChangeLabel)
                }
                OutlinedButton(
                    onClick = { onConfirm(chickenLabel, 0, -1, 0) },
                    modifier = Modifier.fillMaxWidth().sizeIn(minHeight = 48.dp),
                    border = BorderStroke(1.dp, NeonWarning.copy(alpha = 0.5f)),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = NeonGray100)
                ) {
                    Text(chickenLabel)
                }
                OutlinedButton(
                    onClick = { onConfirm(noArmorLabel, 0, 0, 0) },
                    modifier = Modifier.fillMaxWidth().sizeIn(minHeight = 48.dp),
                    border = BorderStroke(1.dp, GlassBorder),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = NeonGray100)
                ) {
                    Text(noArmorLabel)
                }

                HorizontalDivider(color = GlassBorderDim)

                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(40) },
                    label = { Text(stringResource(R.string.curse_name_label)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = combatPenalty,
                        onValueChange = { v ->
                            combatPenalty = if (v.startsWith("-")) "-" + v.drop(1).filter { it.isDigit() }.take(2)
                            else v.filter { it.isDigit() }.take(2)
                        },
                        label = { Text(stringResource(R.string.curse_combat_penalty)) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f),
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = runAwayPenalty,
                        onValueChange = { v ->
                            runAwayPenalty = if (v.startsWith("-")) "-" + v.drop(1).filter { it.isDigit() }.take(1)
                            else v.filter { it.isDigit() }.take(1)
                        },
                        label = { Text(stringResource(R.string.curse_runaway_penalty)) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f),
                        singleLine = true
                    )
                }
                OutlinedTextField(
                    value = combatsRemaining,
                    onValueChange = { combatsRemaining = it.filter { c -> c.isDigit() }.take(2) },
                    label = { Text(stringResource(R.string.curse_combats_remaining)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (name.isNotBlank()) {
                        onConfirm(
                            name.trim(),
                            (combatPenalty.toIntOrNull() ?: 0).coerceIn(-20, 20),
                            (runAwayPenalty.toIntOrNull() ?: 0).coerceIn(-5, 5),
                            (combatsRemaining.toIntOrNull() ?: 0).coerceIn(0, 10)
                        )
                    }
                },
                enabled = name.isNotBlank(),
                modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp),
                colors = ButtonDefaults.buttonColors(containerColor = NeonPrimary, contentColor = NeonWhite)
            ) {
                Text(stringResource(R.string.curse_add))
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)
            ) {
                Text(stringResource(R.string.cancel), color = NeonGray300)
            }
        }
    )
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
            modifier = Modifier
                .fillMaxWidth()
                .sizeIn(minHeight = 48.dp),
            border = BorderStroke(1.dp, GlassBorder),
            colors = ButtonDefaults.outlinedButtonColors(
                containerColor = NeonSurface,
                contentColor = NeonGray100
            )
        ) {
            Text(labelMapper(selectedOption), color = NeonGray100)
            Spacer(modifier = Modifier.weight(1f))
            Icon(
                Icons.Default.ArrowDropDown,
                contentDescription = null,
                tint = NeonGray300
            )
        }

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier
                .fillMaxWidth(0.85f)
                .background(NeonSurface)
        ) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(labelMapper(option), color = NeonGray100) },
                    onClick = {
                        onOptionSelected(option)
                        expanded = false
                    }
                )
            }
        }
    }
}


