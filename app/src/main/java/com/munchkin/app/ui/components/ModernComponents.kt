package com.munchkin.app.ui.components

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.munchkin.app.ui.theme.*

/**
 * Modern glass-effect card with neon glow border option.
 */
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 24.dp,
    borderColor: Color = GlassBorder,
    containerColor: Color = GlassDark,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    Surface(
        modifier = modifier
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier)
            .border(
                width = 1.dp,
                color = borderColor,
                shape = RoundedCornerShape(cornerRadius)
            ),
        shape = RoundedCornerShape(cornerRadius),
        color = containerColor,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            content = content
        )
    }
}

/**
 * Gradient button with Neon gradients.
 */
@Composable
fun GradientButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: ImageVector? = null,
    gradientColors: List<Color> = GradientNeonPurple
) {
    val alpha = if (enabled) 1f else 0.5f
    
    Box(
        modifier = modifier
            .height(56.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(
                brush = Brush.horizontalGradient(gradientColors),
                alpha = alpha
            )
            .clickable(enabled = enabled) { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Row(
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            icon?.let {
                Icon(
                    imageVector = it,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
            }
            Text(
                text = text,
                style = MaterialTheme.typography.titleMedium,
                color = Color.White,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

/**
 * Outlined glass button.
 */
@Composable
fun GlassOutlinedButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: ImageVector? = null,
    color: Color = NeonGray100
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.height(56.dp),
        shape = RoundedCornerShape(16.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, color.copy(alpha = 0.5f)),
        colors = ButtonDefaults.outlinedButtonColors(
            contentColor = color
        )
    ) {
        icon?.let {
            Icon(
                imageVector = it,
                contentDescription = null,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
        }
        Text(
            text = text,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Medium
        )
    }
}

/**
 * Modern text field with dark glass style and Neon accents.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GlassTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    placeholder: String? = null,
    leadingIcon: ImageVector? = null,
    trailingIcon: @Composable (() -> Unit)? = null,
    singleLine: Boolean = true,
    enabled: Boolean = true
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        label = label?.let { { Text(it) } },
        placeholder = placeholder?.let { { Text(it, color = NeonGray500) } },
        leadingIcon = leadingIcon?.let { 
            { Icon(it, contentDescription = null, tint = NeonGray400) } 
        },
        trailingIcon = trailingIcon,
        singleLine = singleLine,
        enabled = enabled,
        shape = RoundedCornerShape(16.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = NeonPrimary,
            unfocusedBorderColor = NeonGray500,
            focusedLabelColor = NeonPrimary,
            unfocusedLabelColor = NeonGray400,
            cursorColor = NeonPrimary,
            focusedContainerColor = NeonSurface,
            unfocusedContainerColor = NeonSurface.copy(alpha = 0.5f),
            disabledContainerColor = NeonSurface.copy(alpha = 0.2f),
            disabledBorderColor = NeonGray500.copy(alpha = 0.5f)
        )
    )
}

/**
 * Animated stat counter with modern look.
 */
@OptIn(ExperimentalAnimationApi::class)
@Composable
fun StatCounter(
    value: Int,
    label: String,
    onIncrement: () -> Unit,
    onDecrement: () -> Unit,
    modifier: Modifier = Modifier,
    accentColor: Color = NeonPrimary,
    containerColor: Color = NeonSurface,
    showSign: Boolean = false,
    minValue: Int = Int.MIN_VALUE,
    maxValue: Int = Int.MAX_VALUE
) {
    GlassCard(
        modifier = modifier,
        containerColor = containerColor,
        borderColor = accentColor.copy(alpha = 0.3f)
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = label.uppercase(),
                style = MaterialTheme.typography.labelSmall,
                color = NeonGray400,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.5.sp

            )
            
            Spacer(modifier = Modifier.height(12.dp))
            
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Decrement button
                IconButton(
                    onClick = onDecrement,
                    enabled = value > minValue,
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(NeonSurfaceVariant)
                ) {
                    Icon(
                        Icons.Default.Remove,
                        contentDescription = "Decrementar",
                        tint = if (value > minValue) NeonGray100 else NeonGray500
                    )
                }
                
                // Value
                AnimatedContent(
                    targetState = value,
                    transitionSpec = {
                        if (targetState > initialState) {
                            slideInVertically { -it } + fadeIn() togetherWith
                            slideOutVertically { it } + fadeOut()
                        } else {
                            slideInVertically { it } + fadeIn() togetherWith
                            slideOutVertically { -it } + fadeOut()
                        }.using(SizeTransform(clip = false))
                    },
                    label = "value"
                ) { animatedValue ->
                    val displayValue = if (showSign && animatedValue > 0) "+$animatedValue" else animatedValue.toString()
                    Text(
                        text = displayValue,
                        style = MaterialTheme.typography.displayMedium,
                        fontWeight = FontWeight.Bold,
                        color = accentColor
                    )
                }
                
                // Increment button
                IconButton(
                    onClick = onIncrement,
                    enabled = value < maxValue,
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(accentColor)
                ) {
                    Icon(
                        Icons.Default.Add,
                        contentDescription = "Incrementar",
                        tint = Color.White
                    )
                }
            }
        }
    }
}

/**
 * Section header with optional action.
 */
@Composable
fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    action: @Composable (() -> Unit)? = null
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = NeonGray100
        )
        action?.invoke()
    }
}

/**
 * Modern chip for tags/traits
 */
@Composable
fun TraitChip(
    name: String,
    onRemove: () -> Unit
) {
    Surface(
        color = NeonPrimary.copy(alpha = 0.2f),
        shape = RoundedCornerShape(50),
        border = androidx.compose.foundation.BorderStroke(1.dp, NeonPrimary.copy(alpha = 0.5f))
    ) {
        Row(
            modifier = Modifier
                .clickable { onRemove() }
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = name,
                style = MaterialTheme.typography.bodyMedium,
                color = NeonGray100
            )
            Spacer(modifier = Modifier.width(4.dp))
            Icon(
                Icons.Default.Close,
                contentDescription = "Remover",
                modifier = Modifier.size(14.dp),
                tint = NeonGray100
            )
        }
    }
}

/**
 * Glass Top App Bar with Neon styling.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GlassTopAppBar(
    title: String,
    navigationIcon: ImageVector? = null,
    onNavigationClick: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {}
) {
    CenterAlignedTopAppBar(
        title = { Text(title, color = NeonGray100, fontWeight = FontWeight.Bold) },
        navigationIcon = {
            if (navigationIcon != null && onNavigationClick != null) {
                IconButton(onClick = onNavigationClick) {
                    Icon(navigationIcon, contentDescription = null, tint = NeonGray100)
                }
            }
        },
        actions = actions,
        colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
            containerColor = Color.Transparent,
            titleContentColor = NeonGray100,
            actionIconContentColor = NeonGray400,
            navigationIconContentColor = NeonGray100
        ),
        modifier = Modifier.background(
            Brush.verticalGradient(
                colors = listOf(NeonBackground.copy(alpha = 0.95f), NeonBackground.copy(alpha = 0.0f))
            )
        )
    )
}
