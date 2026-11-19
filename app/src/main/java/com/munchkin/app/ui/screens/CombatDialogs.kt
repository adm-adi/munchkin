package com.munchkin.app.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.munchkin.app.R
import com.munchkin.app.core.*
import com.munchkin.app.network.CatalogMonster
import com.munchkin.app.ui.components.labelRes
import com.munchkin.app.ui.theme.*
import kotlinx.coroutines.delay

@Composable
internal fun AddTempBonusDialog(
    onDismiss: () -> Unit,
    onConfirm: (target: BonusTarget, label: String, amount: Int) -> Unit
) {
    val defaultLabel = stringResource(R.string.bonus_default_label)
    var target by remember { mutableStateOf(BonusTarget.HEROES) }
    var label by remember { mutableStateOf("") }
    var amountText by remember { mutableStateOf("2") }
    val parsedAmount = (amountText.toIntOrNull() ?: 0)
        .coerceIn(GameEngine.TEMP_COMBAT_BONUS_MIN, GameEngine.TEMP_COMBAT_BONUS_MAX)

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = NeonSurface,
        title = { Text(stringResource(R.string.add_bonus), color = NeonGray100) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .imePadding(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = stringResource(R.string.applies_to),
                    style = MaterialTheme.typography.labelLarge,
                    color = NeonGray300
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val isHeroes = target == BonusTarget.HEROES
                    OutlinedButton(
                        onClick = { target = BonusTarget.HEROES },
                        modifier = Modifier
                            .weight(1f)
                            .sizeIn(minHeight = 48.dp),
                        border = BorderStroke(
                            1.dp,
                            if (isHeroes) HeroGreen else GlassBorderDim
                        ),
                        colors = ButtonDefaults.outlinedButtonColors(
                            containerColor = if (isHeroes) HeroGreen.copy(alpha = 0.16f) else NeonSurfaceVariant,
                            contentColor = if (isHeroes) HeroGreen else NeonGray300
                        )
                    ) {
                        Text(stringResource(R.string.heroes), fontWeight = FontWeight.Bold)
                    }
                    OutlinedButton(
                        onClick = { target = BonusTarget.MONSTER },
                        modifier = Modifier
                            .weight(1f)
                            .sizeIn(minHeight = 48.dp),
                        border = BorderStroke(
                            1.dp,
                            if (!isHeroes) MonsterRed else GlassBorderDim
                        ),
                        colors = ButtonDefaults.outlinedButtonColors(
                            containerColor = if (!isHeroes) MonsterRed.copy(alpha = 0.16f) else NeonSurfaceVariant,
                            contentColor = if (!isHeroes) MonsterRed else NeonGray300
                        )
                    ) {
                        Text(stringResource(R.string.monsters), fontWeight = FontWeight.Bold)
                    }
                }

                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it.take(40) },
                    label = { Text(stringResource(R.string.bonus_label_hint)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )

                OutlinedTextField(
                    value = amountText,
                    onValueChange = { v ->
                        val sanitized = if (v.startsWith("-")) {
                            "-" + v.drop(1).filter { it.isDigit() }.take(3)
                        } else {
                            v.filter { it.isDigit() }.take(3)
                        }
                        val n = sanitized.toIntOrNull()
                        amountText = if (n != null) {
                            n.coerceIn(GameEngine.TEMP_COMBAT_BONUS_MIN, GameEngine.TEMP_COMBAT_BONUS_MAX).toString()
                        } else {
                            sanitized
                        }
                    },
                    label = { Text(stringResource(R.string.bonus_amount_label)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    listOf(-5, -1, 1, 5).forEach { delta ->
                        OutlinedButton(
                            onClick = {
                                val next = (parsedAmount + delta)
                                    .coerceIn(GameEngine.TEMP_COMBAT_BONUS_MIN, GameEngine.TEMP_COMBAT_BONUS_MAX)
                                amountText = next.toString()
                            },
                            modifier = Modifier
                                .weight(1f)
                                .sizeIn(minWidth = 48.dp, minHeight = 48.dp),
                            contentPadding = PaddingValues(0.dp),
                            border = BorderStroke(1.dp, GlassBorder),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = NeonGray100)
                        ) {
                            Text(if (delta > 0) "+$delta" else "$delta")
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onConfirm(
                        target,
                        label.trim().ifEmpty { defaultLabel },
                        parsedAmount
                    )
                },
                enabled = parsedAmount != 0,
                modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = NeonPrimary,
                    contentColor = NeonWhite
                )
            ) {
                Text(stringResource(R.string.add_bonus))
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)
            ) {
                Text(stringResource(R.string.cancel), color = NeonGray300)
            }
        }
    )
}

@Composable
fun MonsterSearchDialog(
    searchResults: List<CatalogMonster>,
    onSearch: (String) -> Unit,
    onDismiss: () -> Unit,
    onSelectMonster: (CatalogMonster) -> Unit,
    onCreateNew: (name: String, level: Int, modifier: Int, isUndead: Boolean, treasures: Int, levels: Int, badStuff: String) -> Unit
) {
    var query by remember { mutableStateOf("") }
    var level by remember { mutableStateOf("1") }
    var modifier by remember { mutableStateOf("0") }
    var treasures by remember { mutableStateOf("1") }
    var levels by remember { mutableStateOf("1") }
    var badStuff by remember { mutableStateOf("") }
    var isUndead by remember { mutableStateOf(false) }

    LaunchedEffect(query) {
        if (query.length >= 2) {
            delay(500)
            onSearch(query)
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = NeonSurface,
        title = { Text(stringResource(R.string.add_monster), color = NeonGray100) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .imePadding(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text(stringResource(R.string.search_monster_hint)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )

                if (query.isNotEmpty() && searchResults.isNotEmpty()) {
                    Text(
                        stringResource(R.string.results_label),
                        style = MaterialTheme.typography.labelSmall,
                        color = NeonGray300
                    )
                    LazyColumn(
                        modifier = Modifier.heightIn(max = 150.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        items(searchResults, key = { it.id }) { monster ->
                            Surface(
                                onClick = { onSelectMonster(monster) },
                                color = NeonSurfaceVariant,
                                shape = RoundedCornerShape(10.dp),
                                border = BorderStroke(1.dp, GlassBorderDim),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .sizeIn(minHeight = 48.dp)
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 12.dp, vertical = 8.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(monster.name, fontWeight = FontWeight.Bold, color = NeonGray100)
                                        Text(
                                            "💰 ${monster.treasures} • ⭐ ${monster.levels}" +
                                                if (monster.conditionalModifiers.isNotEmpty()) " • ⚡" else "",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = NeonGray300
                                        )
                                    }
                                    Text(
                                        "${stringResource(R.string.level)} ${monster.level}",
                                        color = NeonSecondary,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }
                    }
                }

                HorizontalDivider(color = GlassBorderDim)
                Text(
                    stringResource(R.string.or_create_new),
                    style = MaterialTheme.typography.labelSmall,
                    color = NeonGray300
                )

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = level,
                        onValueChange = { v ->
                            val digits = v.filter { c -> c.isDigit() }.take(2)
                            val n = digits.toIntOrNull()
                            level = if (n != null) n.coerceIn(1, 20).toString() else digits
                        },
                        label = { Text(stringResource(R.string.level)) },
                        modifier = Modifier.weight(1f),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = modifier,
                        onValueChange = { v ->
                            val sanitized = if (v.startsWith("-")) "-" + v.drop(1).filter { c -> c.isDigit() }.take(2)
                            else v.filter { c -> c.isDigit() }.take(2)
                            val n = sanitized.toIntOrNull()
                            modifier = if (n != null) n.coerceIn(-10, 10).toString() else sanitized
                        },
                        label = { Text(stringResource(R.string.modifier)) },
                        modifier = Modifier.weight(1f),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = treasures,
                        onValueChange = { v ->
                            val digits = v.filter { c -> c.isDigit() }.take(2)
                            val n = digits.toIntOrNull()
                            treasures = if (n != null) n.coerceIn(0, 10).toString() else digits
                        },
                        label = { Text(stringResource(R.string.treasures_label)) },
                        modifier = Modifier.weight(1f),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = levels,
                        onValueChange = { v ->
                            val digits = v.filter { c -> c.isDigit() }.take(1)
                            val n = digits.toIntOrNull()
                            levels = if (n != null) n.coerceIn(1, 5).toString() else digits
                        },
                        label = { Text(stringResource(R.string.levels_reward_label)) },
                        modifier = Modifier.weight(1f),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true
                    )
                }
                OutlinedTextField(
                    value = badStuff,
                    onValueChange = { badStuff = it.take(200) },
                    label = { Text(stringResource(R.string.combat_bad_stuff_label)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier
                        .fillMaxWidth()
                        .sizeIn(minHeight = 48.dp)
                        .clickable(role = Role.Switch) { isUndead = !isUndead }
                ) {
                    Text(
                        stringResource(R.string.combat_is_undead),
                        style = MaterialTheme.typography.bodyMedium,
                        color = NeonGray100
                    )
                    Switch(checked = isUndead, onCheckedChange = null)
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (query.isNotBlank()) {
                        onCreateNew(
                            query.trim(),
                            (level.toIntOrNull() ?: 1).coerceIn(1, 20),
                            (modifier.toIntOrNull() ?: 0).coerceIn(-10, 10),
                            isUndead,
                            (treasures.toIntOrNull() ?: 1).coerceIn(0, 10),
                            (levels.toIntOrNull() ?: 1).coerceIn(1, 5),
                            badStuff.trim()
                        )
                    }
                },
                enabled = query.isNotBlank(),
                modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = NeonPrimary,
                    contentColor = NeonWhite
                )
            ) {
                Text(stringResource(R.string.create_new_button))
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)
            ) {
                Text(stringResource(R.string.cancel), color = NeonGray300)
            }
        }
    )
}

@Composable
internal fun CombatSideSection(
    title: String,
    power: Int,
    color: Color,
    content: @Composable ColumnScope.() -> Unit
) {
    Surface(
        color = color.copy(alpha = 0.08f),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, color.copy(alpha = 0.35f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = NeonGray100
                )
                Text(
                    text = "${stringResource(R.string.power)}: $power",
                    style = MaterialTheme.typography.headlineSmall,
                    color = color,
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            content()
        }
    }
}

/**
 * 1-tap Munchkin card presets (Ancient +10, Enraged +5, Baby -5, Potions +2/+3/+5).
 */
@Composable
internal fun CombatPresetsBar(
    onAddTempBonus: (BonusTarget, String, Int) -> Unit
) {
    val presets = listOf(
        Triple(BonusTarget.MONSTER, stringResource(R.string.preset_ancient), 10),
        Triple(BonusTarget.MONSTER, stringResource(R.string.preset_enraged), 5),
        Triple(BonusTarget.MONSTER, stringResource(R.string.preset_baby), -5),
        Triple(BonusTarget.HEROES, stringResource(R.string.preset_potion_2), 2),
        Triple(BonusTarget.HEROES, stringResource(R.string.preset_potion_3), 3),
        Triple(BonusTarget.HEROES, stringResource(R.string.preset_potion_5), 5)
    )

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = stringResource(R.string.combat_presets_title),
            style = MaterialTheme.typography.labelLarge,
            color = NeonGray300,
            fontWeight = FontWeight.SemiBold
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            presets.forEach { (target, label, amount) ->
                val accent = if (target == BonusTarget.HEROES) HeroGreen else MonsterRed
                OutlinedButton(
                    onClick = { onAddTempBonus(target, label, amount) },
                    modifier = Modifier.sizeIn(minHeight = 48.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                    border = BorderStroke(1.dp, accent.copy(alpha = 0.55f)),
                    colors = ButtonDefaults.outlinedButtonColors(
                        containerColor = NeonSurfaceVariant,
                        contentColor = NeonGray100
                    )
                ) {
                    Text(label, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

/**
 * Helper treasure deal row showing agreed helper treasure share (0..max).
 */
@Composable
internal fun HelperTreasureDealRow(
    currentShare: Int?,
    totalTreasures: Int,
    canEdit: Boolean,
    onChangeShare: (Int?) -> Unit
) {
    val effectiveTotal = totalTreasures.coerceAtLeast(1)
    val share = currentShare ?: 0

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = if (currentShare != null) {
                stringResource(R.string.combat_helper_treasures_deal, currentShare, totalTreasures)
            } else {
                stringResource(R.string.combat_helper_treasures)
            },
            style = MaterialTheme.typography.bodySmall,
            color = NeonSecondary,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f)
        )
        if (canEdit) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                OutlinedButton(
                    onClick = { onChangeShare((share - 1).coerceAtLeast(0)) },
                    enabled = share > 0,
                    modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp),
                    contentPadding = PaddingValues(0.dp),
                    border = BorderStroke(1.dp, GlassBorder),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = NeonGray100)
                ) {
                    Text("−", fontWeight = FontWeight.Bold)
                }
                Text(
                    text = "$share",
                    style = MaterialTheme.typography.titleMedium,
                    color = NeonSecondary,
                    fontWeight = FontWeight.Bold
                )
                OutlinedButton(
                    onClick = { onChangeShare((share + 1).coerceAtMost(effectiveTotal.coerceAtMost(10))) },
                    enabled = share < effectiveTotal.coerceAtMost(10),
                    modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp),
                    contentPadding = PaddingValues(0.dp),
                    border = BorderStroke(1.dp, GlassBorder),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = NeonGray100)
                ) {
                    Text("+", fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

/**
 * Lists the class and race abilities in play for the combat participants.
 */
@Composable
fun AbilityReminders(participants: List<PlayerState>) {
    val entries = participants.flatMap { player ->
        Abilities.forPlayer(player).map { player to it }
    }
    if (entries.isEmpty()) return

    var expanded by remember { mutableStateOf(false) }

    Surface(
        onClick = { expanded = !expanded },
        color = NeonSurfaceVariant,
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, GlassBorderDim),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .sizeIn(minHeight = 48.dp)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.AutoAwesome,
                    contentDescription = null,
                    tint = NeonSecondary,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.abilities_title),
                    style = MaterialTheme.typography.titleSmall,
                    color = NeonGray100,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
                Icon(
                    if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = null,
                    tint = NeonGray400
                )
            }

            if (!expanded) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.abilities_collapsed_hint, entries.size),
                    style = MaterialTheme.typography.bodySmall,
                    color = NeonGray400
                )
                return@Column
            }

            Spacer(modifier = Modifier.height(10.dp))

            participants.forEach { player ->
                val playerAbilities = Abilities.forPlayer(player)
                if (playerAbilities.isEmpty()) return@forEach

                Text(
                    text = player.name,
                    style = MaterialTheme.typography.labelMedium,
                    color = NeonSecondary,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(4.dp))

                playerAbilities.forEach { ability ->
                    val isAuto = ability.kind == AbilityKind.AUTOMATIC
                    Row(modifier = Modifier.padding(vertical = 3.dp)) {
                        Text(
                            text = if (isAuto) "✓" else "•",
                            color = if (isAuto) NeonSuccess else NeonWarning,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.width(18.dp)
                        )
                        Column {
                            Text(
                                text = stringResource(ability.sourceKey.labelRes()) + ": " +
                                    stringResource(ability.descriptionKey.labelRes()),
                                style = MaterialTheme.typography.bodySmall,
                                color = NeonGray300
                            )
                            Text(
                                text = stringResource(
                                    if (isAuto) R.string.ability_automatic
                                    else R.string.ability_manual
                                ),
                                style = MaterialTheme.typography.labelSmall,
                                color = if (isAuto) NeonSuccess else NeonWarning
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
            }
        }
    }
}
