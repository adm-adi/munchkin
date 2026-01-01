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
import com.munchkin.app.network.CatalogMonster
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
    monsterSearchResults: List<CatalogMonster>,
    onStartCombat: () -> Unit,
    onAddMonster: (name: String, level: Int, modifier: Int, isUndead: Boolean) -> Unit,
    onSearchMonsters: (String) -> Unit,
    onRequestCreateGlobalMonster: (String, Int, Int, Boolean) -> Unit,
    onAddHelper: (PlayerId) -> Unit,
    onRemoveHelper: () -> Unit,
    onEndCombat: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val combatState = gameState.combat
    // myPlayer removed - was unused
    
    // Monster input state
    var showAddMonster by remember { mutableStateOf(false) }
    var showAddHelper by remember { mutableStateOf(false) }

    if (showAddHelper) {
        AlertDialog(
            onDismissRequest = { showAddHelper = false },
            title = { Text("Seleccionar Ayudante") },
            text = {
                LazyColumn {
                    val candidates = gameState.playerList.filter { 
                        it.playerId != combatState?.mainPlayerId && it.playerId != combatState?.helperPlayerId 
                    }
                    if (candidates.isEmpty()) {
                        item { Text("No hay más jugadores disponibles.") }
                    } else {
                        items(candidates) { player ->
                            ListItem(
                                headlineContent = { Text(player.name) },
                                supportingContent = { Text("Nivel ${player.level} | Poder ${player.combatPower}") },
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
                    Text("Cancelar")
                }
            }
        )
    }
    
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
                
                // Result banner (only shows when monsters are added)
                if (combatState.monsters.isNotEmpty()) {
                    result?.let { r ->
                        item {
                            CombatResultBanner(
                                isWin = r.outcome == CombatOutcome.WIN,
                                difference = r.diff
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
                            Text(
                                text = "${player.name}: Poder ${player.combatPower}",
                                style = MaterialTheme.typography.bodyLarge
                            )
                        }
                        
                        combatState.helperPlayerId?.let { helperId ->
                            Spacer(modifier = Modifier.height(8.dp))
                            val helper = gameState.players[helperId]
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = "${helper?.name ?: "?"} (ayuda)",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                IconButton(onClick = onRemoveHelper) {
                                    Icon(Icons.Default.Close, contentDescription = "Quitar ayudante", tint = MaterialTheme.colorScheme.error)
                                }
                            }
                            helper?.let { player ->
                                Text(
                                    text = "Poder ${player.combatPower}",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        if (combatState.helperPlayerId == null) {
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
                    result?.let { _ ->
                        Button(
                            onClick = onEndCombat,
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
    
    // Debounce search
    LaunchedEffect(query) {
        if (query.length >= 2) {
            kotlinx.coroutines.delay(500)
            onSearch(query)
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Añadir Monstruo") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("Buscar Monstruo (Ej: Dragón...)") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )

                if (query.isNotEmpty() && searchResults.isNotEmpty()) {
                    Text("Resultados:", style = MaterialTheme.typography.labelSmall)
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
                Text("O crear nuevo:", style = MaterialTheme.typography.labelSmall)
                
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = level,
                        onValueChange = { level = it.filter { c -> c.isDigit() } },
                        label = { Text("Nivel") },
                        modifier = Modifier.weight(1f),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (query.isNotBlank()) {
                         // Default logic: Create new if not clicked in list
                         onCreateNew(query, level.toIntOrNull() ?: 1, 0, false)
                    }
                },
                enabled = query.isNotBlank()
            ) {
                Text("Crear Nuevo")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancelar") }
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
