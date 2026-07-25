package com.munchkin.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.munchkin.app.network.GameHistoryItem
import com.munchkin.app.ui.components.GlassCard
import com.munchkin.app.ui.components.GlassTopAppBar
import com.munchkin.app.ui.theme.*
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun HistoryScreen(
    history: List<GameHistoryItem>,
    isLoading: Boolean,
    onBack: () -> Unit,
    onRefresh: () -> Unit
) {
    LaunchedEffect(Unit) {
        onRefresh()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(NeonBackground)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
        ) {
            GlassTopAppBar(
                title = "Historial de Partidas",
                navigationIcon = Icons.AutoMirrored.Filled.ArrowBack,
                onNavigationClick = onBack,
                actions = {
                    IconButton(onClick = onRefresh) {
                        if (isLoading) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                color = LumaAccent,
                                strokeWidth = 2.dp
                            )
                        } else {
                            Icon(
                                Icons.Default.Refresh,
                                contentDescription = "Recargar",
                                tint = NeonGray400
                            )
                        }
                    }
                }
            )

            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp),
                contentPadding = PaddingValues(bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (history.isEmpty() && !isLoading) {
                    item {
                        GlassCard {
                            Text(
                                text = "No has jugado ninguna partida aún.",
                                color = NeonGray500,
                                modifier = Modifier.padding(16.dp)
                            )
                        }
                    }
                }

                items(history) { game ->
                    HistoryItem(game)
                }
            }
        }
    }
}

@Composable
fun HistoryItem(game: GameHistoryItem) {
    val dateFormat = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault())
    val dateStr = dateFormat.format(Date(game.endedAt))
    
    // The server resolves the winner to a name and tells us whether it was us, so
    // this no longer has to guess from a raw user id.
    val didIWin = game.didIWin
    val winnerLabel = when {
        didIWin -> "Tú"
        game.winnerName != null -> game.winnerName
        // No winner recorded: the game ended without one being confirmed.
        game.winnerId == null -> "Sin ganador"
        // A winner exists but has no account, so there is no name to show.
        else -> "Invitado"
    }

    GlassCard(
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = if (didIWin) "Victoria" else "Partida Finalizada",
                    style = MaterialTheme.typography.titleMedium,
                    color = if (didIWin) NeonWarning else NeonGray100,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "${game.playerCount} Jugadores",
                    style = MaterialTheme.typography.bodyMedium,
                    color = NeonSecondary
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = dateStr,
                style = MaterialTheme.typography.labelMedium,
                color = NeonGray500
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                 Text("Ganador: ", color = NeonGray300)
                 Text(
                     text = winnerLabel,
                     color = when {
                         didIWin -> NeonWarning
                         game.winnerId == null -> NeonGray500
                         else -> NeonGray100
                     },
                     fontWeight = FontWeight.Bold
                 )
            }
        }
    }
}
