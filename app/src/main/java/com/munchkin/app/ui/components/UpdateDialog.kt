package com.munchkin.app.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.NewReleases
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.munchkin.app.R
import com.munchkin.app.ui.theme.DungeonBorder
import com.munchkin.app.ui.theme.NeonGray100
import com.munchkin.app.ui.theme.NeonGray200
import com.munchkin.app.ui.theme.NeonGray300
import com.munchkin.app.ui.theme.NeonPrimary
import com.munchkin.app.ui.theme.NeonPrimaryDark
import com.munchkin.app.ui.theme.NeonPrimaryLight
import com.munchkin.app.ui.theme.NeonSurface
import com.munchkin.app.ui.theme.NeonSurfaceVariant
import com.munchkin.app.update.UpdateInfo
import java.util.Locale

/**
 * Dialog showing available app update details and initiating in-app APK download.
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
            color = NeonSurface,
            border = BorderStroke(1.dp, DungeonBorder)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(
                    modifier = Modifier
                        .size(64.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(NeonPrimaryDark.copy(alpha = 0.45f))
                        .border(1.dp, NeonPrimaryLight.copy(alpha = 0.4f), RoundedCornerShape(16.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.SystemUpdate,
                        contentDescription = null,
                        tint = NeonPrimaryLight,
                        modifier = Modifier.size(32.dp)
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = stringResource(R.string.update_available),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = NeonGray100,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(6.dp))

                Text(
                    text = "v${updateInfo.version}",
                    style = MaterialTheme.typography.titleMedium.copy(fontFeatureSettings = "tnum"),
                    fontWeight = FontWeight.SemiBold,
                    color = NeonPrimaryLight
                )

                Spacer(modifier = Modifier.height(16.dp))

                val notesScrollState = rememberScrollState()
                val notesShape = RoundedCornerShape(12.dp)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 60.dp, max = 180.dp)
                        .clip(notesShape)
                        .background(NeonSurfaceVariant)
                        .border(1.dp, DungeonBorder, notesShape)
                ) {
                    Text(
                        text = updateInfo.releaseNotes,
                        style = MaterialTheme.typography.bodyMedium,
                        color = NeonGray200,
                        modifier = Modifier
                            .fillMaxWidth()
                            .verticalScroll(notesScrollState)
                            .padding(12.dp)
                    )
                    if (notesScrollState.canScrollForward) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .fillMaxWidth()
                                .height(24.dp)
                                .background(
                                    brush = Brush.verticalGradient(
                                        colors = listOf(Color.Transparent, NeonSurfaceVariant)
                                    )
                                )
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = stringResource(
                        R.string.update_size_format,
                        formatFileSize(updateInfo.fileSize)
                    ),
                    style = MaterialTheme.typography.bodySmall.copy(fontFeatureSettings = "tnum"),
                    color = NeonGray300
                )

                Spacer(modifier = Modifier.height(24.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedButton(
                        onClick = onDismiss,
                        enabled = !isDownloading,
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 48.dp),
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, DungeonBorder),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = NeonGray100,
                            disabledContentColor = NeonGray300.copy(alpha = 0.5f)
                        )
                    ) {
                        Text(text = stringResource(R.string.later))
                    }

                    Button(
                        onClick = onDownload,
                        enabled = !isDownloading,
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 48.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = NeonPrimary,
                            contentColor = MaterialTheme.colorScheme.onPrimary,
                            disabledContainerColor = NeonPrimaryDark,
                            disabledContentColor = MaterialTheme.colorScheme.onPrimary
                        )
                    ) {
                        if (isDownloading) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary,
                                strokeCap = StrokeCap.Round
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = stringResource(R.string.downloading),
                                color = MaterialTheme.colorScheme.onPrimary
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Default.Download,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(text = stringResource(R.string.update))
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
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp),
        shape = RoundedCornerShape(12.dp),
        color = NeonSurfaceVariant,
        border = BorderStroke(1.dp, NeonPrimaryLight.copy(alpha = 0.45f))
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.NewReleases,
                contentDescription = null,
                tint = NeonPrimaryLight,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.update_available),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = NeonGray100
                )
                Text(
                    text = "v$version",
                    style = MaterialTheme.typography.bodySmall.copy(fontFeatureSettings = "tnum"),
                    color = NeonPrimaryLight
                )
            }
            Icon(
                imageVector = Icons.Default.ChevronRight,
                contentDescription = null,
                tint = NeonGray300
            )
        }
    }
}

private fun formatFileSize(bytes: Long): String {
    return when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${bytes / 1024} KB"
        else -> String.format(Locale.getDefault(), "%.1f MB", bytes / (1024.0 * 1024.0))
    }
}
