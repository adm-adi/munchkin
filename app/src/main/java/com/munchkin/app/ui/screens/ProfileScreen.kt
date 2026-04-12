package com.munchkin.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.munchkin.app.R
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
    error: String?,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onClearError: () -> Unit,
    onUpdateProfile: (String?, String?) -> Unit
) {
    // Initial load
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
                title = stringResource(R.string.profile_title),
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
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Header (User Info)
                item {
                    ProfileHeader(
                        user = userProfile,
                        isLoading = isLoading,
                        error = error,
                        onClearError = onClearError,
                        onUpdateProfile = onUpdateProfile
                    )
                }

                // Stats Summary
                item {
                    StatsSummary(userProfile, gameHistory)
                }

                item {
                    Text(
                        text = stringResource(R.string.game_history),
                        style = MaterialTheme.typography.titleMedium,
                        color = NeonGray300,
                        modifier = Modifier.padding(vertical = 8.dp)
                    )
                }

                if (gameHistory.isEmpty() && !isLoading) {
                    item {
                        GlassCard(modifier = Modifier.fillMaxWidth()) {
                            Text(
                                text = stringResource(R.string.no_games_yet),
                                style = MaterialTheme.typography.bodyMedium,
                                color = NeonGray500,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                                textAlign = TextAlign.Center
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
fun ProfileHeader(
    user: UserProfile,
    isLoading: Boolean = false,
    error: String? = null,
    onClearError: () -> Unit = {},
    onUpdateProfile: (String?, String?) -> Unit = { _, _ -> }
) {
    var isEditing by remember { mutableStateOf(false) }
    var editedUsername by remember { mutableStateOf(user.username) }
    var editedPassword by remember { mutableStateOf("") }
    var savePending by remember { mutableStateOf(false) }

    // Close edit mode and clear password when save completes without error
    LaunchedEffect(isLoading) {
        if (savePending && !isLoading && error == null) {
            isEditing = false
            editedPassword = ""
            savePending = false
        }
    }

    GlassCard(
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(8.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Avatar Placeholder
                Box(
                    modifier = Modifier
                        .size(64.dp)
                        .background(NeonPrimary.copy(alpha = 0.2f), shape = MaterialTheme.shapes.medium),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = user.username.take(1).uppercase(),
                        style = MaterialTheme.typography.headlineMedium,
                        color = NeonPrimary
                    )
                }

                Spacer(modifier = Modifier.width(16.dp))

                if (!isEditing) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = user.username,
                            style = MaterialTheme.typography.titleLarge,
                            color = NeonGray100
                        )
                        Text(
                            text = user.email,
                            style = MaterialTheme.typography.bodyMedium,
                            color = NeonGray500
                        )
                    }
                    IconButton(onClick = { isEditing = true; onClearError() }) {
                        Icon(
                            Icons.Default.Edit,
                            contentDescription = stringResource(R.string.edit_profile),
                            tint = NeonSecondary
                        )
                    }
                } else {
                    Column(modifier = Modifier.weight(1f)) {
                        OutlinedTextField(
                            value = editedUsername,
                            onValueChange = { editedUsername = it; onClearError() },
                            label = { Text(stringResource(R.string.username_label)) },
                            singleLine = true,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = NeonGray100,
                                unfocusedTextColor = NeonGray100,
                                focusedBorderColor = NeonSecondary,
                                unfocusedBorderColor = NeonGray500
                            )
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedTextField(
                            value = editedPassword,
                            onValueChange = { editedPassword = it; onClearError() },
                            label = { Text(stringResource(R.string.new_password_optional)) },
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = NeonGray100,
                                unfocusedTextColor = NeonGray100,
                                focusedBorderColor = NeonSecondary,
                                unfocusedBorderColor = NeonGray500
                            )
                        )
                        // Inline error display
                        if (error != null) {
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = error,
                                color = NeonError,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }
            }

            if (isEditing) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 16.dp),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(
                        onClick = {
                            isEditing = false
                            editedUsername = user.username
                            editedPassword = ""
                            savePending = false
                            onClearError()
                        }
                    ) {
                        Text(stringResource(R.string.cancel), color = NeonError)
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = {
                            if (editedUsername.isNotBlank()) {
                                onClearError()
                                onUpdateProfile(editedUsername, editedPassword.ifBlank { null })
                                savePending = true
                                // Do NOT close edit mode here — wait for server response
                            }
                        },
                        enabled = !isLoading && editedUsername.isNotBlank(),
                        colors = ButtonDefaults.buttonColors(containerColor = NeonPrimary)
                    ) {
                        if (isLoading && savePending) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                                color = Color.Black
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                        }
                        Text(stringResource(R.string.save))
                    }
                }
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
            label = stringResource(R.string.stat_games),
            value = totalGames.toString(),
            modifier = Modifier.weight(1f)
        )
        StatCard(
            label = stringResource(R.string.stat_wins),
            value = wins.toString(),
            modifier = Modifier.weight(1f),
            color = NeonWarning
        )
        StatCard(
            label = stringResource(R.string.stat_win_rate),
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
    color: Color = NeonGray100
) {
    GlassCard(modifier = modifier) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp)
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
                color = NeonGray500
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
        borderColor = if (isWin) NeonWarning.copy(alpha = 0.5f) else GlassBorder
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column {
                Text(
                    text = if (isWin) stringResource(R.string.victory) else stringResource(R.string.defeat),
                    style = MaterialTheme.typography.titleMedium,
                    color = if (isWin) NeonWarning else NeonGray300,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = formattedDate,
                    style = MaterialTheme.typography.bodySmall,
                    color = NeonGray500
                )
            }
        }
    }
}
