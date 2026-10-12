package app.aftercheck

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.graphics.Color
import app.aftercheck.data.AfterCheckDatabase
import app.aftercheck.ui.AfterCheckApp
import app.aftercheck.worker.SyncScheduler

private val AfterCheckColors = darkColorScheme(
    primary = Color(0xFF9BE36D),
    onPrimary = Color(0xFF122014),
    secondary = Color(0xFFBBDDA4),
    background = Color(0xFF101512),
    surface = Color(0xFF1A211C),
    onSurface = Color(0xFFEAF1E9),
    onSurfaceVariant = Color(0xFFA6B2A7),
    error = Color(0xFFFFB4AB)
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val database = AfterCheckDatabase.get(applicationContext)
        SyncScheduler.ensurePeriodic(applicationContext)
        setContent {
            MaterialTheme(colorScheme = AfterCheckColors) {
                AfterCheckApp(database = database)
            }
        }
    }
}
