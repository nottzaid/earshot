package dev.nottzaid.earshot.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import dev.nottzaid.earshot.audio.LatencyMode
import dev.nottzaid.earshot.data.Prefs
import dev.nottzaid.earshot.service.Runtime

/** Link and capture facts for the curious; shown at the bottom of the sheet. */
data class Diagnostics(
    val rttMs: Float?,
    val jitterMs: Float?,
    val mmap: Boolean,
    val exclusive: Boolean,
    val burstFrames: Int,
    val overruns: Int,
    val live: Boolean,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsSheet(
    prefs: Prefs,
    runtime: Runtime,
    diagnostics: Diagnostics,
    onDismiss: () -> Unit,
    onLatency: (LatencyMode) -> Unit,
    onSmartStandby: (Boolean) -> Unit,
    onRumble: (Boolean) -> Unit,
    onLevelControl: (Boolean) -> Unit,
    onEchoCancel: (Boolean) -> Unit,
    onDefaultMic: (Boolean) -> Unit,
    runsLocked: Boolean,
    onRunsLocked: (Boolean) -> Unit,
    onForget: () -> Unit,
) {
    val p = LocalPalette.current
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = p.surface,
        contentColor = p.text,
        scrimColor = p.background.copy(alpha = 0.55f),
    ) {
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .navigationBarsPadding()
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text("Settings", style = MaterialTheme.typography.titleLarge, color = p.text)
            Spacer(Modifier.height(14.dp))

            SectionLabel("Latency") {
                Text("${prefs.latency.targetMs} ms buffer", style = MaterialTheme.typography.labelMedium, color = p.text)
            }
            Spacer(Modifier.height(4.dp))
            Segmented(LatencyMode.entries, prefs.latency, { it.title }, onLatency)
            Text(prefs.latency.blurb, style = MaterialTheme.typography.bodyMedium, color = p.textMuted, modifier = Modifier.padding(top = 6.dp, start = 4.dp))
            Spacer(Modifier.height(14.dp))

            SectionLabel("Behaviour")
            ToggleRow(
                "Smart standby",
                "Open the phone's mic only while an app on the PC is listening",
                prefs.smartStandby, onSmartStandby,
            )
            ToggleRow(
                "PC default mic",
                "Make Earshot the PC's default mic while connected",
                prefs.defaultMic, onDefaultMic,
            )
            ToggleRow(
                "Echo cancellation",
                "Remove what the PC's speakers play from the mic; voices and the room stay untouched",
                prefs.echoCancel, onEchoCancel,
            )
            ToggleRow(
                "Level control",
                "Set the mic's level by hand; off keeps it as the phone captures it",
                prefs.levelControl, onLevelControl,
            )
            ToggleRow(
                "Rumble filter",
                "Cut hum, wind and handling noise below 80 Hz",
                prefs.rumbleFilter, onRumble,
            )
            ToggleRow(
                "Run while locked",
                "Keep the PC link with the screen off; Android cuts it otherwise",
                runsLocked, onRunsLocked,
            )

            Spacer(Modifier.height(14.dp))
            HorizontalDivider(color = p.outline)
            Spacer(Modifier.height(14.dp))

            SectionLabel("Connection")
            val tabular = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = "tnum")
            Fact("PC", runtime.pcName?.let { name -> runtime.pc?.address?.let { "$name · $it" } ?: name } ?: "Not found yet", tabular)
            Fact(
                "Round trip",
                diagnostics.rttMs?.let { "${"%.0f".format(it)} ms  ·  jitter ${"%.1f".format(diagnostics.jitterMs ?: 0f)} ms" } ?: "—",
                tabular,
            )
            Fact(
                "Capture",
                if (!diagnostics.live) "Mic closed"
                else buildString {
                    append(if (diagnostics.mmap) "Low-latency path" else "Standard path")
                    if (diagnostics.exclusive) append(" · exclusive")
                    if (diagnostics.burstFrames > 0) append(" · ${"%.1f".format(diagnostics.burstFrames / 48f)} ms bursts")
                },
                tabular,
            )
            if (diagnostics.overruns > 0) Fact("Dropped bursts", diagnostics.overruns.toString(), tabular)
            if (prefs.paired) {
                Spacer(Modifier.height(10.dp))
                Text(
                    "Forget ${prefs.pcName ?: "this PC"}",
                    style = MaterialTheme.typography.labelLarge,
                    color = p.danger,
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .clickable(role = Role.Button, onClick = onForget)
                        .padding(horizontal = 4.dp, vertical = 14.dp),
                )
                Text(
                    if (runtime.connected) "The PC forgets this phone too, and you can pair again."
                    else "If the PC is away, also run earshotd unpair on it before pairing again.",
                    style = MaterialTheme.typography.bodySmall, color = p.textMuted, modifier = Modifier.padding(start = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun Fact(label: String, value: String, style: androidx.compose.ui.text.TextStyle) {
    val p = LocalPalette.current
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp, horizontal = 4.dp)) {
        Text(label, style = style, color = p.textMuted, modifier = Modifier.weight(0.4f))
        Text(value, style = style, color = p.text, modifier = Modifier.weight(0.6f))
    }
}
