package com.munchkin.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.HttpURLConnection
import java.net.URL

@Serializable
data class MonsterResult(
    val id: String,
    val name: String,
    val level: Int,
    val modifier: Int,
    val treasures: Int,
    val levels: Int,
    val bad_stuff: String = "",
    val expansion: String = "base"
)

class CatalogViewModel : ViewModel() {
    var searchResults by mutableStateOf<List<MonsterResult>>(emptyList())
    var isLoading by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)

    fun search(query: String) {
        isLoading = true
        error = null
        // Using simple HttpURLConnection directly for simplicity given just one endpoint
        // Host: 23.88.48.58:8765
        val urlStr = "http://23.88.48.58:8765/api/monsters?q=${query.trim()}"
        
        // Coroutine
        val scope = kotlinx.coroutines.CoroutineScope(Dispatchers.IO)
        scope.launch {
            try {
                val url = URL(urlStr)
                with(url.openConnection() as HttpURLConnection) {
                    requestMethod = "GET"
                    connectTimeout = 5000
                    readTimeout = 5000
                    
                    if (responseCode == 200) {
                        val response = inputStream.bufferedReader().use { it.readText() }
                        // Parse
                        val results = Json { ignoreUnknownKeys = true }.decodeFromString<List<MonsterResult>>(response)
                        withContext(Dispatchers.Main) {
                            searchResults = results
                            isLoading = false
                        }
                    } else {
                        withContext(Dispatchers.Main) {
                            error = "Error: $responseCode"
                            isLoading = false
                        }
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    error = e.localizedMessage ?: "Unknown Error"
                    isLoading = false
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MonsterCatalogScreen(
    onBack: () -> Unit,
    viewModel: CatalogViewModel = viewModel()
) {
    var searchQuery by remember { mutableStateOf("") }
    
    // Initial search
    LaunchedEffect(Unit) {
        viewModel.search("")
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Catálogo de Monstruos") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Volver")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .padding(16.dp)
        ) {
            // Search Bar
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { 
                    searchQuery = it
                    viewModel.search(it) 
                },
                label = { Text("Buscar monstruo...") },
                leadingIcon = { Icon(Icons.Default.Search, null) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
            
            Spacer(modifier = Modifier.height(16.dp))
            
            if (viewModel.isLoading) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else if (viewModel.error != null) {
                Text("Error: ${viewModel.error}", color = MaterialTheme.colorScheme.error)
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(viewModel.searchResults) { monster ->
                        MonsterCard(monster)
                    }
                }
            }
        }
    }
}

@Composable
fun MonsterCard(monster: MonsterResult) {
    Card(
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = monster.name,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "Nivel ${monster.level}",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            Row {
                Text("Tesoros: ${monster.treasures}", modifier = Modifier.weight(1f))
                Text("Niveles: ${monster.levels}", modifier = Modifier.weight(1f))
            }
            if (monster.bad_stuff.isNotEmpty()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Mal Rollo: ${monster.bad_stuff}", 
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}
