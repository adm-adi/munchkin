package com.munchkin.app.ui.screens

import androidx.compose.animation.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.unit.dp
import com.munchkin.app.R
import com.munchkin.app.core.Gender
import com.munchkin.app.ui.components.AmbientOrb
import com.munchkin.app.ui.components.GradientButton
import com.munchkin.app.ui.theme.*
import com.munchkin.app.network.UserProfile

/**
 * Screen for creating a new game as host.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreateGameScreen(
    isLoading: Boolean,
    error: String?,
    userProfile: UserProfile? = null,
    onCreateGame: (name: String, avatarId: Int, gender: Gender, timerSeconds: Int, superMunchkin: Boolean) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    var name by remember { mutableStateOf(userProfile?.username ?: "") }
    var selectedAvatarId by remember { mutableIntStateOf(userProfile?.avatarId ?: 0) }
    var selectedGender by remember { mutableStateOf(Gender.M) }  // Default to Male
    var selectedTimerSeconds by remember { mutableIntStateOf(0) }
    var isSuperMunchkin by remember { mutableStateOf(false) }
    
    // Only lock the name if logged in, avatar can always be changed
    val isNameLocked = userProfile != null

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
                    { Icon(Icons.Default.Lock, contentDescription = "Locked", tint = MaterialTheme.colorScheme.primary) }
                } else null
            )
            
            Spacer(modifier = Modifier.height(24.dp))
            
            // Avatar selection
            Text(
                text = stringResource(R.string.select_avatar),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.align(Alignment.Start)
            )
            
            Spacer(modifier = Modifier.height(12.dp))
            
            // Avatar grid with images
            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                modifier = Modifier.height(200.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                itemsIndexed((0 until AvatarResources.AVATAR_COUNT).toList()) { _, avatarId ->
                    val isSelected = selectedAvatarId == avatarId
                    val drawableResId = remember(avatarId, selectedGender) {
                        AvatarResources.getAvatarDrawable(avatarId, selectedGender == Gender.F)
                    }

                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .background(
                                if (isSelected) NeonPrimary.copy(alpha = 0.15f)
                                else GlassBase
                            )
                            .border(
                                2.dp,
                                if (isSelected) NeonPrimary else GlassBorder,
                                RoundedCornerShape(12.dp)
                            )
                            .clickable { selectedAvatarId = avatarId }
                            .padding(8.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(56.dp)
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
                            color = if (isSelected) NeonPrimary else NeonGray400
                        )
                    }
                }
            }
            
            Spacer(modifier = Modifier.height(24.dp))
            
            // Gender selection
            Text(
                text = stringResource(R.string.gender),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.align(Alignment.Start)
            )
            
            Spacer(modifier = Modifier.height(12.dp))
            
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Only show M and F genders (not NA)
                listOf(Gender.M, Gender.F).forEach { gender ->
                    val isSelected = selectedGender == gender
                    FilterChip(
                        selected = isSelected,
                        onClick = { selectedGender = gender },
                        label = {
                            Text(
                                text = when (gender) {
                                    Gender.M -> "♂"
                                    Gender.F -> "♀"
                                    else -> ""
                                },
                                style = MaterialTheme.typography.titleMedium
                            )
                        },
                        leadingIcon = if (isSelected) {
                            { Icon(Icons.Default.Check, contentDescription = null, Modifier.size(16.dp)) }
                        } else null,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(24.dp))
            
            // Turn Timer selection
            Text(
                text = "⏱️ Timer por turno",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.align(Alignment.Start)
            )
            
            Spacer(modifier = Modifier.height(12.dp))
            
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                val timerOptions = listOf(0 to "Off", 30 to "30s", 60 to "1m", 90 to "90s", 120 to "2m")
                timerOptions.forEach { (seconds, label) ->
                    val isSelected = selectedTimerSeconds == seconds
                    FilterChip(
                        selected = isSelected,
                        onClick = { selectedTimerSeconds = seconds },
                        label = { Text(label, style = MaterialTheme.typography.labelSmall) },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(24.dp))

            // Game Mode selection
            Text(
                text = stringResource(R.string.game_mode),
                style = MaterialTheme.typography.titleSmall,
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
                    label = { Text(stringResource(R.string.game_mode_normal), style = MaterialTheme.typography.labelSmall) },
                    leadingIcon = if (!isSuperMunchkin) {
                        { Icon(Icons.Default.Check, contentDescription = null, Modifier.size(16.dp)) }
                    } else null,
                    modifier = Modifier.weight(1f)
                )
                FilterChip(
                    selected = isSuperMunchkin,
                    onClick = { isSuperMunchkin = true },
                    label = { Text(stringResource(R.string.game_mode_super), style = MaterialTheme.typography.labelSmall) },
                    leadingIcon = if (isSuperMunchkin) {
                        { Icon(Icons.Default.Check, contentDescription = null, Modifier.size(16.dp)) }
                    } else null,
                    modifier = Modifier.weight(1f)
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

            Spacer(modifier = Modifier.weight(1f))
            Spacer(modifier = Modifier.height(24.dp))

            GradientButton(
                text = if (isLoading) "Creando..." else stringResource(R.string.create),
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
        }
    }
    } // close outer Box
}
