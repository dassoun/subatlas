package com.sensitivemobility.subatlas

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.round
import kotlin.math.roundToInt

private val PressureColor = Color(0xFF8E7CC3)

/** Couleur d'un tronçon de remontée trop rapide ; `null` tant que la vitesse reste raisonnable. */
private fun ascentColor(rate: Double): Color? = when {
    rate > ASCENT_RATE_DANGER -> StatusKo
    rate > ASCENT_RATE_WARN -> StatusWarn
    else -> null
}

/**
 * Courbe d'une plongée : profondeur en fonction du temps (la surface en haut), pression de la bouteille s'il y avait un
 * capteur, et vitesse de remontée (tronçons orange puis rouges quand elle devient rapide, bilan sous la courbe). Un appui
 * ou un glissement du doigt affiche les valeurs au point touché.
 *
 * @param onFullscreen affiche un bouton « plein écran » quand il est fourni.
 * @param fullscreen version qui remplit tout l'espace donné (plein écran) au lieu d'une hauteur fixe.
 */
@Composable
internal fun ProfileChart(
    profile: DiveProfile,
    modifier: Modifier = Modifier,
    fullscreen: Boolean = false,
    onFullscreen: (() -> Unit)? = null,
) {
    val s = LocalStrings.current
    val measurer = rememberTextMeasurer()
    val depthColor = MaterialTheme.colorScheme.primary
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    val labelStyle = TextStyle(fontSize = if (fullscreen) 13.sp else 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    var selected by remember { mutableStateOf<Int?>(null) }

    val n = profile.depthDm.size
    val rates = remember(profile) { profile.ascentRates() }
    val maxRate = rates.maxOrNull() ?: 0.0
    val secondsOver = rates.count { it > ASCENT_RATE_WARN } * profile.intervalSeconds
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
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                s.profileTitle,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(1f),
            )
            if (onFullscreen != null) {
                Box(Modifier.size(40.dp).clickable(onClick = onFullscreen), contentAlignment = Alignment.Center) {
                    Canvas(Modifier.size(20.dp)) {
                        // Quatre coins qui s'écartent : « agrandir ».
                        val w = size.width
                        val h = size.height
                        val a = w * 0.38f
                        val stroke = 2.2f * density
                        for ((cx, cy) in listOf(0f to 0f, w to 0f, 0f to h, w to h)) {
                            val dx = if (cx == 0f) a else -a
                            val dy = if (cy == 0f) a else -a
                            drawLine(depthColor, Offset(cx, cy), Offset(cx + dx, cy), stroke, StrokeCap.Round)
                            drawLine(depthColor, Offset(cx, cy), Offset(cx, cy + dy), stroke, StrokeCap.Round)
                        }
                    }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("● " + s.profileDepthLegend, style = MaterialTheme.typography.labelSmall, color = depthColor)
            if (hasPressure) Text("● " + s.profilePressureLegend, style = MaterialTheme.typography.labelSmall, color = PressureColor)
        }
        Canvas(
            (if (fullscreen) Modifier.weight(1f).fillMaxWidth() else Modifier.fillMaxWidth().height(210.dp))
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
                    val t = measurer.measure("${pr.roundToInt()}", labelStyle.copy(color = PressureColor))
                    drawText(t, topLeft = Offset(p.left + p.width + 4f * density, y - t.size.height / 2f))
                    pr += 50.0
                }
                val line = Path()
                profile.pressureBar.forEachIndexed { i, bar ->
                    val y = p.top + p.height * (1f - (bar / maxPressure).toFloat())
                    if (i == 0) line.moveTo(x(i), y) else line.lineTo(x(i), y)
                }
                drawPath(line, PressureColor, style = Stroke(width = 2f * density, cap = StrokeCap.Round, join = StrokeJoin.Round))
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

            // Remontée trop rapide : le tronçon de courbe prend la couleur de la gravité (orange, puis rouge).
            for (i in 1 until n) {
                val color = ascentColor(rates[i]) ?: continue
                drawLine(color, Offset(x(i - 1), yDepth(profile.depthDm[i - 1])), Offset(x(i), yDepth(profile.depthDm[i])), 4f * density, StrokeCap.Round)
            }

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
            val rate = rates[i]
            val parts = listOfNotNull(
                "${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}",
                "${formatNum(profile.depthDm[i] / 10.0)} m",
                profile.tempDc.getOrNull(i)?.let { "${formatNum(it / 10.0)} °C" },
                if (hasPressure) "${profile.pressureBar[i]} bar" else null,
                if (abs(rate) >= 0.5) "${if (rate > 0) "↑" else "↓"} ${formatNum(round(abs(rate) * 10) / 10)} m/min" else null,
            )
            Text(parts.joinToString(" · "), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = 4.dp))
        }

        // Bilan de la vitesse de remontée.
        if (maxRate > 0.5) {
            val color = ascentColor(maxRate) ?: StatusOk
            Text(
                s.labelled(s.ascentRateLabel, "max ${formatNum(round(maxRate * 10) / 10)} m/min") +
                    " · " + if (secondsOver > 0) s.ascentOver(ASCENT_RATE_WARN.toInt(), secondsOver) else s.ascentFine,
                style = MaterialTheme.typography.bodyMedium,
                color = color,
                modifier = Modifier.padding(start = 4.dp),
            )
        }
    }
}

/**
 * La courbe en plein écran, en paysage. Sur Android l'écran est verrouillé en paysage le temps de l'affichage ; là où la
 * plateforme ne sait pas le faire, le contenu est pivoté d'un quart de tour (on tourne alors le téléphone soi-même).
 */
@Composable
internal fun ProfileFullscreen(profile: DiveProfile, actions: PlatformActions, onClose: () -> Unit) {
    val rotatedByPlatform = remember { actions.setLandscape(true) }
    DisposableEffect(Unit) { onDispose { actions.setLandscape(false) } }

    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            val content: @Composable () -> Unit = {
                Box(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp)) {
                    ProfileChart(profile, Modifier.fillMaxSize(), fullscreen = true)
                    Box(
                        Modifier.align(Alignment.TopEnd).size(44.dp).clickable(onClick = onClose),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("✕", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
            if (rotatedByPlatform) {
                content()
            } else {
                Box(
                    Modifier.fillMaxSize().layout { measurable, c ->
                        // Contenu mesuré « couché » (largeur et hauteur échangées) puis pivoté de 90 degrés.
                        val placeable = measurable.measure(Constraints.fixed(c.maxHeight, c.maxWidth))
                        layout(c.maxWidth, c.maxHeight) {
                            placeable.placeWithLayer((c.maxWidth - placeable.width) / 2, (c.maxHeight - placeable.height) / 2) {
                                rotationZ = 90f
                            }
                        }
                    },
                ) { content() }
            }
        }
    }
}
