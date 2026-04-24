package com.munchkin.app.ui.screens

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.DirectionsRun
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.munchkin.app.R
import com.munchkin.app.core.*
import com.munchkin.app.network.CatalogMonster
import com.munchkin.app.ui.components.CombatResultBanner
import com.munchkin.app.ui.components.GradientButton
import com.munchkin.app.ui.components.PlayerAvatar
import com.munchkin.app.ui.components.QuickModifierButtons
import com.munchkin.app.ui.components.RunAwayDialog
import com.munchkin.app.ui.theme.*

/**
 * Combat calculator screen.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CombatScreen(
    gameState: GameState,
    myPlayerId: PlayerId,
    monsterSearchResults: List<CatalogMonster>,
    onStartCombat: () -> Unit,
    onAddMonster: (name: String, level: Int, modifier: Int, isUndead: Boolean) -> Unit,
    onSearchMonsters: (String) -> Unit,
    onRequestCreateGlobalMonster: (String, Int, Int, Boolean) -> Unit,
    onAddHelper: (PlayerId) -> Unit,
    onRemoveHelper: () -> Unit,
    onModifyModifier: (target: BonusTarget, delta: Int) -> Unit,
    onRollCombatDice: (DiceRollPurpose, Int?, Boolean) -> Unit,
    onEndCombat: () -> Unit,
    onResolveRunAway: (success: Boolean) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val combatState = gameState.combat
    // myPlayer removed - was unused
    
    // Monster input state
    var showAddMonster by remember { mutableStateOf(false) }
    var showAddHelper by remember { mutableStateOf(false) }
    
    // Animation State
    var combatAnimation by remember { mutableStateOf<com.munchkin.app.ui.components.CombatAnimationType?>(null) }
    var showRunAwayDialog by remember { mutableStateOf(false) }
    
    // Dice Result Overlay State
    var showDiceResult by remember { mutableStateOf<DiceRollInfo?>(null) }
    
    // Listen for global dice rolls
    LaunchedEffect(combatState?.lastDiceRoll) {
        combatState?.lastDiceRoll?.let { roll ->
            // Relaxed check: Show if within last 20 seconds (to handle minor clock skews)
            // But usually LaunchedEffect triggers on change, so we trust it's new.
            // We mainly want to avoid showing very old info on rejoin.
            val age = System.currentTimeMillis() - roll.timestamp
            if (age < 20000) { // 20 seconds tolerance
                // Play sound
                com.munchkin.app.ui.components.SoundManager.playDiceRoll()
                
                showDiceResult = roll
                // Auto hide after 3 seconds
                kotlinx.coroutines.delay(3000)
                showDiceResult = null
                
                // Show run-away animation to both main player and helper
                val isParticipant = myPlayerId == combatState.mainPlayerId || myPlayerId == combatState.helperPlayerId
                if (roll.purpose == DiceRollPurpose.RUN_AWAY && isParticipant) {
                    combatAnimation = if (roll.success)
                        com.munchkin.app.ui.components.CombatAnimationType.ESCAPE_SUCCESS
                    else
                        com.munchkin.app.ui.components.CombatAnimationType.ESCAPE_FAIL
                }
            }
        }
    }

    if (showAddHelper) {
        AlertDialog(
            onDismissRequest = { showAddHelper = false },
            title = { Text(stringResource(R.string.select_helper)) },
            text = {
                LazyColumn {
                    val candidates = gameState.playerList.filter { 
                        it.playerId != combatState?.mainPlayerId && it.playerId != combatState?.helperPlayerId 
                    }
                    if (candidates.isEmpty()) {
                        item { Text(stringResource(R.string.no_players_available)) }
                    } else {
                        items(candidates) { player ->
                            ListItem(
                                headlineContent = { Text(player.name) },
                                supportingContent = { Text("Nivel ${player.level} | Fuerza ${player.combatPower}") },
                                leadingContent = { PlayerAvatar(player, size = 40) },
                                modifier = Modifier.clickable {
                                    onAddHelper(player.playerId)
                                    showAddHelper = false
                                }
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showAddHelper = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }
    
    // Calculate result
    val result = remember(combatState, gameState) {
        combatState?.let { CombatCalculator.calculateResult(it, gameState) }
    }
    
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(NeonBackground, NeonSurface.copy(alpha = 0.4f), NeonBackground)
                )
            )
    ) {
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(R.string.combat_title),
                        color = NeonGray100,
                        fontWeight = FontWeight.Bold
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back),
                            tint = NeonGray100
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
                modifier = Modifier.background(
                    Brush.verticalGradient(
                        listOf(NeonBackground.copy(alpha = 0.96f), Color.Transparent)
                    )
                ),
                actions = {
                    if (combatState != null && myPlayerId == combatState.mainPlayerId) {
                        IconButton(onClick = {
                            onEndCombat()
                            onBack()
                        }) {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = stringResource(R.string.combat_cancel),
                                tint = NeonError
                            )
                        }
                    }
                    val isParticipant = combatState != null && (myPlayerId == combatState.mainPlayerId || myPlayerId == combatState.helperPlayerId)
                    if (combatState != null && combatState.monsters.isNotEmpty() && isParticipant) {
                        IconButton(onClick = { showRunAwayDialog = true }) {
                            Icon(
                                Icons.AutoMirrored.Filled.DirectionsRun,
                                contentDescription = stringResource(R.string.combat_run_away),
                                tint = NeonWarning
                            )
                        }
                    }
                }
            )
        }
    ) { padding ->
        if (combatState == null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(text = "⚔️", style = MaterialTheme.typography.displayMedium)
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = stringResource(R.string.combat_start_prompt),
                        style = MaterialTheme.typography.headlineSmall,
                        color = NeonGray200
                    )
                    Spacer(modifier = Modifier.height(24.dp))
                    GradientButton(
                        text = stringResource(R.string.start_combat),
                        onClick = onStartCombat,
                        modifier = Modifier,
                        gradientColors = GradientNeonFire
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                item { Spacer(modifier = Modifier.height(8.dp)) }
                
                // Result banner (only shows when monsters are added)
                if (combatState.monsters.isNotEmpty()) {
                    result?.let { r ->
                        item {
                            CombatResultBanner(
                                isWin = r.outcome == CombatOutcome.WIN,
                                difference = r.diff,
                                marginToWin = r.marginToWin
                            )
                        }
                    }
                }
                
                // Heroes section
                item {
                    CombatSideCard(
                        title = stringResource(R.string.heroes),
                        power = result?.heroesPower ?: 0,
                        color = MunchkinTheme.combatColors.heroColor
                    ) {
                        val mainPlayer = gameState.players[combatState.mainPlayerId]
                        mainPlayer?.let { player ->
                            Column {
                                Text(
                                    text = "${player.name} (Nivel ${player.level})",
                                    style = MaterialTheme.typography.titleMedium
                                )
                                Text(
                                    text = "${player.characterRace.displayName()} • ${player.characterClass.displayName()}",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    text = "⚔️ Fuerza Total: ${player.combatPower}",
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                        
                        combatState.helperPlayerId?.let { helperId ->
                            Spacer(modifier = Modifier.height(12.dp))
                            HorizontalDivider()
                            Spacer(modifier = Modifier.height(12.dp))
                            
                            val helper = gameState.players[helperId]
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "${helper?.name ?: "?"} (${stringResource(R.string.helper)})",
                                        style = MaterialTheme.typography.titleMedium
                                    )
                                    if (helper != null) {
                                        Text(
                                            text = "${stringResource(R.string.level)} ${helper.level} • ${helper.characterRace.displayName()} • ${helper.characterClass.displayName()}",
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        Text(
                                            text = "⚔️ ${stringResource(R.string.power)}: ${helper.combatPower}",
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }
                                IconButton(onClick = onRemoveHelper) {
                                    Icon(Icons.Default.Close, contentDescription = stringResource(R.string.combat_remove_helper), tint = MaterialTheme.colorScheme.error)
                                }
                            }
                        }

                        // Only main player can add helper
                        if (combatState.helperPlayerId == null && myPlayerId == combatState.mainPlayerId) {
                            Spacer(modifier = Modifier.height(8.dp))
                            OutlinedButton(
                                onClick = { showAddHelper = true },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(Icons.Default.PersonAdd, contentDescription = null)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(stringResource(R.string.add_helper))
                            }
                        }
                        
                        // Quick modifier buttons (Only Main Player + Helper)
                        val isParticipant = myPlayerId == combatState.mainPlayerId || myPlayerId == combatState.helperPlayerId
                        
                        if (isParticipant) {
                            Spacer(modifier = Modifier.height(16.dp))
                            
                            Text(
                                text = "Modificadores (Bonus/Malus): ${if (combatState.heroModifier >= 0) "+" else ""}${combatState.heroModifier}", 
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold
                            )
                            
                            Spacer(modifier = Modifier.height(8.dp))
                            
                            QuickModifierButtons(
                                onModify = { amount -> onModifyModifier(BonusTarget.HEROES, amount) }
                            )
                        }
                    }
                }
                
                // Monsters section
                item {
                    CombatSideCard(
                        title = stringResource(R.string.monsters),
                        power = result?.monstersPower ?: 0,
                        color = MunchkinTheme.combatColors.monsterColor
                    ) {
                        if (combatState.monsters.isEmpty()) {
                            Text(
                                text = stringResource(R.string.combat_no_monsters),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        
                        combatState.monsters.forEach { monster ->
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        text = monster.name,
                                        style = MaterialTheme.typography.bodyLarge,
                                        modifier = Modifier.weight(1f)
                                    )
                                    Text(
                                        text = "Nivel ${monster.baseLevel}" + 
                                            if (monster.flatModifier != 0) " (${if (monster.flatModifier > 0) "+" else ""}${monster.flatModifier})" 
                                            else "",
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                }
                                // Show mal rollo if available
                                if (monster.badStuff.isNotEmpty()) {
                                    Text(
                                        text = "⚠️ ${stringResource(R.string.combat_bad_stuff_label)}: ${monster.badStuff}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.error
                                    )
                                }
                            }
                        }
                        
                        // Only main player can add monsters
                        if (myPlayerId == combatState.mainPlayerId) {
                            Spacer(modifier = Modifier.height(8.dp))
                            OutlinedButton(
                                onClick = { showAddMonster = true },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(Icons.Default.Add, contentDescription = null)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(stringResource(R.string.add_monster))
                            }
                        }
                        
                        // Quick modifier buttons for monsters (Only Main Player + Helper)
                        val isParticipant = myPlayerId == combatState.mainPlayerId || myPlayerId == combatState.helperPlayerId
                        
                        if (isParticipant && combatState.monsters.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(16.dp))
                            
                            Text(
                                text = "Modificadores (Bonus/Malus): ${if (combatState.monsterModifier >= 0) "+" else ""}${combatState.monsterModifier}", 
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold
                            )
                            
                            Spacer(modifier = Modifier.height(8.dp))
                            
                            QuickModifierButtons(
                                onModify = { amount -> onModifyModifier(BonusTarget.MONSTER, amount) }
                            )
                        }
                    }
                }
                
                // End combat button (Only main player)
                if (myPlayerId == combatState.mainPlayerId) {
                    item {
                        result?.let { r ->
                            Button(
                                onClick = {
                                    if (r.outcome == CombatOutcome.WIN) {
                                        combatAnimation = com.munchkin.app.ui.components.CombatAnimationType.VICTORY
                                    } else {
                                        combatAnimation = com.munchkin.app.ui.components.CombatAnimationType.DEFEAT
                                    }
                                },
                                enabled = combatState.monsters.isNotEmpty(),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(56.dp),
                                shape = RoundedCornerShape(16.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (r.outcome == CombatOutcome.WIN) 
                                        androidx.compose.ui.graphics.Color(0xFF4CAF50) 
                                    else 
                                        MaterialTheme.colorScheme.error,
                                    disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant
                                )
                            ) {
                                Text(if (r.outcome == CombatOutcome.WIN) stringResource(R.string.combat_victory_end) else stringResource(R.string.combat_accept_defeat))
                            }
                        }
                    }
                }
                
                item { Spacer(modifier = Modifier.height(16.dp)) }
            }
        }
    }
    
    // Add monster dialog
    if (showAddMonster) {
        MonsterSearchDialog(
            searchResults = monsterSearchResults,
            onSearch = onSearchMonsters,
            onDismiss = { showAddMonster = false },
            onSelectMonster = { monster ->
                // Add local AND ensure it's in catalog (implicit by selection)
                onAddMonster(monster.name, monster.level, monster.modifier, monster.isUndead)
                showAddMonster = false
            },
            onCreateNew = { name, level, mod, undead ->
                // Create global then add local (handled by ViewModel on success)
                onRequestCreateGlobalMonster(name, level, mod, undead)
                showAddMonster = false
            }
        )
    }
    // Run Dice Logic
    // Dice Result Overlay
    if (showDiceResult != null) {
        val roll = showDiceResult!!
        AlertDialog(
            onDismissRequest = { /* Auto-dismiss only */ },
            icon = { 
                Text(
                    text = "🎲", 
                    style = MaterialTheme.typography.displayLarge
                ) 
            },
            title = { 
                Text(
                    text = "${roll.playerName} ${stringResource(R.string.combat_rolled)} ${roll.result}",
                    style = MaterialTheme.typography.headlineSmall,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                ) 
            },
            text = {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                    if (roll.purpose == DiceRollPurpose.RUN_AWAY) {
                         Text(
                             text = if (roll.success) stringResource(R.string.combat_escaped) else stringResource(R.string.combat_failed_escape),
                             style = MaterialTheme.typography.titleLarge,
                             color = if (roll.success) androidx.compose.ui.graphics.Color(0xFF4CAF50) else MaterialTheme.colorScheme.error,
                             fontWeight = FontWeight.Bold
                         )
                    }
                }
            },
            confirmButton = {}
        )
    }

    // Animation Overlay
    combatAnimation?.let { type ->
        com.munchkin.app.ui.components.CombatResultOverlay(
            type = type,
            onAnimationFinished = {
                combatAnimation = null
                when (type) {
                    com.munchkin.app.ui.components.CombatAnimationType.VICTORY,
                    com.munchkin.app.ui.components.CombatAnimationType.DEFEAT -> onEndCombat()
                    com.munchkin.app.ui.components.CombatAnimationType.ESCAPE_SUCCESS -> onResolveRunAway(true)
                    com.munchkin.app.ui.components.CombatAnimationType.ESCAPE_FAIL -> onResolveRunAway(false)
                }
            }
        )
    }

    if (showRunAwayDialog) {
        RunAwayDialog(
            onDismiss = { showRunAwayDialog = false },
            onResult = { result, success ->
                showRunAwayDialog = false
                onRollCombatDice(DiceRollPurpose.RUN_AWAY, result, success)
            }
        )
    }
    } // close outer Box
}

@Composable
fun MonsterSearchDialog(
    searchResults: List<CatalogMonster>,
    onSearch: (String) -> Unit,
    onDismiss: () -> Unit,
    onSelectMonster: (CatalogMonster) -> Unit,
    onCreateNew: (String, Int, Int, Boolean) -> Unit
) {
    var query by remember { mutableStateOf("") }
    var level by remember { mutableStateOf("1") }
    var modifier by remember { mutableStateOf("0") }
    var isUndead by remember { mutableStateOf(false) }

    // Debounce search
    LaunchedEffect(query) {
        if (query.length >= 2) {
            kotlinx.coroutines.delay(500)
            onSearch(query)
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.add_monster)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text(stringResource(R.string.search_monster_hint)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )

                if (query.isNotEmpty() && searchResults.isNotEmpty()) {
                    Text(stringResource(R.string.results_label), style = MaterialTheme.typography.labelSmall)
                    LazyColumn(
                        modifier = Modifier.heightIn(max = 150.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        items(searchResults) { monster ->
                            Card(
                                onClick = { onSelectMonster(monster) },
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(8.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(monster.name, fontWeight = FontWeight.Bold)
                                    Text("Lvl ${monster.level}")
                                }
                            }
                        }
                    }
                }
                
                HorizontalDivider()
                Text(stringResource(R.string.or_create_new), style = MaterialTheme.typography.labelSmall)
                
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = level,
                        onValueChange = { v ->
                            val digits = v.filter { c -> c.isDigit() }.take(2)
                            val n = digits.toIntOrNull()
                            level = if (n != null) n.coerceIn(1, 20).toString() else digits
                        },
                        label = { Text(stringResource(R.string.level)) },
                        modifier = Modifier.weight(1f),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                    )
                    OutlinedTextField(
                        value = modifier,
                        onValueChange = { v ->
                            val sanitized = if (v.startsWith("-")) "-" + v.drop(1).filter { c -> c.isDigit() }.take(2)
                                           else v.filter { c -> c.isDigit() }.take(2)
                            val n = sanitized.toIntOrNull()
                            modifier = if (n != null) n.coerceIn(-10, 10).toString() else sanitized
                        },
                        label = { Text(stringResource(R.string.modifier)) },
                        modifier = Modifier.weight(1f),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                    )
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.combat_is_undead), style = MaterialTheme.typography.bodyMedium)
                    Switch(checked = isUndead, onCheckedChange = { isUndead = it })
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (query.isNotBlank()) {
                        onCreateNew(
                            query,
                            (level.toIntOrNull() ?: 1).coerceIn(1, 20),
                            (modifier.toIntOrNull() ?: 0).coerceIn(-10, 10),
                            isUndead
                        )
                    }
                },
                enabled = query.isNotBlank()
            ) {
                Text(stringResource(R.string.create_new_button))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        }
    )
    }



@Composable
private fun CombatSideCard(
    title: String,
    power: Int,
    color: androidx.compose.ui.graphics.Color,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = color.copy(alpha = 0.1f)
        ),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "${stringResource(R.string.power)}: $power",
                    style = MaterialTheme.typography.headlineSmall,
                    color = color,
                    fontWeight = FontWeight.Bold
                )
            }
            
            Spacer(modifier = Modifier.height(12.dp))
            
            content()
        }
    }
}

@Composable
private fun CharacterClass.displayName(): String = when(this) {
    CharacterClass.NONE -> stringResource(R.string.class_none)
    CharacterClass.WARRIOR -> stringResource(R.string.class_warrior)
    CharacterClass.WIZARD -> stringResource(R.string.class_wizard)
    CharacterClass.THIEF -> stringResource(R.string.class_thief)
    CharacterClass.CLERIC -> stringResource(R.string.class_cleric)
}

@Composable
private fun CharacterRace.displayName(): String = when(this) {
    CharacterRace.HUMAN -> stringResource(R.string.race_human)
    CharacterRace.ELF -> stringResource(R.string.race_elf)
    CharacterRace.DWARF -> stringResource(R.string.race_dwarf)
    CharacterRace.HALFLING -> stringResource(R.string.race_halfling)
}
