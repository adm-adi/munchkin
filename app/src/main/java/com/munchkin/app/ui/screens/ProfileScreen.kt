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
import com.munchkin.app.network.PlayerTotals
import com.munchkin.app.network.UserProfile
import com.munchkin.app.ui.components.GlassCard
import com.munchkin.app.ui.components.GlassTopAppBar
import androidx.compose.foundation.Image
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import com.munchkin.app.core.Gender
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.CircleShape
import com.munchkin.app.ui.theme.*
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileScreen(
    userProfile: UserProfile,
    gameHistory: List<GameHistoryItem>,
    playerTotals: PlayerTotals,
    isLoading: Boolean,
    error: String?,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onClearError: () -> Unit,
    onUpdateProfile: (String?, String?, Int?, Gender?) -> Unit
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
                    StatsSummary(playerTotals)
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
    onUpdateProfile: (String?, String?, Int?, Gender?) -> Unit = { _, _, _, _ -> }
) {
    var isEditing by remember { mutableStateOf(false) }
    var editedUsername by remember { mutableStateOf(user.username) }
    var editedPassword by remember { mutableStateOf("") }
    var editedAvatarId by remember(user.avatarId) { mutableStateOf(user.avatarId) }
    var editedGender by remember(user.gender) { mutableStateOf(user.gender) }
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
                // The account stores an avatar slot and a gender, so show the real
                // portrait instead of the first letter of the username.
                Image(
                    painter = painterResource(
                        id = AvatarResources.getAvatarDrawable(
                            if (isEditing) editedAvatarId else user.avatarId,
                            (if (isEditing) editedGender else user.gender) == Gender.F
                        )
                    ),
                    contentDescription = AvatarResources.getAvatarName(user.avatarId),
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(64.dp)
                        .clip(MaterialTheme.shapes.medium)
                        .background(getAvatarColor(user.avatarId).copy(alpha = 0.25f))
                )

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
                // Avatar and gender were previously write-once at registration, which
                // itself hardcoded slot 0 — so every account looked identical.
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = stringResource(R.string.select_avatar),
                    style = MaterialTheme.typography.titleSmall,
                    color = NeonGray300
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Gender.entries.forEach { option ->
                        val selected = editedGender == option
                        FilterChip(
                            selected = selected,
                            onClick = { editedGender = option; onClearError() },
                            label = {
                                Text(
                                    when (option) {
                                        Gender.M -> "Masculino"
                                        Gender.F -> "Femenino"
                                        Gender.NA -> "Otro"
                                    }
                                )
                            }
                        )
                    }
                }
                Spacer(modifier = Modifier.height(10.dp))
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    items(AvatarResources.AVATAR_COUNT) { slot ->
                        val selected = editedAvatarId == slot
                        Image(
                            painter = painterResource(
                                id = AvatarResources.getAvatarDrawable(slot, editedGender == Gender.F)
                            ),
                            contentDescription = AvatarResources.getAvatarName(slot),
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .size(52.dp)
                                .clip(CircleShape)
                                .background(getAvatarColor(slot).copy(alpha = 0.25f))
                                .border(
                                    width = if (selected) 2.dp else 1.dp,
                                    color = if (selected) NeonPrimary else GlassBorder,
                                    shape = CircleShape
                                )
                                .clickable { editedAvatarId = slot; onClearError() }
                        )
                    }
                }

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
                            editedAvatarId = user.avatarId
                            editedGender = user.gender
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
                                onUpdateProfile(
                                    editedUsername,
                                    editedPassword.ifBlank { null },
                                    editedAvatarId.takeIf { it != user.avatarId },
                                    editedGender.takeIf { it != user.gender }
                                )
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
fun StatsSummary(totals: PlayerTotals) {
    // Comes from the server. Counting the history list instead understated anyone
    // past 50 games (the list is capped) and disagreed with the ranking.
    val totalGames = totals.gamesPlayed
    val wins = totals.wins
    val winRate = totals.winRate

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
