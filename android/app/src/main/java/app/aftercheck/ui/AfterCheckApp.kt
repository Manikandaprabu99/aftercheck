package app.aftercheck.ui

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Analytics
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Extension
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import app.aftercheck.analysis.PracticePuzzleFactory
import app.aftercheck.data.AfterCheckDatabase
import app.aftercheck.data.ChessComSyncService
import app.aftercheck.data.ChessGameEntity
import app.aftercheck.worker.SyncScheduler
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val Night = Color(0xFF101512)
private val Panel = Color(0xFF1A211C)
private val PanelRaised = Color(0xFF222C24)
private val Lime = Color(0xFF9BE36D)
private val Muted = Color(0xFFA6B2A7)
private val Paper = Color(0xFFEAF1E9)

@Composable
fun AfterCheckApp(database: AfterCheckDatabase) {
    val context = LocalContext.current
    val dao = remember(database) { database.gameDao() }
    val games by dao.observeRecent(250).collectAsState(initial = emptyList())
    val gameCount by dao.observeCount().collectAsState(initial = 0)
    val reviewedCount by dao.observeReviewedCount().collectAsState(initial = 0)
    var selectedTab by remember { mutableIntStateOf(0) }
    var selectedGame by remember { mutableStateOf<ChessGameEntity?>(null) }
    BackHandler(enabled = selectedGame != null) { selectedGame = null }

    Scaffold(
        containerColor = Night,
        bottomBar = {
            if (selectedGame == null) NavigationBar(containerColor = Panel, contentColor = Paper) {
                val labels = listOf("Home", "Games", "Insights", "Practice", "Settings")
                val icons = listOf(
                    Icons.Rounded.Home,
                    Icons.Rounded.History,
                    Icons.Rounded.Analytics,
                    Icons.Rounded.Extension,
                    Icons.Rounded.Settings
                )
                labels.forEachIndexed { index, label ->
                    NavigationBarItem(
                        selected = selectedTab == index,
                        onClick = { selectedTab = index },
                        icon = { Icon(icons[index], contentDescription = label) },
                        label = { Text(label, fontSize = 10.sp) }
                    )
                }
            }
        }
    ) { padding ->
        val openGame: (ChessGameEntity) -> Unit = { selectedGame = it }
        if (selectedGame != null) {
            GameReviewScreen(
                database = database,
                game = selectedGame!!,
                onBack = { selectedGame = null },
                modifier = Modifier.padding(padding)
            )
        } else when (selectedTab) {
            0 -> HomeScreen(
                context = context,
                games = games,
                gameCount = gameCount,
                reviewedCount = reviewedCount,
                onOpenSettings = { selectedTab = 4 },
                onGameClick = openGame,
                modifier = Modifier.padding(padding)
            )
            1 -> GamesScreen(games = games, onGameClick = openGame, modifier = Modifier.padding(padding))
            2 -> InsightsScreen(games = games, modifier = Modifier.padding(padding))
            3 -> PracticeScreen(database = database, modifier = Modifier.padding(padding))
            else -> SettingsScreen(context = context, modifier = Modifier.padding(padding))
        }
    }
}

@Composable
private fun HomeScreen(
    context: Context,
    games: List<ChessGameEntity>,
    gameCount: Int,
    reviewedCount: Int,
    onOpenSettings: () -> Unit,
    onGameClick: (ChessGameEntity) -> Unit,
    modifier: Modifier = Modifier
) {
    val prefs = remember { context.getSharedPreferences(ChessComSyncService.PREFS, Context.MODE_PRIVATE) }
    val preferenceRevision = rememberPreferencesRevision(prefs)
    val username = remember(preferenceRevision) { prefs.getString(ChessComSyncService.KEY_USERNAME, null).orEmpty() }
    val status = remember(preferenceRevision) { prefs.getString(ChessComSyncService.KEY_STATUS, "NOT_CONFIGURED").orEmpty() }
    val lastSync = remember(preferenceRevision) { prefs.getLong(ChessComSyncService.KEY_LAST_SYNC, 0L) }
    val error = remember(preferenceRevision) { prefs.getString(ChessComSyncService.KEY_ERROR, null) }
    val analysisError = remember(preferenceRevision) { prefs.getString("last_analysis_error", null) }

    LazyColumn(
        modifier = modifier.fillMaxSize().background(Night),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 20.dp, top = 24.dp, end = 20.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.weight(1f)) {
                    Text("AFTERCHECK", color = Lime, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.2.sp)
                    Spacer(Modifier.height(7.dp))
                    Text("Every game teaches.", color = Paper, fontSize = 28.sp, fontWeight = FontWeight.Bold)
                    Text("Turn your games into progress.", color = Muted, fontSize = 13.sp)
                }
                Box(Modifier.size(52.dp).clip(CircleShape).background(Lime), contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.Bolt, contentDescription = "AfterCheck", tint = Night, modifier = Modifier.size(29.dp))
                }
            }
        }
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = PanelRaised),
                shape = RoundedCornerShape(24.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(19.dp), verticalArrangement = Arrangement.spacedBy(13.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("YOUR REVIEW WORKSPACE", color = Lime, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.4.sp)
                            Spacer(Modifier.height(7.dp))
                            Text("Find the move that changed everything.", color = Paper, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                        }
                        Icon(Icons.Rounded.ChevronRight, contentDescription = null, tint = Lime)
                    }
                    Text("Local-first analysis · Built for improvement", color = Muted, fontSize = 12.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                        Tag("Move-by-move")
                        Tag("Offline library")
                        Tag("No paid API")
                    }
                }
            }
        }
        item {
            Text("YOUR LIBRARY", color = Muted, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.6.sp)
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                MetricCard("Games imported", gameCount.toString(), Modifier.weight(1f))
                MetricCard("Reviews ready", reviewedCount.toString(), Modifier.weight(1f))
                MetricCard("Recent games", games.size.toString(), Modifier.weight(1f))
            }
        }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(22.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(17.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Sync, contentDescription = null, tint = Lime, modifier = Modifier.size(23.dp))
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(if (username.isBlank()) "Connect your game history" else "Chess.com · $username", color = Paper, fontWeight = FontWeight.SemiBold)
                            Text(syncLabel(status, lastSync), color = Muted, fontSize = 12.sp)
                        }
                    }
                    if (!error.isNullOrBlank()) Text(error, color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                    if (!analysisError.isNullOrBlank()) Text("Analysis: $analysisError", color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = {
                                if (username.isNotBlank()) SyncScheduler.syncNow(context) else onOpenSettings()
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Lime, contentColor = Night),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Rounded.Refresh, contentDescription = null, modifier = Modifier.size(17.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(if (username.isBlank()) "Set up sync" else "Sync now")
                        }
                        OutlinedButton(onClick = onOpenSettings, modifier = Modifier.weight(1f)) {
                            Icon(Icons.Rounded.Settings, contentDescription = null, modifier = Modifier.size(17.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Settings")
                        }
                    }
                }
            }
        }
        item { Text("RECENT GAMES", color = Muted, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.6.sp) }
        if (games.isEmpty()) {
            item { EmptyCard("Your next review starts here", "Add your Chess.com username in Settings. Publicly available completed games can then sync automatically.") }
        } else {
            items(games.take(4), key = { it.url }) { game -> GameCard(game) { onGameClick(game) } }
        }
    }
}

@Composable
private fun GamesScreen(games: List<ChessGameEntity>, onGameClick: (ChessGameEntity) -> Unit, modifier: Modifier = Modifier) {
    var searchQuery by remember { mutableStateOf("") }
    val filteredGames = remember(games, searchQuery) {
        val query = searchQuery.trim().lowercase(Locale.ROOT)
        if (query.isBlank()) games else games.filter { game ->
            listOf(game.white, game.black, game.opening, game.eco, game.result, game.timeControl)
                .any { it.lowercase(Locale.ROOT).contains(query) }
        }
    }
    Column(modifier.fillMaxSize().background(Night).padding(horizontal = 20.dp, vertical = 24.dp)) {
        Text("Games", color = Paper, fontSize = 29.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(5.dp))
        Text("Search your locally stored game history", color = Muted, fontSize = 13.sp)
        Spacer(Modifier.height(14.dp))
        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text("Opponent, opening, ECO or result") }
        )
        Spacer(Modifier.height(12.dp))
        Text("${filteredGames.size} games", color = Muted, fontSize = 11.sp)
        Spacer(Modifier.height(8.dp))
        if (filteredGames.isEmpty()) {
            EmptyCard(
                if (games.isEmpty()) "No games yet" else "No matching games",
                if (games.isEmpty()) "Connect a Chess.com username in Settings. Only games available from supported public archives can be imported." else "Try another opponent name, opening, ECO code, or result."
            )
        } else {
            LazyColumn(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(filteredGames, key = { it.url }) { game -> GameCard(game) { onGameClick(game) } }
            }
        }
    }
}

@Composable
private fun InsightsScreen(games: List<ChessGameEntity>, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxSize().background(Night).padding(20.dp), verticalArrangement = Arrangement.spacedBy(15.dp)) {
        Text("Insights", color = Paper, fontSize = 29.sp, fontWeight = FontWeight.Bold)
        Text("Patterns from your own games—not made-up stats.", color = Muted, fontSize = 13.sp)
        if (games.isEmpty()) EmptyCard("Insights will grow with your history", "Once your games are synchronized and analyzed, AfterCheck can summarize results, openings, and recurring mistakes.")
        else {
            val username = games.firstOrNull()?.username.orEmpty()
            val userWins = games.count { game ->
                (game.white.equals(username, ignoreCase = true) && game.result == "1-0") ||
                    (game.black.equals(username, ignoreCase = true) && game.result == "0-1")
            }
            val userLosses = games.count { game ->
                (game.white.equals(username, ignoreCase = true) && game.result == "0-1") ||
                    (game.black.equals(username, ignoreCase = true) && game.result == "1-0")
            }
            val draws = games.count { it.result == "1/2-1/2" }
            val unknown = games.count { it.result == "*" }
            MetricCardWide("Games in this view", games.size.toString(), "Latest imported games")
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                MetricCard("Wins", userWins.toString(), Modifier.weight(1f))
                MetricCard("Draws", draws.toString(), Modifier.weight(1f))
                MetricCard("Losses", userLosses.toString(), Modifier.weight(1f))
            }
            MetricCardWide("Unfinished / unknown result", unknown.toString(), "Excluded from win/loss totals")
            Text("Record for $username · latest imported games only", color = Muted, fontSize = 12.sp)
        }
    }
}

@Composable
private fun PracticeScreen(database: AfterCheckDatabase, modifier: Modifier = Modifier) {
    val dao = remember(database) { database.gameDao() }
    val practicePositions by dao.observePracticePositions().collectAsState(initial = emptyList())
    var positionIndex by remember { mutableIntStateOf(0) }
    val safeIndex = if (practicePositions.isEmpty()) 0 else positionIndex.coerceIn(0, practicePositions.lastIndex)
    val puzzle = practicePositions.getOrNull(safeIndex)
    val options = remember(puzzle) { puzzle?.let { PracticePuzzleFactory.options(it) }.orEmpty() }
    var selectedUci by remember(puzzle?.gameUrl, puzzle?.plyNumber) { mutableStateOf<String?>(null) }
    val selectedOption = options.firstOrNull { it.uci == selectedUci }
    val bestOption = options.firstOrNull { it.isBestMove }

    LazyColumn(
        modifier = modifier.fillMaxSize().background(Night),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(15.dp)
    ) {
        item {
            Text("Practice", color = Paper, fontSize = 29.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(5.dp))
            Text("Find a stronger move from your own analyzed games.", color = Muted, fontSize = 13.sp)
        }
        if (puzzle == null) {
            item {
                EmptyCard(
                    "Your training set is being built",
                    "Analyze completed games first. Positions classified as inaccuracies, mistakes, or blunders will become practice questions."
                )
            }
        } else if (options.size < 2 || bestOption == null) {
            item {
                EmptyCard(
                    "This position cannot be turned into a quiz",
                    "The recorded engine move could not be matched against legal moves from this position. Other practice positions may still be available."
                )
            }
            item {
                Button(
                    onClick = { positionIndex = if (positionIndex < practicePositions.lastIndex) positionIndex + 1 else 0 },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Next position") }
            }
        } else {
            item {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.weight(1f)) {
                        Text("POSITION ${safeIndex + 1} OF ${practicePositions.size}", color = Lime, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.4.sp)
                        Text(if (puzzle.fenBefore.split(' ').getOrNull(1) == "b") "Black to move" else "White to move", color = Paper, fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
                    }
                    Surface(color = classificationBackground(puzzle.classification), shape = RoundedCornerShape(20.dp)) {
                        Text(puzzle.classification, color = Night, fontSize = 10.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 9.dp, vertical = 6.dp))
                    }
                }
            }
            item {
                Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(22.dp)) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        ChessBoard(fen = puzzle.fenBefore, modifier = Modifier.fillMaxWidth().aspectRatio(1f))
                        Text("Choose the strongest move", color = Paper, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                        Text("Pick one legal move. The engine recommendation stays hidden until you answer.", color = Muted, fontSize = 12.sp)
                    }
                }
            }
            items(options, key = { puzzle.gameUrl + ":" + puzzle.plyNumber + ":" + it.uci }) { option ->
                Button(
                    onClick = { selectedUci = option.uci },
                    enabled = selectedUci == null,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (selectedUci != null && option.uci == bestOption.uci) Color(0xFF9BE36D) else PanelRaised,
                        contentColor = if (selectedUci != null && option.uci == bestOption.uci) Night else Paper,
                        disabledContainerColor = if (selectedUci != null && option.uci == bestOption.uci) Color(0xFF9BE36D) else Panel
                    )
                ) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(option.san, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                        Spacer(Modifier.weight(1f))
                        if (selectedUci != null && option.uci == bestOption.uci) Text("ENGINE BEST", fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        else if (selectedUci == option.uci) Text("YOUR PICK", fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
            if (selectedOption != null) {
                item {
                    Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(20.dp)) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                if (selectedOption.isBestMove) "Correct — that is the engine's top move." else "Not the engine's top choice.",
                                color = if (selectedOption.isBestMove) Lime else Color(0xFFF0D36A),
                                fontWeight = FontWeight.Bold,
                                fontSize = 16.sp
                            )
                            Text("In the game you played ${puzzle.san}. The engine's best move was ${bestOption.san.ifBlank { puzzle.bestMoveUci }}.", color = Paper, fontSize = 13.sp)
                            Text("The recorded evaluation swing was ${puzzle.centipawnLoss} centipawns at depth ${puzzle.depth}. This is AfterCheck's estimate, not Chess.com's official score.", color = Muted, fontSize = 12.sp)
                            if (puzzle.principalVariationSan.isNotBlank()) {
                                Text("Suggested line: ${puzzle.principalVariationSan}", color = Muted, fontSize = 12.sp)
                            }
                        }
                    }
                }
                item {
                    Button(
                        onClick = { positionIndex = if (positionIndex < practicePositions.lastIndex) positionIndex + 1 else 0 },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = Lime, contentColor = Night)
                    ) { Text(if (safeIndex < practicePositions.lastIndex) "Next position" else "Start again") }
                }
            }
        }
    }
}

private fun classificationBackground(value: String): Color = when (value) {
    "INACCURACY" -> Color(0xFFF0D36A)
    "MISTAKE" -> Color(0xFFF0A45A)
    else -> Color(0xFFFF887E)
}

@Composable
private fun SettingsScreen(context: Context, modifier: Modifier = Modifier) {
    val prefs = remember { context.getSharedPreferences(ChessComSyncService.PREFS, Context.MODE_PRIVATE) }
    val preferenceRevision = rememberPreferencesRevision(prefs)
    var username by remember { mutableStateOf(prefs.getString(ChessComSyncService.KEY_USERNAME, "").orEmpty()) }
    val analysisError = remember(preferenceRevision) { prefs.getString("last_analysis_error", null) }
    var message by remember { mutableStateOf<String?>(null) }
    var invalid by remember { mutableStateOf(false) }

    Column(modifier.fillMaxSize().background(Night).padding(20.dp), verticalArrangement = Arrangement.spacedBy(15.dp)) {
        Text("Settings", color = Paper, fontSize = 29.sp, fontWeight = FontWeight.Bold)
        Text("Private by default. No password or cookie is requested.", color = Muted, fontSize = 13.sp)
        Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(22.dp)) {
            Column(Modifier.fillMaxWidth().padding(17.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Chess.com public history", color = Paper, fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
                Text("Enter your username to retrieve games exposed by the documented public archive API. This is not an account login and cannot access private games.", color = Muted, fontSize = 12.sp)
                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it; invalid = false; message = null },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("Chess.com username") },
                    isError = invalid
                )
                Button(
                    onClick = {
                        val normalized = username.trim().lowercase(Locale.ROOT)
                        if (!normalized.matches(Regex("[a-z0-9_-]{1,30}"))) {
                            invalid = true
                            message = "Enter a valid username (letters, numbers, underscore or hyphen)."
                        } else {
                            username = normalized
                            prefs.edit().putString(ChessComSyncService.KEY_USERNAME, normalized)
                                .putString(ChessComSyncService.KEY_STATUS, "QUEUED")
                                .remove(ChessComSyncService.KEY_ERROR)
                                .apply()
                            SyncScheduler.ensurePeriodic(context)
                            SyncScheduler.syncNow(context)
                            message = "Saved. Sync is queued; Android may schedule it after a short delay."
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Lime, contentColor = Night),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Rounded.Sync, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Save username & sync")
                }
                message?.let { Text(it, color = if (invalid) MaterialTheme.colorScheme.error else Lime, fontSize = 12.sp) }
            }
        }
        Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(22.dp)) {
            Column(Modifier.padding(17.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Background behavior", color = Paper, fontWeight = FontWeight.SemiBold)
                Text("Periodic sync is scheduled every six hours when network access is available. WorkManager is OS-scheduled, so exact start times are not guaranteed.", color = Muted, fontSize = 12.sp)
                Text("Stockfish analysis runs on-device. The official evaluation network is downloaded and verified once, then kept locally for offline reviews.", color = Muted, fontSize = 12.sp)
                if (!analysisError.isNullOrBlank()) Text("Last analysis issue: $analysisError", color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun rememberPreferencesRevision(prefs: SharedPreferences): Int {
    var revision by remember(prefs) { mutableIntStateOf(0) }
    DisposableEffect(prefs) {
        val mainHandler = Handler(Looper.getMainLooper())
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
            mainHandler.post { revision++ }
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose {
            prefs.unregisterOnSharedPreferenceChangeListener(listener)
        }
    }
    return revision
}

@Composable
private fun MetricCard(label: String, value: String, modifier: Modifier = Modifier) {
    Card(modifier = modifier, colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(18.dp)) {
        Column(Modifier.padding(13.dp)) {
            Text(value, color = Paper, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(5.dp))
            Text(label, color = Muted, fontSize = 10.sp, lineHeight = 13.sp)
        }
    }
}

@Composable
private fun MetricCardWide(title: String, value: String, subtitle: String) {
    Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, color = Paper, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(4.dp))
                Text(subtitle, color = Muted, fontSize = 12.sp)
            }
            Text(value, color = Lime, fontSize = 24.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun Tag(text: String) {
    Surface(color = Color(0xFF2B3B2C), shape = RoundedCornerShape(30.dp)) {
        Text(text, color = Lime, fontSize = 10.sp, modifier = Modifier.padding(horizontal = 9.dp, vertical = 7.dp))
    }
}

@Composable
private fun EmptyCard(title: String, detail: String) {
    Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(22.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Box(Modifier.size(42.dp).clip(RoundedCornerShape(14.dp)).background(PanelRaised), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.Bolt, contentDescription = null, tint = Lime)
            }
            Text(title, color = Paper, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Text(detail, color = Muted, fontSize = 12.sp, lineHeight = 18.sp)
        }
    }
}

@Composable
private fun GameCard(game: ChessGameEntity, onClick: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(Modifier.padding(15.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(42.dp).clip(RoundedCornerShape(13.dp)).background(PanelRaised), contentAlignment = Alignment.Center) {
                Text(resultGlyph(game.result), color = if (game.result == "1-0") Lime else Paper, fontSize = 17.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("${game.white}  vs  ${game.black}", color = Paper, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(4.dp))
                Text(listOf(game.opening.ifBlank { "Opening not available" }, game.timeControl).joinToString(" · "), color = Muted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(if (game.endTimeEpochMs > 0) SimpleDateFormat("dd MMM yyyy", Locale.getDefault()).format(Date(game.endTimeEpochMs)) else "Date unavailable", color = Muted, fontSize = 10.sp)
            }
            Spacer(Modifier.width(8.dp))
            Text(game.reviewStatus, color = if (game.reviewStatus == "READY") Lime else Muted, fontSize = 9.sp, fontWeight = FontWeight.Bold)
        }
    }
}

private fun resultGlyph(result: String): String = when (result) {
    "1-0" -> "1–0"
    "0-1" -> "0–1"
    "1/2-1/2" -> "½"
    else -> "•"
}

private fun syncLabel(status: String, lastSync: Long): String {
    if (status == "SYNCING" || status == "SYNCING_HISTORY" || status == "QUEUED") {
        return if (status == "SYNCING_HISTORY") "Importing older archive months…" else "Sync queued or in progress"
    }
    if (status == "ERROR") return "Last sync failed — retry when online"
    if (lastSync <= 0L) return "Public archive sync has not run yet"
    val formatted = SimpleDateFormat("dd MMM, HH:mm", Locale.getDefault()).format(Date(lastSync))
    return "Last sync $formatted · $status"
}
