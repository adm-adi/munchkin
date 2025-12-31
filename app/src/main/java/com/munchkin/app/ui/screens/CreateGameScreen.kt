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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.munchkin.app.R
import com.munchkin.app.core.Gender
import com.munchkin.app.ui.theme.AvatarResources
import com.munchkin.app.ui.theme.getAvatarColor
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
    onCreateGame: (name: String, avatarId: Int, gender: Gender) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    var name by remember { mutableStateOf(userProfile?.username ?: "") }
    var selectedAvatarId by remember { mutableIntStateOf(userProfile?.avatarId ?: 0) }
    var selectedGender by remember { mutableStateOf(Gender.NA) }
    
    val isLocked = userProfile != null
    
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.create_game)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp),
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
                            .padding(bottom = 16.dp)
                    ) {
                        Text(
                            text = it,
                            modifier = Modifier.padding(16.dp),
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                }
            }
            
            // Name input
            OutlinedTextField(
                value = name,
                onValueChange = { if (!isLocked) name = it.take(20) },
                label = { Text(stringResource(R.string.your_name)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                readOnly = isLocked,
                leadingIcon = {
                    Icon(Icons.Default.Person, contentDescription = null)
                },
                trailingIcon = if (isLocked) {
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
                    val borderColor = if (isSelected) MaterialTheme.colorScheme.primary else Color.Transparent
                    
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .background(
                                if (isSelected) MaterialTheme.colorScheme.primaryContainer 
                                else MaterialTheme.colorScheme.surfaceVariant
                            )
                            .border(2.dp, borderColor, RoundedCornerShape(12.dp))
                            .clickable(enabled = !isLocked) { selectedAvatarId = avatarId }
                            .padding(8.dp)
                    ) {
                        // Color-based avatar with initial letter
                        Box(
                            modifier = Modifier
                                .size(56.dp)
                                .clip(CircleShape)
                                .background(getAvatarColor(avatarId)),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = AvatarResources.getAvatarName(avatarId).first().toString(),
                                style = MaterialTheme.typography.headlineSmall,
                                color = Color.White,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = AvatarResources.getAvatarName(avatarId),
                            style = MaterialTheme.typography.labelSmall,
                            color = if (isSelected) 
                                MaterialTheme.colorScheme.onPrimaryContainer 
                            else 
                                MaterialTheme.colorScheme.onSurfaceVariant
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
                Gender.entries.forEach { gender ->
                    val isSelected = selectedGender == gender
                    FilterChip(
                        selected = isSelected,
                        onClick = { selectedGender = gender },
                        label = {
                            Text(
                                when (gender) {
                                    Gender.M -> stringResource(R.string.gender_male)
                                    Gender.F -> stringResource(R.string.gender_female)
                                    Gender.NA -> stringResource(R.string.gender_na)
                                }
                            )
                        },
                        leadingIcon = if (isSelected) {
                            { Icon(Icons.Default.Check, contentDescription = null, Modifier.size(16.dp)) }
                        } else null,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
            
            Spacer(modifier = Modifier.weight(1f))
            
            // Create button
            Button(
                onClick = { 
                    if (name.isNotBlank()) {
                        onCreateGame(name, selectedAvatarId, selectedGender)
                    }
                },
                enabled = name.isNotBlank() && !isLoading,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                shape = RoundedCornerShape(16.dp)
            ) {
                if (isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        color = MaterialTheme.colorScheme.onPrimary,
                        strokeWidth = 2.dp
                    )
                } else {
                    Icon(Icons.Default.PlayArrow, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.create))
                }
            }
        }
    }
}
