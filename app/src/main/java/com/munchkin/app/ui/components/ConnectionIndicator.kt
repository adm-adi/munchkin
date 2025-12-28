package com.munchkin.app.ui.components

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * Connection quality indicator showing latency and status.
 */
@Composable
fun ConnectionIndicator(
    latencyMs: Long,
    isConnected: Boolean,
    isReconnecting: Boolean = false,
    modifier: Modifier = Modifier
) {
    val (color, icon) = when {
        !isConnected -> Color.Red to Icons.Default.SignalWifiOff
        isReconnecting -> Color.Yellow to Icons.Default.Sync
        latencyMs < 50 -> Color.Green to Icons.Default.SignalWifi4Bar
        latencyMs < 100 -> Color(0xFF90EE90) to Icons.Default.NetworkWifi3Bar
        latencyMs < 200 -> Color.Yellow to Icons.Default.NetworkWifi2Bar
        else -> Color(0xFFFFA500) to Icons.Default.NetworkWifi1Bar
    }
    
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        // Status dot
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(color)
        )
        
        // Icon
        Icon(
            imageVector = icon,
            contentDescription = "Estado de conexión",
            tint = color,
            modifier = Modifier.size(20.dp)
        )
        
        // Latency text
        if (isConnected && !isReconnecting) {
            Text(
                text = "${latencyMs}ms",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * Connection status banner for showing connection issues.
 */
@Composable
fun ConnectionBanner(
    isConnected: Boolean,
    isReconnecting: Boolean,
    reconnectAttempt: Int = 0,
    modifier: Modifier = Modifier
) {
    AnimatedVisibility(
        visible = !isConnected || isReconnecting,
        enter = slideInVertically() + fadeIn(),
        exit = slideOutVertically() + fadeOut()
    ) {
        Surface(
            modifier = modifier.fillMaxWidth(),
            color = if (isReconnecting) 
                MaterialTheme.colorScheme.tertiaryContainer 
            else 
                MaterialTheme.colorScheme.errorContainer
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                if (isReconnecting) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Reconectando... (intento $reconnectAttempt)",
                        style = MaterialTheme.typography.bodySmall
                    )
                } else {
                    Icon(
                        Icons.Default.WifiOff,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Sin conexión",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
    }
}
