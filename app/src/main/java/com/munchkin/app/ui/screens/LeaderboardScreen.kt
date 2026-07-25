package com.munchkin.app.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.munchkin.app.core.Gender
import com.munchkin.app.network.LeaderboardEntry
import com.munchkin.app.network.LeaderboardSelf
import com.munchkin.app.ui.components.GlassCard
import com.munchkin.app.ui.components.GlassTopAppBar
import com.munchkin.app.ui.theme.*

@Composable
fun LeaderboardScreen(
    leaderboard: List<LeaderboardEntry>,
    isLoading: Boolean,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    /** Totals for the signed-in player; null when browsing without an account. */
    self: LeaderboardSelf? = null,
    /** Used to highlight the signed-in player's own row. */
    currentUserId: String? = null
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
                if (self != null) {
                    item {
                        Spacer(modifier = Modifier.height(8.dp))
                        SelfStatsCard(self)
                    }
                }

                item {
                    Text(
                        text = "Clasificación",
                        style = MaterialTheme.typography.titleMedium,
                        color = NeonGray300,
                        modifier = Modifier.padding(vertical = 8.dp)
                    )
                }

                if (leaderboard.isEmpty() && !isLoading) {
                    item {
                        GlassCard {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Text(
                                    text = "Aún no hay partidas registradas.",
                                    color = NeonGray300
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "Solo cuentan las partidas terminadas con un ganador " +
                                        "confirmado, y solo puntúan los jugadores con cuenta.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = NeonGray500
                                )
                            }
                        }
                    }
                }

                itemsIndexed(leaderboard) { index, entry ->
                    LeaderboardItem(
                        rank = index + 1,
                        entry = entry,
                        isCurrentUser = currentUserId != null && entry.id == currentUserId
                    )
                }
            }
        }
    }
}

/**
 * The signed-in player's own totals. Shown above the table so they can see their
 * standing even when they fall outside the returned page of the ranking.
 */
@Composable
private fun SelfStatsCard(self: LeaderboardSelf) {
    GlassCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "Tus estadísticas",
                style = MaterialTheme.typography.labelMedium,
                color = LumaAccent,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                StatBlock(value = "${self.wins}", label = "Victorias", color = NeonWarning)
                StatBlock(value = "${self.gamesPlayed}", label = "Partidas", color = NeonGray100)
                StatBlock(value = "${self.winRate}%", label = "Acierto", color = NeonSecondary)
                StatBlock(
                    value = if (self.rank > 0) "#${self.rank}" else "—",
                    label = "Puesto",
                    color = if (self.rank > 0) LumaAccent else NeonGray500
                )
            }
            if (self.rank == 0) {
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = "Todavía no estás entre los primeros. ¡Sigue jugando!",
                    style = MaterialTheme.typography.bodySmall,
                    color = NeonGray500
                )
            }
        }
    }
}

@Composable
private fun StatBlock(value: String, label: String, color: androidx.compose.ui.graphics.Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = value,
            style = MaterialTheme.typography.titleLarge,
            color = color,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = NeonGray500
        )
    }
}

@Composable
fun LeaderboardItem(
    rank: Int,
    entry: LeaderboardEntry,
    isCurrentUser: Boolean = false
) {
    val rankColor = when (rank) {
        1 -> NeonWarning
        2 -> NeonGray300 // Silver-ish
        3 -> NeonSecondary // Bronze-ish substitute
        else -> NeonGray500
    }

    val rankSize = when (rank) {
        1 -> 24.sp
        else -> 18.sp
    }

    GlassCard(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (isCurrentUser) {
                    Modifier.border(1.dp, LumaAccent, MaterialTheme.shapes.medium)
                } else {
                    Modifier
                }
            )
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

            // Avatar, using the account's own slot and gender.
            Image(
                painter = painterResource(
                    id = AvatarResources.getAvatarDrawable(
                        entry.avatarId,
                        entry.gender == Gender.F
                    )
                ),
                contentDescription = AvatarResources.getAvatarName(entry.avatarId),
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(getAvatarColor(entry.avatarId).copy(alpha = 0.25f))
            )

            Spacer(modifier = Modifier.width(16.dp))

            // Name plus the record behind the win count, so a player who won once in
            // one game is not shown as equal to one who won once in fifty.
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = entry.username,
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (isCurrentUser) LumaAccent else NeonGray100,
                    fontWeight = if (isCurrentUser) FontWeight.Bold else FontWeight.Normal
                )
                Text(
                    text = "${entry.gamesPlayed} partidas · ${entry.winRate}% acierto",
                    style = MaterialTheme.typography.labelSmall,
                    color = NeonGray500
                )
            }

            // Wins
            Column(
                horizontalAlignment = Alignment.End
            ) {
                Text(
                    text = "${entry.wins}",
                    style = MaterialTheme.typography.titleLarge,
                    color = NeonWarning,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = if (entry.wins == 1) "Victoria" else "Victorias",
                    style = MaterialTheme.typography.labelSmall,
                    color = NeonGray500
                )
            }
        }
    }
}
