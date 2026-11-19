package com.munchkin.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.munchkin.app.R
import com.munchkin.app.core.GameLogEntry
import com.munchkin.app.core.LogType
import com.munchkin.app.ui.theme.DungeonBorder
import com.munchkin.app.ui.theme.NeonError
import com.munchkin.app.ui.theme.NeonGray100
import com.munchkin.app.ui.theme.NeonGray300
import com.munchkin.app.ui.theme.NeonSecondary
import com.munchkin.app.ui.theme.NeonSuccess
import com.munchkin.app.ui.theme.NeonSurface
import com.munchkin.app.ui.theme.NeonSurfaceVariant
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Bottom sheet displaying chronological session events in reverse chronological order.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GameLogDrawer(
    logEntries: List<GameLogEntry>,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = NeonSurface,
        contentColor = NeonGray100
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 32.dp)
        ) {
            Text(
                text = stringResource(R.string.game_log_title),
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                color = NeonGray100,
                modifier = Modifier.padding(bottom = 16.dp)
            )

            if (logEntries.isEmpty()) {
                Text(
                    text = stringResource(R.string.game_log_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = NeonGray300,
                    modifier = Modifier.padding(top = 12.dp)
                )
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(
                        items = logEntries,
                        key = { it.id }
                    ) { entry ->
                        LogEntryItem(entry = entry)
                    }
                }
            }
        }
    }
}

@Composable
fun LogEntryItem(entry: GameLogEntry) {
    val timeFormat = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }
    val timeString = remember(entry.timestamp) { timeFormat.format(Date(entry.timestamp)) }
    val shape = RoundedCornerShape(10.dp)

    val messageColor = when (entry.type) {
        LogType.LEVEL_UP -> NeonSuccess
        LogType.COMBAT -> NeonError
        LogType.GAME_EVENT -> NeonSecondary
        else -> NeonGray100
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(NeonSurfaceVariant)
            .border(1.dp, DungeonBorder, shape)
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        Text(
            text = timeString,
            style = MaterialTheme.typography.labelSmall.copy(
                fontFeatureSettings = "tnum"
            ),
            color = NeonGray300,
            modifier = Modifier.widthIn(min = 42.dp)
        )

        Spacer(modifier = Modifier.width(8.dp))

        Text(
            text = entry.message,
            style = MaterialTheme.typography.bodyMedium.copy(
                fontWeight = if (entry.type == LogType.LEVEL_UP) FontWeight.Bold else FontWeight.Normal
            ),
            color = messageColor,
            modifier = Modifier.weight(1f)
        )
    }
}
