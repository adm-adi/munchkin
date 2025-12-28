package com.munchkin.app.ui.components

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.munchkin.app.ui.theme.*
import com.munchkin.app.update.UpdateInfo

/**
 * Dialog showing update is available with download option.
 */
@Composable
fun UpdateDialog(
    updateInfo: UpdateInfo,
    isDownloading: Boolean,
    onDownload: () -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = { if (!isDownloading) onDismiss() }) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = LumaGray900,
            tonalElevation = 8.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Icon with glow
                Box(
                    modifier = Modifier
                        .size(64.dp)
                        .background(
                            LumaPrimary.copy(alpha = 0.2f),
                            RoundedCornerShape(16.dp)
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Default.SystemUpdate,
                        contentDescription = null,
                        tint = LumaPrimary,
                        modifier = Modifier.size(32.dp)
                    )
                }
                
                Spacer(modifier = Modifier.height(16.dp))
                
                Text(
                    text = "¡Nueva versión disponible!",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = LumaGray50
                )
                
                Spacer(modifier = Modifier.height(8.dp))
                
                Text(
                    text = "v${updateInfo.version}",
                    style = MaterialTheme.typography.titleMedium,
                    color = LumaPrimary
                )
                
                Spacer(modifier = Modifier.height(16.dp))
                
                // Release notes
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = LumaGray800,
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(
                        text = updateInfo.releaseNotes,
                        style = MaterialTheme.typography.bodyMedium,
                        color = LumaGray300,
                        modifier = Modifier.padding(12.dp)
                    )
                }
                
                Spacer(modifier = Modifier.height(8.dp))
                
                // File size
                Text(
                    text = "Tamaño: ${formatFileSize(updateInfo.fileSize)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = LumaGray500
                )
                
                Spacer(modifier = Modifier.height(24.dp))
                
                // Buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedButton(
                        onClick = onDismiss,
                        enabled = !isDownloading,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text("Luego")
                    }
                    
                    Button(
                        onClick = onDownload,
                        enabled = !isDownloading,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = LumaPrimary
                        )
                    ) {
                        if (isDownloading) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp,
                                color = LumaGray50
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Descargando...")
                        } else {
                            Icon(
                                Icons.Default.Download,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Actualizar")
                        }
                    }
                }
            }
        }
    }
}

/**
 * Small banner to show update available on home screen.
 */
@Composable
fun UpdateBanner(
    version: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = LumaPrimary.copy(alpha = 0.15f)
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Default.NewReleases,
                contentDescription = null,
                tint = LumaPrimary,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Nueva versión disponible",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = LumaGray100
                )
                Text(
                    text = "v$version",
                    style = MaterialTheme.typography.bodySmall,
                    color = LumaPrimary
                )
            }
            Icon(
                Icons.Default.ChevronRight,
                contentDescription = null,
                tint = LumaGray500
            )
        }
    }
}

private fun formatFileSize(bytes: Long): String {
    return when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${bytes / 1024} KB"
        else -> String.format("%.1f MB", bytes / (1024.0 * 1024.0))
    }
}
