package com.munchkin.app.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.munchkin.app.R
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
    error: String? = null,
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
                .navigationBarsPadding()
        ) {
            GlassTopAppBar(
                title = stringResource(R.string.global_ranking),
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
                                contentDescription = stringResource(R.string.reload),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            )

            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp),
                contentPadding = PaddingValues(top = 8.dp, bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (self != null) {
                    item(key = "self_stats") {
                        SelfStatsCard(self)
                    }
                }

                item(key = "standings_title") {
                    Text(
                        text = stringResource(R.string.leaderboard_standings),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }

                if (error != null) {
                    item(key = "leaderboard_error") {
                        GlassCard(
                            modifier = Modifier.fillMaxWidth(),
                            borderColor = NeonError.copy(alpha = 0.45f)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    Icons.Default.ErrorOutline,
                                    contentDescription = null,
                                    tint = NeonError
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Text(
                                    text = error,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = NeonGray100,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                            Spacer(modifier = Modifier.height(12.dp))
                            OutlinedButton(
                                onClick = onRefresh,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 48.dp)
                            ) {
                                Text(stringResource(R.string.reconnect_retry))
                            }
                        }
                    }
                } else if (leaderboard.isEmpty() && !isLoading) {
                    item(key = "leaderboard_empty") {
                        GlassCard(modifier = Modifier.fillMaxWidth()) {
                            Column {
                                Text(
                                    text = stringResource(R.string.leaderboard_empty_title),
                                    color = NeonGray200,
                                    style = MaterialTheme.typography.bodyMedium
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = stringResource(R.string.leaderboard_empty_subtitle),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }

                itemsIndexed(leaderboard, key = { _, entry -> entry.id }) { index, entry ->
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
        Column {
            Text(
                text = stringResource(R.string.leaderboard_your_stats),
                style = MaterialTheme.typography.labelMedium,
                color = LumaAccent,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                StatBlock(
                    value = "${self.wins}",
                    label = stringResource(R.string.stat_wins),
                    color = NeonWarning
                )
                StatBlock(
                    value = "${self.gamesPlayed}",
                    label = stringResource(R.string.stat_games),
                    color = NeonGray100
                )
                StatBlock(
                    value = "${self.winRate}%",
                    label = stringResource(R.string.stat_win_rate),
                    color = NeonSecondary
                )
                StatBlock(
                    value = if (self.rank > 0) "#${self.rank}" else "—",
                    label = stringResource(R.string.leaderboard_rank),
                    color = if (self.rank > 0) LumaAccent else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (self.rank == 0) {
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = stringResource(R.string.leaderboard_unranked_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun StatBlock(value: String, label: String, color: Color) {
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
            color = MaterialTheme.colorScheme.onSurfaceVariant
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
        2 -> NeonGray200
        3 -> NeonSecondary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    val rankSize = when (rank) {
        1 -> 24.sp
        else -> 18.sp
    }

    GlassCard(
        modifier = Modifier.fillMaxWidth(),
        borderColor = if (isCurrentUser) LumaAccent else GlassBorder
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
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

            // Name plus the record behind the win count
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = entry.username,
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (isCurrentUser) LumaAccent else NeonGray100,
                    fontWeight = if (isCurrentUser) FontWeight.Bold else FontWeight.Normal
                )
                Text(
                    text = stringResource(
                        R.string.leaderboard_entry_stats,
                        entry.gamesPlayed,
                        entry.winRate
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (entry.currentStreak >= 2 || entry.totalMonstersKilled > 0) {
                    Spacer(modifier = Modifier.height(2.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (entry.currentStreak >= 2) {
                            Text(
                                text = stringResource(R.string.stats_streak_badge, entry.currentStreak),
                                style = MaterialTheme.typography.labelSmall,
                                color = NeonWarning,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                        if (entry.totalMonstersKilled > 0) {
                            Text(
                                text = stringResource(R.string.stats_kills_badge, entry.totalMonstersKilled),
                                style = MaterialTheme.typography.labelSmall,
                                color = NeonSecondary
                            )
                        }
                    }
                }
            }

            // Wins
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = "${entry.wins}",
                    style = MaterialTheme.typography.titleLarge,
                    color = NeonWarning,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = if (entry.wins == 1) {
                        stringResource(R.string.leaderboard_win_singular)
                    } else {
                        stringResource(R.string.stat_wins)
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
