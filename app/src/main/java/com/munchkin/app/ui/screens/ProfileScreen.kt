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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.munchkin.app.network.GameHistoryItem
import com.munchkin.app.network.UserProfile
import com.munchkin.app.ui.components.GlassCard
import com.munchkin.app.ui.components.GlassTopAppBar
import com.munchkin.app.ui.theme.*
import java.text.SimpleDateFormat
import java.util.*
import androidx.compose.ui.res.stringResource
import com.munchkin.app.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileScreen(
    userProfile: UserProfile,
    gameHistory: List<GameHistoryItem>,
    isLoading: Boolean,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
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
                    ProfileHeader(userProfile, onUpdateProfile)
                }

                // Stats Summary
                item {
                    StatsSummary(userProfile, gameHistory)
                }

                item {
                    Text(
                        text = "Historial de Partidas",
                        style = MaterialTheme.typography.titleMedium,
                        color = NeonGray300,
                        modifier = Modifier.padding(vertical = 8.dp)
                    )
                }

                if (gameHistory.isEmpty() && !isLoading) {
                    item {
                        GlassCard(modifier = Modifier.fillMaxWidth()) {
                            Text(
                                text = "Aún no has jugado ninguna partida.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = NeonGray500,
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
fun ProfileHeader(
    user: UserProfile, 
    onUpdateProfile: (String?, String?) -> Unit = { _, _ -> }
) {
    var isEditing by remember { mutableStateOf(false) }
    var editedUsername by remember { mutableStateOf(user.username) }
    var editedPassword by remember { mutableStateOf("") }
    
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
                    IconButton(onClick = { isEditing = true }) {
                        Icon(Icons.Default.Edit, contentDescription = "Editar", tint = NeonSecondary)
                    }
                } else {
                    Column(modifier = Modifier.weight(1f)) {
                        OutlinedTextField(
                            value = editedUsername,
                            onValueChange = { editedUsername = it },
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
                            onValueChange = { editedPassword = it },
                            label = { Text(stringResource(R.string.new_password_optional)) },
                            singleLine = true,
                            visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = NeonGray100,
                                unfocusedTextColor = NeonGray100,
                                focusedBorderColor = NeonSecondary,
                                unfocusedBorderColor = NeonGray500
                            )
                        )
                    }
                }
            }
            
            if (isEditing) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(
                        onClick = { 
                            isEditing = false
                            editedUsername = user.username
                            editedPassword = ""
                        }
                    ) {
                        Text(stringResource(R.string.cancel), color = NeonError)
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = {
                            if (editedUsername.isNotBlank()) {
                                onUpdateProfile(editedUsername, editedPassword.ifBlank { null })
                                isEditing = false
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = NeonPrimary)
                    ) {
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
            label = "Partidas",
            value = totalGames.toString(),
            modifier = Modifier.weight(1f)
        )
        StatCard(
            label = "Victorias",
            value = wins.toString(),
            modifier = Modifier.weight(1f),
            color = NeonWarning
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
    color: Color = NeonGray100
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
                    text = if (isWin) "🏆 ¡Victoria!" else "Derrota",
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
            
            // Should verify if we have player count in GameHistoryItem. 
            // Previous check showed: val id: String, val endedAt: Long, val winnerId: String
            // server.js handles: playerCount, but let's check Kotlin definition.
            
            // Assuming we don't have playercount yet on client model, omitting.
        }
    }
}
