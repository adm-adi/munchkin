package com.munchkin.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.munchkin.app.MunchkinApp
import com.munchkin.app.R
import com.munchkin.app.network.GameClient
import com.munchkin.app.network.ServerConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable

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

    private val client = GameClient()
    private var searchJob: Job? = null

    fun search(query: String) {
        searchJob?.cancel()

        if (query.isBlank()) {
            searchResults = emptyList()
            isLoading = false
            error = null
            return
        }

        isLoading = true
        error = null
        searchJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                val results = client
                    .searchMonsters(ServerConfig.WS_URL, query.trim())
                    .getOrElse { emptyList() }
                    .map { monster ->
                        MonsterResult(
                            id = monster.id,
                            name = monster.name,
                            level = monster.level,
                            modifier = monster.modifier,
                            treasures = monster.treasures,
                            levels = monster.levels,
                            bad_stuff = monster.badStuff,
                            expansion = monster.expansion
                        )
                    }

                withContext(Dispatchers.Main) {
                    searchResults = results
                    isLoading = false
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    error = e.localizedMessage ?: MunchkinApp.context.getString(R.string.error_unknown)
                    isLoading = false
                }
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        searchJob?.cancel()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MonsterCatalogScreen(
    onBack: () -> Unit,
    viewModel: CatalogViewModel = viewModel()
) {
    var searchQuery by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        viewModel.search("drag")
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.monster_catalog)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back)
                        )
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
            OutlinedTextField(
                value = searchQuery,
                onValueChange = {
                    searchQuery = it
                    viewModel.search(it)
                },
                label = { Text(stringResource(R.string.search_monster_placeholder)) },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            Spacer(modifier = Modifier.height(16.dp))

            when {
                viewModel.isLoading -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }

                viewModel.error != null -> {
                    Text(
                        text = "${stringResource(R.string.error)}: ${viewModel.error}",
                        color = MaterialTheme.colorScheme.error
                    )
                }

                else -> {
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
}

@Composable
fun MonsterCard(monster: MonsterResult) {
    val levelLabel = stringResource(R.string.level)
    val treasuresLabel = stringResource(R.string.treasures_format, monster.treasures)
    val levelsLabel = stringResource(R.string.levels_format, monster.levels)
    val badStuffLabel = stringResource(R.string.combat_bad_stuff_label)

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
                    text = "$levelLabel ${monster.level}",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            Row {
                Text(treasuresLabel, modifier = Modifier.weight(1f))
                Text(levelsLabel, modifier = Modifier.weight(1f))
            }
            if (monster.bad_stuff.isNotEmpty()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "$badStuffLabel: ${monster.bad_stuff}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}
