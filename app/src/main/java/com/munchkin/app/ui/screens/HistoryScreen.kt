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
    
    // We assume winnerId matches current user for "WIN" or logic elsewhere helps
    // Actually ID is ambiguous without knowing "my" ID here.
    // For now we just show Winner ID or "Tú" if we could check.
    
    GlassCard(
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "Partida Finalizada",
                    style = MaterialTheme.typography.titleMedium,
                    color = NeonGray100,
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
                 // If winnerId is UUID, it's ugly. Server should probably return Name.
                 // But Protocol.kt defines GameHistoryItem with winnerId.
                 // Assuming server sends ID.
                 // Ideally server sends name, but let's stick to ID or "Unknown" for now.
                 // Improving: Server Step 8596 sends winnerId.
                 // We can't resolve name easily without user cache.
                 // Let's just show "Jugador" or ID prefix.
                 Text(
                     text = if (game.winnerId == "aborted") "Cancelada" else "Jugador",
                     color = if (game.winnerId == "aborted") NeonError else NeonWarning,
                     fontWeight = FontWeight.Bold
                 )
            }
        }
    }
}
