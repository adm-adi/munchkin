package com.munchkin.app.ui.screens

import androidx.compose.animation.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.munchkin.app.R
import com.munchkin.app.core.*
import com.munchkin.app.network.CatalogMonster
import com.munchkin.app.ui.components.CombatAnimationType
import com.munchkin.app.ui.components.CombatResultBanner
import com.munchkin.app.ui.components.CombatResultOverlay
import com.munchkin.app.ui.components.GradientButton
import com.munchkin.app.ui.components.PlayerAvatar
import com.munchkin.app.ui.components.QuickModifierButtons
import com.munchkin.app.ui.components.RunAwayDialog
import com.munchkin.app.ui.components.SoundManager
import com.munchkin.app.ui.components.labelRes
import com.munchkin.app.ui.theme.*
import com.munchkin.app.viewmodel.canTouchCombat
import kotlinx.coroutines.delay

/**
 * Combat calculator screen with monster modifiers, temporary bonuses, power breakdown,
 * and authoritative defeat/victory resolution.
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
    onGoToBoard: () -> Unit,
    modifier: Modifier = Modifier,
    onUpdateMonsterModifier: (monsterId: String, flatModifier: Int) -> Unit = { _, _ -> },
    onAddTempBonus: (target: BonusTarget, label: String, amount: Int) -> Unit = { _, _, _ -> },
    onRemoveTempBonus: (bonusId: String) -> Unit = {},
    onBack: () -> Unit = onGoToBoard,
    isCombatResolving: Boolean = false,
    serverTimeOffsetMs: Long = 0L
) {
    val combatState = gameState.combat
    val canEditCombat = canTouchCombat(gameState, myPlayerId)
    val canStartCombat = myPlayerId == gameState.turnPlayerId || myPlayerId == gameState.hostId
    val canCancelCombat = canEditCombat && combatState != null &&
        (myPlayerId == combatState.mainPlayerId || myPlayerId == gameState.hostId)

    var showAddMonster by remember { mutableStateOf(false) }
    var showAddHelper by remember { mutableStateOf(false) }
    var showAddBonusDialog by remember { mutableStateOf(false) }
    var showCancelConfirm by remember { mutableStateOf(false) }
    var showBreakdown by remember { mutableStateOf(false) }

    var combatAnimation by remember { mutableStateOf<CombatAnimationType?>(null) }
    var showRunAwayDialog by remember { mutableStateOf(false) }

    // Intercept system Back so returning to the Board leaves combat active.
    androidx.activity.compose.BackHandler {
        onGoToBoard()
    }

    /**
     * Holds the authoritative resolution callback triggered once the result animation completes.
     * Flushed on disposal so leaving the screen mid-animation does not drop the server event.
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

    var showDiceResult by remember { mutableStateOf<DiceRollInfo?>(null) }

    // Freshness uses server-adjusted clock so client clock skew neither replays stale rolls nor drops live ones.
    LaunchedEffect(combatState?.lastDiceRoll) {
        combatState?.lastDiceRoll?.let { roll ->
            val adjustedNow = System.currentTimeMillis() + serverTimeOffsetMs
            val age = adjustedNow - roll.timestamp
            if (age in -5_000L..20_000L) {
                SoundManager.playDiceRoll()

                showDiceResult = roll
                delay(3000)
                showDiceResult = null

                if (roll.purpose == DiceRollPurpose.RUN_AWAY && canEditCombat) {
                    if (myPlayerId == roll.playerId) {
                        pendingResolution.value = { onResolveRunAway(roll.success) }
                    }
                    combatAnimation = if (roll.success) {
                        CombatAnimationType.ESCAPE_SUCCESS
                    } else {
                        CombatAnimationType.ESCAPE_FAIL
                    }
                }
            }
        }
    }

    if (showAddHelper) {
        AlertDialog(
            onDismissRequest = { showAddHelper = false },
            containerColor = NeonSurface,
            title = { Text(stringResource(R.string.select_helper), color = NeonGray100) },
            text = {
                LazyColumn(modifier = Modifier.heightIn(max = 320.dp)) {
                    val candidates = gameState.playerList.filter {
                        it.playerId != combatState?.mainPlayerId && it.playerId != combatState?.helperPlayerId
                    }
                    if (candidates.isEmpty()) {
                        item(key = "no_helpers") {
                            Text(
                                stringResource(R.string.no_players_available),
                                color = NeonGray300
                            )
                        }
                    } else {
                        items(candidates, key = { it.playerId.value }) { player ->
                            ListItem(
                                colors = ListItemDefaults.colors(
                                    containerColor = NeonSurfaceVariant,
                                    headlineColor = NeonGray100,
                                    supportingColor = NeonGray300
                                ),
                                headlineContent = { Text(player.name) },
                                supportingContent = {
                                    Text(stringResource(R.string.level_power_format, player.level, player.combatPower))
                                },
                                leadingContent = { PlayerAvatar(player, size = 40) },
                                modifier = Modifier
                                    .sizeIn(minHeight = 48.dp)
                                    .clickable {
                                        onAddHelper(player.playerId)
                                        showAddHelper = false
                                    }
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = { showAddHelper = false },
                    modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                ) {
                    Text(stringResource(R.string.cancel), color = NeonGray300)
                }
            }
        )
    }

    val breakdown = remember(combatState, gameState) {
        combatState?.let { CombatCalculator.getBreakdown(it, gameState) }
    }
    val result = breakdown?.result

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
                        IconButton(
                            onClick = onGoToBoard,
                            modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                        ) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = stringResource(R.string.board_nav_label),
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
                        TextButton(
                            onClick = onGoToBoard,
                            modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                        ) {
                            Text(
                                text = stringResource(R.string.board_nav_label),
                                color = NeonGray200,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                        val canRunAway = canEditCombat &&
                            combatState?.monsters?.isNotEmpty() == true &&
                            !isCombatResolving
                        if (canEditCombat && combatState?.monsters?.isNotEmpty() == true) {
                            IconButton(
                                onClick = { showRunAwayDialog = true },
                                enabled = canRunAway,
                                modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                            ) {
                                Icon(
                                    Icons.AutoMirrored.Filled.DirectionsRun,
                                    contentDescription = stringResource(R.string.combat_run_away),
                                    tint = if (canRunAway) NeonWarning else NeonGray500
                                )
                            }
                        }
                        if (canCancelCombat) {
                            IconButton(
                                onClick = {
                                    if (combatState?.monsters?.isEmpty() == true) {
                                        onCancelCombat()
                                        onBack()
                                    } else {
                                        showCancelConfirm = true
                                    }
                                },
                                enabled = !isCombatResolving,
                                modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                            ) {
                                Icon(
                                    Icons.Default.Close,
                                    contentDescription = stringResource(R.string.combat_cancel),
                                    tint = NeonError
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
                                modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp),
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
                        .padding(horizontal = 16.dp)
                        .testTag("combat_list"),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    item(key = "spacer_top") { Spacer(modifier = Modifier.height(8.dp)) }

                    if (combatState.monsters.isNotEmpty() && result != null) {
                        item(key = "result_banner") {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                CombatResultBanner(
                                    isWin = result.outcome == CombatOutcome.WIN,
                                    difference = result.diff,
                                    marginToWin = result.marginToWin
                                )
                                if (result.warriorTieBreak) {
                                    Surface(
                                        color = NeonWarning.copy(alpha = 0.16f),
                                        shape = RoundedCornerShape(12.dp),
                                        border = BorderStroke(1.dp, NeonWarning.copy(alpha = 0.5f))
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
                                if (result.outcome == CombatOutcome.WIN) {
                                    Text(
                                        text = "💰 ${stringResource(R.string.treasures_format, result.totalTreasures)} • ⭐ ${stringResource(R.string.levels_format, result.totalLevels)}" +
                                            if (result.helperLevelsGained > 0) " (+${result.helperLevelsGained} ${stringResource(R.string.race_elf)})" else "",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = NeonSuccess,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                }
                            }
                        }
                    }

                    // Heroes Section
                    item(key = "heroes_section") {
                        CombatSideSection(
                            title = stringResource(R.string.heroes),
                            power = result?.heroesPower ?: 0,
                            color = MunchkinTheme.combatColors.heroColor
                        ) {
                            val mainPlayer = gameState.players[combatState.mainPlayerId]
                            mainPlayer?.let { player ->
                                Column {
                                    Text(
                                        text = "${player.name} (${stringResource(R.string.level)} ${player.level})",
                                        style = MaterialTheme.typography.titleMedium,
                                        color = NeonGray100
                                    )
                                    Text(
                                        text = "${player.characterRace.displayName()} • ${player.characterClass.displayName()}",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = NeonGray300
                                    )
                                    Text(
                                        text = "⚔️ ${stringResource(R.string.power)}: ${player.combatPower}",
                                        style = MaterialTheme.typography.bodyLarge,
                                        fontWeight = FontWeight.Bold,
                                        color = NeonGray100
                                    )
                                }
                            }

                            combatState.helperPlayerId?.let { helperId ->
                                Spacer(modifier = Modifier.height(12.dp))
                                HorizontalDivider(color = GlassBorderDim)
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
                                            style = MaterialTheme.typography.titleMedium,
                                            color = NeonGray100
                                        )
                                        if (helper != null) {
                                            Text(
                                                text = "${stringResource(R.string.level)} ${helper.level} • ${helper.characterRace.displayName()} • ${helper.characterClass.displayName()}",
                                                style = MaterialTheme.typography.bodyMedium,
                                                color = NeonGray300
                                            )
                                            Text(
                                                text = "⚔️ ${stringResource(R.string.power)}: ${helper.combatPower}",
                                                style = MaterialTheme.typography.bodyMedium,
                                                fontWeight = FontWeight.Bold,
                                                color = NeonGray100
                                            )
                                        }
                                    }
                                    if (canEditCombat) {
                                        IconButton(
                                            onClick = onRemoveHelper,
                                            modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                                        ) {
                                            Icon(
                                                Icons.Default.Close,
                                                contentDescription = stringResource(R.string.combat_remove_helper),
                                                tint = NeonError
                                            )
                                        }
                                    }
                                }
                            }

                            if (combatState.helperPlayerId == null && canEditCombat) {
                                Spacer(modifier = Modifier.height(10.dp))
                                OutlinedButton(
                                    onClick = { showAddHelper = true },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .sizeIn(minHeight = 48.dp),
                                    border = BorderStroke(1.dp, GlassBorder),
                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = NeonGray100)
                                ) {
                                    Icon(Icons.Default.PersonAdd, contentDescription = null)
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(stringResource(R.string.add_helper))
                                }
                            }

                            if (canEditCombat) {
                                Spacer(modifier = Modifier.height(16.dp))
                                val modStr = "${if (combatState.heroModifier >= 0) "+" else ""}${combatState.heroModifier}"
                                Text(
                                    text = stringResource(R.string.modifiers_label, modStr),
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = NeonGray100
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                QuickModifierButtons(
                                    onModify = { amount -> onModifyModifier(BonusTarget.HEROES, amount) }
                                )
                            }
                        }
                    }

                    // Monsters Section
                    item(key = "monsters_section") {
                        CombatSideSection(
                            title = stringResource(R.string.monsters),
                            power = result?.monstersPower ?: 0,
                            color = MunchkinTheme.combatColors.monsterColor
                        ) {
                            if (combatState.monsters.isEmpty()) {
                                Text(
                                    text = stringResource(R.string.combat_no_monsters),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = NeonGray300
                                )
                            }

                            combatState.monsters.forEachIndexed { idx, monster ->
                                if (idx > 0) {
                                    HorizontalDivider(
                                        color = GlassBorderDim,
                                        modifier = Modifier.padding(vertical = 8.dp)
                                    )
                                }
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
                                                fontWeight = FontWeight.SemiBold,
                                                color = NeonGray100
                                            )
                                            Text(
                                                text = "💰 ${stringResource(R.string.treasures_format, monster.treasures)} • ⭐ ${stringResource(R.string.levels_format, monster.levels)}",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = NeonGray300
                                            )
                                        }
                                        Text(
                                            text = "${stringResource(R.string.level)} ${monster.baseLevel}" +
                                                if (monster.flatModifier != 0) " (${if (monster.flatModifier > 0) "+" else ""}${monster.flatModifier})"
                                                else "",
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.Bold,
                                            color = NeonGray100
                                        )
                                        if (canEditCombat) {
                                            IconButton(
                                                onClick = { onRemoveMonster(monster.id) },
                                                modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                                            ) {
                                                Icon(
                                                    Icons.Default.Delete,
                                                    contentDescription = stringResource(R.string.remove_monster),
                                                    tint = NeonError
                                                )
                                            }
                                        }
                                    }

                                    // Per-monster flatModifier steppers (>= 48dp touch targets)
                                    if (canEditCombat) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                                            modifier = Modifier.padding(top = 4.dp)
                                        ) {
                                            Text(
                                                text = stringResource(R.string.modifier),
                                                style = MaterialTheme.typography.labelMedium,
                                                color = NeonGray300,
                                                modifier = Modifier.weight(1f)
                                            )
                                            OutlinedIconButton(
                                                onClick = {
                                                    onUpdateMonsterModifier(
                                                        monster.id,
                                                        (monster.flatModifier - 1).coerceIn(-GameEngine.MODIFIER_LIMIT, GameEngine.MODIFIER_LIMIT)
                                                    )
                                                },
                                                modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                                            ) {
                                                Icon(
                                                    Icons.Default.Remove,
                                                    contentDescription = stringResource(R.string.monster_modifier_decrease, monster.name),
                                                    tint = NeonGray100
                                                )
                                            }
                                            Text(
                                                text = "${if (monster.flatModifier >= 0) "+" else ""}${monster.flatModifier}",
                                                style = MaterialTheme.typography.titleSmall,
                                                fontWeight = FontWeight.Bold,
                                                color = if (monster.flatModifier >= 0) NeonWarning else HeroGreen,
                                                modifier = Modifier.widthIn(min = 32.dp),
                                                textAlign = TextAlign.Center
                                            )
                                            OutlinedIconButton(
                                                onClick = {
                                                    onUpdateMonsterModifier(
                                                        monster.id,
                                                        (monster.flatModifier + 1).coerceIn(-GameEngine.MODIFIER_LIMIT, GameEngine.MODIFIER_LIMIT)
                                                    )
                                                },
                                                modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                                            ) {
                                                Icon(
                                                    Icons.Default.Add,
                                                    contentDescription = stringResource(R.string.monster_modifier_increase, monster.name),
                                                    tint = NeonGray100
                                                )
                                            }
                                        }
                                    }

                                    if (monster.badStuff.isNotEmpty()) {
                                        Text(
                                            text = "⚠️ ${stringResource(R.string.combat_bad_stuff_label)}: ${monster.badStuff}",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = NeonError,
                                            modifier = Modifier.padding(top = 4.dp)
                                        )
                                    }
                                }
                            }

                            if (canEditCombat) {
                                Spacer(modifier = Modifier.height(10.dp))
                                OutlinedButton(
                                    onClick = { showAddMonster = true },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .sizeIn(minHeight = 48.dp),
                                    border = BorderStroke(1.dp, GlassBorder),
                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = NeonGray100)
                                ) {
                                    Icon(Icons.Default.Add, contentDescription = null)
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(stringResource(R.string.add_monster))
                                }
                            }

                            if (canEditCombat && combatState.monsters.isNotEmpty()) {
                                Spacer(modifier = Modifier.height(16.dp))
                                val modStr = "${if (combatState.monsterModifier >= 0) "+" else ""}${combatState.monsterModifier}"
                                Text(
                                    text = stringResource(R.string.modifiers_label, modStr),
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = NeonGray100
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                QuickModifierButtons(
                                    onModify = { amount -> onModifyModifier(BonusTarget.MONSTER, amount) }
                                )
                            }
                        }
                    }

                    // Temporary Bonuses Section (tempBonuses add/remove)
                    item(key = "temp_bonuses_section") {
                        Surface(
                            color = NeonSurfaceVariant,
                            shape = RoundedCornerShape(16.dp),
                            border = BorderStroke(1.dp, GlassBorderDim),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        text = stringResource(R.string.temp_bonus),
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = NeonGray100
                                    )
                                    if (canEditCombat) {
                                        OutlinedButton(
                                            onClick = { showAddBonusDialog = true },
                                            modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp),
                                            border = BorderStroke(1.dp, NeonSecondary.copy(alpha = 0.5f)),
                                            colors = ButtonDefaults.outlinedButtonColors(contentColor = NeonSecondary)
                                        ) {
                                            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Text(stringResource(R.string.add_bonus))
                                        }
                                    }
                                }

                                if (combatState.tempBonuses.isEmpty()) {
                                    Text(
                                        text = stringResource(R.string.temp_bonuses_empty),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = NeonGray400
                                    )
                                } else {
                                    combatState.tempBonuses.forEach { bonus ->
                                        val isHeroTarget = bonus.appliesTo == BonusTarget.HEROES
                                        val targetColor = if (isHeroTarget) HeroGreen else MonsterRed
                                        val sign = if (bonus.amount >= 0) "+" else ""
                                        Surface(
                                            color = NeonSurface,
                                            shape = RoundedCornerShape(12.dp),
                                            border = BorderStroke(1.dp, targetColor.copy(alpha = 0.35f)),
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            Row(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Text(
                                                    text = stringResource(
                                                        if (isHeroTarget) R.string.heroes else R.string.monsters
                                                    ),
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = targetColor,
                                                    fontWeight = FontWeight.Bold
                                                )
                                                Spacer(modifier = Modifier.width(8.dp))
                                                Text(
                                                    text = "${bonus.label} ($sign${bonus.amount})",
                                                    style = MaterialTheme.typography.bodyMedium,
                                                    color = NeonGray100,
                                                    modifier = Modifier.weight(1f),
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                                if (canEditCombat) {
                                                    IconButton(
                                                        onClick = { onRemoveTempBonus(bonus.id) },
                                                        modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                                                    ) {
                                                        Icon(
                                                            Icons.Default.Close,
                                                            contentDescription = stringResource(R.string.remove_temp_bonus, bonus.label),
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
                    }

                    // Collapsible Power Breakdown Panel (backed by CombatCalculator.getBreakdown)
                    if (breakdown != null) {
                        item(key = "combat_breakdown") {
                            Surface(
                                onClick = { showBreakdown = !showBreakdown },
                                color = NeonSurfaceVariant,
                                shape = RoundedCornerShape(16.dp),
                                border = BorderStroke(1.dp, GlassBorderDim),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .sizeIn(minHeight = 48.dp)
                            ) {
                                Column(modifier = Modifier.padding(14.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(
                                            Icons.Default.Info,
                                            contentDescription = null,
                                            tint = NeonSecondary,
                                            modifier = Modifier.size(18.dp)
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(
                                            text = stringResource(R.string.combat_breakdown_title),
                                            style = MaterialTheme.typography.titleSmall,
                                            color = NeonGray100,
                                            fontWeight = FontWeight.Bold,
                                            modifier = Modifier.weight(1f)
                                        )
                                        Text(
                                            text = "${breakdown.result.heroesPower} vs ${breakdown.result.monstersPower}",
                                            style = MaterialTheme.typography.labelLarge,
                                            color = NeonSecondary,
                                            fontWeight = FontWeight.Bold
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Icon(
                                            if (showBreakdown) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                            contentDescription = null,
                                            tint = NeonGray400
                                        )
                                    }

                                    if (showBreakdown) {
                                        Spacer(modifier = Modifier.height(10.dp))
                                        Text(
                                            text = stringResource(R.string.heroes),
                                            style = MaterialTheme.typography.labelLarge,
                                            color = HeroGreen,
                                            fontWeight = FontWeight.Bold
                                        )
                                        Spacer(modifier = Modifier.height(4.dp))
                                        breakdown.heroSources.forEach { src ->
                                            Row(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .padding(vertical = 2.dp),
                                                horizontalArrangement = Arrangement.SpaceBetween
                                            ) {
                                                Text(src.label, style = MaterialTheme.typography.bodySmall, color = NeonGray300)
                                                Text(
                                                    text = "${if (src.amount >= 0) "+" else ""}${src.amount}",
                                                    style = MaterialTheme.typography.bodySmall,
                                                    fontWeight = FontWeight.Bold,
                                                    color = NeonGray100
                                                )
                                            }
                                        }

                                        HorizontalDivider(
                                            color = GlassBorderDim,
                                            modifier = Modifier.padding(vertical = 8.dp)
                                        )

                                        Text(
                                            text = stringResource(R.string.monsters),
                                            style = MaterialTheme.typography.labelLarge,
                                            color = MonsterRed,
                                            fontWeight = FontWeight.Bold
                                        )
                                        Spacer(modifier = Modifier.height(4.dp))
                                        breakdown.monsterSources.forEach { src ->
                                            Row(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .padding(vertical = 2.dp),
                                                horizontalArrangement = Arrangement.SpaceBetween
                                            ) {
                                                Text(src.label, style = MaterialTheme.typography.bodySmall, color = NeonGray300)
                                                Text(
                                                    text = "${if (src.amount >= 0) "+" else ""}${src.amount}",
                                                    style = MaterialTheme.typography.bodySmall,
                                                    fontWeight = FontWeight.Bold,
                                                    color = NeonGray100
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // Resolve combat button (disabled when no monsters or already resolving)
                    if (canEditCombat && result != null) {
                        item(key = "end_combat_btn") {
                            val canResolve = combatState.monsters.isNotEmpty() &&
                                !isCombatResolving &&
                                pendingResolution.value == null
                            val isVictory = result.outcome == CombatOutcome.WIN
                            Button(
                                onClick = {
                                    pendingResolution.value = onEndCombat
                                    combatAnimation = if (isVictory) {
                                        CombatAnimationType.VICTORY
                                    } else {
                                        CombatAnimationType.DEFEAT
                                    }
                                },
                                enabled = canResolve,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .sizeIn(minHeight = 56.dp),
                                shape = RoundedCornerShape(16.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (isVictory) NeonSuccess else NeonError,
                                    contentColor = if (isVictory) NeonBackground else NeonWhite,
                                    disabledContainerColor = NeonSurfaceVariant,
                                    disabledContentColor = NeonGray400
                                )
                            ) {
                                if (isCombatResolving) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(20.dp),
                                        strokeWidth = 2.dp,
                                        color = NeonGray300
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                }
                                Text(
                                    text = if (isVictory) stringResource(R.string.combat_victory_end)
                                    else stringResource(R.string.combat_accept_defeat),
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }

                    // Participant ability reminders
                    item(key = "abilities_section") {
                        val participants = listOfNotNull(
                            gameState.players[combatState.mainPlayerId],
                            combatState.helperPlayerId?.let { gameState.players[it] }
                        )
                        AbilityReminders(participants)
                    }

                    item(key = "spacer_bottom") { Spacer(modifier = Modifier.height(16.dp)) }
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

        // Add Temporary Bonus dialog
        if (showAddBonusDialog) {
            AddTempBonusDialog(
                onDismiss = { showAddBonusDialog = false },
                onConfirm = { target, label, amount ->
                    onAddTempBonus(target, label, amount)
                    showAddBonusDialog = false
                }
            )
        }

        // Cancel combat confirmation
        if (showCancelConfirm) {
            AlertDialog(
                onDismissRequest = { showCancelConfirm = false },
                containerColor = NeonSurface,
                title = { Text(stringResource(R.string.combat_cancel), color = NeonGray100) },
                text = { Text(stringResource(R.string.combat_cancel_confirm), color = NeonGray300) },
                confirmButton = {
                    TextButton(
                        onClick = {
                            showCancelConfirm = false
                            onCancelCombat()
                            onBack()
                        },
                        modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                    ) {
                        Text(stringResource(R.string.confirm_action), color = NeonError)
                    }
                },
                dismissButton = {
                    TextButton(
                        onClick = { showCancelConfirm = false },
                        modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                    ) {
                        Text(stringResource(R.string.cancel), color = NeonGray300)
                    }
                }
            )
        }

        // Dice Result Overlay
        showDiceResult?.let { roll ->
            AlertDialog(
                onDismissRequest = { showDiceResult = null },
                containerColor = NeonSurface,
                icon = {
                    Text(text = "🎲", style = MaterialTheme.typography.displayLarge)
                },
                title = {
                    Text(
                        text = "${roll.playerName} ${stringResource(R.string.combat_rolled)} ${roll.result}",
                        style = MaterialTheme.typography.headlineSmall,
                        color = NeonGray100,
                        textAlign = TextAlign.Center
                    )
                },
                text = {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        if (roll.purpose == DiceRollPurpose.RUN_AWAY) {
                            Text(
                                text = if (roll.success) stringResource(R.string.combat_escaped)
                                else stringResource(R.string.combat_failed_escape),
                                style = MaterialTheme.typography.titleLarge,
                                color = if (roll.success) NeonSuccess else NeonError,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                },
                confirmButton = {}
            )
        }

        // Combat Animation Overlay
        combatAnimation?.let { type ->
            CombatResultOverlay(
                type = type,
                onAnimationFinished = {
                    combatAnimation = null
                    flushPendingResolution()
                }
            )
        }

        // Run Away dialog — resolves Elf/Halfling bonus for the ACTUAL rolling player (myPlayerId)
        if (showRunAwayDialog) {
            val runner = gameState.players[myPlayerId]
                ?: combatState?.let { gameState.players[it.mainPlayerId] }
            val labelContext = LocalContext.current
            RunAwayDialog(
                onDismiss = { showRunAwayDialog = false },
                onResult = { resultValue, success ->
                    showRunAwayDialog = false
                    onRollCombatDice(DiceRollPurpose.RUN_AWAY, resultValue, success)
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
    }
}

@Composable
private fun AddTempBonusDialog(
    onDismiss: () -> Unit,
    onConfirm: (target: BonusTarget, label: String, amount: Int) -> Unit
) {
    val defaultLabel = stringResource(R.string.bonus_default_label)
    var target by remember { mutableStateOf(BonusTarget.HEROES) }
    var label by remember { mutableStateOf("") }
    var amountText by remember { mutableStateOf("2") }
    val parsedAmount = (amountText.toIntOrNull() ?: 0)
        .coerceIn(GameEngine.TEMP_COMBAT_BONUS_MIN, GameEngine.TEMP_COMBAT_BONUS_MAX)

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = NeonSurface,
        title = { Text(stringResource(R.string.add_bonus), color = NeonGray100) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .imePadding(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = stringResource(R.string.applies_to),
                    style = MaterialTheme.typography.labelLarge,
                    color = NeonGray300
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val isHeroes = target == BonusTarget.HEROES
                    OutlinedButton(
                        onClick = { target = BonusTarget.HEROES },
                        modifier = Modifier
                            .weight(1f)
                            .sizeIn(minHeight = 48.dp),
                        border = BorderStroke(
                            1.dp,
                            if (isHeroes) HeroGreen else GlassBorderDim
                        ),
                        colors = ButtonDefaults.outlinedButtonColors(
                            containerColor = if (isHeroes) HeroGreen.copy(alpha = 0.16f) else NeonSurfaceVariant,
                            contentColor = if (isHeroes) HeroGreen else NeonGray300
                        )
                    ) {
                        Text(stringResource(R.string.heroes), fontWeight = FontWeight.Bold)
                    }
                    OutlinedButton(
                        onClick = { target = BonusTarget.MONSTER },
                        modifier = Modifier
                            .weight(1f)
                            .sizeIn(minHeight = 48.dp),
                        border = BorderStroke(
                            1.dp,
                            if (!isHeroes) MonsterRed else GlassBorderDim
                        ),
                        colors = ButtonDefaults.outlinedButtonColors(
                            containerColor = if (!isHeroes) MonsterRed.copy(alpha = 0.16f) else NeonSurfaceVariant,
                            contentColor = if (!isHeroes) MonsterRed else NeonGray300
                        )
                    ) {
                        Text(stringResource(R.string.monsters), fontWeight = FontWeight.Bold)
                    }
                }

                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it.take(40) },
                    label = { Text(stringResource(R.string.bonus_label_hint)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )

                OutlinedTextField(
                    value = amountText,
                    onValueChange = { v ->
                        val sanitized = if (v.startsWith("-")) {
                            "-" + v.drop(1).filter { it.isDigit() }.take(3)
                        } else {
                            v.filter { it.isDigit() }.take(3)
                        }
                        val n = sanitized.toIntOrNull()
                        amountText = if (n != null) {
                            n.coerceIn(GameEngine.TEMP_COMBAT_BONUS_MIN, GameEngine.TEMP_COMBAT_BONUS_MAX).toString()
                        } else {
                            sanitized
                        }
                    },
                    label = { Text(stringResource(R.string.bonus_amount_label)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    listOf(-5, -1, 1, 5).forEach { delta ->
                        OutlinedButton(
                            onClick = {
                                val next = (parsedAmount + delta)
                                    .coerceIn(GameEngine.TEMP_COMBAT_BONUS_MIN, GameEngine.TEMP_COMBAT_BONUS_MAX)
                                amountText = next.toString()
                            },
                            modifier = Modifier
                                .weight(1f)
                                .sizeIn(minWidth = 48.dp, minHeight = 48.dp),
                            contentPadding = PaddingValues(0.dp),
                            border = BorderStroke(1.dp, GlassBorder),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = NeonGray100)
                        ) {
                            Text(if (delta > 0) "+$delta" else "$delta")
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onConfirm(
                        target,
                        label.trim().ifEmpty { defaultLabel },
                        parsedAmount
                    )
                },
                enabled = parsedAmount != 0,
                modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = NeonPrimary,
                    contentColor = NeonWhite
                )
            ) {
                Text(stringResource(R.string.add_bonus))
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

    LaunchedEffect(query) {
        if (query.length >= 2) {
            delay(500)
            onSearch(query)
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = NeonSurface,
        title = { Text(stringResource(R.string.add_monster), color = NeonGray100) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .imePadding(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text(stringResource(R.string.search_monster_hint)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )

                if (query.isNotEmpty() && searchResults.isNotEmpty()) {
                    Text(
                        stringResource(R.string.results_label),
                        style = MaterialTheme.typography.labelSmall,
                        color = NeonGray300
                    )
                    LazyColumn(
                        modifier = Modifier.heightIn(max = 150.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        items(searchResults, key = { it.id }) { monster ->
                            Surface(
                                onClick = { onSelectMonster(monster) },
                                color = NeonSurfaceVariant,
                                shape = RoundedCornerShape(10.dp),
                                border = BorderStroke(1.dp, GlassBorderDim),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .sizeIn(minHeight = 48.dp)
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 12.dp, vertical = 8.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(monster.name, fontWeight = FontWeight.Bold, color = NeonGray100)
                                        Text(
                                            "💰 ${monster.treasures} • ⭐ ${monster.levels}",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = NeonGray300
                                        )
                                    }
                                    Text(
                                        "${stringResource(R.string.level)} ${monster.level}",
                                        color = NeonSecondary,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }
                    }
                }

                HorizontalDivider(color = GlassBorderDim)
                Text(
                    stringResource(R.string.or_create_new),
                    style = MaterialTheme.typography.labelSmall,
                    color = NeonGray300
                )

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
                            levels = if (n != null) n.coerceIn(1, 5).toString() else digits
                        },
                        label = { Text(stringResource(R.string.levels_reward_label)) },
                        modifier = Modifier.weight(1f),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true
                    )
                }
                OutlinedTextField(
                    value = badStuff,
                    onValueChange = { badStuff = it.take(200) },
                    label = { Text(stringResource(R.string.combat_bad_stuff_label)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier
                        .fillMaxWidth()
                        .sizeIn(minHeight = 48.dp)
                        .clickable(role = Role.Switch) { isUndead = !isUndead }
                ) {
                    Text(
                        stringResource(R.string.combat_is_undead),
                        style = MaterialTheme.typography.bodyMedium,
                        color = NeonGray100
                    )
                    Switch(checked = isUndead, onCheckedChange = null)
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (query.isNotBlank()) {
                        onCreateNew(
                            query.trim(),
                            (level.toIntOrNull() ?: 1).coerceIn(1, 20),
                            (modifier.toIntOrNull() ?: 0).coerceIn(-10, 10),
                            isUndead,
                            (treasures.toIntOrNull() ?: 1).coerceIn(0, 10),
                            (levels.toIntOrNull() ?: 1).coerceIn(1, 5),
                            badStuff.trim()
                        )
                    }
                },
                enabled = query.isNotBlank(),
                modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = NeonPrimary,
                    contentColor = NeonWhite
                )
            ) {
                Text(stringResource(R.string.create_new_button))
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
private fun CombatSideSection(
    title: String,
    power: Int,
    color: Color,
    content: @Composable ColumnScope.() -> Unit
) {
    Surface(
        color = color.copy(alpha = 0.08f),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, color.copy(alpha = 0.35f)),
        modifier = Modifier.fillMaxWidth()
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
                    fontWeight = FontWeight.Bold,
                    color = NeonGray100
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
private fun CharacterClass.displayName(): String = when (this) {
    CharacterClass.NONE -> stringResource(R.string.class_none)
    CharacterClass.WARRIOR -> stringResource(R.string.class_warrior)
    CharacterClass.WIZARD -> stringResource(R.string.class_wizard)
    CharacterClass.THIEF -> stringResource(R.string.class_thief)
    CharacterClass.CLERIC -> stringResource(R.string.class_cleric)
}

@Composable
private fun CharacterRace.displayName(): String = when (this) {
    CharacterRace.HUMAN -> stringResource(R.string.race_human)
    CharacterRace.ELF -> stringResource(R.string.race_elf)
    CharacterRace.DWARF -> stringResource(R.string.race_dwarf)
    CharacterRace.HALFLING -> stringResource(R.string.race_halfling)
}

/**
 * Lists the class and race abilities in play for the combat participants.
 */
@Composable
fun AbilityReminders(participants: List<PlayerState>) {
    val entries = participants.flatMap { player ->
        Abilities.forPlayer(player).map { player to it }
    }
    if (entries.isEmpty()) return

    var expanded by remember { mutableStateOf(false) }

    Surface(
        onClick = { expanded = !expanded },
        color = NeonSurfaceVariant,
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, GlassBorderDim),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .sizeIn(minHeight = 48.dp)
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
                    color = NeonGray400
                )
                return@Column
            }

            Spacer(modifier = Modifier.height(10.dp))

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
                                text = stringResource(ability.sourceKey.labelRes()) + ": " +
                                    stringResource(ability.descriptionKey.labelRes()),
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
