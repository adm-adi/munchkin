package com.munchkin.app.ui.screens

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SearchOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.munchkin.app.MunchkinApp
import com.munchkin.app.R
import com.munchkin.app.network.CatalogMonster
import com.munchkin.app.ui.components.GlassCard
import com.munchkin.app.ui.components.GlassTopAppBar
import com.munchkin.app.ui.theme.*
import com.munchkin.app.viewmodel.CatalogViewModel

private data class ExpansionOption(
    val code: String,
    @StringRes val labelRes: Int
)

private val CATALOG_EXPANSIONS = listOf(
    ExpansionOption("base", R.string.expansion_base),
    ExpansionOption("hacha_descomunal", R.string.expansion_hacha_descomunal),
    ExpansionOption("pifias_clericales", R.string.expansion_pifias_clericales),
    ExpansionOption("anda_que_ojo", R.string.expansion_anda_que_ojo),
    ExpansionOption("exploradores", R.string.expansion_exploradores),
    ExpansionOption("mazmorras", R.string.expansion_mazmorras),
    ExpansionOption("trampas", R.string.expansion_trampas),
    ExpansionOption("centauros", R.string.expansion_centauros),
    ExpansionOption("jurasico", R.string.expansion_jurasico),
    ExpansionOption("extra", R.string.expansion_extra)
)

@Composable
private fun expansionDisplayLabel(code: String): String {
    val match = CATALOG_EXPANSIONS.find { it.code == code }
    return if (match != null) {
        stringResource(match.labelRes)
    } else if (code.equals("custom", ignoreCase = true)) {
        stringResource(R.string.expansion_extra)
    } else {
        code
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MonsterCatalogScreen(
    onBack: () -> Unit,
    canCreateMonster: Boolean = false,
    viewModel: CatalogViewModel = viewModel(factory = MunchkinApp.container.viewModelFactory)
) {
    var searchQuery by remember { mutableStateOf("") }
    var selectedExpansion by remember { mutableStateOf<String?>(null) }
    var undeadOnly by remember { mutableStateOf(false) }
    var levelBand by remember { mutableStateOf<String?>(null) } // "low" | "mid" | "high" | null
    var showCreateDialog by remember { mutableStateOf(false) }
    var editingMonster by remember { mutableStateOf<CatalogMonster?>(null) }
    var deletingMonster by remember { mutableStateOf<CatalogMonster?>(null) }

    fun levelBounds(band: String?): Pair<Int?, Int?> = when (band) {
        "low" -> 1 to 5
        "mid" -> 6 to 12
        "high" -> 13 to 20
        else -> null to null
    }

    fun triggerSearch(
        q: String = searchQuery,
        exp: String? = selectedExpansion,
        undead: Boolean = undeadOnly,
        band: String? = levelBand,
        debounceMs: Long = 0L
    ) {
        val (minLv, maxLv) = levelBounds(band)
        viewModel.search(
            query = q,
            expansion = exp,
            isUndead = if (undead) true else null,
            minLevel = minLv,
            maxLevel = maxLv,
            resetPage = true,
            debounceMs = debounceMs
        )
    }

    val searchResults by viewModel.searchResults.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val isLoadingMore by viewModel.isLoadingMore.collectAsState()
    val hasMore by viewModel.hasMore.collectAsState()
    val error by viewModel.error.collectAsState()
    val statusMessage by viewModel.statusMessage.collectAsState()
    val canCreate = canCreateMonster || viewModel.canCreateMonster

    val listState = rememberLazyListState()
    val shouldLoadMore by remember {
        derivedStateOf {
            val totalItems = listState.layoutInfo.totalItemsCount
            val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            totalItems > 0 && lastVisible >= totalItems - 4
        }
    }

    LaunchedEffect(Unit) {
        triggerSearch(q = "", exp = null, undead = false, band = null, debounceMs = 0L)
    }

    LaunchedEffect(shouldLoadMore, hasMore, isLoading, isLoadingMore) {
        if (shouldLoadMore && hasMore && !isLoading && !isLoadingMore) {
            viewModel.loadMore()
        }
    }

    if (showCreateDialog && canCreate) {
        CreateCatalogMonsterDialog(
            initialMonster = null,
            error = error,
            onDismiss = {
                viewModel.clearError()
                showCreateDialog = false
            },
            onSubmit = { name, level, modifier, isUndead, treasures, levelsReward, badStuff, expansion, onDone ->
                viewModel.createMonster(
                    name = name,
                    level = level,
                    modifier = modifier,
                    isUndead = isUndead,
                    treasures = treasures,
                    levels = levelsReward,
                    badStuff = badStuff,
                    expansion = expansion,
                    onCompleted = { result ->
                        onDone()
                        if (result.isSuccess) {
                            showCreateDialog = false
                        }
                    }
                )
            }
        )
    }

    editingMonster?.let { target ->
        CreateCatalogMonsterDialog(
            initialMonster = target,
            error = error,
            onDismiss = {
                viewModel.clearError()
                editingMonster = null
            },
            onSubmit = { name, level, modifier, isUndead, treasures, levelsReward, badStuff, expansion, onDone ->
                viewModel.updateMonster(
                    monsterId = target.id,
                    name = name,
                    level = level,
                    modifier = modifier,
                    isUndead = isUndead,
                    treasures = treasures,
                    levels = levelsReward,
                    badStuff = badStuff,
                    expansion = expansion,
                    onCompleted = { result ->
                        onDone()
                        if (result.isSuccess) {
                            editingMonster = null
                        }
                    }
                )
            }
        )
    }

    deletingMonster?.let { target ->
        AlertDialog(
            onDismissRequest = { deletingMonster = null },
            containerColor = NeonSurface,
            title = {
                Text(
                    text = stringResource(R.string.catalog_delete_monster),
                    color = NeonGray100,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Text(
                    text = stringResource(R.string.catalog_delete_confirm, target.name),
                    color = NeonGray300
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val id = target.id
                        deletingMonster = null
                        viewModel.deleteMonster(id)
                    },
                    modifier = Modifier.heightIn(min = 48.dp)
                ) {
                    Text(stringResource(R.string.delete_action), color = NeonError, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { deletingMonster = null },
                    modifier = Modifier.heightIn(min = 48.dp)
                ) {
                    Text(stringResource(R.string.cancel), color = NeonGray200)
                }
            }
        )
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(NeonBackground, NeonSurface.copy(alpha = 0.5f), NeonBackground)
                )
            )
    ) {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            containerColor = Color.Transparent,
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            floatingActionButton = {
                if (canCreate) {
                    FloatingActionButton(
                        onClick = {
                            viewModel.clearError()
                            showCreateDialog = true
                        },
                        modifier = Modifier.navigationBarsPadding(),
                        containerColor = NeonPrimary,
                        contentColor = NeonWhite
                    ) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = stringResource(R.string.create_new)
                        )
                    }
                }
            }
        ) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .statusBarsPadding()
                    .navigationBarsPadding()
            ) {
                GlassTopAppBar(
                    title = stringResource(R.string.monster_catalog),
                    navigationIcon = Icons.AutoMirrored.Filled.ArrowBack,
                    onNavigationClick = onBack
                )

                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 16.dp)
                ) {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { next ->
                            searchQuery = next
                            triggerSearch(q = next, debounceMs = 350L)
                        },
                        label = { Text(stringResource(R.string.search_monster_placeholder)) },
                        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                        trailingIcon = if (searchQuery.isNotEmpty()) {
                            {
                                IconButton(
                                    onClick = {
                                        searchQuery = ""
                                        triggerSearch(q = "", debounceMs = 0L)
                                    }
                                ) {
                                    Icon(
                                        Icons.Default.Close,
                                        contentDescription = stringResource(R.string.close)
                                    )
                                }
                            }
                        } else null,
                        supportingText = if (searchQuery.trim().length == 1) {
                            {
                                Text(
                                    text = stringResource(R.string.catalog_search_min_chars_hint),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        } else null,
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = NeonGray100,
                            unfocusedTextColor = NeonGray100,
                            focusedBorderColor = NeonPrimary,
                            unfocusedBorderColor = GlassBorder,
                            focusedLabelColor = NeonPrimary,
                            unfocusedLabelColor = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    // Expansion filter chips
                    LazyRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        item(key = "exp_all") {
                            val selected = selectedExpansion == null
                            FilterChip(
                                selected = selected,
                                onClick = {
                                    selectedExpansion = null
                                    triggerSearch(exp = null, debounceMs = 0L)
                                },
                                modifier = Modifier.heightIn(min = 48.dp),
                                label = { Text(stringResource(R.string.catalog_all_expansions)) },
                                leadingIcon = if (selected) {
                                    {
                                        Icon(
                                            Icons.Default.Check,
                                            contentDescription = null,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                } else null
                            )
                        }
                        items(CATALOG_EXPANSIONS, key = { it.code }) { exp ->
                            val selected = selectedExpansion == exp.code
                            FilterChip(
                                selected = selected,
                                onClick = {
                                    val next = if (selected) null else exp.code
                                    selectedExpansion = next
                                    triggerSearch(exp = next, debounceMs = 0L)
                                },
                                modifier = Modifier.heightIn(min = 48.dp),
                                label = { Text(stringResource(exp.labelRes)) },
                                leadingIcon = if (selected) {
                                    {
                                        Icon(
                                            Icons.Default.Check,
                                            contentDescription = null,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                } else null
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    // Undead & level-band filter chips
                    LazyRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        item(key = "filter_undead") {
                            FilterChip(
                                selected = undeadOnly,
                                onClick = {
                                    val next = !undeadOnly
                                    undeadOnly = next
                                    triggerSearch(undead = next, debounceMs = 0L)
                                },
                                modifier = Modifier.heightIn(min = 48.dp),
                                label = { Text("💀 ${stringResource(R.string.catalog_filter_undead)}") }
                            )
                        }
                        val bands = listOf(
                            "low" to R.string.catalog_level_low,
                            "mid" to R.string.catalog_level_mid,
                            "high" to R.string.catalog_level_high
                        )
                        items(bands, key = { it.first }) { (code, labelRes) ->
                            val selected = levelBand == code
                            FilterChip(
                                selected = selected,
                                onClick = {
                                    val next = if (selected) null else code
                                    levelBand = next
                                    triggerSearch(band = next, debounceMs = 0L)
                                },
                                modifier = Modifier.heightIn(min = 48.dp),
                                label = { Text(stringResource(labelRes)) }
                            )
                        }
                    }

                    // Status confirmation banner
                    if (statusMessage != null) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(NeonSuccess.copy(alpha = 0.14f))
                                .border(1.dp, NeonSuccess.copy(alpha = 0.4f), RoundedCornerShape(12.dp))
                                .padding(start = 14.dp, top = 6.dp, bottom = 6.dp, end = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = statusMessage ?: "",
                                color = NeonSuccess,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.weight(1f)
                            )
                            IconButton(onClick = { viewModel.clearStatusMessage() }) {
                                Icon(
                                    Icons.Default.Close,
                                    contentDescription = stringResource(R.string.close),
                                    tint = NeonGray200
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    when {
                        isLoading && searchResults.isEmpty() -> {
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center
                            ) {
                                CircularProgressIndicator(color = NeonPrimary)
                            }
                        }

                        error != null && searchResults.isEmpty() -> {
                            GlassCard(
                                modifier = Modifier.fillMaxWidth(),
                                borderColor = NeonError.copy(alpha = 0.45f)
                            ) {
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Icon(
                                        Icons.Default.ErrorOutline,
                                        contentDescription = null,
                                        tint = NeonError,
                                        modifier = Modifier.size(36.dp)
                                    )
                                    Spacer(modifier = Modifier.height(10.dp))
                                    Text(
                                        text = error ?: stringResource(R.string.error),
                                        color = NeonGray100,
                                        style = MaterialTheme.typography.bodyMedium,
                                        textAlign = TextAlign.Center
                                    )
                                    Spacer(modifier = Modifier.height(14.dp))
                                    OutlinedButton(
                                        onClick = {
                                            viewModel.clearError()
                                            viewModel.search(
                                                query = searchQuery,
                                                expansion = selectedExpansion,
                                                resetPage = true,
                                                debounceMs = 0L
                                            )
                                        },
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .heightIn(min = 48.dp)
                                    ) {
                                        Text(stringResource(R.string.reconnect_retry))
                                    }
                                }
                            }
                        }

                        searchResults.isEmpty() && !isLoading -> {
                            GlassCard(modifier = Modifier.fillMaxWidth()) {
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Icon(
                                        Icons.Default.SearchOff,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(40.dp)
                                    )
                                    Spacer(modifier = Modifier.height(10.dp))
                                    Text(
                                        text = stringResource(R.string.catalog_empty_title),
                                        style = MaterialTheme.typography.titleMedium,
                                        color = NeonGray100,
                                        textAlign = TextAlign.Center
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = stringResource(R.string.catalog_empty_subtitle),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        textAlign = TextAlign.Center
                                    )
                                }
                            }
                        }

                        else -> {
                            if (error != null) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(bottom = 8.dp)
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(NeonError.copy(alpha = 0.12f))
                                        .border(1.dp, NeonError.copy(alpha = 0.4f), RoundedCornerShape(12.dp))
                                        .padding(start = 12.dp, top = 6.dp, bottom = 6.dp, end = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = error ?: "",
                                        color = NeonError,
                                        style = MaterialTheme.typography.bodySmall,
                                        modifier = Modifier.weight(1f)
                                    )
                                    TextButton(
                                        onClick = {
                                            viewModel.clearError()
                                            viewModel.search(
                                                query = searchQuery,
                                                expansion = selectedExpansion,
                                                resetPage = true,
                                                debounceMs = 0L
                                            )
                                        }
                                    ) {
                                        Text(stringResource(R.string.reconnect_retry))
                                    }
                                }
                            }

                            LazyColumn(
                                state = listState,
                                verticalArrangement = Arrangement.spacedBy(10.dp),
                                contentPadding = PaddingValues(bottom = 92.dp)
                            ) {
                                itemsIndexed(
                                    items = searchResults,
                                    key = { index, monster ->
                                        monster.id.ifEmpty { "${monster.name}_${monster.expansion}_$index" }
                                    }
                                ) { _, monster ->
                                    MonsterCard(
                                        monster = monster,
                                        onEdit = if (monster.canEdit) {
                                            {
                                                viewModel.clearError()
                                                editingMonster = monster
                                            }
                                        } else null,
                                        onDelete = if (monster.canEdit) {
                                            { deletingMonster = monster }
                                        } else null
                                    )
                                }

                                if (isLoadingMore) {
                                    item(key = "catalog_loading_more") {
                                        Box(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(16.dp),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            CircularProgressIndicator(
                                                modifier = Modifier.size(28.dp),
                                                color = NeonPrimary
                                            )
                                        }
                                    }
                                } else if (hasMore) {
                                    item(key = "catalog_load_more") {
                                        OutlinedButton(
                                            onClick = { viewModel.loadMore() },
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .heightIn(min = 48.dp)
                                        ) {
                                            Text(stringResource(R.string.catalog_load_more))
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun MonsterCard(
    monster: CatalogMonster,
    onEdit: (() -> Unit)? = null,
    onDelete: (() -> Unit)? = null
) {
    val levelLabel = stringResource(R.string.level)
    val treasuresLabel = stringResource(R.string.treasures_format, monster.treasures)
    val levelsLabel = stringResource(R.string.levels_format, monster.levels)
    val badStuffLabel = stringResource(R.string.combat_bad_stuff_label)
    val expansionText = expansionDisplayLabel(monster.expansion)

    GlassCard(modifier = Modifier.fillMaxWidth()) {
        Column {
            Row(
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = monster.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = NeonGray100,
                    modifier = Modifier.weight(1f)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "$levelLabel ${monster.level}" +
                        if (monster.modifier != 0) {
                            " (${if (monster.modifier > 0) "+" else ""}${monster.modifier})"
                        } else "",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = NeonSecondary
                )
            }

            Spacer(modifier = Modifier.height(6.dp))

            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = expansionText,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (monster.isUndead) {
                    Text(
                        text = "• ${stringResource(R.string.combat_is_undead)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = NeonPrimaryLight,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            Row(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = treasuresLabel,
                    style = MaterialTheme.typography.bodySmall,
                    color = NeonGray200,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = levelsLabel,
                    style = MaterialTheme.typography.bodySmall,
                    color = NeonGray200,
                    modifier = Modifier.weight(1f)
                )
            }

            if (monster.badStuff.isNotBlank()) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "$badStuffLabel: ${monster.badStuff}",
                    style = MaterialTheme.typography.bodySmall,
                    color = NeonError
                )
            }

            if (!monster.createdBy.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = stringResource(R.string.created_by, monster.createdBy),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (onEdit != null || onDelete != null) {
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (onEdit != null) {
                        TextButton(
                            onClick = onEdit,
                            modifier = Modifier.heightIn(min = 48.dp)
                        ) {
                            Icon(
                                Icons.Default.Edit,
                                contentDescription = null,
                                tint = NeonSecondary,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(stringResource(R.string.catalog_edit_monster), color = NeonSecondary)
                        }
                    }
                    if (onDelete != null) {
                        TextButton(
                            onClick = onDelete,
                            modifier = Modifier.heightIn(min = 48.dp)
                        ) {
                            Icon(
                                Icons.Default.Delete,
                                contentDescription = null,
                                tint = NeonError,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(stringResource(R.string.delete_action), color = NeonError)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CreateCatalogMonsterDialog(
    initialMonster: CatalogMonster? = null,
    error: String?,
    onDismiss: () -> Unit,
    onSubmit: (
        name: String,
        level: Int,
        modifier: Int,
        isUndead: Boolean,
        treasures: Int,
        levelsReward: Int,
        badStuff: String,
        expansion: String,
        onDone: () -> Unit
    ) -> Unit
) {
    val isEditing = initialMonster != null
    var name by remember(initialMonster?.id) { mutableStateOf(initialMonster?.name ?: "") }
    var level by remember(initialMonster?.id) { mutableIntStateOf(initialMonster?.level ?: 1) }
    var modifierValue by remember(initialMonster?.id) { mutableIntStateOf(initialMonster?.modifier ?: 0) }
    var isUndead by remember(initialMonster?.id) { mutableStateOf(initialMonster?.isUndead ?: false) }
    var treasures by remember(initialMonster?.id) { mutableIntStateOf(initialMonster?.treasures ?: 1) }
    var levelsReward by remember(initialMonster?.id) { mutableIntStateOf(initialMonster?.levels ?: 1) }
    var badStuff by remember(initialMonster?.id) { mutableStateOf(initialMonster?.badStuff ?: "") }
    var selectedExpansion by remember(initialMonster?.id) { mutableStateOf(initialMonster?.expansion ?: "base") }
    var isSubmitting by remember { mutableStateOf(false) }

    val trimmedName = name.trim()
    val nameTooLong = trimmedName.length > 80
    val badStuffTooLong = badStuff.length > 200
    val canSubmit = !isSubmitting &&
        trimmedName.isNotEmpty() &&
        !nameTooLong &&
        !badStuffTooLong

    AlertDialog(
        onDismissRequest = { if (!isSubmitting) onDismiss() },
        containerColor = NeonSurface,
        title = {
            Text(
                text = stringResource(if (isEditing) R.string.catalog_edit_monster else R.string.create_new),
                color = NeonGray100,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (error != null) {
                    Text(
                        text = error,
                        color = NeonError,
                        style = MaterialTheme.typography.bodySmall
                    )
                }

                OutlinedTextField(
                    value = name,
                    onValueChange = { if (it.length <= 80) name = it },
                    label = { Text(stringResource(R.string.monster_name)) },
                    singleLine = true,
                    isError = name.isNotEmpty() && trimmedName.isEmpty(),
                    modifier = Modifier.fillMaxWidth()
                )

                NumberStepperRow(
                    label = stringResource(R.string.monster_level),
                    value = level,
                    range = 1..20,
                    onValueChange = { level = it }
                )

                NumberStepperRow(
                    label = stringResource(R.string.modifier),
                    value = modifierValue,
                    range = -10..10,
                    onValueChange = { modifierValue = it },
                    signed = true
                )

                NumberStepperRow(
                    label = stringResource(R.string.treasures_label),
                    value = treasures,
                    range = 0..10,
                    onValueChange = { treasures = it }
                )

                NumberStepperRow(
                    label = stringResource(R.string.levels_reward_label),
                    value = levelsReward,
                    range = 1..5,
                    onValueChange = { levelsReward = it }
                )

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = stringResource(R.string.combat_is_undead),
                        color = NeonGray100,
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Switch(
                        checked = isUndead,
                        onCheckedChange = { isUndead = it },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = NeonBackground,
                            checkedTrackColor = NeonSecondary
                        )
                    )
                }

                Text(
                    text = stringResource(R.string.catalog_expansion_label),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    items(CATALOG_EXPANSIONS, key = { it.code }) { exp ->
                        val selected = selectedExpansion == exp.code
                        FilterChip(
                            selected = selected,
                            onClick = { selectedExpansion = exp.code },
                            modifier = Modifier.heightIn(min = 48.dp),
                            label = { Text(stringResource(exp.labelRes)) }
                        )
                    }
                }

                OutlinedTextField(
                    value = badStuff,
                    onValueChange = { if (it.length <= 200) badStuff = it },
                    label = { Text(stringResource(R.string.combat_bad_stuff_label)) },
                    supportingText = {
                        Text(
                            text = "${badStuff.length}/200",
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    },
                    maxLines = 3,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (canSubmit) {
                        isSubmitting = true
                        onSubmit(
                            trimmedName,
                            level,
                            modifierValue,
                            isUndead,
                            treasures,
                            levelsReward,
                            badStuff.trim(),
                            selectedExpansion
                        ) {
                            isSubmitting = false
                        }
                    }
                },
                enabled = canSubmit,
                modifier = Modifier.heightIn(min = 48.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = NeonPrimary,
                    contentColor = NeonWhite
                )
            ) {
                if (isSubmitting) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = NeonWhite
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                }
                Text(stringResource(if (isEditing) R.string.catalog_save_changes else R.string.create_new_button))
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                enabled = !isSubmitting,
                modifier = Modifier.heightIn(min = 48.dp)
            ) {
                Text(stringResource(R.string.cancel), color = NeonGray200)
            }
        }
    )
}

@Composable
private fun NumberStepperRow(
    label: String,
    value: Int,
    range: IntRange,
    onValueChange: (Int) -> Unit,
    signed: Boolean = false
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = NeonGray100,
            modifier = Modifier.weight(1f)
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(
                onClick = { if (value > range.first) onValueChange(value - 1) },
                enabled = value > range.first
            ) {
                Icon(
                    imageVector = Icons.Default.Remove,
                    contentDescription = stringResource(R.string.decrease_value, label),
                    tint = if (value > range.first) NeonGray100 else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(
                text = if (signed && value > 0) "+$value" else value.toString(),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = NeonSecondary,
                modifier = Modifier.widthIn(min = 36.dp),
                textAlign = TextAlign.Center
            )
            IconButton(
                onClick = { if (value < range.last) onValueChange(value + 1) },
                enabled = value < range.last
            ) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = stringResource(R.string.increase_value, label),
                    tint = if (value < range.last) NeonGray100 else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
