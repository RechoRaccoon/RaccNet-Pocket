package com.mediaviewer.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.mediaviewer.util.rememberHapticTap
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin

/**
 * A proper color picker: a hue/saturation wheel, a brightness slider, a
 * hex code field (#RRGGBB) and a before/after preview. Every part stays in
 * sync — dragging the wheel updates the hex, typing a hex moves the wheel.
 * The whole panel wears the color being picked.
 */
@Composable
fun ColorWheelDialog(
    initial: Color,
    title: String,
    onDismiss: () -> Unit,
    onPick: (Color) -> Unit
) {
    val tap = rememberHapticTap()
    val view = androidx.compose.ui.platform.LocalView.current
    val start = remember { FloatArray(3).also { android.graphics.Color.colorToHSV(initial.toArgb(), it) } }
    var hue by remember { mutableFloatStateOf(start[0]) }
    var sat by remember { mutableFloatStateOf(start[1]) }
    var value by remember { mutableFloatStateOf(start[2].coerceAtLeast(0.05f)) }
    fun current() = Color(android.graphics.Color.HSVToColor(floatArrayOf(hue, sat, value)))
    fun hexOf(c: Color) = "%06X".format(c.toArgb() and 0xFFFFFF)
    var hex by remember { mutableStateOf(hexOf(initial)) }
    var hexError by remember { mutableStateOf(false) }
    val picked = current()

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        val shape = RoundedCornerShape(24.dp)
        Column(
            Modifier.padding(horizontal = 24.dp).widthIn(max = 380.dp).fillMaxWidth()
                .clip(shape)
                .background(androidx.compose.ui.graphics.lerp(Color(0xFF111115), picked, 0.18f))
                .border(1.5.dp, Brush.linearGradient(listOf(picked, Color.White.copy(0.5f), picked)), shape)
                .padding(18.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(title, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(14.dp))

            // ── Hue (angle) / saturation (distance from centre) wheel ──
            Box(
                Modifier.fillMaxWidth(0.82f).aspectRatio(1f)
                    .pointerInput(Unit) {
                        fun pick(p: Offset) {
                            val cx = size.width / 2f; val cy = size.height / 2f
                            val r = min(cx, cy)
                            val dx = p.x - cx; val dy = p.y - cy
                            var deg = Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).toFloat()
                            if (deg < 0f) deg += 360f
                            hue = deg
                            sat = (hypot(dx, dy) / r).coerceIn(0f, 1f)
                            hex = hexOf(current()); hexError = false
                        }
                        awaitEachGesture {
                            val down = awaitFirstDown()
                            pick(down.position); down.consume()
                            view.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
                            while (true) {
                                val e = awaitPointerEvent()
                                val ch = e.changes.firstOrNull { it.id == down.id } ?: break
                                if (!ch.pressed) break
                                pick(ch.position); ch.consume()
                            }
                        }
                    }
            ) {
                Canvas(Modifier.matchParentSize()) {
                    val r = min(size.width, size.height) / 2f
                    val c = Offset(size.width / 2f, size.height / 2f)
                    // Hue around the edge…
                    drawCircle(
                        brush = Brush.sweepGradient(
                            listOf(Color.Red, Color.Yellow, Color.Green, Color.Cyan, Color.Blue, Color.Magenta, Color.Red),
                            center = c
                        ),
                        radius = r, center = c
                    )
                    // …fading to white (no saturation) in the middle…
                    drawCircle(
                        brush = Brush.radialGradient(listOf(Color.White, Color.White.copy(alpha = 0f)), center = c, radius = r),
                        radius = r, center = c
                    )
                    // …and darkened by the brightness slider.
                    drawCircle(Color.Black.copy(alpha = 1f - value), radius = r, center = c)
                    drawCircle(Color.White.copy(alpha = 0.35f), radius = r, center = c, style = Stroke(1.dp.toPx()))
                    // The picked spot.
                    val a = Math.toRadians(hue.toDouble())
                    val p = Offset(c.x + (cos(a) * sat * r).toFloat(), c.y + (sin(a) * sat * r).toFloat())
                    drawCircle(Color.Black.copy(alpha = 0.5f), radius = 13.dp.toPx(), center = p)
                    drawCircle(picked, radius = 10.dp.toPx(), center = p)
                    drawCircle(Color.White, radius = 11.dp.toPx(), center = p, style = Stroke(2.5.dp.toPx()))
                }
            }

            Spacer(Modifier.height(16.dp))
            // ── Brightness ──
            Box(
                Modifier.fillMaxWidth().height(26.dp).clip(RoundedCornerShape(13.dp))
                    .background(Brush.horizontalGradient(listOf(Color.Black, Color(android.graphics.Color.HSVToColor(floatArrayOf(hue, sat, 1f))))))
                    .border(1.dp, Color.White.copy(0.25f), RoundedCornerShape(13.dp))
                    .pointerInput(Unit) {
                        fun pick(x: Float) {
                            value = (x / size.width).coerceIn(0.02f, 1f)
                            hex = hexOf(current()); hexError = false
                        }
                        awaitEachGesture {
                            val down = awaitFirstDown()
                            pick(down.position.x); down.consume()
                            while (true) {
                                val e = awaitPointerEvent()
                                val ch = e.changes.firstOrNull { it.id == down.id } ?: break
                                if (!ch.pressed) break
                                pick(ch.position.x); ch.consume()
                            }
                        }
                    }
            ) {
                Canvas(Modifier.matchParentSize()) {
                    val x = (value * size.width).coerceIn(size.height / 2f, size.width - size.height / 2f)
                    drawCircle(Color.White, radius = size.height / 2f - 3.dp.toPx(), center = Offset(x, size.height / 2f), style = Stroke(2.5.dp.toPx()))
                }
            }

            Spacer(Modifier.height(16.dp))
            // ── Preview (before → after) + hex code ──
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.clip(RoundedCornerShape(12.dp)).border(1.dp, Color.White.copy(0.25f), RoundedCornerShape(12.dp))) {
                    Box(Modifier.size(width = 34.dp, height = 40.dp).background(initial))
                    Box(Modifier.size(width = 44.dp, height = 40.dp).background(picked))
                }
                Spacer(Modifier.width(12.dp))
                Row(
                    Modifier.weight(1f).height(40.dp).clip(RoundedCornerShape(12.dp))
                        .background(Color.Black.copy(alpha = 0.35f))
                        .border(1.dp, if (hexError) Color(0xFFE0245E) else picked.copy(alpha = 0.7f), RoundedCornerShape(12.dp))
                        .padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("#", color = Color.White.copy(0.6f), fontSize = 15.sp, fontFamily = FontFamily.Monospace)
                    BasicTextField(
                        value = hex,
                        onValueChange = { raw ->
                            val cleaned = raw.removePrefix("#").filter { it.isLetterOrDigit() }.take(6).uppercase()
                            hex = cleaned
                            val parsed = if (cleaned.length == 6 && cleaned.all { it in '0'..'9' || it in 'A'..'F' }) cleaned.toLong(16).toInt() else null
                            hexError = parsed == null && cleaned.isNotEmpty()
                            if (parsed != null) {
                                val hsv = FloatArray(3)
                                android.graphics.Color.colorToHSV(parsed or 0xFF000000.toInt(), hsv)
                                hue = hsv[0]; sat = hsv[1]; value = hsv[2].coerceAtLeast(0.02f)
                            }
                        },
                        singleLine = true,
                        textStyle = TextStyle(color = Color.White, fontSize = 15.sp, fontFamily = FontFamily.Monospace),
                        cursorBrush = SolidColor(Color.White),
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, autoCorrect = false, imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { }),
                        modifier = Modifier.weight(1f).padding(start = 4.dp)
                    )
                }
            }

            Spacer(Modifier.height(18.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                val pill = RoundedCornerShape(19.dp)
                Box(
                    Modifier.weight(1f).height(40.dp).clip(pill).background(Color.White.copy(0.1f))
                        .border(1.dp, Color.White.copy(0.2f), pill).clickable { tap(); onDismiss() },
                    contentAlignment = Alignment.Center
                ) { Text("Cancel", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold) }
                Box(
                    Modifier.weight(1f).height(40.dp).clip(pill).background(picked.copy(alpha = 0.55f))
                        .border(1.dp, androidx.compose.ui.graphics.lerp(picked, Color.White, 0.4f), pill)
                        .clickable {
                            view.performHapticFeedback(android.view.HapticFeedbackConstants.CONFIRM)
                            onPick(picked)
                        },
                    contentAlignment = Alignment.Center
                ) { Text("Apply", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center) }
            }
        }
    }
}
