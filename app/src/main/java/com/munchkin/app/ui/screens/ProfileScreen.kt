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
import com.munchkin.app.network.GameHistoryItem
import com.munchkin.app.network.UserProfile
import com.munchkin.app.ui.components.GlassCard
import com.munchkin.app.ui.components.GlassTopAppBar
import com.munchkin.app.ui.theme.*
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileScreen(
    userProfile: UserProfile,
    gameHistory: List<GameHistoryItem>,
    isLoading: Boolean,
    onBack: () -> Unit,
    onRefresh: () -> Unit
) {
    // Initial load
    LaunchedEffect(Unit) {
        onRefresh()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(LumaGray950)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
        ) {
            GlassTopAppBar(
                title = "Perfil de Jugador",
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
                                tint = LumaGray400
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
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Header (User Info)
                item {
                    ProfileHeader(userProfile)
                }

                // Stats Summary
                item {
                    StatsSummary(userProfile, gameHistory)
                }

                item {
                    Text(
                        text = "Historial de Partidas",
                        style = MaterialTheme.typography.titleMedium,
                        color = LumaGray300,
                        modifier = Modifier.padding(vertical = 8.dp)
                    )
                }

                if (gameHistory.isEmpty() && !isLoading) {
                    item {
                        GlassCard(modifier = Modifier.fillMaxWidth()) {
                            Text(
                                text = "Aún no has jugado ninguna partida.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = LumaGray500,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center
                            )
                        }
                    }
                }

                items(gameHistory) { game ->
                    GameHistoryCard(game, userProfile.id)
                }
            }
        }
    }
}

@Composable
fun ProfileHeader(user: UserProfile) {
    GlassCard(
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Avatar Placeholder (We could map avatarId to image resource)
            Box(
                modifier = Modifier
                    .size(64.dp)
                    .background(LumaPrimary.copy(alpha = 0.2f), shape = MaterialTheme.shapes.medium),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = user.username.take(1).uppercase(),
                    style = MaterialTheme.typography.headlineMedium,
                    color = LumaPrimary
                )
            }
            
            Spacer(modifier = Modifier.width(16.dp))
            
            Column {
                Text(
                    text = user.username,
                    style = MaterialTheme.typography.titleLarge,
                    color = LumaGray50
                )
                Text(
                    text = user.email,
                    style = MaterialTheme.typography.bodyMedium,
                    color = LumaGray500
                )
            }
        }
    }
}

@Composable
fun StatsSummary(user: UserProfile, history: List<GameHistoryItem>) {
    val totalGames = history.size
    val wins = history.count { it.winnerId == user.id }
    val winRate = if (totalGames > 0) (wins.toFloat() / totalGames * 100).toInt() else 0

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        StatCard(
            label = "Partidas",
            value = totalGames.toString(),
            modifier = Modifier.weight(1f)
        )
        StatCard(
            label = "Victorias",
            value = wins.toString(),
            modifier = Modifier.weight(1f),
            color = Gold400
        )
        StatCard(
            label = "Win Rate",
            value = "$winRate%",
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
fun StatCard(
    label: String, 
    value: String, 
    modifier: Modifier = Modifier,
    color: Color = LumaGray50
) {
    GlassCard(modifier = modifier) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = value,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = color
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = LumaGray500
            )
        }
    }
}

@Composable
fun GameHistoryCard(game: GameHistoryItem, myUserId: String) {
    val isWin = game.winnerId == myUserId
    val date = Date(game.endedAt)
    val formattedDate = SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.getDefault()).format(date)

    GlassCard(
        modifier = Modifier.fillMaxWidth(),
        borderColor = if (isWin) Gold400.copy(alpha = 0.5f) else null
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column {
                Text(
                    text = if (isWin) "🏆 ¡Victoria!" else "Derrota",
                    style = MaterialTheme.typography.titleMedium,
                    color = if (isWin) Gold400 else LumaGray300,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = formattedDate,
                    style = MaterialTheme.typography.bodySmall,
                    color = LumaGray500
                )
            }
            
            // Should verify if we have player count in GameHistoryItem. 
            // Previous check showed: val id: String, val endedAt: Long, val winnerId: String
            // server.js handles: playerCount, but let's check Kotlin definition.
            
            // Assuming we don't have playercount yet on client model, omitting.
        }
    }
}
