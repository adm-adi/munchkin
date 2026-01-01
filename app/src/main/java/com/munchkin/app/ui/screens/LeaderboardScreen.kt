package com.munchkin.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.munchkin.app.network.LeaderboardEntry
import com.munchkin.app.ui.components.GlassCard
import com.munchkin.app.ui.components.GlassTopAppBar
import com.munchkin.app.ui.theme.*

@Composable
fun LeaderboardScreen(
    leaderboard: List<LeaderboardEntry>,
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
            .background(LumaGray950)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
        ) {
            GlassTopAppBar(
                title = "Ranking Global",
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
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item {
                    Text(
                        text = "Top Ganadores",
                        style = MaterialTheme.typography.titleMedium,
                        color = LumaGray300,
                        modifier = Modifier.padding(vertical = 16.dp)
                    )
                }
                
                if (leaderboard.isEmpty() && !isLoading) {
                    item {
                         GlassCard {
                             Text(
                                 text = "No hay datos aún.",
                                 color = LumaGray500,
                                 modifier = Modifier.padding(16.dp)
                             )
                         }
                    }
                }

                itemsIndexed(leaderboard) { index, entry ->
                    LeaderboardItem(index + 1, entry)
                }
            }
        }
    }
}

@Composable
fun LeaderboardItem(rank: Int, entry: LeaderboardEntry) {
    val rankColor = when (rank) {
        1 -> Gold400
        2 -> LumaGray300 // Silver-ish
        3 -> LumaAccent // Bronze-ish substitute
        else -> LumaGray500
    }
    
    val rankSize = when (rank) {
        1 -> 24.sp
        else -> 18.sp
    }

    GlassCard(
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Rank
            Text(
                text = "#$rank",
                fontSize = rankSize,
                color = rankColor,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.width(48.dp)
            )
            
            // Avatar (Placeholder)
            Box(
                 modifier = Modifier
                     .size(40.dp)
                     .background(LumaPrimary.copy(alpha = 0.2f), shape = MaterialTheme.shapes.small),
                 contentAlignment = Alignment.Center
            ) {
                Text(
                    text = entry.username.take(1).uppercase(),
                    color = LumaPrimary,
                    fontWeight = FontWeight.Bold
                )
            }
            
            Spacer(modifier = Modifier.width(16.dp))
            
            // Name
            Text(
                text = entry.username,
                style = MaterialTheme.typography.bodyLarge,
                color = LumaGray50,
                modifier = Modifier.weight(1f)
            )
            
            // Wins
            Column(
                horizontalAlignment = Alignment.End
            ) {
                Text(
                    text = "${entry.wins}",
                    style = MaterialTheme.typography.titleLarge,
                    color = Gold400,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "Victorias",
                    style = MaterialTheme.typography.labelSmall,
                    color = LumaGray600
                )
            }
        }
    }
}
