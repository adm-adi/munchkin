package com.munchkin.app.ui.screens

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.munchkin.app.R
import com.munchkin.app.BuildConfig
import com.munchkin.app.ui.components.AmbientOrb
import com.munchkin.app.ui.components.EntranceAnimation
import com.munchkin.app.ui.components.NeonDivider
import com.munchkin.app.ui.theme.*
import com.munchkin.app.util.LocaleManager

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    isCheckingUpdate: Boolean = false,
    onBack: () -> Unit,
    onCheckUpdate: () -> Unit
) {
    val context = LocalContext.current
    var currentLocale by remember { mutableStateOf(LocaleManager.getCurrentLocale(context)) }
    var showLanguageDropdown by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(NeonBackground, NeonSurface.copy(alpha = 0.5f), NeonBackground)
                )
            )
    ) {
        // Ambient orbs
        AmbientOrb(
            modifier = Modifier.align(Alignment.TopEnd).offset(x = 60.dp, y = (-60).dp),
            color = NeonPrimary, size = 280.dp, alpha = 0.10f
        )
        AmbientOrb(
            modifier = Modifier.align(Alignment.BottomStart).offset(x = (-60).dp, y = 60.dp),
            color = NeonCyan, size = 220.dp, alpha = 0.08f
        )

        Scaffold(
            containerColor = Color.Transparent,
            topBar = {
                TopAppBar(
                    title = {
                        Text(
                            stringResource(R.string.settings_title),
                            fontWeight = FontWeight.Bold,
                            color = NeonGray100,
                            style = MaterialTheme.typography.titleLarge
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
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = Color.Transparent,
                        titleContentColor = NeonGray100,
                        navigationIconContentColor = NeonGray100
                    ),
                    modifier = Modifier.background(
                        Brush.verticalGradient(
                            listOf(NeonBackground.copy(alpha = 0.98f), Color.Transparent)
                        )
                    )
                )
            }
        ) { paddingValues ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp)
            ) {
                // App Info Section
                EntranceAnimation(delayMs = 0) {
                    GlassSettingsSection(
                        title = stringResource(R.string.about),
                        titleColor = NeonCyan
                    ) {
                        GlassSettingsItem(
                            icon = Icons.Default.Info,
                            iconTint = NeonCyan,
                            title = stringResource(R.string.version),
                            subtitle = "v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"
                        )
                        NeonDivider(color = NeonCyan)
                        GlassSettingsItem(
                            icon = Icons.Default.Refresh,
                            iconTint = NeonPrimary,
                            title = stringResource(R.string.check_updates),
                            subtitle = if (isCheckingUpdate) stringResource(R.string.connecting)
                                       else stringResource(R.string.ok),
                            onClick = onCheckUpdate,
                            isLoading = isCheckingUpdate
                        )
                    }
                }

                // Language Section
                EntranceAnimation(delayMs = 80) {
                    GlassSettingsSection(
                        title = stringResource(R.string.language),
                        titleColor = NeonSecondary
                    ) {
                        Box {
                            GlassSettingsItem(
                                icon = Icons.Default.Language,
                                iconTint = NeonSecondary,
                                title = stringResource(R.string.language),
                                subtitle = currentLocale.displayName,
                                onClick = { showLanguageDropdown = true }
                            )
                            DropdownMenu(
                                expanded = showLanguageDropdown,
                                onDismissRequest = { showLanguageDropdown = false },
                                modifier = Modifier.background(NeonSurface)
                            ) {
                                LocaleManager.getAvailableLocales().forEach { locale ->
                                    DropdownMenuItem(
                                        text = {
                                            Text(
                                                if (locale == LocaleManager.AppLocale.SYSTEM)
                                                    stringResource(R.string.language_system)
                                                else locale.displayName,
                                                color = NeonGray100
                                            )
                                        },
                                        onClick = {
                                            LocaleManager.setLocale(context, locale)
                                            currentLocale = locale
                                            showLanguageDropdown = false
                                        },
                                        leadingIcon = {
                                            if (locale == currentLocale) {
                                                Icon(
                                                    Icons.Default.Check,
                                                    contentDescription = null,
                                                    tint = NeonSecondary
                                                )
                                            }
                                        }
                                    )
                                }
                            }
                        }
                    }
                }

                // Developer Section
                EntranceAnimation(delayMs = 160) {
                    GlassSettingsSection(
                        title = "Developer",
                        titleColor = NeonGold
                    ) {
                        GlassSettingsItem(
                            icon = Icons.Default.Code,
                            iconTint = NeonGold,
                            title = "Developer",
                            subtitle = "Alejandro. El mejor del mundo y te callas."
                        )
                    }
                }

                Spacer(modifier = Modifier.height(32.dp))
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// GLASS SETTINGS SECTION
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun GlassSettingsSection(
    title: String,
    titleColor: Color = NeonPrimary,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(0.dp)
    ) {
        // Section label
        Row(
            modifier = Modifier.padding(start = 4.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(3.dp, 16.dp)
                    .background(
                        brush = Brush.verticalGradient(listOf(titleColor, titleColor.copy(alpha = 0.3f))),
                        shape = RoundedCornerShape(2.dp)
                    )
            )
            Text(
                text = title.uppercase(),
                style = MaterialTheme.typography.labelMedium,
                color = titleColor,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.2.sp
            )
        }

        // Glass container
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(GlassBase)
                .border(
                    width = 1.dp,
                    brush = Brush.linearGradient(
                        listOf(GlassBorder.copy(alpha = 0.6f), GlassBorderDim)
                    ),
                    shape = RoundedCornerShape(20.dp)
                )
        ) {
            // Glass highlight
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .background(
                        brush = Brush.linearGradient(
                            0f to Color.White.copy(alpha = 0.06f),
                            0.4f to Color.Transparent
                        )
                    )
            )
            Column(
                modifier = Modifier.padding(vertical = 4.dp),
                content = content
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// GLASS SETTINGS ITEM
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun GlassSettingsItem(
    icon: ImageVector,
    iconTint: Color = NeonPrimary,
    title: String,
    subtitle: String,
    onClick: (() -> Unit)? = null,
    isLoading: Boolean = false
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (onClick != null && !isLoading)
                    Modifier.clickable { onClick() }
                else Modifier
            )
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Icon box with neon glow
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(iconTint.copy(alpha = 0.15f))
                .border(1.dp, iconTint.copy(alpha = 0.3f), RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center
        ) {
            if (isLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(22.dp),
                    strokeWidth = 2.dp,
                    color = iconTint
                )
            } else {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = iconTint,
                    modifier = Modifier.size(22.dp)
                )
            }
        }

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                color = NeonGray100
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = NeonGray400
            )
        }

        if (onClick != null && !isLoading) {
            Icon(
                Icons.Default.ChevronRight,
                contentDescription = null,
                tint = NeonGray500,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}
