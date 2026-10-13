package app.aftercheck.ui.games

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.cachedIn
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import androidx.paging.LoadState
import app.aftercheck.AppContainer
import app.aftercheck.R
import app.aftercheck.data.db.GameSummary
import app.aftercheck.ui.components.AppIcon
import app.aftercheck.ui.components.EmptyState
import app.aftercheck.ui.components.Format
import app.aftercheck.ui.components.ResultBadge
import app.aftercheck.ui.components.Skeleton
import app.aftercheck.ui.components.StatusPill
import app.aftercheck.ui.theme.AppTheme
import app.aftercheck.ui.theme.numeric
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.update

data class GameFilter(
    val query: String = "",
    val result: String = "",
    val color: String = "",
    val timeClass: String = "",
    val status: String = "",
    val sort: Int = 0,
) {
    val active: Boolean get() = result.isNotEmpty() || color.isNotEmpty() || timeClass.isNotEmpty() || status.isNotEmpty()
}

@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
class GamesViewModel(private val c: AppContainer) : ViewModel() {
    val filter = MutableStateFlow(GameFilter())

    val games = filter.debounce(150).flatMapLatest { f ->
        Pager(PagingConfig(pageSize = 40, prefetchDistance = 20, enablePlaceholders = false)) {
            c.db.games().library(f.query.trim().lowercase(), f.result, f.color, f.timeClass, f.status, f.sort)
        }.flow
    }.cachedIn(viewModelScope)

    fun update(transform: (GameFilter) -> GameFilter) = filter.update(transform)
}

private val SORTS = listOf("Newest first", "Oldest first", "Highest accuracy", "Lowest accuracy")

@Composable
fun GamesScreen(container: AppContainer, onOpenGame: (String) -> Unit) {
    val vm: GamesViewModel = viewModel { GamesViewModel(container) }
    val filter by vm.filter.collectAsStateWithLifecycle()
    val items = vm.games.collectAsLazyPagingItems()

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.widthIn(max = 720.dp).fillMaxSize().statusBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Games", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
                var sortOpen by remember { mutableStateOf(false) }
                Box {
                    IconButton(onClick = { sortOpen = true }) { AppIcon(R.drawable.ic_sort, "Sort: ${SORTS[filter.sort]}") }
                    DropdownMenu(expanded = sortOpen, onDismissRequest = { sortOpen = false }) {
                        SORTS.forEachIndexed { i, label ->
                            DropdownMenuItem(
                                text = { Text(label) },
                                onClick = { vm.update { it.copy(sort = i) }; sortOpen = false },
                                trailingIcon = if (i == filter.sort) ({ AppIcon(R.drawable.ic_done, null, size = 18.dp) }) else null,
                            )
                        }
                    }
                }
            }
            SearchField(filter.query, { q -> vm.update { it.copy(query = q) } }, Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
            FilterRow(filter, vm::update)
            Spacer(Modifier.height(4.dp))
            HorizontalDivider(color = AppTheme.colors.border)

            val refreshing = items.loadState.refresh is LoadState.Loading && items.itemCount == 0
            when {
                refreshing -> Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                    repeat(7) { RowSkeleton() }
                }
                items.itemCount == 0 -> EmptyState(
                    icon = if (filter.query.isNotEmpty() || filter.active) R.drawable.ic_search else R.drawable.ic_chess_knight,
                    title = if (filter.query.isNotEmpty() || filter.active) "No matching games" else "No games yet",
                    body = if (filter.query.isNotEmpty() || filter.active) "Try another search or clear the filters."
                    else "Games from your Chess.com archive appear here as they're imported.",
                )
                else -> LazyColumn(contentPadding = PaddingValues(bottom = 16.dp)) {
                    items(count = items.itemCount, key = items.itemKey { it.id }) { index ->
                        val g = items[index]
                        if (g == null) RowSkeleton(Modifier.padding(20.dp)) else GameRow(g) { onOpenGame(g.id) }
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchField(value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().height(46.dp).clip(RoundedCornerShape(23.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppIcon(R.drawable.ic_search, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, size = 20.dp)
        Spacer(Modifier.width(10.dp))
        Box(Modifier.weight(1f)) {
            if (value.isEmpty()) {
                Text("Search opponent or opening", style = MaterialTheme.typography.bodyMedium, color = AppTheme.colors.textMuted)
            }
            BasicTextField(
                value = value,
                onValueChange = onChange,
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (value.isNotEmpty()) {
            IconButton(onClick = { onChange("") }, modifier = Modifier.size(32.dp)) {
                AppIcon(R.drawable.ic_close, "Clear search", size = 18.dp, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

private data class Choice(val label: String, val value: String)

@Composable
private fun FilterRow(f: GameFilter, update: ((GameFilter) -> GameFilter) -> Unit) {
    LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            ChoiceChip("Result", f.result, listOf(Choice("Wins", "win"), Choice("Losses", "loss"), Choice("Draws", "draw"))) { v -> update { it.copy(result = v) } }
        }
        item {
            ChoiceChip("Time", f.timeClass, listOf(Choice("Bullet", "bullet"), Choice("Blitz", "blitz"), Choice("Rapid", "rapid"), Choice("Daily", "daily"))) { v ->
                update { it.copy(timeClass = v) }
            }
        }
        item {
            ChoiceChip("Colour", f.color, listOf(Choice("White", "w"), Choice("Black", "b"))) { v -> update { it.copy(color = v) } }
        }
        item {
            ChoiceChip("Status", f.status, listOf(Choice("Reviewed", "ready"), Choice("Waiting", "pending"), Choice("Failed", "failed"))) { v ->
                update { it.copy(status = v) }
            }
        }
    }
}

@Composable
private fun ChoiceChip(label: String, value: String, choices: List<Choice>, onSelect: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val selected = choices.firstOrNull { it.value == value }
    Box {
        FilterChip(
            selected = selected != null,
            onClick = { open = true },
            label = { Text(selected?.label ?: label) },
            trailingIcon = { AppIcon(R.drawable.ic_expand_more, null, size = 18.dp, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
            shape = RoundedCornerShape(10.dp),
            colors = FilterChipDefaults.filterChipColors(
                selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
            ),
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text("Any ${label.lowercase()}") }, onClick = { onSelect(""); open = false })
            choices.forEach { c ->
                DropdownMenuItem(
                    text = { Text(c.label) },
                    onClick = { onSelect(c.value); open = false },
                    trailingIcon = if (c.value == value) ({ AppIcon(R.drawable.ic_done, null, size = 18.dp) }) else null,
                )
            }
        }
    }
}

@Composable
fun GameRow(g: GameSummary, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 72.dp).clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ResultBadge(g.userResult, size = 36.dp)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ColorDot(g.userColor)
                Spacer(Modifier.width(6.dp))
                Text(g.opponent, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                g.opponentRating?.let {
                    Spacer(Modifier.width(6.dp))
                    Text("$it", style = MaterialTheme.typography.labelMedium.numeric, color = AppTheme.colors.textMuted)
                }
            }
            Text(
                g.openingName ?: "Unknown opening",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                "${Format.timeClass(g.timeClass)} ${Format.timeControl(g.timeControl)} · ${Format.date(g.endTime)}" +
                    (g.termination?.let { " · $it" } ?: ""),
                style = MaterialTheme.typography.labelSmall,
                color = AppTheme.colors.textMuted,
                maxLines = 1,
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(horizontalAlignment = Alignment.End) {
            if (g.status == "ready") {
                Text(Format.percent(g.userAccuracy), style = MaterialTheme.typography.titleMedium.numeric)
                Text("accuracy", style = MaterialTheme.typography.labelSmall, color = AppTheme.colors.textMuted)
            } else StatusPill(g.status, g.progress)
        }
    }
}

@Composable
private fun ColorDot(color: String?) {
    val fill = if (color == "b") AppTheme.colors.evalBlack else AppTheme.colors.evalWhite
    Box(
        Modifier.size(10.dp).clip(RoundedCornerShape(3.dp)).background(AppTheme.colors.border).padding(1.dp)
            .clip(RoundedCornerShape(2.dp)).background(fill),
    )
}

@Composable
private fun RowSkeleton(modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Skeleton(Modifier.size(36.dp))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Skeleton(Modifier.fillMaxWidth(0.5f).height(13.dp))
            Spacer(Modifier.height(6.dp))
            Skeleton(Modifier.fillMaxWidth(0.75f).height(10.dp))
        }
        Spacer(Modifier.width(14.dp))
        Skeleton(Modifier.width(40.dp).height(20.dp))
    }
}
