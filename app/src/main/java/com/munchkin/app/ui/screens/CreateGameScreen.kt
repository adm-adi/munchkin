package com.munchkin.app.ui.screens

import androidx.compose.animation.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.munchkin.app.R
import com.munchkin.app.core.Gender
import com.munchkin.app.network.UserProfile
import com.munchkin.app.ui.components.AmbientOrb
import com.munchkin.app.ui.components.GradientButton
import com.munchkin.app.ui.theme.*

/**
 * Screen for creating a new game as host.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreateGameScreen(
    isLoading: Boolean,
    error: String?,
    onCreateGame: (name: String, avatarId: Int, gender: Gender, timerSeconds: Int, superMunchkin: Boolean) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    userProfile: UserProfile? = null
) {
    var name by remember(userProfile) { mutableStateOf(userProfile?.username ?: "") }
    var selectedAvatarId by remember(userProfile) {
        mutableIntStateOf(AvatarResources.normalizeAvatarId(userProfile?.avatarId ?: 0))
    }
    var selectedGender by remember(userProfile) {
        mutableStateOf(userProfile?.gender ?: Gender.M)
    }
    var selectedTimerSeconds by remember { mutableIntStateOf(0) }
    var isSuperMunchkin by remember { mutableStateOf(false) }

    // Only lock the name if logged in, avatar can always be changed
    val isNameLocked = userProfile != null
    val avatarRows = remember { (0 until AvatarResources.AVATAR_COUNT).chunked(4) }
    val timerRows = remember { listOf(0, 60, 90, 120, 180, 300).chunked(3) }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(NeonBackground, NeonSurface.copy(alpha = 0.4f), NeonBackground)
                )
            )
    ) {
        AmbientOrb(
            modifier = Modifier.align(Alignment.TopEnd).offset(x = 80.dp, y = (-80).dp),
            color = NeonSecondary, size = 240.dp, alpha = 0.08f
        )
        AmbientOrb(
            modifier = Modifier.align(Alignment.BottomStart).offset(x = (-40).dp, y = 40.dp),
            color = NeonCyan, size = 200.dp, alpha = 0.06f
        )

        Scaffold(
            modifier = Modifier.fillMaxSize(),
            containerColor = Color.Transparent,
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            topBar = {
                TopAppBar(
                    title = {
                        Text(
                            stringResource(R.string.create_game),
                            color = NeonGray100,
                            fontWeight = FontWeight.Bold
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = stringResource(R.string.back),
                                tint = NeonGray100
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
                    modifier = Modifier.background(
                        Brush.verticalGradient(
                            listOf(NeonBackground.copy(alpha = 0.96f), Color.Transparent)
                        )
                    )
                )
            }
        ) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .imePadding()
                    .navigationBarsPadding()
                    .verticalScroll(rememberScrollState())
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Error message
                AnimatedVisibility(visible = error != null) {
                    error?.let {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 16.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(NeonError.copy(alpha = 0.10f))
                                .border(1.dp, NeonError.copy(alpha = 0.4f), RoundedCornerShape(12.dp))
                                .padding(16.dp)
                        ) {
                            Text(text = it, color = NeonError, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }

                // Name input
                OutlinedTextField(
                    value = name,
                    onValueChange = { if (!isNameLocked) name = it.take(20) },
                    label = { Text(stringResource(R.string.your_name)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    readOnly = isNameLocked,
                    leadingIcon = {
                        Icon(Icons.Default.Person, contentDescription = null)
                    },
                    trailingIcon = if (isNameLocked) {
                        {
                            Icon(
                                Icons.Default.Lock,
                                contentDescription = stringResource(R.string.field_locked),
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    } else null
                )

                Spacer(modifier = Modifier.height(24.dp))

                // Avatar selection (2x4 non-scrolling grid)
                Text(
                    text = stringResource(R.string.select_avatar),
                    style = MaterialTheme.typography.titleSmall,
                    color = NeonGray100,
                    modifier = Modifier.align(Alignment.Start)
                )

                Spacer(modifier = Modifier.height(12.dp))

                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    avatarRows.forEach { rowIds ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            rowIds.forEach { avatarId ->
                                val isSelected = selectedAvatarId == avatarId
                                val drawableResId = remember(avatarId, selectedGender) {
                                    AvatarResources.getAvatarDrawable(avatarId, selectedGender == Gender.F)
                                }

                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    modifier = Modifier
                                        .weight(1f)
                                        .heightIn(min = 48.dp)
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(
                                            if (isSelected) NeonPrimary.copy(alpha = 0.16f)
                                            else GlassBase
                                        )
                                        .border(
                                            width = if (isSelected) 2.dp else 1.dp,
                                            color = if (isSelected) NeonPrimary else GlassBorder,
                                            shape = RoundedCornerShape(12.dp)
                                        )
                                        .clickable { selectedAvatarId = avatarId }
                                        .padding(vertical = 8.dp, horizontal = 4.dp)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(48.dp)
                                            .clip(CircleShape)
                                            .background(getAvatarColor(avatarId)),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Image(
                                            painter = painterResource(id = drawableResId),
                                            contentDescription = AvatarResources.getAvatarName(avatarId),
                                            modifier = Modifier.fillMaxSize(),
                                            contentScale = ContentScale.Crop
                                        )
                                    }
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = AvatarResources.getAvatarName(avatarId),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = if (isSelected) NeonPrimaryLight else MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                            repeat(4 - rowIds.size) {
                                Spacer(modifier = Modifier.weight(1f))
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                // Gender selection (M, F, NA)
                Text(
                    text = stringResource(R.string.gender),
                    style = MaterialTheme.typography.titleSmall,
                    color = NeonGray100,
                    modifier = Modifier.align(Alignment.Start)
                )

                Spacer(modifier = Modifier.height(12.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Gender.entries.forEach { gender ->
                        val isSelected = selectedGender == gender
                        val label = when (gender) {
                            Gender.M -> "♂ ${stringResource(R.string.gender_male)}"
                            Gender.F -> "♀ ${stringResource(R.string.gender_female)}"
                            Gender.NA -> stringResource(R.string.gender_na)
                        }
                        FilterChip(
                            selected = isSelected,
                            onClick = { selectedGender = gender },
                            label = {
                                Text(
                                    text = label,
                                    style = MaterialTheme.typography.labelMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            },
                            leadingIcon = if (isSelected) {
                                { Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp)) }
                            } else null,
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = 48.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                // Turn Timer selection (0..300s matching Web PWA)
                Text(
                    text = stringResource(R.string.turn_timer_label),
                    style = MaterialTheme.typography.titleSmall,
                    color = NeonGray100,
                    modifier = Modifier.align(Alignment.Start)
                )

                Spacer(modifier = Modifier.height(12.dp))

                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    timerRows.forEach { rowSeconds ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            rowSeconds.forEach { seconds ->
                                val isSelected = selectedTimerSeconds == seconds
                                val label = when (seconds) {
                                    0 -> stringResource(R.string.timer_off)
                                    90 -> stringResource(R.string.timer_seconds_format, 90)
                                    else -> stringResource(R.string.timer_minutes_format, seconds / 60)
                                }
                                FilterChip(
                                    selected = isSelected,
                                    onClick = { selectedTimerSeconds = seconds },
                                    label = {
                                        Text(
                                            text = label,
                                            style = MaterialTheme.typography.labelMedium,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    },
                                    leadingIcon = if (isSelected) {
                                        { Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp)) }
                                    } else null,
                                    modifier = Modifier
                                        .weight(1f)
                                        .heightIn(min = 48.dp)
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                // Game Mode selection
                Text(
                    text = stringResource(R.string.game_mode),
                    style = MaterialTheme.typography.titleSmall,
                    color = NeonGray100,
                    modifier = Modifier.align(Alignment.Start)
                )

                Spacer(modifier = Modifier.height(12.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    FilterChip(
                        selected = !isSuperMunchkin,
                        onClick = { isSuperMunchkin = false },
                        label = {
                            Text(
                                stringResource(R.string.game_mode_normal),
                                style = MaterialTheme.typography.labelSmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        },
                        leadingIcon = if (!isSuperMunchkin) {
                            { Icon(Icons.Default.Check, contentDescription = null, Modifier.size(16.dp)) }
                        } else null,
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 48.dp)
                    )
                    FilterChip(
                        selected = isSuperMunchkin,
                        onClick = { isSuperMunchkin = true },
                        label = {
                            Text(
                                stringResource(R.string.game_mode_super),
                                style = MaterialTheme.typography.labelSmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        },
                        leadingIcon = if (isSuperMunchkin) {
                            { Icon(Icons.Default.Check, contentDescription = null, Modifier.size(16.dp)) }
                        } else null,
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 48.dp)
                    )
                }

                AnimatedVisibility(visible = isSuperMunchkin) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(NeonSecondary.copy(alpha = 0.08f))
                            .border(1.dp, NeonSecondary.copy(alpha = 0.3f), RoundedCornerShape(12.dp))
                            .padding(12.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.game_mode_super_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = NeonSecondary
                        )
                    }
                }

                Spacer(modifier = Modifier.height(28.dp))

                GradientButton(
                    text = if (isLoading) stringResource(R.string.creating_game) else stringResource(R.string.create),
                    onClick = {
                        if (name.isNotBlank()) {
                            onCreateGame(name, selectedAvatarId, selectedGender, selectedTimerSeconds, isSuperMunchkin)
                        }
                    },
                    enabled = name.isNotBlank() && !isLoading,
                    modifier = Modifier.fillMaxWidth(),
                    icon = if (isLoading) null else Icons.Default.PlayArrow,
                    gradientColors = GradientNeonPurple
                )

                Spacer(modifier = Modifier.height(8.dp))
            }
        }
    }
}
