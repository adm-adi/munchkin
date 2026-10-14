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
import com.munchkin.app.ui.components.labelRes
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
    onAddMonster: (name: String, level: Int, modifier: Int, isUndead: Boolean, badStuff: String, treasures: Int, levels: Int) -> Unit,
    onRemoveMonster: (monsterId: String) -> Unit,
    onSearchMonsters: (String) -> Unit,
    onRequestCreateGlobalMonster: (name: String, level: Int, modifier: Int, isUndead: Boolean, treasures: Int, levels: Int, badStuff: String) -> Unit,
    onAddHelper: (PlayerId) -> Unit,
    onRemoveHelper: () -> Unit,
    onModifyModifier: (target: BonusTarget, delta: Int) -> Unit,
    onRollCombatDice: (DiceRollPurpose, Int?, Boolean) -> Unit,
    onEndCombat: () -> Unit,
    onCancelCombat: () -> Unit,
    onResolveRunAway: (success: Boolean) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val combatState = gameState.combat
    val canTouchCombat = combatState != null && (
        myPlayerId == combatState.mainPlayerId ||
        myPlayerId == combatState.helperPlayerId ||
        myPlayerId == gameState.turnPlayerId ||
        myPlayerId == gameState.hostId
    )
    val canStartCombat = myPlayerId == gameState.turnPlayerId || myPlayerId == gameState.hostId
    
    // Monster input state
    var showAddMonster by remember { mutableStateOf(false) }
    var showAddHelper by remember { mutableStateOf(false) }
    
    // Animation State
    var combatAnimation by remember { mutableStateOf<com.munchkin.app.ui.components.CombatAnimationType?>(null) }
    var showRunAwayDialog by remember { mutableStateOf(false) }

    /**
     * The authoritative action a finished animation is standing in for.
     *
     * Resolving a combat only reached the server from the overlay's
     * onAnimationFinished callback, roughly three seconds after the tap. Anything
     * that took this screen out of composition in that window — a back press, the
     * host ending the turn, the app being backgrounded — dropped it silently: the
     * player had accepted a victory and got no levels, no treasure, and a combat
     * that was still open. Holding it here lets the disposal path flush it.
     */
    val pendingResolution = remember { mutableStateOf<(() -> Unit)?>(null) }
    val flushPendingResolution = {
        val action = pendingResolution.value
        pendingResolution.value = null
        action?.invoke()
    }
    DisposableEffect(Unit) {
        onDispose { flushPendingResolution() }
    }
    
    // Dice Result Overlay State
    var showDiceResult by remember { mutableStateOf<DiceRollInfo?>(null) }
    
    // Listen for global dice rolls
    LaunchedEffect(combatState?.lastDiceRoll) {
        combatState?.lastDiceRoll?.let { roll ->
            val age = System.currentTimeMillis() - roll.timestamp
            if (age < 20000) { // 20 seconds tolerance
                com.munchkin.app.ui.components.SoundManager.playDiceRoll()
                
                showDiceResult = roll
                kotlinx.coroutines.delay(3000)
                showDiceResult = null
                
                if (roll.purpose == DiceRollPurpose.RUN_AWAY && canTouchCombat) {
                    // The player who rolled resolves the escape server-side (or main player as fallback)
                    if (myPlayerId == roll.playerId) {
                        pendingResolution.value = { onResolveRunAway(roll.success) }
                    }
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
                                supportingContent = { Text(stringResource(R.string.level_power_format, player.level, player.combatPower)) },
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
                    if (canTouchCombat) {
                        IconButton(onClick = {
                            onCancelCombat()
                            onBack()
                        }) {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = stringResource(R.string.combat_cancel),
                                tint = NeonError
                            )
                        }
                    }
                    val canRunAway = canTouchCombat && combatState?.monsters?.isNotEmpty() == true
                    if (canRunAway) {
                        IconButton(onClick = { showRunAwayDialog = true }) {
                            Icon(
                                Icons.Default.DirectionsRun,
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
                    if (canStartCombat) {
                        GradientButton(
                            text = stringResource(R.string.start_combat),
                            onClick = onStartCombat,
                            modifier = Modifier,
                            gradientColors = GradientNeonFire
                        )
                    }
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
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                CombatResultBanner(
                                    isWin = r.outcome == CombatOutcome.WIN,
                                    difference = r.diff,
                                    marginToWin = r.marginToWin
                                )
                                if (r.warriorTieBreak) {
                                    Surface(
                                        color = NeonWarning.copy(alpha = 0.2f),
                                        shape = RoundedCornerShape(12.dp),
                                        modifier = Modifier.border(1.dp, NeonWarning.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
                                    ) {
                                        Text(
                                            text = stringResource(R.string.warrior_tie_win_badge),
                                            style = MaterialTheme.typography.labelLarge,
                                            color = NeonWarning,
                                            fontWeight = FontWeight.Bold,
                                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                                        )
                                    }
                                }
                                if (r.outcome == CombatOutcome.WIN) {
                                    Text(
                                        text = "💰 ${stringResource(R.string.treasures_format, r.totalTreasures)} • ⭐ ${stringResource(R.string.levels_format, r.totalLevels)}" +
                                            if (r.helperLevelsGained > 0) " (+${r.helperLevelsGained} ${stringResource(R.string.race_elf)})" else "",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = NeonSuccess,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                }
                            }
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
                                    text = "${player.name} (${stringResource(R.string.level)} ${player.level})",
                                    style = MaterialTheme.typography.titleMedium
                                )
                                Text(
                                    text = "${player.characterRace.displayName()} • ${player.characterClass.displayName()}",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    text = "⚔️ ${stringResource(R.string.power)}: ${player.combatPower}",
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
                                if (canTouchCombat) {
                                    IconButton(onClick = onRemoveHelper) {
                                        Icon(Icons.Default.Close, contentDescription = stringResource(R.string.combat_remove_helper), tint = MaterialTheme.colorScheme.error)
                                    }
                                }
                            }
                        }

                        if (combatState.helperPlayerId == null && canTouchCombat) {
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
                        
                        if (canTouchCombat) {
                            Spacer(modifier = Modifier.height(16.dp))
                            val modStr = "${if (combatState.heroModifier >= 0) "+" else ""}${combatState.heroModifier}"
                            Text(
                                text = stringResource(R.string.modifiers_label, modStr),
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
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = monster.name + if (monster.isUndead) " 🧟" else "",
                                            style = MaterialTheme.typography.bodyLarge,
                                            fontWeight = FontWeight.SemiBold
                                        )
                                        Text(
                                            text = "💰 ${stringResource(R.string.treasures_format, monster.treasures)} • ⭐ ${stringResource(R.string.levels_format, monster.levels)}",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    Text(
                                        text = "${stringResource(R.string.level)} ${monster.baseLevel}" + 
                                            if (monster.flatModifier != 0) " (${if (monster.flatModifier > 0) "+" else ""}${monster.flatModifier})" 
                                            else "",
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Bold
                                    )
                                    if (canTouchCombat) {
                                        IconButton(onClick = { onRemoveMonster(monster.id) }) {
                                            Icon(
                                                Icons.Default.Delete,
                                                contentDescription = stringResource(R.string.remove_monster),
                                                tint = MaterialTheme.colorScheme.error
                                            )
                                        }
                                    }
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
                        
                        if (canTouchCombat) {
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
                        
                        if (canTouchCombat && combatState.monsters.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(16.dp))
                            val modStr = "${if (combatState.monsterModifier >= 0) "+" else ""}${combatState.monsterModifier}"
                            Text(
                                text = stringResource(R.string.modifiers_label, modStr),
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
                
                // End combat button
                if (canTouchCombat) {
                    item {
                        result?.let { r ->
                            Button(
                                onClick = {
                                    pendingResolution.value = onEndCombat
                                    combatAnimation = if (r.outcome == CombatOutcome.WIN) {
                                        com.munchkin.app.ui.components.CombatAnimationType.VICTORY
                                    } else {
                                        com.munchkin.app.ui.components.CombatAnimationType.DEFEAT
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
                
                // Reminders of what the participants' classes and races do.
                item {
                    val participants = listOfNotNull(
                        gameState.players[combatState.mainPlayerId],
                        combatState.helperPlayerId?.let { gameState.players[it] }
                    )
                    AbilityReminders(participants)
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
                onAddMonster(
                    monster.name,
                    monster.level,
                    monster.modifier,
                    monster.isUndead,
                    monster.badStuff,
                    monster.treasures,
                    monster.levels
                )
                showAddMonster = false
            },
            onCreateNew = { name, level, mod, undead, treasures, levels, badStuff ->
                onRequestCreateGlobalMonster(name, level, mod, undead, treasures, levels, badStuff)
                showAddMonster = false
            }
        )
    }
    // Dice Result Overlay
    if (showDiceResult != null) {
        val roll = showDiceResult!!
        AlertDialog(
            onDismissRequest = { showDiceResult = null },
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
                flushPendingResolution()
            }
        )
    }

    if (showRunAwayDialog) {
        val runner = gameState.players[combatState?.mainPlayerId ?: myPlayerId]
        val labelContext = androidx.compose.ui.platform.LocalContext.current
        RunAwayDialog(
            onDismiss = { showRunAwayDialog = false },
            onResult = { result, success ->
                showRunAwayDialog = false
                onRollCombatDice(DiceRollPurpose.RUN_AWAY, result, success)
            },
            runAwayBonus = runner?.let { Abilities.runAwayBonus(it) } ?: 0,
            runAwayBonusLabel = runner
                ?.let { Abilities.runAwayModifiers(it) }
                ?.takeIf { it.isNotEmpty() }
                ?.joinToString(", ") { (race, amount) ->
                    val sign = if (amount >= 0) "+" else ""
                    "$sign$amount ${labelContext.getString(race.labelRes())}"
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
    onCreateNew: (name: String, level: Int, modifier: Int, isUndead: Boolean, treasures: Int, levels: Int, badStuff: String) -> Unit
) {
    var query by remember { mutableStateOf("") }
    var level by remember { mutableStateOf("1") }
    var modifier by remember { mutableStateOf("0") }
    var treasures by remember { mutableStateOf("1") }
    var levels by remember { mutableStateOf("1") }
    var badStuff by remember { mutableStateOf("") }
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
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(monster.name, fontWeight = FontWeight.Bold)
                                        Text(
                                            "💰 ${monster.treasures} • ⭐ ${monster.levels}",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    Text("${stringResource(R.string.level)} ${monster.level}")
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
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true
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
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = treasures,
                        onValueChange = { v ->
                            val digits = v.filter { c -> c.isDigit() }.take(2)
                            val n = digits.toIntOrNull()
                            treasures = if (n != null) n.coerceIn(0, 10).toString() else digits
                        },
                        label = { Text(stringResource(R.string.treasures_label)) },
                        modifier = Modifier.weight(1f),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = levels,
                        onValueChange = { v ->
                            val digits = v.filter { c -> c.isDigit() }.take(1)
                            val n = digits.toIntOrNull()
                            levels = if (n != null) n.coerceIn(1, 2).toString() else digits
                        },
                        label = { Text(stringResource(R.string.levels_reward_label)) },
                        modifier = Modifier.weight(1f),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true
                    )
                }
                OutlinedTextField(
                    value = badStuff,
                    onValueChange = { badStuff = it.take(150) },
                    label = { Text(stringResource(R.string.combat_bad_stuff_label)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
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
                            isUndead,
                            (treasures.toIntOrNull() ?: 1).coerceIn(0, 10),
                            (levels.toIntOrNull() ?: 1).coerceIn(1, 2),
                            badStuff.trim()
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

/**
 * Lists the class and race abilities in play for the combat participants.
 *
 * Each entry is marked as either applied by the app or left to the player. Most
 * Munchkin abilities are resolved by discarding cards or from inventory, and this
 * app tracks neither, so pretending it handles everything would be worse than
 * saying plainly which is which.
 */
@Composable
fun AbilityReminders(participants: List<PlayerState>) {
    val entries = participants.flatMap { player ->
        Abilities.forPlayer(player).map { player to it }
    }
    if (entries.isEmpty()) return

    var expanded by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clickable { expanded = !expanded },
        colors = CardDefaults.cardColors(containerColor = GlassBase),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.AutoAwesome,
                    contentDescription = null,
                    tint = NeonSecondary,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.abilities_title),
                    style = MaterialTheme.typography.titleSmall,
                    color = NeonGray100,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
                Icon(
                    if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = null,
                    tint = NeonGray400
                )
            }

            if (!expanded) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.abilities_collapsed_hint, entries.size),
                    style = MaterialTheme.typography.bodySmall,
                    color = NeonGray500
                )
                return@Column
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Grouped by player so a helper's abilities are not mistaken for yours.
            participants.forEach { player ->
                val playerAbilities = Abilities.forPlayer(player)
                if (playerAbilities.isEmpty()) return@forEach

                Text(
                    text = player.name,
                    style = MaterialTheme.typography.labelMedium,
                    color = NeonSecondary,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(4.dp))

                playerAbilities.forEach { ability ->
                    val isAuto = ability.kind == AbilityKind.AUTOMATIC
                    Row(modifier = Modifier.padding(vertical = 3.dp)) {
                        Text(
                            text = if (isAuto) "✓" else "•",
                            color = if (isAuto) NeonSuccess else NeonWarning,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.width(18.dp)
                        )
                        Column {
                            Text(
                                text = stringResource(ability.sourceRes) + ": " +
                                    stringResource(ability.descriptionRes),
                                style = MaterialTheme.typography.bodySmall,
                                color = NeonGray300
                            )
                            Text(
                                text = stringResource(
                                    if (isAuto) R.string.ability_automatic
                                    else R.string.ability_manual
                                ),
                                style = MaterialTheme.typography.labelSmall,
                                color = if (isAuto) NeonSuccess else NeonWarning
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
            }
        }
    }
}
