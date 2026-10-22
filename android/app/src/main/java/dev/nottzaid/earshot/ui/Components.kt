package dev.nottzaid.earshot.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.MicOff
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.FloatState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.max
import kotlin.math.min

enum class OrbLook { Off, Searching, Standby, Live, Muted, Problem }

private val gentle = spring<Color>(stiffness = Spring.StiffnessLow)

/**
 * The centrepiece: a core that carries the state, a glow and ripples that carry the voice.
 * [level] is read only while drawing, so a moving meter never recomposes anything.
 */
@Composable
fun Orb(
    look: OrbLook,
    level: FloatState,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 236.dp,
    contentDescription: String,
) {
    val p = LocalPalette.current
    val coreA by animateColorAsState(
        when (look) {
            OrbLook.Live -> p.accent
            OrbLook.Muted -> lerp(p.danger, p.surfaceHigh, 0.55f)
            OrbLook.Standby -> p.standby
            OrbLook.Problem -> lerp(p.caution, p.surfaceHigh, 0.35f)
            else -> p.surfaceHigh
        }, gentle, label = "coreA",
    )
    val coreB by animateColorAsState(
        when (look) {
            OrbLook.Live -> p.accentWarm
            OrbLook.Muted -> lerp(p.danger, p.surfaceHigh, 0.75f)
            OrbLook.Standby -> lerp(p.standby, p.accent, 0.35f)
            OrbLook.Problem -> lerp(p.caution, p.accentWarm, 0.4f)
            else -> p.surfaceHigh
        }, gentle, label = "coreB",
    )
    val glow by animateColorAsState(
        when (look) {
            OrbLook.Live -> p.accent
            OrbLook.Muted -> p.danger
            OrbLook.Standby -> p.standby
            OrbLook.Problem -> p.caution
            else -> Color.Transparent
        }, gentle, label = "glow",
    )
    val iconTint by animateColorAsState(
        when (look) {
            OrbLook.Live, OrbLook.Standby, OrbLook.Problem -> Color.White
            OrbLook.Muted -> if (p.dark) Color(0xFFFFB3BC) else Color.White
            else -> p.textMuted
        }, gentle, label = "icon",
    )
    val rippleAmount by animateFloatAsState(if (look == OrbLook.Live) 1f else 0f, tween(600), label = "ripples")
    val spinnerAlpha by animateFloatAsState(if (look == OrbLook.Searching) 1f else 0f, tween(400), label = "spinner")
    val ringAlpha by animateFloatAsState(if (look == OrbLook.Off || look == OrbLook.Searching) 1f else 0f, tween(400), label = "ring")

    val loop = rememberInfiniteTransition(label = "orb")
    val ripplePhase by loop.animateFloat(0f, 1f, infiniteRepeatable(tween(2600, easing = LinearEasing)), label = "ripple")
    val spin by loop.animateFloat(0f, 360f, infiniteRepeatable(tween(1400, easing = LinearEasing)), label = "spin")
    val sweep by loop.animateFloat(0f, 360f, infiniteRepeatable(tween(9000, easing = LinearEasing)), label = "sweep")
    val breath by loop.animateFloat(
        0f, 1f, infiniteRepeatable(tween(2800, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "breath",
    )

    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val pressScale by animateFloatAsState(
        if (pressed) 0.94f else 1f, spring(dampingRatio = 0.5f, stiffness = Spring.StiffnessMedium), label = "press",
    )

    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val c = center
            val coreR = this.size.minDimension * 0.30f
            val lv = level.floatValue
            val breathe = if (look == OrbLook.Standby) breath * 0.06f else 0f

            // Glow: a soft halo whose reach follows the voice.
            if (glow.alpha > 0f) {
                val reach = coreR * (1.35f + 0.55f * lv * rippleAmount + breathe * 4f)
                val strength = when (look) {
                    OrbLook.Live -> 0.30f + 0.35f * lv
                    OrbLook.Standby -> 0.16f + breath * 0.08f
                    else -> 0.18f
                } * (if (p.dark) 1f else 0.75f)
                drawCircle(
                    Brush.radialGradient(
                        listOf(glow.copy(alpha = strength * glow.alpha), glow.copy(alpha = 0f)),
                        center = c, radius = reach * 1.25f,
                    ),
                    radius = reach * 1.25f, center = c,
                )
            }

            // Ripples: three rings drifting outwards, as bright as the voice is loud.
            if (rippleAmount > 0f) {
                for (i in 0 until 3) {
                    val t = (ripplePhase + i / 3f) % 1f
                    val r = coreR * (1.05f + 0.62f * t)
                    val a = (1f - t) * (1f - t) * rippleAmount * (0.10f + 0.65f * lv)
                    drawCircle(lerp(p.accent, p.accentWarm, t).copy(alpha = a), r, c, style = Stroke(width = 1.6.dp.toPx() + 2.dp.toPx() * lv))
                }
            }

            // Outline ring for the quiet states.
            if (ringAlpha > 0f) {
                drawCircle(p.outline.copy(alpha = ringAlpha), coreR + 10.dp.toPx(), c, style = Stroke(1.dp.toPx()))
            }

            // Searching: a comet arc orbiting the core.
            if (spinnerAlpha > 0f) {
                rotate(spin, c) {
                    drawArc(
                        Brush.sweepGradient(listOf(Color.Transparent, p.accent.copy(alpha = spinnerAlpha), Color.Transparent), c),
                        startAngle = 0f, sweepAngle = 300f, useCenter = false,
                        topLeft = Offset(c.x - coreR - 10.dp.toPx(), c.y - coreR - 10.dp.toPx()),
                        size = Size((coreR + 10.dp.toPx()) * 2, (coreR + 10.dp.toPx()) * 2),
                        style = Stroke(2.dp.toPx(), cap = StrokeCap.Round),
                    )
                }
            }

            // Core: a slowly turning gradient; it swells a touch with the voice.
            val r = coreR * (1f + breathe + 0.05f * lv * rippleAmount) * pressScale
            rotate(sweep, c) {
                drawCircle(Brush.sweepGradient(listOf(coreA, coreB, coreA), c), r, c)
            }
            // Top-left sheen for depth.
            drawCircle(
                Brush.radialGradient(
                    listOf(Color.White.copy(alpha = if (p.dark) 0.16f else 0.24f), Color.Transparent),
                    center = Offset(c.x - r * 0.35f, c.y - r * 0.45f), radius = r * 1.1f,
                ),
                r, c,
            )
        }
        Box(
            Modifier
                .size(size * 0.62f)
                .clip(CircleShape)
                .semantics { this.contentDescription = contentDescription }
                .clickable(interactionSource = interaction, indication = null, role = Role.Button, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                when (look) {
                    OrbLook.Off -> Icons.Rounded.PowerSettingsNew
                    OrbLook.Muted -> Icons.Rounded.MicOff
                    else -> Icons.Rounded.Mic
                },
                contentDescription = null,
                tint = iconTint,
                modifier = Modifier.size(44.dp).scale(pressScale),
            )
        }
    }
}

/** RMS bar with a peak tick; red while clipping. Draw-phase only. */
@Composable
fun LevelMeter(rms: FloatState, peak: FloatState, clipping: FloatState, modifier: Modifier = Modifier) {
    val p = LocalPalette.current
    Canvas(modifier.fillMaxWidth().height(6.dp)) {
        val h = size.height
        val radius = CornerRadius(h / 2, h / 2)
        drawRoundRect(p.surfaceHigh, cornerRadius = radius)
        val clip = clipping.floatValue
        val w = size.width * rms.floatValue
        if (w > 0.5f) {
            val brush = Brush.horizontalGradient(
                listOf(lerp(p.accent, p.danger, clip), lerp(p.accentWarm, p.danger, clip)),
                startX = 0f, endX = size.width,
            )
            drawRoundRect(brush, size = Size(max(w, h), h), cornerRadius = radius)
        }
        val px = size.width * peak.floatValue
        if (px > h) {
            val tick = 2.dp.toPx()
            drawRoundRect(
                lerp(p.text, p.danger, clip).copy(alpha = 0.85f),
                topLeft = Offset(min(px, size.width - tick) - tick / 2, -2.dp.toPx()),
                size = Size(tick, h + 4.dp.toPx()),
                cornerRadius = CornerRadius(tick / 2, tick / 2),
            )
        }
    }
}

/** A segmented picker whose highlight glides between options. */
@Composable
fun <T> Segmented(
    options: List<T>,
    selected: T,
    label: (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    val p = LocalPalette.current
    val haptics = LocalHapticFeedback.current
    val index = options.indexOf(selected).coerceAtLeast(0)
    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .height(44.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(p.surfaceHigh)
            .padding(4.dp),
    ) {
        val segW = maxWidth / options.size
        val x by animateDpAsState(segW * index, spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessMediumLow), label = "seg")
        Box(
            Modifier
                .offset(x = x)
                .width(segW)
                .fillMaxHeight()
                .clip(RoundedCornerShape(10.dp))
                .background(if (p.dark) p.outline else p.surface)
                .drawBehind {
                    if (!p.dark) drawRoundRect(p.outline, cornerRadius = CornerRadius(10.dp.toPx()), style = Stroke(1.dp.toPx()))
                },
        )
        Row(Modifier.fillMaxSize()) {
            options.forEachIndexed { i, opt ->
                val color by animateColorAsState(if (i == index) p.text else p.textMuted, tween(220), label = "segText")
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(10.dp))
                        .selectable(i == index, role = Role.Tab) {
                            if (i != index) {
                                haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                                onSelect(opt)
                            }
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(label(opt), style = MaterialTheme.typography.labelLarge, color = color, maxLines = 1)
                }
            }
        }
    }
}

/** Pills for picking one of a few things, e.g. which mic. */
@Composable
fun <T> PillRow(
    options: List<T>,
    selected: T?,
    label: (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    val p = LocalPalette.current
    val haptics = LocalHapticFeedback.current
    Row(modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { opt ->
            val on = opt == selected
            val bg by animateColorAsState(if (on) p.accent.copy(alpha = if (p.dark) 0.22f else 0.12f) else Color.Transparent, tween(220), label = "pillBg")
            val border by animateColorAsState(if (on) p.accent.copy(alpha = 0.7f) else p.outline, tween(220), label = "pillBorder")
            val fg by animateColorAsState(if (on) p.text else p.textMuted, tween(220), label = "pillFg")
            Box(
                Modifier
                    .height(36.dp)
                    .clip(CircleShape)
                    .background(bg)
                    .border(1.dp, border, CircleShape)
                    .selectable(on, role = Role.RadioButton) {
                        if (!on) {
                            haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                            onSelect(opt)
                        }
                    }
                    .padding(horizontal = 16.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(label(opt), style = MaterialTheme.typography.labelLarge, color = fg, maxLines = 1)
            }
        }
    }
}

/** A setting with a title, a one-line explanation, and a switch. The whole row toggles. */
@Composable
fun ToggleRow(title: String, detail: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    val p = LocalPalette.current
    val haptics = LocalHapticFeedback.current
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .toggleable(checked, role = Role.Switch) {
                haptics.performHapticFeedback(if (it) HapticFeedbackType.ToggleOn else HapticFeedbackType.ToggleOff)
                onChange(it)
            }
            .padding(vertical = 12.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = p.text)
            Spacer(Modifier.height(2.dp))
            Text(detail, style = MaterialTheme.typography.bodyMedium, color = p.textMuted)
        }
        Spacer(Modifier.width(16.dp))
        Switch(
            checked = checked,
            onCheckedChange = null,
            colors = SwitchDefaults.colors(
                checkedTrackColor = p.accent,
                checkedThumbColor = Color.White,
                uncheckedTrackColor = p.surfaceHigh,
                uncheckedBorderColor = p.outline,
                uncheckedThumbColor = p.textFaint,
            ),
        )
    }
}

/** Small status pill: a dot and a line of text. */
@Composable
fun StatusPill(dot: Color, text: String, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null) {
    val p = LocalPalette.current
    val dotColor by animateColorAsState(dot, tween(300), label = "dot")
    Row(
        modifier
            .height(32.dp)
            .clip(CircleShape)
            .background(p.surfaceHigh.copy(alpha = if (p.dark) 0.8f else 1f))
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(7.dp).clip(CircleShape).background(dotColor))
        Spacer(Modifier.width(8.dp))
        Text(text, style = MaterialTheme.typography.labelMedium, color = p.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier, trailing: @Composable (() -> Unit)? = null) {
    val p = LocalPalette.current
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(text.uppercase(), style = MaterialTheme.typography.labelSmall, color = p.textFaint, modifier = Modifier.weight(1f))
        trailing?.invoke()
    }
}
