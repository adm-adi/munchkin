package com.munchkin.app.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.munchkin.app.R
import com.munchkin.app.core.Gender
import com.munchkin.app.network.GameHistoryItem
import com.munchkin.app.network.PlayerTotals
import com.munchkin.app.network.UserProfile
import com.munchkin.app.ui.components.GlassCard
import com.munchkin.app.ui.components.GlassTopAppBar
import com.munchkin.app.ui.theme.*
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private const val MIN_PASSWORD_LENGTH = 8

private val PROFILE_DATE_FORMATTER: DateTimeFormatter =
    DateTimeFormatter.ofPattern("dd MMM yyyy, HH:mm").withZone(ZoneId.systemDefault())

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
    onUpdateProfile: (name: String?, newPassword: String?, currentPassword: String?, avatarId: Int?, gender: Gender?) -> Unit,
    onDeleteAccount: (password: String) -> Unit = {}
) {
    var showDeleteDialog by remember { mutableStateOf(false) }

    // Initial load
    LaunchedEffect(Unit) {
        onRefresh()
    }

    if (showDeleteDialog) {
        DeleteAccountDialog(
            isLoading = isLoading,
            onDismiss = { showDeleteDialog = false },
            onConfirm = { password ->
                showDeleteDialog = false
                onClearError()
                onDeleteAccount(password)
            }
        )
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
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Header (User Info)
                item(key = "profile_header") {
                    ProfileHeader(
                        user = userProfile,
                        isLoading = isLoading,
                        error = error,
                        onClearError = onClearError,
                        onUpdateProfile = onUpdateProfile
                    )
                }

                // Stats Summary
                item(key = "stats_summary") {
                    StatsSummary(playerTotals)
                }

                item(key = "history_title") {
                    Text(
                        text = stringResource(R.string.game_history),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }

                if (error != null && gameHistory.isEmpty() && !isLoading) {
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
                                onClick = {
                                    onClearError()
                                    onRefresh()
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 48.dp)
                            ) {
                                Text(stringResource(R.string.reconnect_retry))
                            }
                        }
                    }
                } else if (gameHistory.isEmpty() && !isLoading) {
                    item(key = "history_empty") {
                        GlassCard(modifier = Modifier.fillMaxWidth()) {
                            Text(
                                text = stringResource(R.string.no_games_yet),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.fillMaxWidth(),
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                }

                items(gameHistory, key = { it.id }) { game ->
                    GameHistoryCard(game, userProfile.id)
                }

                // Danger Zone (Account Deletion)
                item(key = "danger_zone") {
                    GlassCard(
                        modifier = Modifier.fillMaxWidth(),
                        borderColor = NeonError.copy(alpha = 0.35f)
                    ) {
                        Text(
                            text = stringResource(R.string.danger_zone),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = NeonError
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = stringResource(R.string.delete_account_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        OutlinedButton(
                            onClick = { showDeleteDialog = true },
                            enabled = !isLoading,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp),
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = NeonError
                            ),
                            border = BorderStroke(1.dp, NeonError.copy(alpha = 0.5f))
                        ) {
                            Icon(
                                Icons.Default.DeleteForever,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(stringResource(R.string.delete_account))
                        }
                    }
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
    onUpdateProfile: (name: String?, newPassword: String?, currentPassword: String?, avatarId: Int?, gender: Gender?) -> Unit = { _, _, _, _, _ -> }
) {
    var isEditing by remember { mutableStateOf(false) }
    var editedUsername by remember(user.username) { mutableStateOf(user.username) }
    var editedPassword by remember { mutableStateOf("") }
    var currentPassword by remember { mutableStateOf("") }
    var showNewPassword by remember { mutableStateOf(false) }
    var showCurrentPassword by remember { mutableStateOf(false) }
    var editedAvatarId by remember(user.avatarId) { mutableIntStateOf(user.avatarId) }
    var editedGender by remember(user.gender) { mutableStateOf(user.gender) }
    var savePending by remember { mutableStateOf(false) }

    val wantsPasswordChange = editedPassword.isNotBlank()
    val passwordTooShort = wantsPasswordChange && editedPassword.length < MIN_PASSWORD_LENGTH
    val missingCurrentPassword = wantsPasswordChange && currentPassword.isBlank()
    val canSave = !isLoading &&
        editedUsername.isNotBlank() &&
        !passwordTooShort &&
        !missingCurrentPassword

    // Close edit mode and clear secrets when save completes without error
    LaunchedEffect(isLoading) {
        if (savePending && !isLoading && error == null) {
            isEditing = false
            editedPassword = ""
            currentPassword = ""
            savePending = false
        }
    }

    GlassCard(modifier = Modifier.fillMaxWidth()) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
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
                            color = MaterialTheme.colorScheme.onSurfaceVariant
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
                            onValueChange = { editedUsername = it.take(30); onClearError() },
                            label = { Text(stringResource(R.string.username_label)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = NeonGray100,
                                unfocusedTextColor = NeonGray100,
                                focusedBorderColor = NeonSecondary,
                                unfocusedBorderColor = GlassBorder
                            )
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedTextField(
                            value = editedPassword,
                            onValueChange = { editedPassword = it; onClearError() },
                            label = { Text(stringResource(R.string.new_password_optional)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                            visualTransformation = if (showNewPassword) {
                                VisualTransformation.None
                            } else {
                                PasswordVisualTransformation()
                            },
                            trailingIcon = {
                                IconButton(
                                    onClick = { showNewPassword = !showNewPassword },
                                    modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                                ) {
                                    Icon(
                                        imageVector = if (showNewPassword) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                                        contentDescription = if (showNewPassword) {
                                            stringResource(R.string.hide_password)
                                        } else {
                                            stringResource(R.string.show_password)
                                        },
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            },
                            isError = passwordTooShort,
                            supportingText = if (passwordTooShort) {
                                {
                                    Text(
                                        text = stringResource(R.string.password_min_length, MIN_PASSWORD_LENGTH),
                                        color = NeonError
                                    )
                                }
                            } else null,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = NeonGray100,
                                unfocusedTextColor = NeonGray100,
                                focusedBorderColor = NeonSecondary,
                                unfocusedBorderColor = GlassBorder
                            )
                        )

                        if (wantsPasswordChange) {
                            Spacer(modifier = Modifier.height(8.dp))
                            OutlinedTextField(
                                value = currentPassword,
                                onValueChange = { currentPassword = it; onClearError() },
                                label = { Text(stringResource(R.string.current_password_label)) },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                                visualTransformation = if (showCurrentPassword) {
                                    VisualTransformation.None
                                } else {
                                    PasswordVisualTransformation()
                                },
                                trailingIcon = {
                                    IconButton(
                                        onClick = { showCurrentPassword = !showCurrentPassword },
                                        modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                                    ) {
                                        Icon(
                                            imageVector = if (showCurrentPassword) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                                            contentDescription = if (showCurrentPassword) {
                                                stringResource(R.string.hide_password)
                                            } else {
                                                stringResource(R.string.show_password)
                                            },
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                },
                                isError = missingCurrentPassword,
                                supportingText = if (missingCurrentPassword) {
                                    {
                                        Text(
                                            text = stringResource(R.string.current_password_required),
                                            color = NeonError
                                        )
                                    }
                                } else null,
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedTextColor = NeonGray100,
                                    unfocusedTextColor = NeonGray100,
                                    focusedBorderColor = NeonSecondary,
                                    unfocusedBorderColor = GlassBorder
                                )
                            )
                        }
                    }
                }
            }

            // Inline error banner visible both inside and outside edit mode (e.g., delete error)
            if (error != null) {
                Spacer(modifier = Modifier.height(10.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(NeonError.copy(alpha = 0.12f))
                        .border(1.dp, NeonError.copy(alpha = 0.4f), RoundedCornerShape(10.dp))
                        .padding(start = 12.dp, top = 6.dp, bottom = 6.dp, end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = error,
                        color = NeonError,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = onClearError) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = stringResource(R.string.close),
                            tint = NeonGray200
                        )
                    }
                }
            }

            if (isEditing) {
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = stringResource(R.string.select_avatar),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Gender.entries.forEach { option ->
                        val selected = editedGender == option
                        val genderLabel = when (option) {
                            Gender.M -> stringResource(R.string.gender_male)
                            Gender.F -> stringResource(R.string.gender_female)
                            Gender.NA -> stringResource(R.string.gender_na)
                        }
                        FilterChip(
                            selected = selected,
                            onClick = { editedGender = option; onClearError() },
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = 48.dp),
                            label = {
                                Text(
                                    text = genderLabel,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
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
                            currentPassword = ""
                            editedAvatarId = user.avatarId
                            editedGender = user.gender
                            savePending = false
                            onClearError()
                        },
                        modifier = Modifier.heightIn(min = 48.dp)
                    ) {
                        Text(stringResource(R.string.cancel), color = NeonError)
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = {
                            if (canSave) {
                                onClearError()
                                val trimmedPassword = editedPassword.ifBlank { null }
                                onUpdateProfile(
                                    editedUsername.trim(),
                                    trimmedPassword,
                                    if (trimmedPassword != null) currentPassword else null,
                                    editedAvatarId.takeIf { it != user.avatarId },
                                    editedGender.takeIf { it != user.gender }
                                )
                                savePending = true
                            }
                        },
                        enabled = canSave,
                        modifier = Modifier.heightIn(min = 48.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = NeonPrimary,
                            contentColor = NeonWhite
                        )
                    ) {
                        if (isLoading && savePending) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                                color = NeonWhite
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
private fun DeleteAccountDialog(
    isLoading: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var password by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = NeonSurface,
        title = {
            Text(
                text = stringResource(R.string.delete_account_confirm_title),
                color = NeonError,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = stringResource(R.string.delete_account_confirm_desc),
                    color = NeonGray200,
                    style = MaterialTheme.typography.bodyMedium
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text(stringResource(R.string.current_password_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    visualTransformation = if (passwordVisible) {
                        VisualTransformation.None
                    } else {
                        PasswordVisualTransformation()
                    },
                    trailingIcon = {
                        IconButton(
                            onClick = { passwordVisible = !passwordVisible },
                            modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                        ) {
                            Icon(
                                imageVector = if (passwordVisible) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                                contentDescription = if (passwordVisible) {
                                    stringResource(R.string.hide_password)
                                } else {
                                    stringResource(R.string.show_password)
                                },
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(password) },
                enabled = password.isNotBlank() && !isLoading,
                colors = ButtonDefaults.buttonColors(
                    containerColor = NeonError,
                    contentColor = NeonWhite
                ),
                modifier = Modifier.heightIn(min = 48.dp)
            ) {
                Text(stringResource(R.string.delete_account))
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.heightIn(min = 48.dp)
            ) {
                Text(stringResource(R.string.cancel), color = NeonGray200)
            }
        }
    )
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
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
fun GameHistoryCard(game: GameHistoryItem, myUserId: String) {
    val isWin = game.winnerId == myUserId
    val formattedDate = PROFILE_DATE_FORMATTER.format(Instant.ofEpochMilli(game.endedAt))

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
                    color = if (isWin) NeonWarning else MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = formattedDate,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
