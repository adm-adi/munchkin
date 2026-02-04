package com.munchkin.app.ui.screens

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.munchkin.app.R
import com.munchkin.app.core.Gender
import com.munchkin.app.ui.theme.getAvatarColor
import com.munchkin.app.network.UserProfile
import com.munchkin.app.network.DiscoveredGame
import kotlinx.coroutines.launch

/**
 * Screen for joining an existing game.
 * Shows available games from server + join by code option.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun JoinGameScreen(
    isLoading: Boolean,
    error: String?,
    discoveredGames: List<DiscoveredGame> = emptyList(),
    isDiscovering: Boolean = false,
    userProfile: UserProfile? = null,
    onJoinGame: (wsUrl: String, joinCode: String, name: String, avatarId: Int, gender: Gender) -> Unit,
    onJoinDiscoveredGame: (DiscoveredGame, name: String, avatarId: Int, gender: Gender) -> Unit = { _, _, _, _ -> },
    onStartDiscovery: () -> Unit = {},
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    // Join code for manual entry
    var joinCode by remember { mutableStateOf("") }
    
    // Player info
    var name by remember { mutableStateOf(userProfile?.username ?: "") }
    var selectedAvatarId by remember { mutableIntStateOf(userProfile?.avatarId ?: 0) }
    var selectedGender by remember { mutableStateOf(Gender.NA) }
    
    val isNameLocked = userProfile != null
    
    // Validation states
    var nameError by remember { mutableStateOf(false) }
    var genderError by remember { mutableStateOf(false) }
    
    // Shake animation for validation feedback
    var shakeKey by remember { mutableIntStateOf(0) }
    val shakeOffset by animateFloatAsState(
        targetValue = if (shakeKey % 2 == 0) 0f else 10f,
        animationSpec = repeatable(
            iterations = 3,
            animation = tween(50),
            repeatMode = RepeatMode.Reverse
        ),
        finishedListener = { shakeKey = 0 },
        label = "shake"
    )
    
    val scope = rememberCoroutineScope()
    
    // Validation function
    fun validateFields(): Boolean {
        val isNameValid = name.isNotBlank()
        val isGenderValid = selectedGender != Gender.NA
        
        nameError = !isNameValid
        genderError = !isGenderValid
        
        if (!isNameValid || !isGenderValid) {
            shakeKey++
            return false
        }
        return true
    }
    
    // Clear errors when user interacts
    LaunchedEffect(name) {
        if (name.isNotBlank()) nameError = false
    }
    
    LaunchedEffect(selectedGender) {
        if (selectedGender != Gender.NA) genderError = false
    }
    
    // Request available games on screen load
    LaunchedEffect(Unit) {
        onStartDiscovery()
    }
    
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.join_game)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
                actions = {
                    // Refresh button
                    IconButton(onClick = onStartDiscovery) {
                        if (isDiscovering) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(24.dp),
                                strokeWidth = 2.dp
                            )
                        } else {
                            Icon(Icons.Default.Refresh, contentDescription = "Actualizar")
                        }
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Error message
            AnimatedVisibility(visible = error != null) {
                error?.let {
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp)
                    ) {
                        Text(
                            text = it,
                            modifier = Modifier.padding(16.dp),
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                }
            }
            
            // === PLAYER INFO SECTION ===
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                )
            ) {
                Column(
                    modifier = Modifier.padding(16.dp)
                ) {
                    Text(
                        text = "Tu personaje",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                    
                    Spacer(modifier = Modifier.height(12.dp))
                    
                    OutlinedTextField(
                        value = name,
                        onValueChange = { if (!isNameLocked) name = it.take(20) },
                        label = { Text(stringResource(R.string.your_name)) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .offset(x = if (nameError) shakeOffset.dp else 0.dp),
                        singleLine = true,
                        readOnly = isNameLocked,
                        isError = nameError,
                        leadingIcon = {
                            Icon(Icons.Default.Person, contentDescription = null)
                        },
                        trailingIcon = if (isNameLocked) {
                            { Icon(Icons.Default.Lock, contentDescription = "Locked", tint = MaterialTheme.colorScheme.primary) }
                        } else null,
                        supportingText = if (nameError && !isNameLocked) {
                            { Text("El nombre es obligatorio", color = MaterialTheme.colorScheme.error) }
                        } else null,
                        colors = OutlinedTextFieldDefaults.colors(
                            errorBorderColor = MaterialTheme.colorScheme.error,
                            errorLabelColor = MaterialTheme.colorScheme.error
                        )
                    )
                    
                    Spacer(modifier = Modifier.height(12.dp))
                    
                    // Avatar + Gender row
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .offset(x = if (genderError) shakeOffset.dp else 0.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Avatars
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            (0..5).forEach { avatarId ->
                                val isSelected = selectedAvatarId == avatarId
                                val avatarRes = com.munchkin.app.ui.theme.AvatarResources.getAvatarDrawable(avatarId)
                                Box(
                                    modifier = Modifier
                                        .size(40.dp)
                                        .clip(CircleShape)
                                        .background(
                                            if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)
                                            else Color.Transparent
                                        )
                                        .clickable { selectedAvatarId = avatarId }
                                        .then(
                                            if (isSelected) Modifier.padding(2.dp) else Modifier
                                        ),
                                    contentAlignment = Alignment.Center
                                ) {
                                    androidx.compose.foundation.Image(
                                        painter = androidx.compose.ui.res.painterResource(avatarRes),
                                        contentDescription = com.munchkin.app.ui.theme.AvatarResources.getAvatarName(avatarId),
                                        modifier = Modifier
                                            .size(32.dp)
                                            .clip(CircleShape)
                                    )
                                }
                            }
                        }
                        
                        // Gender chips
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            listOf(Gender.M, Gender.F).forEach { gender ->
                                FilterChip(
                                    selected = selectedGender == gender,
                                    onClick = { selectedGender = gender },
                                    label = {
                                        Text(
                                            when (gender) {
                                                Gender.M -> "♂"
                                                Gender.F -> "♀"
                                                else -> "?"
                                            },
                                            fontWeight = FontWeight.Bold,
                                            style = MaterialTheme.typography.titleMedium
                                        )
                                    },
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                                        selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                                    ),
                                    border = if (genderError && selectedGender != gender) {
                                        FilterChipDefaults.filterChipBorder(
                                            enabled = true,
                                            selected = false,
                                            borderColor = MaterialTheme.colorScheme.error,
                                            selectedBorderColor = MaterialTheme.colorScheme.error,
                                            borderWidth = 2.dp,
                                            selectedBorderWidth = 2.dp
                                        )
                                    } else null
                                )
                            }
                        }
                    }
                }
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            
            // === JOIN BY CODE ===
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
                )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedTextField(
                        value = joinCode,
                        onValueChange = { joinCode = it.uppercase().filter { c -> c.isLetterOrDigit() }.take(6) },
                        label = { Text("Código") },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        textStyle = MaterialTheme.typography.titleMedium.copy(
                            fontFamily = FontFamily.Monospace,
                            letterSpacing = 2.sp
                        ),
                        placeholder = { Text("ABCD12") },
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters)
                    )
                    
                    Button(
                        onClick = {
                            if (validateFields()) {
                                // Join using server URL with code
                                val wsUrl = "ws://23.88.48.58:8765"
                                onJoinGame(wsUrl, joinCode, name, selectedAvatarId, selectedGender)
                            }
                        },
                        enabled = joinCode.length >= 4 && !isLoading,
                        modifier = Modifier.height(56.dp)
                    ) {
                        if (isLoading) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                color = MaterialTheme.colorScheme.onPrimary,
                                strokeWidth = 2.dp
                            )
                        } else {
                            Icon(Icons.Default.Login, contentDescription = null)
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Unirse")
                        }
                    }
                }
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            
            // === AVAILABLE GAMES LIST ===
            Text(
                text = "Partidas disponibles",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 4.dp, bottom = 8.dp)
            )
            
            if (discoveredGames.isEmpty()) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                    )
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            Icons.Default.SearchOff,
                            contentDescription = null,
                            modifier = Modifier.size(48.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = if (isDiscovering) "Buscando partidas..." else "No hay partidas disponibles",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (!isDiscovering) {
                            Text(
                                text = "Crea una partida o introduce un código",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                            )
                        }
                    }
                }
            } else {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    discoveredGames.forEach { game ->
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(enabled = !isLoading) {
                                    if (validateFields()) {
                                        onJoinDiscoveredGame(game, name, selectedAvatarId, selectedGender)
                                    }
                                },
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f)
                            )
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                // Host avatar
                                Box(
                                    modifier = Modifier
                                        .size(48.dp)
                                        .clip(CircleShape)
                                        .background(MaterialTheme.colorScheme.primary),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        Icons.Default.Person,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onPrimary
                                    )
                                }
                                
                                Spacer(modifier = Modifier.width(16.dp))
                                
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = game.hostName,
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(
                                        text = "${game.playerCount}/${game.maxPlayers} jugadores • ${game.joinCode}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                
                                Icon(
                                    Icons.AutoMirrored.Filled.ArrowForward,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }
                }
            }
            
            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}
