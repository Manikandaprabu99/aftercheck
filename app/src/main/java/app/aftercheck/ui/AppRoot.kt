package app.aftercheck.ui

import androidx.annotation.DrawableRes
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import app.aftercheck.AppContainer
import app.aftercheck.R
import app.aftercheck.data.settings.AppSettings
import app.aftercheck.ui.components.AppIcon
import app.aftercheck.ui.games.GamesScreen
import app.aftercheck.ui.home.HomeScreen
import app.aftercheck.ui.insights.InsightsScreen
import app.aftercheck.ui.onboarding.OnboardingScreen
import app.aftercheck.ui.practice.DrillScreen
import app.aftercheck.ui.practice.PracticeScreen
import app.aftercheck.ui.queue.QueueScreen
import app.aftercheck.ui.review.ReviewScreen
import app.aftercheck.ui.settings.SettingsScreen
import app.aftercheck.ui.theme.AppTheme

private enum class Tab(val route: String, val label: String, @DrawableRes val icon: Int, @DrawableRes val selectedIcon: Int) {
    HOME("home", "Home", R.drawable.ic_home, R.drawable.ic_home_filled),
    GAMES("games", "Games", R.drawable.ic_chess_knight, R.drawable.ic_chess_knight_filled),
    INSIGHTS("insights", "Insights", R.drawable.ic_insights, R.drawable.ic_insights_filled),
    PRACTICE("practice", "Practice", R.drawable.ic_target, R.drawable.ic_target_filled),
    SETTINGS("settings", "Settings", R.drawable.ic_settings, R.drawable.ic_settings_filled),
}

object Routes {
    const val WELCOME = "welcome"
    const val QUEUE = "queue"
    fun review(id: String) = "review/${android.net.Uri.encode(id)}"
    fun drill(theme: String = "", starred: Boolean = false) = "drill?theme=$theme&starred=$starred"
}

@Composable
fun AppRoot(container: AppContainer, settings: AppSettings) {
    val nav = rememberNavController()
    val entry by nav.currentBackStackEntryAsState()
    val route = entry?.destination?.route
    val showBar = Tab.entries.any { it.route == route }
    val haptics = LocalHapticFeedback.current
    // Fixed for the lifetime of this host; later changes navigate explicitly.
    val startDestination = remember { if (settings.isConnected) Tab.HOME.route else Routes.WELCOME }

    // Disconnecting from Settings returns to the welcome flow.
    LaunchedEffect(settings.isConnected) {
        if (!settings.isConnected && route != null && route != Routes.WELCOME) {
            nav.navigate(Routes.WELCOME) { popUpTo(nav.graph.id) { inclusive = true } }
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0),
        bottomBar = {
            if (showBar) {
                Column {
                    Box(Modifier.fillMaxWidth().height(1.dp).background(AppTheme.colors.border))
                    NavigationBar(containerColor = MaterialTheme.colorScheme.surface, tonalElevation = 0.dp) {
                        Tab.entries.forEach { tab ->
                            val selected = route == tab.route
                            NavigationBarItem(
                                selected = selected,
                                onClick = {
                                    if (!selected) {
                                        if (settings.haptics) haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                                        nav.navigateToTab(tab.route)
                                    }
                                },
                                icon = { AppIcon(if (selected) tab.selectedIcon else tab.icon, null, tint = LocalContentColor.current) },
                                label = { Text(tab.label, style = MaterialTheme.typography.labelMedium) },
                                colors = NavigationBarItemDefaults.colors(
                                    selectedIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                    selectedTextColor = MaterialTheme.colorScheme.onSurface,
                                    indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                                    unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                    unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                ),
                            )
                        }
                    }
                }
            }
        },
    ) { padding ->
        NavHost(
            navController = nav,
            startDestination = startDestination,
            modifier = Modifier.fillMaxSize().padding(bottom = padding.calculateBottomPadding()),
            enterTransition = { fadeIn(tween(220)) },
            exitTransition = { fadeOut(tween(160)) },
        ) {
            composable(Routes.WELCOME) {
                OnboardingScreen(container) {
                    nav.navigate(Tab.HOME.route) { popUpTo(Routes.WELCOME) { inclusive = true } }
                }
            }
            composable(Tab.HOME.route) {
                HomeScreen(
                    container = container,
                    onOpenGame = { nav.navigate(Routes.review(it)) },
                    onOpenTab = { nav.navigateToTab(it) },
                    onPractice = { nav.navigate(Routes.drill()) },
                    onOpenQueue = { nav.navigate(Routes.QUEUE) },
                )
            }
            composable(Tab.GAMES.route) {
                GamesScreen(container, onOpenQueue = { nav.navigate(Routes.QUEUE) }, onOpenGame = { nav.navigate(Routes.review(it)) })
            }
            composable(
                Routes.QUEUE,
                enterTransition = { slideInHorizontally(tween(260)) { it / 6 } + fadeIn(tween(220)) },
                popExitTransition = { slideOutHorizontally(tween(220)) { it / 6 } + fadeOut(tween(180)) },
            ) {
                QueueScreen(container, onBack = { nav.popBackStack() }, onOpenGame = { nav.navigate(Routes.review(it)) })
            }
            composable(Tab.INSIGHTS.route) {
                InsightsScreen(container, onPracticeTheme = { nav.navigate(Routes.drill(theme = it)) })
            }
            composable(Tab.PRACTICE.route) {
                PracticeScreen(
                    container,
                    onStart = { theme, starred -> nav.navigate(Routes.drill(theme, starred)) },
                    onOpenGame = { nav.navigate(Routes.review(it)) },
                )
            }
            composable(Tab.SETTINGS.route) {
                SettingsScreen(container)
            }
            composable(
                "review/{id}",
                arguments = listOf(navArgument("id") { type = NavType.StringType }),
                enterTransition = { slideInHorizontally(tween(260)) { it / 6 } + fadeIn(tween(220)) },
                popExitTransition = { slideOutHorizontally(tween(220)) { it / 6 } + fadeOut(tween(180)) },
            ) { backStack ->
                ReviewScreen(
                    container,
                    backStack.arguments?.getString("id").orEmpty(),
                    onOpenQueue = { nav.navigate(Routes.QUEUE) },
                    onBack = { nav.popBackStack() },
                )
            }
            composable(
                "drill?theme={theme}&starred={starred}",
                arguments = listOf(
                    navArgument("theme") { type = NavType.StringType; defaultValue = "" },
                    navArgument("starred") { type = NavType.BoolType; defaultValue = false },
                ),
                enterTransition = { slideInHorizontally(tween(260)) { it / 6 } + fadeIn(tween(220)) },
                popExitTransition = { slideOutHorizontally(tween(220)) { it / 6 } + fadeOut(tween(180)) },
            ) { backStack ->
                DrillScreen(
                    container,
                    theme = backStack.arguments?.getString("theme").orEmpty(),
                    starredOnly = backStack.arguments?.getBoolean("starred") ?: false,
                    onBack = { nav.popBackStack() },
                    onOpenGame = { nav.navigate(Routes.review(it)) },
                )
            }
        }
    }
}

private fun NavHostController.navigateToTab(route: String) {
    navigate(route) {
        // Home is the root of the tab graph once onboarding is done.
        popUpTo(Tab.HOME.route) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}
