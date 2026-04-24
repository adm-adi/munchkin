package com.munchkin.app.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Login
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import com.munchkin.app.R
import com.munchkin.app.ui.components.*
import com.munchkin.app.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AuthScreen(
    onLogin: (String, String) -> Unit,
    onRegister: (String, String, String) -> Unit,
    onBack: () -> Unit,
    isLoading: Boolean,
    error: String?,
    modifier: Modifier = Modifier
) {
    var isRegister by remember { mutableStateOf(false) }
    var username by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(NeonBackground, NeonSurface.copy(alpha = 0.4f), NeonBackground)
                )
            )
    ) {
        // Ambient background orbs
        AmbientOrb(
            modifier = Modifier.align(Alignment.TopCenter).offset(y = (-80).dp),
            color = NeonPrimary, size = 360.dp, alpha = 0.14f
        )
        AmbientOrb(
            modifier = Modifier.align(Alignment.BottomEnd).offset(x = 60.dp, y = 60.dp),
            color = NeonSecondary, size = 280.dp, alpha = 0.10f
        )
        AmbientOrb(
            modifier = Modifier.align(Alignment.BottomStart).offset(x = (-40).dp, y = 40.dp),
            color = NeonCyan, size = 200.dp, alpha = 0.08f
        )

        Scaffold(
            modifier = Modifier.fillMaxSize(),
            containerColor = Color.Transparent,
            topBar = {
                TopAppBar(
                    title = {},
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                stringResource(R.string.back),
                                tint = NeonGray100
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
                )
            }
        ) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                EntranceAnimation(delayMs = 0) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(24.dp)
                    ) {
                        // Icon with neon glow ring
                        Box(contentAlignment = Alignment.Center) {
                            // Outer glow ring
                            Box(
                                modifier = Modifier
                                    .size(100.dp)
                                    .background(
                                        Brush.radialGradient(
                                            listOf(NeonPrimary.copy(alpha = 0.2f), Color.Transparent)
                                        ),
                                        CircleShape
                                    )
                            )
                            // Icon container
                            Box(
                                modifier = Modifier
                                    .size(72.dp)
                                    .clip(CircleShape)
                                    .background(
                                        Brush.linearGradient(GradientNeonPurple)
                                    )
                                    .border(1.dp, GlassBorder, CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Person,
                                    contentDescription = null,
                                    modifier = Modifier.size(36.dp),
                                    tint = Color.White
                                )
                            }
                        }

                        // Title
                        AnimatedContent(
                            targetState = isRegister,
                            transitionSpec = { fadeIn() togetherWith fadeOut() },
                            label = "authTitle"
                        ) { register ->
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    text = if (register) stringResource(R.string.create_account)
                                           else stringResource(R.string.login_title),
                                    style = MaterialTheme.typography.headlineMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = NeonGray100
                                )
                                Text(
                                    text = if (register) stringResource(R.string.no_account)
                                           else "Munchkin Tracker",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = NeonGray400
                                )
                            }
                        }

                        // Glass form card
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(28.dp))
                                .background(GlassBase)
                                .border(
                                    1.dp,
                                    Brush.linearGradient(
                                        listOf(GlassBorder.copy(alpha = 0.7f), GlassBorderDim)
                                    ),
                                    RoundedCornerShape(28.dp)
                                )
                        ) {
                            // Glass highlight
                            Box(
                                modifier = Modifier
                                    .matchParentSize()
                                    .background(
                                        Brush.linearGradient(
                                            0f to Color.White.copy(alpha = 0.08f),
                                            0.5f to Color.Transparent
                                        )
                                    )
                            )
                            Column(
                                modifier = Modifier.padding(24.dp),
                                verticalArrangement = Arrangement.spacedBy(16.dp)
                            ) {
                                // Username (register only)
                                AnimatedContent(
                                    targetState = isRegister,
                                    transitionSpec = {
                                        (fadeIn() + androidx.compose.animation.expandVertically()) togetherWith
                                        (fadeOut() + androidx.compose.animation.shrinkVertically())
                                    },
                                    label = "usernameField"
                                ) { register ->
                                    if (register) {
                                        GlassTextField(
                                            value = username,
                                            onValueChange = { username = it },
                                            label = stringResource(R.string.username),
                                            leadingIcon = Icons.Default.Person,
                                            modifier = Modifier.fillMaxWidth()
                                        )
                                    } else {
                                        GlassTextField(
                                            value = email,
                                            onValueChange = { email = it },
                                            label = stringResource(R.string.email_or_username),
                                            leadingIcon = Icons.Default.Email,
                                            modifier = Modifier.fillMaxWidth()
                                        )
                                    }
                                }

                                // If register, also show email
                                if (isRegister) {
                                    GlassTextField(
                                        value = email,
                                        onValueChange = { email = it },
                                        label = "Email",
                                        leadingIcon = Icons.Default.Email,
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                }

                                // Password field
                                OutlinedTextField(
                                    value = password,
                                    onValueChange = { password = it },
                                    label = { Text(stringResource(R.string.password)) },
                                    singleLine = true,
                                    visualTransformation = if (passwordVisible)
                                        VisualTransformation.None
                                    else PasswordVisualTransformation(),
                                    modifier = Modifier.fillMaxWidth(),
                                    leadingIcon = {
                                        Icon(Icons.Default.Lock, null, tint = NeonGray400)
                                    },
                                    trailingIcon = {
                                        IconButton(onClick = { passwordVisible = !passwordVisible }) {
                                            Icon(
                                                if (passwordVisible) Icons.Default.Visibility
                                                else Icons.Default.VisibilityOff,
                                                null,
                                                tint = NeonGray400
                                            )
                                        }
                                    },
                                    shape = RoundedCornerShape(16.dp),
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedBorderColor = NeonPrimary,
                                        unfocusedBorderColor = GlassBorder,
                                        focusedLabelColor = NeonPrimary,
                                        unfocusedLabelColor = NeonGray400,
                                        cursorColor = NeonPrimary,
                                        focusedContainerColor = NeonPrimary.copy(alpha = 0.08f),
                                        unfocusedContainerColor = Color.White.copy(alpha = 0.04f),
                                        focusedTextColor = NeonGray100,
                                        unfocusedTextColor = NeonGray200
                                    )
                                )

                                // Error display
                                if (error != null) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(12.dp))
                                            .background(NeonError.copy(alpha = 0.12f))
                                            .border(1.dp, NeonError.copy(alpha = 0.3f), RoundedCornerShape(12.dp))
                                            .padding(12.dp)
                                    ) {
                                        Text(
                                            text = error,
                                            color = NeonError,
                                            style = MaterialTheme.typography.bodySmall
                                        )
                                    }
                                }

                                // Submit button (gradient)
                                GradientButton(
                                    text = if (isRegister) stringResource(R.string.register_button)
                                           else stringResource(R.string.login_button),
                                    onClick = {
                                        if (isRegister) onRegister(username, email, password)
                                        else onLogin(email, password)
                                    },
                                    enabled = !isLoading && password.isNotBlank() &&
                                        ((isRegister && username.isNotBlank() && email.isNotBlank()) ||
                                        (!isRegister && email.isNotBlank())),
                                    modifier = Modifier.fillMaxWidth(),
                                    icon = if (isLoading) null else Icons.AutoMirrored.Filled.Login,
                                    gradientColors = if (isRegister) GradientNeonFire else GradientNeonPurple
                                )

                                // Toggle register/login
                                TextButton(
                                    onClick = { isRegister = !isRegister },
                                    modifier = Modifier.align(Alignment.CenterHorizontally)
                                ) {
                                    Text(
                                        text = if (isRegister) stringResource(R.string.already_have_account)
                                               else stringResource(R.string.no_account),
                                        color = NeonPrimaryLight,
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
