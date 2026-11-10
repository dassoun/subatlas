package com.sensitivemobility.subatlas

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.ui.graphics.drawscope.clipRect
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
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalDensity
import kotlinx.coroutines.delay
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
    onOpenProcedure: (() -> Unit)? = null,
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
    val scale = LocalAscentScale.current
    val secondsOver = rates.count { it > scale.yellowAbove } * profile.intervalSeconds
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

    // Fenêtre visible (en indices d'échantillons, fractionnaires) : zoomée horizontalement à deux doigts en plein écran.
    val fullSpan = max(1, n - 1).toFloat()
    var viewStart by remember(profile) { mutableStateOf(0f) }
    var viewSpan by remember(profile) { mutableStateOf(fullSpan) }

    fun indexAt(x: Float, density: Float): Int {
        val p = plot(density, canvasSize.width.toFloat(), canvasSize.height.toFloat())
        return (viewStart + (x - p.left) / p.width * viewSpan).roundToInt().coerceIn(0, n - 1)
    }

    // Doigt posé en lecture (x en pixels) : tant qu'il reste près d'un bord de la courbe zoomée, la fenêtre défile.
    var holdX by remember(profile) { mutableStateOf<Float?>(null) }
    val screenDensity = LocalDensity.current.density
    LaunchedEffect(holdX != null, profile) {
        while (holdX != null) {
            delay(16)
            val x = holdX ?: break
            if (viewSpan >= fullSpan) continue
            val p = plot(screenDensity, canvasSize.width.toFloat(), canvasSize.height.toFloat())
            val edge = 48f * screenDensity
            val push = when {
                x < p.left + edge -> -((p.left + edge - x) / edge).coerceIn(0f, 1.5f)
                x > p.left + p.width - edge -> ((x - (p.left + p.width - edge)) / edge).coerceIn(0f, 1.5f)
                else -> 0f
            }
            if (push != 0f) {
                // Au bord : une demi-fenêtre par seconde.
                viewStart = (viewStart + push * 0.5f * viewSpan * 0.016f).coerceIn(0f, fullSpan - viewSpan)
                selected = indexAt(x, screenDensity)
            }
        }
    }

    /** Applique un zoom horizontal [factor] et un déplacement du centre des doigts de [fromX] à [toX]. */
    fun zoomPan(factor: Float, fromX: Float, toX: Float, density: Float) {
        val p = plot(density, canvasSize.width.toFloat(), canvasSize.height.toFloat())
        if (p.width <= 0f) return
        val anchor = viewStart + (fromX - p.left) / p.width * viewSpan
        val minSpan = minOf(fullSpan, 8f)
        val span = (viewSpan / factor).coerceIn(minSpan, fullSpan)
        viewSpan = span
        viewStart = (anchor - (toX - p.left) / p.width * span).coerceIn(0f, fullSpan - span)
    }

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
                .then(
                    if (fullscreen) {
                        // Un doigt : lecture du point ; deux doigts : zoom (écartement horizontal) et déplacement.
                        Modifier.pointerInput(profile) {
                            detectTapGestures(onDoubleTap = { viewStart = 0f; viewSpan = fullSpan })
                        }.pointerInput(profile) {
                            awaitEachGesture {
                                val down = awaitFirstDown()
                                selected = indexAt(down.position.x, density)
                                holdX = down.position.x
                                do {
                                    val event = awaitPointerEvent()
                                    val pts = event.changes.filter { it.pressed }
                                    if (pts.size >= 2) {
                                        val a = pts[0]
                                        val b = pts[1]
                                        val span = abs(a.position.x - b.position.x)
                                        val prevSpan = abs(a.previousPosition.x - b.previousPosition.x)
                                        val centre = (a.position.x + b.position.x) / 2f
                                        val prevCentre = (a.previousPosition.x + b.previousPosition.x) / 2f
                                        if (span > 8f && prevSpan > 8f) zoomPan(span / prevSpan, prevCentre, centre, density)
                                        holdX = null
                                        event.changes.forEach { it.consume() }
                                    } else if (pts.size == 1) {
                                        selected = indexAt(pts[0].position.x, density)
                                        holdX = pts[0].position.x
                                        pts[0].consume()
                                    }
                                } while (event.changes.any { it.pressed })
                                holdX = null
                            }
                        }
                    } else {
                        Modifier
                            .pointerInput(profile) {
                                detectTapGestures { pos -> selected = indexAt(pos.x, density) }
                            }
                            .pointerInput(profile) {
                                detectHorizontalDragGestures { change, _ ->
                                    change.consume()
                                    selected = indexAt(change.position.x, density)
                                }
                            }
                    },
                ),
        ) {
            val p = plot(density, size.width, size.height)
            fun x(i: Int) = p.left + p.width * (if (n <= 1) 0f else (i - viewStart) / viewSpan)
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
            // Repères de temps : au plus huit dans la fenêtre visible, en minutes (ou m:ss quand on zoome fort).
            val startSec = viewStart * profile.intervalSeconds
            val visibleSec = max(1f, viewSpan * profile.intervalSeconds)
            val step = listOf(5, 10, 15, 30, 60, 120, 300, 600, 900, 1200, 1800, 3600).firstOrNull { visibleSec / it <= 8 } ?: 7200
            var tSec = ceil(startSec / step).toInt() * step
            while (tSec <= startSec + visibleSec + 0.01f) {
                val xx = p.left + p.width * ((tSec - startSec) / visibleSec)
                drawLine(gridColor, Offset(xx, p.top), Offset(xx, p.top + p.height), strokeWidth = 1f)
                val label = if (step >= 60) "${tSec / 60}" else "${tSec / 60}:${(tSec % 60).toString().padStart(2, '0')}"
                val t = measurer.measure(label, labelStyle)
                drawText(t, topLeft = Offset(xx - t.size.width / 2f, p.top + p.height + 3f * density))
                tSec += step
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
            }
            clipRect(p.left, 0f, p.left + p.width, size.height) {
            if (hasPressure && maxPressure > 0) {
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
                val color = scale.colorFor(rates[i]) ?: continue
                drawLine(color, Offset(x(i - 1), yDepth(profile.depthDm[i - 1])), Offset(x(i), yDepth(profile.depthDm[i])), 2.5f * density, StrokeCap.Round)
            }

            // Point sélectionné.
            selected?.let { i ->
                if (i in 0 until n) {
                    drawLine(depthColor.copy(alpha = 0.6f), Offset(x(i), p.top), Offset(x(i), p.top + p.height), strokeWidth = 1.5f * density)
                    drawCircle(depthColor, 4f * density, Offset(x(i), yDepth(profile.depthDm[i])))
                }
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
            val color = scale.colorFor(maxRate) ?: StatusOk
            Text(
                s.labelled(s.ascentRateLabel, "max ${formatNum(round(maxRate * 10) / 10)} m/min") +
                    " · " + if (secondsOver > 0) s.ascentOver(scale.yellowAbove, secondsOver) else s.ascentFine,
                style = MaterialTheme.typography.bodyMedium,
                color = color,
                modifier = Modifier.padding(start = 4.dp),
            )
        }
        // Au-delà de la définition fédérale d'une remontée rapide : accès direct à la procédure.
        if (onOpenProcedure != null && maxRate > FAST_ASCENT_FFESSM_RATE) {
            TextButton(onClick = onOpenProcedure) { Text(s.safetySeeProcedure) }
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

/**
 * Mini-courbe d'une plongée pour la liste : la forme de la plongée (la surface en haut, l'échelle propre à chaque plongée)
 * avec les remontées trop rapides en orange ou rouge. Pas d'axes ni de gestes.
 */
@Composable
internal fun ProfileSparkline(profile: DiveProfile, modifier: Modifier = Modifier) {
    val depthColor = MaterialTheme.colorScheme.primary
    val scale = LocalAscentScale.current
    val rates = remember(profile) { profile.ascentRates() }
    val n = profile.depthDm.size
    Canvas(modifier) {
        if (n < 2) return@Canvas
        val maxDepth = max(5.0, (profile.depthDm.maxOrNull() ?: 0) / 10.0).toFloat()
        val stroke = 1.6f * density
        // Une centaine de points suffisent à cette taille : on n'en dessine pas des centaines par carte.
        val step = max(1, ceil(n / 120.0).toInt())
        val indices = (0 until n step step).toMutableList().also { if (it.last() != n - 1) it += n - 1 }
        fun x(i: Int) = size.width * i / (n - 1)
        fun y(i: Int) = (size.height - stroke) * (profile.depthDm[i] / 10f / maxDepth) + stroke / 2

        val line = Path()
        indices.forEachIndexed { k, i -> if (k == 0) line.moveTo(x(i), y(i)) else line.lineTo(x(i), y(i)) }
        val area = Path().apply {
            addPath(line)
            lineTo(x(n - 1), 0f)
            lineTo(0f, 0f)
            close()
        }
        drawPath(area, depthColor.copy(alpha = 0.18f))
        drawPath(line, depthColor, style = Stroke(width = stroke, cap = StrokeCap.Round, join = StrokeJoin.Round))
        for (k in 1 until indices.size) {
            val i = indices[k]
            val color = scale.colorFor(rates[i]) ?: continue
            drawLine(color, Offset(x(indices[k - 1]), y(indices[k - 1])), Offset(x(i), y(i)), stroke, StrokeCap.Round)
        }
    }
}
