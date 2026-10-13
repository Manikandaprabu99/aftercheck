package app.aftercheck.ui.onboarding

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import app.aftercheck.AppContainer
import app.aftercheck.R
import app.aftercheck.data.chesscom.ApiException
import app.aftercheck.data.chesscom.ChessComApi
import app.aftercheck.data.chesscom.NotFoundException
import app.aftercheck.data.chesscom.PlayerProfile
import app.aftercheck.data.chesscom.TransientApiException
import app.aftercheck.ui.components.AppCard
import app.aftercheck.ui.components.AppIcon
import app.aftercheck.ui.components.Format
import app.aftercheck.ui.components.Monogram
import app.aftercheck.ui.theme.AppTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class OnboardingState(
    val step: Int = 0,
    val username: String = "",
    val loading: Boolean = false,
    val profile: PlayerProfile? = null,
    val error: String? = null,
)

class OnboardingViewModel(private val c: AppContainer) : ViewModel() {
    val state = MutableStateFlow(OnboardingState())

    fun next() = state.update { it.copy(step = 1, error = null) }
    fun back() = state.update { if (it.profile != null) it.copy(profile = null) else it.copy(step = 0, error = null) }
    fun onUsername(v: String) = state.update { it.copy(username = v.take(40), error = null) }

    fun lookUp() {
        val name = ChessComApi.normalizeUsername(state.value.username)
        if (name == null) {
            state.update { it.copy(error = "Enter your Chess.com username (letters, numbers, - or _).") }
            return
        }
        state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            val error = try {
                val profile = c.api.profile(name)
                state.update { it.copy(loading = false, profile = profile) }
                null
            } catch (e: NotFoundException) {
                "No Chess.com account called “$name”. Check the spelling."
            } catch (e: TransientApiException) {
                "Couldn't reach Chess.com. Check your connection and try again."
            } catch (e: ApiException) {
                e.message ?: "Chess.com returned an unexpected response."
            }
            if (error != null) state.update { it.copy(loading = false, error = error) }
        }
    }

    fun confirm(onDone: () -> Unit) {
        val profile = state.value.profile ?: return
        viewModelScope.launch {
            c.settings.connect(profile.username, profile.name)
            val s = c.settings.current()
            c.scheduler.schedulePeriodicSync(s.syncIntervalMinutes, s.syncWifiOnly, replace = true)
            c.scheduler.syncNow()
            if (!c.engineFiles.isNetworkReady()) c.scheduler.downloadNetwork()
            onDone()
        }
    }
}

@Composable
fun OnboardingScreen(container: AppContainer, onConnected: () -> Unit) {
    val vm: OnboardingViewModel = viewModel { OnboardingViewModel(container) }
    val state by vm.state.collectAsStateWithLifecycle()
    Box(
        Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding(),
        contentAlignment = Alignment.TopCenter,
    ) {
        AnimatedContent(
            targetState = state.step,
            transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(160)) },
            label = "onboarding",
            modifier = Modifier.widthIn(max = 560.dp),
        ) { step ->
            if (step == 0) Welcome(onNext = vm::next) else Connect(state, vm, onConnected)
        }
    }
}

@Composable
private fun Welcome(onNext: () -> Unit) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 16.dp),
    ) {
        Spacer(Modifier.height(32.dp))
        Image(painterResource(R.drawable.ic_splash), contentDescription = null, modifier = Modifier.size(84.dp))
        Spacer(Modifier.height(28.dp))
        Text("Every game,\nreviewed.", style = MaterialTheme.typography.displaySmall)
        Spacer(Modifier.height(12.dp))
        Text(
            "AfterCheck imports your Chess.com games and analyses each one with Stockfish — on your phone, free, with no daily limit.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(32.dp))
        Feature(R.drawable.ic_autorenew, "Automatic", "New games are found and reviewed in the background — no PGNs, no links.")
        Feature(R.drawable.ic_memory, "On-device engine", "Stockfish runs locally. No account, no API key, no subscription.")
        Feature(R.drawable.ic_lock, "Private by design", "Your games and reviews stay on this phone. Nothing is uploaded.")
        Spacer(Modifier.weight(1f))
        Spacer(Modifier.height(24.dp))
        Button(onClick = onNext, modifier = Modifier.fillMaxWidth().height(54.dp), shape = MaterialTheme.shapes.medium) {
            Text("Connect Chess.com", style = MaterialTheme.typography.titleSmall)
        }
        Spacer(Modifier.height(10.dp))
        Text(
            "Independent project — not affiliated with Chess.com.",
            style = MaterialTheme.typography.labelSmall,
            color = AppTheme.colors.textMuted,
            modifier = Modifier.align(Alignment.CenterHorizontally),
        )
    }
}

@Composable
private fun Feature(icon: Int, title: String, body: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.Top) {
        Box(
            Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            AppIcon(icon, null, tint = MaterialTheme.colorScheme.onPrimaryContainer, size = 20.dp)
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun Connect(state: OnboardingState, vm: OnboardingViewModel, onConnected: () -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 16.dp)) {
        TextButton(onClick = vm::back, modifier = Modifier.padding(start = 0.dp)) {
            AppIcon(R.drawable.ic_arrow_back, null, size = 18.dp, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(6.dp))
            Text("Back")
        }
        Spacer(Modifier.height(16.dp))
        Text("Connect your account", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(8.dp))
        Text(
            "Enter the username you play with on Chess.com. AfterCheck reads your finished games from Chess.com's official public archive.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(20.dp))

        val profile = state.profile
        if (profile == null) {
            OutlinedTextField(
                value = state.username,
                onValueChange = vm::onUsername,
                label = { Text("Chess.com username") },
                singleLine = true,
                isError = state.error != null,
                leadingIcon = { AppIcon(R.drawable.ic_person, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, size = 20.dp) },
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.None,
                    autoCorrectEnabled = false,
                    keyboardType = KeyboardType.Ascii,
                    imeAction = ImeAction.Search,
                ),
                keyboardActions = KeyboardActions(onSearch = { vm.lookUp() }),
                shape = MaterialTheme.shapes.medium,
                colors = OutlinedTextFieldDefaults.colors(unfocusedBorderColor = MaterialTheme.colorScheme.outline),
                modifier = Modifier.fillMaxWidth(),
            )
            state.error?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = vm::lookUp,
                enabled = !state.loading && state.username.isNotBlank(),
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = MaterialTheme.shapes.medium,
            ) {
                if (state.loading) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                else Text("Find my account", style = MaterialTheme.typography.titleSmall)
            }
            Spacer(Modifier.height(24.dp))
            HowItWorks()
        } else {
            AppCard(Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Monogram(profile.username, size = 52.dp)
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(profile.username, style = MaterialTheme.typography.titleLarge)
                        val details = listOfNotNull(profile.name, profile.joined?.let { "Joined ${Format.date(it * 1000)}" }, profile.country)
                        if (details.isNotEmpty()) {
                            Text(details.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    AppIcon(R.drawable.ic_check_circle, "Found", tint = MaterialTheme.colorScheme.primary)
                }
            }
            Spacer(Modifier.height(20.dp))
            Text("What happens next", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(8.dp))
            Step(R.drawable.ic_cloud_download, "Your public game history is imported, newest first.")
            Step(R.drawable.ic_memory, "The Stockfish network (40 MB) downloads once; Wi-Fi recommended.")
            Step(R.drawable.ic_auto_awesome, "Reviews appear on Home as soon as each game is analysed.")
            Step(R.drawable.ic_shield, "Only finished games are analysed — never a game in progress.")
            Spacer(Modifier.height(24.dp))
            Button(onClick = { vm.confirm(onConnected) }, modifier = Modifier.fillMaxWidth().height(52.dp), shape = MaterialTheme.shapes.medium) {
                Text("Import my games", style = MaterialTheme.typography.titleSmall)
            }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = vm::back, modifier = Modifier.fillMaxWidth().height(48.dp), shape = MaterialTheme.shapes.medium) {
                Text("That's not me")
            }
        }
    }
}

@Composable
private fun Step(icon: Int, text: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
        AppIcon(icon, null, tint = MaterialTheme.colorScheme.primary, size = 20.dp)
        Spacer(Modifier.width(12.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun HowItWorks() {
    AppCard(Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AppIcon(R.drawable.ic_info, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, size = 18.dp)
            Spacer(Modifier.width(8.dp))
            Text("Why only a username?", style = MaterialTheme.typography.titleSmall)
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "Chess.com doesn't offer a sign-in for independent apps like this one. Its official Published-Data API makes every " +
                "finished game in your archive available by username, so AfterCheck never asks for your password and never touches your session.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            "New games usually appear a few minutes after they end. Variants (Chess960, bughouse…) are skipped.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
