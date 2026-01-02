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
    isReadOnly: Boolean = false,
    modifier: Modifier = Modifier
) {
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
                maxValue = 10,
                enabled = !isReadOnly
            )
            
            Spacer(modifier = Modifier.height(32.dp))
            
            // Gear counter
            CounterButton(
                value = player.gearBonus,
                label = stringResource(R.string.gear),
                onIncrement = onIncrementGear,
                onDecrement = onDecrementGear,
                showSign = true,
                enabled = !isReadOnly
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

