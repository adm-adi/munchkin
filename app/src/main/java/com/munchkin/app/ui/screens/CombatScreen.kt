package com.munchkin.app.ui.screens

import androidx.compose.animation.*
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.munchkin.app.R
import com.munchkin.app.core.*
import com.munchkin.app.ui.components.CombatResultBanner
import com.munchkin.app.ui.theme.MunchkinTheme

/**
 * Combat calculator screen.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CombatScreen(
    gameState: GameState,
    myPlayerId: PlayerId,
    onStartCombat: () -> Unit,
    onAddMonster: (name: String, level: Int, modifier: Int) -> Unit,
    onEndCombat: (CombatOutcome, Int) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val combatState = gameState.combat
    val myPlayer = gameState.players[myPlayerId]
    
    // Monster input state
    var monsterName by remember { mutableStateOf("") }
    var monsterLevel by remember { mutableStateOf("1") }
    var monsterModifier by remember { mutableStateOf("0") }
    var showAddMonster by remember { mutableStateOf(false) }
    
    // Calculate result
    val result = remember(combatState, gameState) {
        combatState?.let { CombatCalculator.calculateResult(it, gameState) }
    }
    
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.combat_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                }
            )
        }
    ) { padding ->
        if (combatState == null) {
            // No active combat - show start button
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Default.FlashOn,
                        contentDescription = null,
                        modifier = Modifier.size(64.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "Inicia un combate",
                        style = MaterialTheme.typography.headlineSmall
                    )
                    Spacer(modifier = Modifier.height(24.dp))
                    Button(
                        onClick = onStartCombat,
                        modifier = Modifier.height(56.dp)
                    ) {
                        Text("Iniciar Combate")
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
                
                // Result banner (always visible with animation)
                result?.let { r ->
                    item {
                        CombatResultBanner(
                            isWin = r.outcome == CombatOutcome.WIN,
                            difference = r.diff
                        )
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
                            Text(
                                text = "${player.name}: Poder ${player.combatPower}",
                                style = MaterialTheme.typography.bodyLarge
                            )
                        }
                        
                        combatState.helperPlayerId?.let { helperId ->
                            val helper = gameState.players[helperId]
                            helper?.let { player ->
                                Text(
                                    text = "${player.name} (ayuda): Poder ${player.combatPower}",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
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
                                text = "Sin monstruos",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        
                        combatState.monsters.forEach { monster ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = monster.name,
                                    style = MaterialTheme.typography.bodyLarge
                                )
                                Text(
                                    text = "Nivel ${monster.baseLevel}" + 
                                        if (monster.flatModifier != 0) " (${if (monster.flatModifier > 0) "+" else ""}${monster.flatModifier})" 
                                        else "",
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            }
                        }
                        
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
                }
                
                // End combat button
                item {
                    result?.let { r ->
                        Button(
                            onClick = { 
                                val levels = if (r.outcome == CombatOutcome.WIN) combatState.monsters.size else 0
                                onEndCombat(r.outcome, levels)
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(56.dp),
                            shape = RoundedCornerShape(16.dp)
                        ) {
                            Text(stringResource(R.string.end_combat))
                        }
                    }
                }
                
                item { Spacer(modifier = Modifier.height(16.dp)) }
            }
        }
    }
    
    // Add monster dialog
    if (showAddMonster) {
        AlertDialog(
            onDismissRequest = { showAddMonster = false },
            title = { Text(stringResource(R.string.add_monster)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = monsterName,
                        onValueChange = { monsterName = it },
                        label = { Text(stringResource(R.string.monster_name)) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedTextField(
                            value = monsterLevel,
                            onValueChange = { monsterLevel = it.filter { c -> c.isDigit() } },
                            label = { Text(stringResource(R.string.monster_level)) },
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                        )
                        OutlinedTextField(
                            value = monsterModifier,
                            onValueChange = { 
                                monsterModifier = it.filter { c -> c.isDigit() || c == '-' }
                            },
                            label = { Text(stringResource(R.string.modifier)) },
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (monsterName.isNotBlank()) {
                            onAddMonster(
                                monsterName,
                                monsterLevel.toIntOrNull() ?: 1,
                                monsterModifier.toIntOrNull() ?: 0
                            )
                            monsterName = ""
                            monsterLevel = "1"
                            monsterModifier = "0"
                            showAddMonster = false
                        }
                    },
                    enabled = monsterName.isNotBlank()
                ) {
                    Text(stringResource(R.string.ok))
                }
            },
            dismissButton = {
                TextButton(onClick = { showAddMonster = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }
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
                    text = "Poder: $power",
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
