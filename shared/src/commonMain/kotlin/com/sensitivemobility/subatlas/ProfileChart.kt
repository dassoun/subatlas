package com.sensitivemobility.subatlas

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.layout.onSizeChanged
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Courbe d'une plongée : profondeur en fonction du temps (la surface en haut) et, s'il y a un capteur, pression de la
 * bouteille. Un appui ou un glissement du doigt affiche les valeurs au point touché.
 */
@Composable
internal fun ProfileChart(profile: DiveProfile, modifier: Modifier = Modifier) {
    val s = LocalStrings.current
    val measurer = rememberTextMeasurer()
    val depthColor = MaterialTheme.colorScheme.primary
    val pressureColor = StatusWarn
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    val labelStyle = TextStyle(fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    var selected by remember { mutableStateOf<Int?>(null) }

    val n = profile.depthDm.size
    val hasPressure = profile.pressureBar.size == n && profile.pressureBar.any { it > 0 }
    val maxDepth = max(5.0, ceil((profile.depthDm.maxOrNull() ?: 0) / 10.0 / 5.0) * 5.0)
    val maxPressure = if (hasPressure) ceil((profile.pressureBar.maxOrNull() ?: 0) / 50.0) * 50.0 else 0.0
    val total = max(1, profile.durationSeconds)

    // Géométrie du tracé (en pixels), la même pour le dessin et pour la lecture du doigt.
    class Plot(val left: Float, val top: Float, val width: Float, val height: Float)

    fun plot(density: Float, w: Float, h: Float) = Plot(
        left = 34f * density,
        top = 8f * density,
        width = w - 34f * density - (if (hasPressure) 38f else 10f) * density,
        height = h - 8f * density - 22f * density,
    )

    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(s.profileTitle, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("● " + s.profileDepthLegend, style = MaterialTheme.typography.labelSmall, color = depthColor)
            if (hasPressure) Text("● " + s.profilePressureLegend, style = MaterialTheme.typography.labelSmall, color = pressureColor)
        }
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(210.dp)
                .onSizeChanged { canvasSize = it }
                .pointerInput(profile) {
                    detectTapGestures { pos ->
                        val p = plot(density, canvasSize.width.toFloat(), canvasSize.height.toFloat())
                        selected = (((pos.x - p.left) / p.width) * (n - 1)).roundToInt().coerceIn(0, n - 1)
                    }
                }
                .pointerInput(profile) {
                    detectHorizontalDragGestures { change, _ ->
                        change.consume()
                        val p = plot(density, canvasSize.width.toFloat(), canvasSize.height.toFloat())
                        selected = (((change.position.x - p.left) / p.width) * (n - 1)).roundToInt().coerceIn(0, n - 1)
                    }
                },
        ) {
            val p = plot(density, size.width, size.height)
            fun x(i: Int) = p.left + p.width * (if (n <= 1) 0f else i.toFloat() / (n - 1))
            fun yDepth(dm: Int) = p.top + p.height * (dm / 10f / maxDepth.toFloat())

            // Grille : une ligne tous les 5 m, avec la profondeur à gauche.
            var d = 0.0
            while (d <= maxDepth + 0.01) {
                val y = p.top + p.height * (d / maxDepth).toFloat()
                drawLine(gridColor, Offset(p.left, y), Offset(p.left + p.width, y), strokeWidth = 1f)
                val t = measurer.measure("${d.roundToInt()}", labelStyle)
                drawText(t, topLeft = Offset(p.left - t.size.width - 4f * density, y - t.size.height / 2f))
                d += 5.0
            }
            // Repères de temps : au plus huit, en minutes entières.
            val minutes = total / 60.0
            val step = listOf(1, 2, 5, 10, 15, 20, 30, 60).firstOrNull { minutes / it <= 8 } ?: 120
            var m = 0
            while (m * 60 <= total) {
                val xx = p.left + p.width * (m * 60f / total)
                drawLine(gridColor, Offset(xx, p.top), Offset(xx, p.top + p.height), strokeWidth = 1f)
                val t = measurer.measure("$m", labelStyle)
                drawText(t, topLeft = Offset(xx - t.size.width / 2f, p.top + p.height + 3f * density))
                m += step
            }
            val unit = measurer.measure("min", labelStyle)
            drawText(unit, topLeft = Offset(p.left + p.width - unit.size.width, p.top + p.height + 3f * density + unit.size.height))

            // Pression : axe à droite.
            if (hasPressure && maxPressure > 0) {
                var pr = 0.0
                while (pr <= maxPressure + 0.01) {
                    val y = p.top + p.height * (1f - (pr / maxPressure).toFloat())
                    val t = measurer.measure("${pr.roundToInt()}", labelStyle.copy(color = pressureColor))
                    drawText(t, topLeft = Offset(p.left + p.width + 4f * density, y - t.size.height / 2f))
                    pr += 50.0
                }
                val line = Path()
                profile.pressureBar.forEachIndexed { i, bar ->
                    val y = p.top + p.height * (1f - (bar / maxPressure).toFloat())
                    if (i == 0) line.moveTo(x(i), y) else line.lineTo(x(i), y)
                }
                drawPath(line, pressureColor, style = Stroke(width = 2f * density, cap = StrokeCap.Round, join = StrokeJoin.Round))
            }

            // Profondeur : aire sous la courbe, puis la courbe.
            val line = Path()
            profile.depthDm.forEachIndexed { i, dm -> if (i == 0) line.moveTo(x(i), yDepth(dm)) else line.lineTo(x(i), yDepth(dm)) }
            val area = Path().apply {
                addPath(line)
                lineTo(x(n - 1), p.top)
                lineTo(x(0), p.top)
                close()
            }
            drawPath(area, depthColor.copy(alpha = 0.18f))
            drawPath(line, depthColor, style = Stroke(width = 2.5f * density, cap = StrokeCap.Round, join = StrokeJoin.Round))

            // Point sélectionné.
            selected?.let { i ->
                if (i in 0 until n) {
                    drawLine(depthColor.copy(alpha = 0.6f), Offset(x(i), p.top), Offset(x(i), p.top + p.height), strokeWidth = 1.5f * density)
                    drawCircle(depthColor, 4f * density, Offset(x(i), yDepth(profile.depthDm[i])))
                }
            }
        }
        // Lecture du point touché.
        val i = selected
        if (i != null && i in 0 until n) {
            val seconds = i * profile.intervalSeconds
            val parts = listOfNotNull(
                "${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}",
                "${formatNum(profile.depthDm[i] / 10.0)} m",
                profile.tempDc.getOrNull(i)?.let { "${formatNum(it / 10.0)} °C" },
                if (hasPressure) "${profile.pressureBar[i]} bar" else null,
            )
            Text(parts.joinToString(" · "), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = 4.dp))
        }
    }
}
