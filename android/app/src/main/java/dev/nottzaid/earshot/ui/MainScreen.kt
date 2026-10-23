package dev.nottzaid.earshot.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.FloatState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.nottzaid.earshot.audio.Mic
import dev.nottzaid.earshot.net.FoundPc
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import dev.nottzaid.earshot.data.Prefs
import kotlin.math.abs
import kotlin.math.roundToInt

/** Everything the main screen shows, already worded. */
data class MainUi(
    val look: OrbLook,
    val title: String,
    val detail: String,
    val pill: String,
    val pillDot: PillDot,
    val running: Boolean,
    val orbLabel: String,
    /** PCs the user can pair with right now; shown in place of the Stop button. */
    val pairable: List<FoundPc> = emptyList(),
)

enum class PillDot { Off, Searching, Connected, Problem }

class Meters {
    val rms = mutableFloatStateOf(0f)
    val clipping = mutableFloatStateOf(0f)
    val orbLevel = mutableFloatStateOf(0f)
}

@Composable
fun MainScreen(
    ui: MainUi,
    prefs: Prefs,
    mics: List<Mic>,
    meters: Meters,
    onOrb: () -> Unit,
    onStop: () -> Unit,
    onMic: (Mic) -> Unit,
    onGainPreview: (Float) -> Unit,
    onGainCommit: (Float) -> Unit,
    onOpenSettings: () -> Unit,
    onPair: (FoundPc) -> Unit,
) {
    val p = LocalPalette.current
    val ambient by animateColorAsState(
        when (ui.look) {
            OrbLook.Live -> p.accent
            OrbLook.Muted -> p.danger
            OrbLook.Standby -> p.standby
            OrbLook.Problem -> p.caution
            else -> p.background
        }, spring(stiffness = Spring.StiffnessVeryLow), label = "ambient",
    )
    Box(
        Modifier
            .fillMaxSize()
            .background(p.background)
            .drawBehind {
                // A wash of the state colour behind the orb, fading into the page.
                drawRect(
                    Brush.radialGradient(
                        listOf(ambient.copy(alpha = if (p.dark) 0.16f else 0.10f), Color.Transparent),
                        center = Offset(size.width / 2, size.height * 0.30f),
                        radius = size.width * 0.95f,
                    ),
                )
            },
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            TopBar(ui, onOpenSettings)
            Spacer(Modifier.weight(1f))
            Orb(ui.look, meters.orbLevel, onOrb, contentDescription = ui.orbLabel)
            Spacer(Modifier.height(20.dp))
            AnimatedContent(
                targetState = ui.title,
                transitionSpec = {
                    (fadeIn(tween(220)) + slideInVertically(tween(320)) { it / 3 }) togetherWith
                        (fadeOut(tween(160)) + slideOutVertically(tween(220)) { -it / 3 })
                },
                label = "title",
            ) { Text(it, style = MaterialTheme.typography.displaySmall, color = p.text) }
            Spacer(Modifier.height(6.dp))
            AnimatedContent(
                targetState = ui.detail,
                transitionSpec = { fadeIn(tween(260, delayMillis = 60)) togetherWith fadeOut(tween(140)) },
                label = "detail",
            ) {
                Text(
                    it, style = MaterialTheme.typography.bodyLarge, color = p.textMuted, textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp), minLines = 2, maxLines = 2,
                )
            }
            if (ui.pairable.isNotEmpty()) PairChoices(ui.pairable, onPair) else StopSlot(ui.running, onStop)
            Spacer(Modifier.weight(1f))
            ControlsCard(prefs, mics, meters, onMic, onGainPreview, onGainCommit)
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun TopBar(ui: MainUi, onOpenSettings: () -> Unit) {
    val p = LocalPalette.current
    Row(Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("Earshot", style = MaterialTheme.typography.titleLarge, color = p.text)
        Spacer(Modifier.weight(1f))
        StatusPill(
            dot = when (ui.pillDot) {
                PillDot.Connected -> p.good
                PillDot.Searching -> p.caution
                PillDot.Problem -> p.danger
                PillDot.Off -> p.textFaint
            },
            text = ui.pill,
            modifier = Modifier.padding(end = 4.dp),
        )
        IconButton(onClick = onOpenSettings) {
            Icon(Icons.Rounded.Tune, contentDescription = "Settings", tint = p.textMuted)
        }
    }
}

@Composable
private fun StopSlot(visible: Boolean, onStop: () -> Unit) {
    Box(Modifier.height(48.dp), contentAlignment = Alignment.Center) {
        AnimatedVisibility(visible, enter = fadeIn() + scaleIn(initialScale = 0.9f), exit = fadeOut() + scaleOut(targetScale = 0.9f)) {
            StopButton(onStop)
        }
    }
}

@Composable
private fun StopButton(onStop: () -> Unit) {
    val p = LocalPalette.current
    // 48 dp tall touch target around a quieter visible pill.
    Box(Modifier.height(48.dp).clickable(role = Role.Button, onClick = onStop), contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .clip(CircleShape)
                .border(1.dp, p.outline, CircleShape)
                .padding(horizontal = 18.dp, vertical = 8.dp),
        ) {
            Text("Stop", style = MaterialTheme.typography.labelLarge, color = p.textMuted)
        }
    }
}

@Composable
private fun PairChoices(pcs: List<FoundPc>, onPair: (FoundPc) -> Unit) {
    val p = LocalPalette.current
    Row(Modifier.height(56.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        pcs.take(3).forEach { pc ->
            Box(
                Modifier
                    .height(44.dp)
                    .clip(CircleShape)
                    .background(p.accent)
                    .clickable(role = Role.Button) { onPair(pc) }
                    .padding(horizontal = 20.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text("Pair with ${pc.name}", style = MaterialTheme.typography.labelLarge, color = Color.White, maxLines = 1)
            }
        }
    }
}

@Composable
private fun ControlsCard(
    prefs: Prefs,
    mics: List<Mic>,
    meters: Meters,
    onMic: (Mic) -> Unit,
    onGainPreview: (Float) -> Unit,
    onGainCommit: (Float) -> Unit,
) {
    val p = LocalPalette.current
    val haptics = LocalHapticFeedback.current
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(28.dp))
            .background(p.surface)
            .border(1.dp, p.outline.copy(alpha = if (p.dark) 0.6f else 1f), RoundedCornerShape(28.dp))
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (mics.size > 1) {
            SectionLabel("Microphone")
            val selected = mics.firstOrNull { it.key == prefs.micKey } ?: mics.firstOrNull()
            PillRow(mics, selected, { it.label }, onMic)
            Spacer(Modifier.height(8.dp))
        }

        // Gain: the slider moves locally and live; the store hears about it when the finger lifts.
        var gain by remember(prefs.gainDb) { mutableStateOf(prefs.gainDb) }
        SectionLabel("Level") {
            if (prefs.levelControl) {
                Text(formatDb(gain), style = MaterialTheme.typography.labelMedium, color = if (abs(gain) < 0.05f) p.textFaint else p.text)
            }
        }
        LevelMeter(meters.rms, meters.clipping, Modifier.padding(top = 4.dp))
        if (prefs.levelControl) Slider(
            value = gain,
            onValueChange = { raw ->
                // A soft detent at 0 dB so "no gain" is easy to find again.
                val v = if (abs(raw) < 0.75f) 0f else (raw * 2).roundToInt() / 2f
                if (v != gain) {
                    if (v == 0f || (v.roundToInt() % 6 == 0 && v == v.roundToInt().toFloat())) {
                        haptics.performHapticFeedback(HapticFeedbackType.SegmentFrequentTick)
                    }
                    gain = v
                    onGainPreview(v)
                }
            },
            onValueChangeFinished = { onGainCommit(gain) },
            valueRange = GAIN_MIN..GAIN_MAX,
            colors = SliderDefaults.colors(
                thumbColor = p.text,
                activeTrackColor = p.text.copy(alpha = 0.85f),
                inactiveTrackColor = p.surfaceHigh,
                activeTickColor = Color.Transparent,
                inactiveTickColor = Color.Transparent,
            ),
            modifier = Modifier
                .fillMaxWidth()
                .height(32.dp)
                .semantics { stateDescription = "Gain ${formatDb(gain)}" },
        )
    }
}

const val GAIN_MIN = -12f
const val GAIN_MAX = 30f

fun formatDb(db: Float): String = when {
    abs(db) < 0.05f -> "0 dB"
    db > 0 -> "+${"%.1f".format(db)} dB"
    else -> "−${"%.1f".format(-db)} dB"
}
