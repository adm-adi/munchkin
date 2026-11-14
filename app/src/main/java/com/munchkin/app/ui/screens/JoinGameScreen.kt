package com.munchkin.app.ui.screens

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.munchkin.app.R
import com.munchkin.app.core.Gender
import com.munchkin.app.network.DiscoveredGame
import com.munchkin.app.network.JoinCodeParser
import com.munchkin.app.network.UserProfile
import com.munchkin.app.ui.components.AmbientOrb
import com.munchkin.app.ui.components.QrScanner
import com.munchkin.app.ui.theme.*

/**
 * Screen for joining an existing game.
 * Shows available games from server + join by code or QR scan option.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun JoinGameScreen(
    isLoading: Boolean,
    error: String?,
    onJoinGame: (joinCode: String, name: String, avatarId: Int, gender: Gender) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    discoveredGames: List<DiscoveredGame> = emptyList(),
    isDiscovering: Boolean = false,
    userProfile: UserProfile? = null,
    initialJoinCode: String = "",
    onJoinDiscoveredGame: (DiscoveredGame, name: String, avatarId: Int, gender: Gender) -> Unit = { _, _, _, _ -> },
    onStartDiscovery: () -> Unit = {}
) {
    // Join code for manual entry (prefilled from deep links / initialJoinCode)
    var joinCode by remember(initialJoinCode) {
        mutableStateOf(
            JoinCodeParser.extractJoinCode(initialJoinCode)
                ?: initialJoinCode.uppercase().filter { it.isLetterOrDigit() }.take(8)
        )
    }
    var showQrScanner by remember { mutableStateOf(false) }
    var qrError by remember { mutableStateOf<String?>(null) }

    // Player info
    var name by remember(userProfile) { mutableStateOf(userProfile?.username ?: "") }
    var selectedAvatarId by remember(userProfile) {
        mutableIntStateOf(AvatarResources.normalizeAvatarId(userProfile?.avatarId ?: 0))
    }
    var selectedGender by remember(userProfile) {
        mutableStateOf(userProfile?.gender ?: Gender.M)
    }

    val isNameLocked = userProfile != null
    val avatarRows = remember { (0 until AvatarResources.AVATAR_COUNT).chunked(4) }
    val invalidQrMessage = stringResource(R.string.qr_invalid_code_error)

    // Validation states
    var nameError by remember { mutableStateOf(false) }

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

    fun validateFields(): Boolean {
        val isNameValid = name.isNotBlank()
        nameError = !isNameValid
        if (!isNameValid) {
            shakeKey++
            return false
        }
        return true
    }

    LaunchedEffect(name) {
        if (name.isNotBlank()) nameError = false
    }

    // Request available games on screen load
    LaunchedEffect(Unit) {
        onStartDiscovery()
    }

    val displayedError = qrError ?: error
    val validJoinCode = JoinCodeParser.extractJoinCode(joinCode)

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
            modifier = Modifier.align(Alignment.TopCenter).offset(y = (-60).dp),
            color = NeonPrimary, size = 260.dp, alpha = 0.08f
        )
        AmbientOrb(
            modifier = Modifier.align(Alignment.BottomEnd).offset(x = 60.dp, y = 60.dp),
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
                            stringResource(R.string.join_game),
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
                    ),
                    actions = {
                        IconButton(onClick = {
                            qrError = null
                            showQrScanner = true
                        }) {
                            Icon(
                                Icons.Default.QrCodeScanner,
                                contentDescription = stringResource(R.string.scan_qr_button),
                                tint = NeonPrimary
                            )
                        }
                        IconButton(onClick = onStartDiscovery) {
                            if (isDiscovering) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(24.dp),
                                    strokeWidth = 2.dp,
                                    color = NeonPrimary
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
            }
        ) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .imePadding()
                    .navigationBarsPadding()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Error message (QR or server/network)
                AnimatedVisibility(visible = displayedError != null) {
                    displayedError?.let { msg ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 8.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(NeonError.copy(alpha = 0.12f))
                                .border(1.dp, NeonError.copy(alpha = 0.4f), RoundedCornerShape(12.dp))
                                .padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Default.ErrorOutline,
                                contentDescription = null,
                                tint = NeonError,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                text = msg,
                                color = NeonError,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }

                // === PLAYER INFO SECTION ===
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(20.dp))
                        .background(GlassBase)
                        .border(
                            1.dp,
                            Brush.linearGradient(listOf(GlassBorder.copy(alpha = 0.6f), GlassBorderDim)),
                            RoundedCornerShape(20.dp)
                        )
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = stringResource(R.string.your_character),
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
                                {
                                    Icon(
                                        Icons.Default.Lock,
                                        contentDescription = stringResource(R.string.field_locked),
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                }
                            } else null,
                            supportingText = if (nameError && !isNameLocked) {
                                {
                                    Text(
                                        stringResource(R.string.name_required_error),
                                        color = MaterialTheme.colorScheme.error
                                    )
                                }
                            } else null,
                            colors = OutlinedTextFieldDefaults.colors(
                                errorBorderColor = MaterialTheme.colorScheme.error,
                                errorLabelColor = MaterialTheme.colorScheme.error
                            )
                        )

                        Spacer(modifier = Modifier.height(16.dp))

                        Text(
                            text = stringResource(R.string.select_avatar),
                            style = MaterialTheme.typography.labelLarge,
                            color = NeonGray100
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        // 2x4 Avatar grid with >= 48dp touch targets
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            avatarRows.forEach { rowAvatarIds ->
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    rowAvatarIds.forEach { avatarId ->
                                        val isSelected = selectedAvatarId == avatarId
                                        val avatarRes = remember(avatarId, selectedGender) {
                                            AvatarResources.getAvatarDrawable(
                                                avatarId,
                                                selectedGender == Gender.F
                                            )
                                        }
                                        Box(
                                            modifier = Modifier
                                                .weight(1f)
                                                .heightIn(min = 48.dp)
                                                .clip(RoundedCornerShape(12.dp))
                                                .background(
                                                    if (isSelected) NeonPrimary.copy(alpha = 0.2f)
                                                    else Color.White.copy(alpha = 0.03f)
                                                )
                                                .border(
                                                    width = if (isSelected) 2.dp else 1.dp,
                                                    color = if (isSelected) NeonPrimary else GlassBorderDim,
                                                    shape = RoundedCornerShape(12.dp)
                                                )
                                                .clickable { selectedAvatarId = avatarId }
                                                .padding(vertical = 6.dp),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Image(
                                                painter = painterResource(avatarRes),
                                                contentDescription = AvatarResources.getAvatarName(avatarId),
                                                modifier = Modifier
                                                    .size(40.dp)
                                                    .clip(CircleShape),
                                                contentScale = ContentScale.Crop
                                            )
                                        }
                                    }
                                    repeat(4 - rowAvatarIds.size) {
                                        Spacer(modifier = Modifier.weight(1f))
                                    }
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        Text(
                            text = stringResource(R.string.gender),
                            style = MaterialTheme.typography.labelLarge,
                            color = NeonGray100
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        // All 3 genders supported by server (M, F, NA)
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
                                        {
                                            Icon(
                                                Icons.Default.Check,
                                                contentDescription = null,
                                                modifier = Modifier.size(16.dp)
                                            )
                                        }
                                    } else null,
                                    modifier = Modifier
                                        .weight(1f)
                                        .heightIn(min = 48.dp)
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // === JOIN BY CODE OR QR ===
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(20.dp))
                        .background(NeonPrimary.copy(alpha = 0.06f))
                        .border(1.dp, NeonPrimary.copy(alpha = 0.3f), RoundedCornerShape(20.dp))
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.Top,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedTextField(
                                value = joinCode,
                                onValueChange = { input ->
                                    qrError = null
                                    val extracted = JoinCodeParser.extractJoinCode(input)
                                    joinCode = extracted
                                        ?: input.uppercase().filter { c -> c.isLetterOrDigit() }.take(8)
                                },
                                label = { Text(stringResource(R.string.join_code)) },
                                modifier = Modifier.weight(1f),
                                singleLine = true,
                                textStyle = MaterialTheme.typography.titleMedium.copy(
                                    fontFamily = FontFamily.Monospace,
                                    letterSpacing = 2.sp
                                ),
                                placeholder = { Text("ABCD2345") },
                                supportingText = {
                                    Text(
                                        text = stringResource(R.string.join_code_format_hint),
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                },
                                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters)
                            )

                            FilledTonalIconButton(
                                onClick = {
                                    qrError = null
                                    showQrScanner = true
                                },
                                modifier = Modifier
                                    .padding(top = 4.dp)
                                    .size(56.dp)
                            ) {
                                Icon(
                                    Icons.Default.QrCodeScanner,
                                    contentDescription = stringResource(R.string.scan_qr_button)
                                )
                            }

                            Button(
                                onClick = {
                                    val code = validJoinCode
                                    if (code != null && validateFields()) {
                                        onJoinGame(code, name.trim(), selectedAvatarId, selectedGender)
                                    }
                                },
                                enabled = validJoinCode != null && !isLoading,
                                modifier = Modifier
                                    .padding(top = 4.dp)
                                    .height(56.dp)
                            ) {
                                if (isLoading) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(20.dp),
                                        color = MaterialTheme.colorScheme.onPrimary,
                                        strokeWidth = 2.dp
                                    )
                                } else {
                                    Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null)
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(stringResource(R.string.join))
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // === AVAILABLE GAMES LIST ===
                Text(
                    text = stringResource(R.string.available_games),
                    style = MaterialTheme.typography.titleSmall,
                    color = NeonCyan,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 4.dp, bottom = 8.dp)
                )

                if (discoveredGames.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .background(GlassBase)
                            .border(1.dp, GlassBorder, RoundedCornerShape(16.dp))
                            .padding(32.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            if (isDiscovering) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(40.dp),
                                    color = NeonPrimary,
                                    strokeWidth = 3.dp
                                )
                                Spacer(modifier = Modifier.height(14.dp))
                                Text(
                                    text = stringResource(R.string.discovering_games),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = NeonGray200
                                )
                            } else {
                                Icon(
                                    Icons.Default.SearchOff,
                                    contentDescription = null,
                                    modifier = Modifier.size(44.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(modifier = Modifier.height(12.dp))
                                Text(
                                    text = stringResource(R.string.no_games_available),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = NeonGray200
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = stringResource(R.string.no_games_available_hint),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
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
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 64.dp)
                                    .clip(RoundedCornerShape(16.dp))
                                    .background(NeonSecondary.copy(alpha = 0.07f))
                                    .border(1.dp, NeonSecondary.copy(alpha = 0.25f), RoundedCornerShape(16.dp))
                                    .clickable(enabled = !isLoading) {
                                        if (validateFields()) {
                                            onJoinDiscoveredGame(game, name.trim(), selectedAvatarId, selectedGender)
                                        }
                                    }
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(16.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(48.dp)
                                            .clip(CircleShape)
                                            .background(Brush.linearGradient(listOf(NeonPrimary, NeonSecondary))),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            Icons.Default.Person,
                                            contentDescription = null,
                                            tint = Color.White
                                        )
                                    }

                                    Spacer(modifier = Modifier.width(16.dp))

                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = game.hostName,
                                            style = MaterialTheme.typography.titleMedium,
                                            fontWeight = FontWeight.Bold,
                                            color = NeonGray100
                                        )
                                        Text(
                                            text = stringResource(
                                                R.string.discovered_game_summary,
                                                game.playerCount,
                                                game.maxPlayers,
                                                game.joinCode
                                            ),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }

                                    Icon(
                                        Icons.AutoMirrored.Filled.ArrowForward,
                                        contentDescription = null,
                                        tint = NeonSecondary
                                    )
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))
            }
        }

        // QR Scanner overlay
        if (showQrScanner) {
            QrScanner(
                onQrCodeScanned = { scannedValue ->
                    showQrScanner = false
                    val extracted = JoinCodeParser.extractJoinCode(scannedValue)
                    if (extracted != null) {
                        qrError = null
                        joinCode = extracted
                        if (validateFields()) {
                            onJoinGame(extracted, name.trim(), selectedAvatarId, selectedGender)
                        }
                    } else {
                        qrError = invalidQrMessage
                    }
                },
                onClose = { showQrScanner = false },
                modifier = Modifier
                    .fillMaxSize()
                    .background(NeonBackground)
            )
        }
    }
}
