package com.munchkin.app.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.*

/**
 * Debug log manager for in-app log viewing.
 */
object DebugLogManager {
    private val _logs = MutableStateFlow<List<LogEntry>>(emptyList())
    val logs: StateFlow<List<LogEntry>> = _logs.asStateFlow()
    
    private val maxLogs = 100
    private val dateFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())
    
    fun log(tag: String, message: String, level: LogLevel = LogLevel.INFO) {
        val entry = LogEntry(
            timestamp = dateFormat.format(Date()),
            tag = tag,
            message = message,
            level = level
        )
        
        _logs.value = (_logs.value + entry).takeLast(maxLogs)
        
        // Also log to Android Logcat
        when (level) {
            LogLevel.DEBUG -> android.util.Log.d(tag, message)
            LogLevel.INFO -> android.util.Log.i(tag, message)
            LogLevel.WARNING -> android.util.Log.w(tag, message)
            LogLevel.ERROR -> android.util.Log.e(tag, message)
        }
    }
    
    fun clear() {
        _logs.value = emptyList()
    }
    
    fun d(tag: String, message: String) = log(tag, message, LogLevel.DEBUG)
    fun i(tag: String, message: String) = log(tag, message, LogLevel.INFO)
    fun w(tag: String, message: String) = log(tag, message, LogLevel.WARNING)
    fun e(tag: String, message: String) = log(tag, message, LogLevel.ERROR)
}

data class LogEntry(
    val timestamp: String,
    val tag: String,
    val message: String,
    val level: LogLevel
)

enum class LogLevel {
    DEBUG, INFO, WARNING, ERROR
}

/**
 * Floating debug button and log viewer.
 */
@Composable
fun DebugLogViewer(
    showTrigger: Boolean = true,
    modifier: Modifier = Modifier
) {
    var isExpanded by remember { mutableStateOf(false) }
    val logs by DebugLogManager.logs.collectAsState()
    val listState = rememberLazyListState()
    val clipboardManager = LocalClipboardManager.current
    
    // Auto-scroll to bottom when new logs arrive
    LaunchedEffect(logs.size) {
        if (logs.isNotEmpty()) {
            listState.animateScrollToItem(logs.size - 1)
        }
    }
    
    Box(modifier = modifier.fillMaxSize()) {
        // Floating button
        if (showTrigger) {
            FloatingActionButton(
                onClick = { isExpanded = !isExpanded },
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(16.dp),
                containerColor = if (logs.any { it.level == LogLevel.ERROR }) 
                    MaterialTheme.colorScheme.error 
                else 
                    MaterialTheme.colorScheme.secondary
            ) {
                Icon(
                    Icons.Default.BugReport,
                    contentDescription = "Debug Logs"
                )
            }
        }
        
        // Log panel
        AnimatedVisibility(
            visible = isExpanded,
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.5f)
                .align(Alignment.BottomCenter)
        ) {
            Surface(
                modifier = Modifier.fillMaxSize(),
                shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
                color = Color.Black.copy(alpha = 0.9f),
                tonalElevation = 8.dp
            ) {
                Column {
                    // Header
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "📋 Debug Logs (${logs.size})",
                            color = Color.White,
                            style = MaterialTheme.typography.titleSmall
                        )
                        
                        Row {
                            IconButton(onClick = {
                                val text = logs.joinToString("\n") { 
                                    "${it.timestamp} ${it.tag}: ${it.message}" 
                                }
                                clipboardManager.setText(AnnotatedString(text))
                            }) {
                                Icon(
                                    Icons.Default.ContentCopy,
                                    contentDescription = "Copy",
                                    tint = Color.White
                                )
                            }
                            
                            IconButton(onClick = { isExpanded = false }) {
                                Icon(
                                    Icons.Default.Close,
                                    contentDescription = "Close",
                                    tint = Color.White
                                )
                            }
                        }
                    }
                    
                    // Log list
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(8.dp)
                    ) {
                        items(logs) { entry ->
                            Text(
                                text = "${entry.timestamp} [${entry.tag}] ${entry.message}",
                                color = when (entry.level) {
                                    LogLevel.DEBUG -> Color.Gray
                                    LogLevel.INFO -> Color.Green
                                    LogLevel.WARNING -> Color.Yellow
                                    LogLevel.ERROR -> Color.Red
                                },
                                fontFamily = FontFamily.Monospace,
                                fontSize = 10.sp,
                                modifier = Modifier.padding(vertical = 2.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}
