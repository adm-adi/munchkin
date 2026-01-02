package com.munchkin.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.munchkin.app.core.GameLogEntry
import com.munchkin.app.core.LogType
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GameLogDrawer(
    logEntries: List<GameLogEntry>,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF1E1E1E) // Dark background
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 32.dp)
        ) {
            Text(
                text = "Historial de Partida",
                style = MaterialTheme.typography.titleLarge,
                color = Color.White,
                modifier = Modifier.padding(bottom = 16.dp)
            )

            if (logEntries.isEmpty()) {
                Text(
                    text = "Aún no hay eventos.",
                    color = Color.Gray,
                    modifier = Modifier.padding(top = 16.dp)
                )
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    reverseLayout = true // Newest at bottom if we want chat style, or top if list style. 
                    // Let's standard list: Newest at bottom needs auto-scroll. 
                    // Usually logs are Newest at TOP for easy reading of "what just happened".
                    // Let's do Newest at TOP -> simply reverse list or iterate
                ) {
                    items(logEntries.reversed()) { entry ->
                        LogEntryItem(entry)
                    }
                }
            }
        }
    }
}

@Composable
fun LogEntryItem(entry: GameLogEntry) {
    val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
    val timeString = timeFormat.format(Date(entry.timestamp))

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF2A2A2A), MaterialTheme.shapes.small)
            .padding(8.dp)
    ) {
        Text(
            text = timeString,
            color = Color.Gray,
            fontSize = 12.sp,
            modifier = Modifier.width(40.dp)
        )
        
        val color = when(entry.type) {
            LogType.LEVEL_UP -> Color(0xFF4CAF50) // Green
            LogType.combat -> Color(0xFFF44336)   // Red
            LogType.GAME_EVENT -> Color(0xFF2196F3) // Blue
            else -> Color.White
        }

        Text(
            text = entry.message,
            color = color,
            fontSize = 14.sp,
            fontWeight = if (entry.type == LogType.LEVEL_UP) FontWeight.Bold else FontWeight.Normal
        )
    }
}
