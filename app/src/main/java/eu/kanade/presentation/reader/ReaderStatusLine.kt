package eu.kanade.presentation.reader

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.text.format.DateFormat
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay
import java.util.Date

/** The time and battery level, for reading with the status bar hidden. */
@Composable
fun ReaderStatusLine(foreground: Color, background: Color, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var battery by remember { mutableIntStateOf(-1) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }

    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                battery = intent.batteryPercent()
            }
        }
        // The broadcast is sticky, so registering hands back the current level straight away.
        ContextCompat.registerReceiver(
            context,
            receiver,
            IntentFilter(Intent.ACTION_BATTERY_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )?.let { battery = it.batteryPercent() }
        onDispose { context.unregisterReceiver(receiver) }
    }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            // Wake at the turn of the next minute, when the clock changes.
            delay(MINUTE - now % MINUTE)
        }
    }

    val time = remember(now) { DateFormat.getTimeFormat(context).format(Date(now)) }
    Text(
        text = if (battery >= 0) "$time · $battery%" else time,
        style = MaterialTheme.typography.labelSmall,
        color = foreground.copy(alpha = 0.7f),
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(background.copy(alpha = 0.85f))
            .padding(horizontal = 8.dp, vertical = 2.dp),
    )
}

private fun Intent.batteryPercent(): Int {
    val level = getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
    val scale = getIntExtra(BatteryManager.EXTRA_SCALE, -1)
    return if (level < 0 || scale <= 0) -1 else level * 100 / scale
}

private const val MINUTE = 60_000L
