package com.munchkin.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.munchkin.app.R
import com.munchkin.app.network.GameHistoryItem
import com.munchkin.app.ui.components.GlassCard
import com.munchkin.app.ui.components.GlassTopAppBar
import com.munchkin.app.ui.theme.*
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val HISTORY_DATE_FORMATTER: DateTimeFormatter =
    DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm").withZone(ZoneId.systemDefault())

@Composable
fun HistoryScreen(
    history: List<GameHistoryItem>,
    isLoading: Boolean,
    error: String? = null,
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
                .navigationBarsPadding()
        ) {
            GlassTopAppBar(
                title = stringResource(R.string.game_history),
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
                if (error != null) {
                    item(key = "history_error") {
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
                } else if (history.isEmpty() && !isLoading) {
                    item(key = "history_empty") {
                        GlassCard(modifier = Modifier.fillMaxWidth()) {
                            Text(
                                text = stringResource(R.string.no_games_yet),
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                items(history, key = { it.id }) { game ->
                    HistoryItem(game)
                }
            }
        }
    }
}

@Composable
fun HistoryItem(game: GameHistoryItem) {
    val dateStr = HISTORY_DATE_FORMATTER.format(Instant.ofEpochMilli(game.endedAt))

    // The server resolves the winner to a name and tells us whether it was us, so
    // this no longer has to guess from a raw user id.
    val didIWin = game.didIWin
    val winnerLabel = when {
        didIWin -> stringResource(R.string.you)
        game.winnerName != null -> game.winnerName
        // No winner recorded: the game ended without one being confirmed.
        game.winnerId == null -> stringResource(R.string.history_no_winner)
        // A winner exists but has no account, so there is no name to show.
        else -> stringResource(R.string.history_guest_winner)
    }

    GlassCard(
        modifier = Modifier.fillMaxWidth(),
        borderColor = if (didIWin) NeonWarning.copy(alpha = 0.5f) else GlassBorder
    ) {
        Column {
            Row(
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = if (didIWin) {
                        stringResource(R.string.victory)
                    } else {
                        stringResource(R.string.history_game_finished)
                    },
                    style = MaterialTheme.typography.titleMedium,
                    color = if (didIWin) NeonWarning else NeonGray100,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = stringResource(R.string.history_players_count, game.playerCount),
                    style = MaterialTheme.typography.bodyMedium,
                    color = NeonSecondary
                )
            }
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = dateStr,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "${stringResource(R.string.winner_label)} ",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = winnerLabel,
                    color = when {
                        didIWin -> NeonWarning
                        game.winnerId == null -> MaterialTheme.colorScheme.onSurfaceVariant
                        else -> NeonGray100
                    },
                    fontWeight = FontWeight.Bold
                )
            }
            val durationMin = (game.durationMs / 60_000L).toInt()
            if (durationMin > 0 || game.monstersKilled > 0 || game.deaths > 0) {
                Spacer(modifier = Modifier.height(6.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (durationMin > 0) {
                        Text(
                            text = "⏱️ ${stringResource(R.string.stats_duration_min, durationMin)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (game.monstersKilled > 0) {
                        Text(
                            text = stringResource(R.string.stats_kills_badge, game.monstersKilled),
                            style = MaterialTheme.typography.labelSmall,
                            color = NeonSecondary
                        )
                    }
                    if (game.deaths > 0) {
                        Text(
                            text = stringResource(R.string.stats_deaths_badge, game.deaths),
                            style = MaterialTheme.typography.labelSmall,
                            color = NeonError
                        )
                    }
                }
            }
        }
    }
}
