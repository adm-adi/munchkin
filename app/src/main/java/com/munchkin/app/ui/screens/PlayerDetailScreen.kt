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
import com.munchkin.app.ui.components.TraitChip

/**
 * Player detail screen for editing own stats.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerDetailScreen(
    player: PlayerState,
    onIncrementLevel: () -> Unit,
    onDecrementLevel: () -> Unit,
    onIncrementGear: () -> Unit,
    onDecrementGear: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.edit_player)) },
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
                maxValue = 10
            )
            
            Spacer(modifier = Modifier.height(32.dp))
            
            // Gear counter
            CounterButton(
                value = player.gearBonus,
                label = stringResource(R.string.gear),
                onIncrement = onIncrementGear,
                onDecrement = onDecrementGear,
                showSign = true
            )
            
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
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        text = "${player.combatPower}",
                        style = MaterialTheme.typography.headlineMedium
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

/**
 * Dialog for picking a catalog entry or creating a new one.
 */
@Composable
fun CatalogPickerDialog(
    title: String,
    entries: List<CatalogEntry>,
    excludeIds: List<EntryId>,
    onSelect: (EntryId) -> Unit,
    onCreate: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var searchQuery by remember { mutableStateOf("") }
    
    val filteredEntries = entries.filter { entry ->
        !excludeIds.contains(entry.entryId) &&
        (searchQuery.isEmpty() || entry.displayName.contains(searchQuery, ignoreCase = true))
    }
    
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    label = { Text(stringResource(R.string.search)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                
                Spacer(modifier = Modifier.height(16.dp))
                
                if (filteredEntries.isEmpty() && searchQuery.isNotBlank()) {
                    // Create new option
                    TextButton(
                        onClick = {
                            onCreate(searchQuery)
                            onDismiss()
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(R.string.create_entry, searchQuery))
                    }
                } else {
                    Column(
                        modifier = Modifier.heightIn(max = 300.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        filteredEntries.forEach { entry ->
                            TextButton(
                                onClick = { onSelect(entry.entryId) },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(entry.displayName)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}
