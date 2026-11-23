package com.sensitivemobility.subatlas

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.luminance
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
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.round
import kotlin.math.roundToInt

private val PressureColor = Color(0xFF8E7CC3)
/** Les bouteilles suivantes : la teinte de la pression, plus claire (2) puis tirant sur le bleu (3 et plus). */
private val ExtraPressureColors = listOf(Color(0xFFB9A9E0), Color(0xFF6FA3D8), Color(0xFFD6A0D6))
/** Une couleur par mélange rencontré dans la plongée, dans l'ordre de leur premier usage. */
private val GasColors = listOf(Color(0xFF4F9DDE), Color(0xFF2BB673), Color(0xFFF2A33A), Color(0xFFD16BA5), Color(0xFF8E7CC3))
private val TempColor = Color(0xFFE8744F)
private val NdlColor =Color(0xFF2BB673)
internal val DecoColor = Color(0xFFE5484D)
private val CnsColor = Color(0xFFF2A33A)
private val Ppo2Color = Color(0xFFD16BA5)

/**
 * Couleurs de l'eau sous la surface, de haut en bas : le bleu clair du lagon, l'océan, puis l'abysse. Sur fond sombre,
 * l'abysse se confondrait avec le fond : le dégradé y part d'un turquoise lumineux (la lumière de la surface) et s'arrête à
 * l'océan, plein, pour que l'écart entre le haut et le bas reste net.
 */
@Composable
internal fun waterGradient(): List<Color> {
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    return if (dark) {
        listOf(Color(0xFF5CC8D6).copy(alpha = 0.70f), Lagoon.copy(alpha = 0.60f), Ocean)
    } else {
        listOf(Lagoon.copy(alpha = 0.18f), Ocean.copy(alpha = 0.55f), Abyss.copy(alpha = 0.85f))
    }
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
    onOpenProcedure: (() -> Unit)? = null,
) {
    val s = LocalStrings.current
    val u = LocalUnits.current
    val measurer = rememberTextMeasurer()
    val depthColor = MaterialTheme.colorScheme.primary
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    val waterColors = waterGradient()
    // Rouge translucide sur l'eau : en thème sombre l'eau est plus claire, il faut plus de rouge pour qu'il ne vire pas au gris.
    val decoSurfaceAlpha = if (MaterialTheme.colorScheme.background.luminance() < 0.5f) 0.62f else 0.38f
    val labelStyle = TextStyle(fontSize = if (fullscreen) 13.sp else 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    var selected by remember { mutableStateOf<Int?>(null) }
    // À l'ouverture de la fiche, la courbe se dessine de gauche à droite (pas en plein écran, où l'on vient la lire).
    val reveal = remember(profile) { Animatable(if (fullscreen) 1f else 0f) }
    LaunchedEffect(profile) {
        if (reveal.value < 1f) reveal.animateTo(1f, tween(900, delayMillis = 150, easing = FastOutSlowInEasing))
    }

    val n = profile.depthDm.size
    val rates = remember(profile) { profile.ascentRates() }
    val maxRate = rates.maxOrNull() ?: 0.0
    val scale = LocalAscentScale.current
    // Le bilan porte sur le seuil le plus bas ; sans aucun seuil réglé, il n'y a pas de limite à citer.
    val lowestLimit = scale.levels.firstOrNull()?.above
    val secondsOver = if (lowestLimit != null) rates.count { it > lowestLimit } * profile.intervalSeconds else 0
    val hasPressure = profile.pressureBar.size == n && profile.pressureBar.any { it > 0 }
    val prefs = LocalChartPrefs.current
    val showPressure = hasPressure && ChartSeries.PRESSURE in prefs.shown
    val hasNdl = profile.ndlMin.size == n
    val hasDeco = profile.decoStopM.size == n && profile.decoStopM.any { it > 0 }
    val hasCns = profile.cnsPct.size == n
    val hasPpo2 = profile.ppo2Cb.size == n
    // Mélanges : repérés seulement s'il y en a au moins deux ; la bande du haut les distingue, un par couleur.
    val gasKeys = profile.gasSwitches.map { it.o2Percent to it.hePercent }.distinct()
    val showGas = gasKeys.size >= 2
    val hasTemp = profile.tempDc.size == n && profile.tempDc.any { it > 0 }
    val showTemp = hasTemp && ChartSeries.TEMP in prefs.shown
    // La température a son échelle : de la plus basse à la plus haute mesure, arrondies au degré (au moins 2° d'écart).
    val tempMinC = if (hasTemp) floor((profile.tempDc.filter { it > 0 }.minOrNull() ?: 0) / 10.0) else 0.0
    val tempMaxC = if (hasTemp) max(tempMinC + 2, ceil((profile.tempDc.maxOrNull() ?: 0) / 10.0)) else 2.0
    val showNdl = hasNdl && ChartSeries.NDL in prefs.shown
    val showDeco = hasDeco && ChartSeries.DECO in prefs.shown
    val showCns = hasCns && ChartSeries.CNS in prefs.shown
    val showPpo2 = hasPpo2 && ChartSeries.PPO2 in prefs.shown
    val cnsMax = if (hasCns) max(5.0, ceil((profile.cnsPct.maxOrNull() ?: 0) / 5.0) * 5.0) else 5.0
    val ppo2Max = if (hasPpo2) max(160, ceil((profile.ppo2Cb.maxOrNull() ?: 0) / 20.0).toInt() * 20) / 100.0 else 1.6
    // Axes : les graduations tombent sur des valeurs rondes de l'unité affichée (5 m ou 10 ft ; 50 bar ou 500 psi) ; le tracé reste en métrique.
    val depthFactor = if (u.imperial) Units.FEET_PER_METER else 1.0
    val depthStep = if (u.imperial) 10.0 else 5.0
    val maxDepthShown = max(depthStep, ceil((profile.depthDm.maxOrNull() ?: 0) / 10.0 * depthFactor / depthStep) * depthStep)
    val maxDepth = maxDepthShown / depthFactor
    val pressureFactor = if (u.imperial) Units.PSI_PER_BAR else 1.0
    val pressureStep = if (u.imperial) 500.0 else 50.0
    val pressureSeries = if (hasPressure) listOf(profile.pressureBar) + profile.extraPressureBar.filter { it.size == n } else emptyList()
    val maxPressureShown = if (hasPressure) ceil((pressureSeries.maxOf { s -> s.maxOrNull() ?: 0 }) * pressureFactor / pressureStep) * pressureStep else 0.0
    val maxPressure = maxPressureShown / pressureFactor
    val total = max(1, profile.durationSeconds)

    // Géométrie du tracé (en pixels), la même pour le dessin et pour la lecture du doigt.
    class Plot(val left: Float, val top: Float, val width: Float, val height: Float)

    fun plot(density: Float, w: Float, h: Float) = Plot(
        left = 34f * density,
        top = 8f * density,
        width = w - 34f * density - (if (showPressure) 38f else 10f) * density,
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
        // Légende : la profondeur, puis une pastille par courbe disponible, qu'on active ou retire d'un appui.
        Row(
            Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("● ${s.profileDepthLegend} (${u.depth.unit})", style = MaterialTheme.typography.labelSmall, color = depthColor)
            @Composable
            fun seriesChip(series: ChartSeries, color: Color, label: String) {
                FilterChip(
                    selected = series in prefs.shown,
                    onClick = { prefs.toggle(series) },
                    label = { Text("● $label", style = MaterialTheme.typography.labelSmall, color = color) },
                    colors = FilterChipDefaults.filterChipColors(selectedContainerColor = color.copy(alpha = 0.22f)),
                )
            }
            if (hasPressure) seriesChip(ChartSeries.PRESSURE, PressureColor, "${s.profilePressureLegend} (${u.pressure.unit})")
            if (hasTemp) seriesChip(ChartSeries.TEMP, TempColor, "${s.seriesTemp} (${u.temperature.text(tempMinC)}–${u.temperature.text(tempMaxC)})")
            if (hasNdl) seriesChip(ChartSeries.NDL, NdlColor, "${s.seriesNdl} (0–99 min)")
            if (hasDeco) seriesChip(ChartSeries.DECO, DecoColor, "${s.seriesDeco} (${u.depth.unit})")
            if (hasCns) seriesChip(ChartSeries.CNS, CnsColor, "${s.seriesCns} (0–${cnsMax.roundToInt()} %)")
            if (hasPpo2) seriesChip(ChartSeries.PPO2, Ppo2Color, "${s.seriesPpo2} (0–${formatNum(ppo2Max)} bar)")
        }
        Canvas(
            (if (fullscreen) Modifier.weight(1f).fillMaxWidth() else Modifier.fillMaxWidth().height(260.dp))
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

            // Grille : une ligne tous les 5 m (10 ft), avec la profondeur à gauche.
            var d = 0.0
            while (d <= maxDepthShown + 0.01) {
                val y = p.top + p.height * (d / maxDepthShown).toFloat()
                drawLine(gridColor, Offset(p.left, y), Offset(p.left + p.width, y), strokeWidth = 1f)
                val t = measurer.measure("${d.roundToInt()}", labelStyle)
                drawText(t, topLeft = Offset(p.left - t.size.width - 4f * density, y - t.size.height / 2f))
                d += depthStep
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
            if (showPressure && maxPressure > 0) {
                var pr = 0.0
                while (pr <= maxPressureShown + 0.01) {
                    val y = p.top + p.height * (1f - (pr / maxPressureShown).toFloat())
                    val t = measurer.measure("${pr.roundToInt()}", labelStyle.copy(color = PressureColor))
                    drawText(t, topLeft = Offset(p.left + p.width + 4f * density, y - t.size.height / 2f))
                    pr += pressureStep
                }
            }
            // Bande des mélanges, en haut du tracé : un segment par mélange, nommé à son début.
            if (showGas) {
                val bandH = 6f * density
                profile.gasSwitches.forEachIndexed { k, g ->
                    val from = g.index
                    val to = profile.gasSwitches.getOrNull(k + 1)?.index ?: (n - 1)
                    val x0 = x(from).coerceIn(p.left, p.left + p.width)
                    val x1 = x(to).coerceIn(p.left, p.left + p.width)
                    if (x1 <= x0) return@forEachIndexed
                    val color = GasColors[gasKeys.indexOf(g.o2Percent to g.hePercent) % GasColors.size]
                    drawRect(color.copy(alpha = 0.85f), Offset(x0, p.top), androidx.compose.ui.geometry.Size(x1 - x0, bandH))
                    val t = measurer.measure(gasLabel(g.o2Percent, g.hePercent, s), labelStyle.copy(color = color))
                    if (x1 - x0 > t.size.width + 6f * density) drawText(t, topLeft = Offset(x0 + 3f * density, p.top + bandH + 1f * density))
                }
            }
            clipRect(p.left, 0f, p.left + p.width * reveal.value, size.height) {
            // Courbes à échelle propre (LND, CNS, PPO2), tracées seulement pendant la plongée.
            fun inDive(i: Int) = profile.depthDm[i] >= 3 && profile.depthDm[i - 1] >= 3
            fun seriesLine(values: List<Int>, color: Color, y: (Int) -> Float) {
                for (i in 1 until n) {
                    if (!inDive(i)) continue
                    drawLine(color, Offset(x(i - 1), y(values[i - 1])), Offset(x(i), y(values[i])), 2f * density, StrokeCap.Round)
                }
            }
            // Les aplats d'abord (l'eau, puis la surface du palier) : toutes les courbes sont dessinées par-dessus.
            // Profondeur : la colonne d'eau entre la surface et la courbe, du bleu clair de la surface à l'abysse (le dégradé
            // suit toute la hauteur du tracé, pas celle de la plongée).
            val line = Path()
            profile.depthDm.forEachIndexed { i, dm -> if (i == 0) line.moveTo(x(i), yDepth(dm)) else line.lineTo(x(i), yDepth(dm)) }
            val area = Path().apply {
                addPath(line)
                lineTo(x(n - 1), p.top)
                lineTo(x(0), p.top)
                close()
            }
            drawPath(area, Brush.verticalGradient(waterColors, startY = p.top, endY = p.top + p.height))

            // Plafond de décompression : la surface interdite, de la surface au premier palier (rouge translucide)...
            if (showDeco) {
                val forbidden = Path().apply {
                    moveTo(x(0), p.top)
                    for (i in 0 until n) lineTo(x(i), yDepth(maxOf(profile.decoStopM[i], 0) * 10))
                    lineTo(x(n - 1), p.top)
                    close()
                }
                drawPath(forbidden, DecoColor.copy(alpha = decoSurfaceAlpha))
            }

            // Les courbes à échelle propre, par-dessus l'eau et la surface du palier.
            if (showTemp) seriesLine(profile.tempDc, TempColor) { p.top + p.height * (1f - ((it / 10.0 - tempMinC) / (tempMaxC - tempMinC)).toFloat().coerceIn(0f, 1f)) }
            if (showNdl) seriesLine(profile.ndlMin, NdlColor) { p.top + p.height * (1f - it.coerceIn(0, 99) / 99f) }
            if (showCns) seriesLine(profile.cnsPct, CnsColor) { p.top + p.height * (1f - (it / cnsMax).toFloat().coerceIn(0f, 1f)) }
            if (showPpo2) seriesLine(profile.ppo2Cb, Ppo2Color) { p.top + p.height * (1f - (it / 100.0 / ppo2Max).toFloat().coerceIn(0f, 1f)) }
            if (showPressure && maxPressure > 0) {
                pressureSeries.forEachIndexed { k, series ->
                    val pressureLine = Path()
                    series.forEachIndexed { i, bar ->
                        val y = p.top + p.height * (1f - (bar / maxPressure).toFloat())
                        if (i == 0) pressureLine.moveTo(x(i), y) else pressureLine.lineTo(x(i), y)
                    }
                    val color = if (k == 0) PressureColor else ExtraPressureColors[(k - 1) % ExtraPressureColors.size]
                    drawPath(pressureLine, color, style = Stroke(width = 2f * density, cap = StrokeCap.Round, join = StrokeJoin.Round))
                }
            }

            // La courbe de profondeur, puis le bord du palier : un trait rouge à la profondeur du palier, sur l'échelle des profondeurs.
            drawPath(line, depthColor, style = Stroke(width = 2.5f * density, cap = StrokeCap.Round, join = StrokeJoin.Round))
            if (showDeco) {
                for (i in 1 until n) {
                    val a = profile.decoStopM[i - 1]
                    val b = profile.decoStopM[i]
                    if (a <= 0 && b <= 0) continue
                    drawLine(DecoColor, Offset(x(i - 1), yDepth(maxOf(a, 0) * 10)), Offset(x(i), yDepth(maxOf(b, 0) * 10)), 3f * density, StrokeCap.Round)
                }
            }

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
                u.depth.text(profile.depthDm[i] / 10.0),
                profile.tempDc.getOrNull(i)?.let { u.temperature.text(it / 10.0) },
                if (hasPressure) pressureSeries.joinToString(" / ") { u.pressure.text(it[i].toDouble()) } else null,
                if (showGas) profile.gasSwitches.lastOrNull { it.index <= i }?.let { gasLabel(it.o2Percent, it.hePercent, s) } else null,
                if (showNdl && profile.depthDm[i] >= 3) "${s.seriesNdl} ${profile.ndlMin[i].let { if (it >= 99) "99+" else "$it" }} min" else null,
                if (showDeco && profile.decoStopM[i] > 0) "${s.seriesDeco} ${u.depth.text(profile.decoStopM[i].toDouble())}" +
                    (profile.ttsMin.getOrNull(i)?.let { " (${s.seriesTts} $it min)" } ?: "") else null,
                if (showCns) "${s.seriesCns} ${profile.cnsPct[i]} %" else null,
                if (showPpo2) "${s.seriesPpo2} ${formatNum(profile.ppo2Cb[i] / 100.0)} bar" else null,
                if (abs(rate) >= 0.5) "${if (rate > 0) "↑" else "↓"} ${u.rate.text(abs(rate))}" else null,
            )
            Text(parts.joinToString(" · "), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = 4.dp))
        }

        // Bilan de la vitesse de remontée.
        if (maxRate > 0.5) {
            val color = scale.colorFor(maxRate) ?: StatusOk
            Text(
                s.labelled(s.ascentRateLabel, "max ${u.rate.text(maxRate)}") +
                    (if (lowestLimit == null) "" else " · " + if (secondsOver > 0) s.ascentOver(u.rate.text(lowestLimit.toDouble()), secondsOver) else s.ascentFine),
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
                        Pictogram(Picto.CLOSE, size = 22.dp, tint = MaterialTheme.colorScheme.primary)
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
    // La surface du palier, comme sur la courbe détaillée, tant que la série « palier » n'est pas masquée.
    val showDeco = profile.decoStopM.size == n && profile.decoStopM.any { it > 0 } && ChartSeries.DECO in LocalChartPrefs.current.shown
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
        if (showDeco) {
            val forbidden = Path().apply {
                moveTo(x(indices.first()), 0f)
                // Hors décompression, le bord reste à la surface (y = 0) : un décalage d'un demi-trait y dessinerait un filet rouge sur toute la largeur.
                for (i in indices) {
                    val stop = profile.decoStopM[i]
                    lineTo(x(i), if (stop > 0) (size.height - stroke) * (stop / maxDepth) + stroke / 2 else 0f)
                }
                lineTo(x(n - 1), 0f)
                close()
            }
            drawPath(forbidden, DecoColor.copy(alpha = 0.40f))
        }
        drawPath(line, depthColor, style = Stroke(width = stroke, cap = StrokeCap.Round, join = StrokeJoin.Round))
        for (k in 1 until indices.size) {
            val i = indices[k]
            val color = scale.colorFor(rates[i]) ?: continue
            drawLine(color, Offset(x(indices[k - 1]), y(indices[k - 1])), Offset(x(i), y(i)), stroke, StrokeCap.Round)
        }
    }
}
