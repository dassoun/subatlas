package com.sensitivemobility.subatlas

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.launch
import kotlin.math.ceil
import kotlin.math.max

// Image d'une plongée à partager : une carte portrait (4 par 5) avec le site, la date, la courbe et les chiffres clés.
// Elle est dessinée avec les mêmes couleurs dans les deux thèmes (c'est une image, pas un écran), puis capturée telle
// qu'affichée dans l'aperçu et envoyée en PNG par la feuille de partage du système.

private val CardMuted = Color.White.copy(alpha = 0.72f)
private val CardSurfaceLight = Color(0xFF5CC8D6)

/** La carte à partager. Sa hauteur suit sa largeur (4 par 5) ; le contenu est dimensionné en dp, donc identique partout. */
@Composable
internal fun DiveShareCard(dive: Dive, site: DiveSite, number: Int, modifier: Modifier = Modifier) {
    val s = LocalStrings.current
    val u = LocalUnits.current
    val figures = listOfNotNull(
        dive.maxDepth?.let { Triple(Picto.ARROW_DOWN, s.depthLabel, u.depth.text(it)) },
        dive.durationMinutes?.let { Triple(Picto.STOPWATCH, s.durationLabel, s.minutesShort(it)) },
        dive.waterTempC?.let { Triple(Picto.THERMOMETER, s.waterTemp, u.temperature.text(it)) },
        dive.avgDepthMeters?.let { Triple(Picto.DEPTH_SPAN, s.avgDepthLabel, u.depth.text(it)) },
        dive.visibilityMeters?.let { Triple(Picto.EYE, s.visibility, u.depth.text(it.toDouble())) },
    ).take(3)

    Box(modifier.aspectRatio(4f / 5f).background(Brush.verticalGradient(listOf(Ocean, Abyss)))) {
        Column(Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Pictogram(Picto.MASK, size = 22.dp, tint = Color.White)
                Text(
                    "SubAtlas",
                    Modifier.padding(start = 8.dp).weight(1f),
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                )
                Text("#$number", color = CardMuted, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
            }
            Spacer(Modifier.height(16.dp))
            if (site.country.isNotBlank()) {
                Text(site.countryText(), color = CardMuted, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text(
                site.name,
                color = Color.White,
                fontWeight = FontWeight.Bold,
                fontSize = 26.sp,
                lineHeight = 30.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                listOfNotNull(s.dateText(dive.date), dive.timeRangeText(s)?.substringBefore(" (")).joinToString(" · "),
                Modifier.padding(top = 2.dp),
                color = CardMuted,
                fontSize = 14.sp,
            )
            Spacer(Modifier.height(10.dp))

            val profile = dive.profile
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                if (profile != null && profile.depthDm.size >= 2) {
                    CardProfile(profile, Modifier.fillMaxSize())
                } else {
                    // Sans courbe : la profondeur max en grand.
                    dive.maxDepth?.let {
                        Text(u.depth.text(it), color = Color.White, fontWeight = FontWeight.Bold, fontSize = 56.sp)
                    }
                }
            }

            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                figures.forEach { (icon, label, value) ->
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                        Pictogram(icon, size = 22.dp, tint = CardSurfaceLight)
                        Text(value, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 20.sp, maxLines = 1, modifier = Modifier.padding(top = 2.dp))
                        Text(label, color = CardMuted, fontSize = 11.sp, lineHeight = 13.sp, textAlign = TextAlign.Center, maxLines = 2)
                    }
                }
            }
        }
    }
}

/**
 * Courbe de la carte : l'eau entre la surface et la courbe, en turquoise qui s'éteint avec la profondeur, la surface du
 * palier en rouge et la courbe en blanc (les remontées trop rapides prennent leur couleur de gravité). Aucun repère sur
 * la courbe : la profondeur max est déjà dans les chiffres clés.
 */
@Composable
private fun CardProfile(profile: DiveProfile, modifier: Modifier = Modifier) {
    val measurer = rememberTextMeasurer()
    val scale = LocalAscentScale.current
    val colored = LocalShareAscentColors.current
    val rates = remember(profile) { profile.ascentRates() }
    val n = profile.depthDm.size
    val hasDeco = profile.decoStopM.size == n && profile.decoStopM.any { it > 0 }
    val gridStyle = TextStyle(fontSize = 10.sp, color = Color.White.copy(alpha = 0.6f))
    Canvas(modifier) {
        val deepest = (profile.depthDm.maxOrNull() ?: return@Canvas) / 10.0
        // Un peu de marge sous le point le plus bas, jusqu'à la prochaine graduation.
        val axis = max(5.0, ceil(deepest * 1.05 / 5.0) * 5.0)
        val left = 28.dp.toPx()
        val top = 8.dp.toPx()
        val width = size.width - left - 4.dp.toPx()
        val height = size.height - top - 2.dp.toPx()
        fun x(i: Int) = left + width * i / (n - 1)
        fun y(meters: Double) = top + height * (meters / axis).toFloat()

        // Grille : la surface, puis une ligne tous les 5 m (10 m au-delà de 20 m), avec la profondeur à gauche.
        val step = if (axis <= 20) 5 else 10
        var g = 0
        while (g <= axis) {
            val gy = y(g.toDouble())
            drawLine(Color.White.copy(alpha = if (g == 0) 0.4f else 0.12f), Offset(left, gy), Offset(left + width, gy), 1.dp.toPx())
            val t = measurer.measure("$g", gridStyle)
            drawText(t, topLeft = Offset(left - t.size.width - 5.dp.toPx(), gy - t.size.height / 2f))
            g += step
        }

        val line = Path()
        profile.depthDm.forEachIndexed { i, dm -> if (i == 0) line.moveTo(x(i), y(dm / 10.0)) else line.lineTo(x(i), y(dm / 10.0)) }
        val water = Path().apply {
            addPath(line)
            lineTo(x(n - 1), y(0.0))
            lineTo(x(0), y(0.0))
            close()
        }
        drawPath(
            water,
            Brush.verticalGradient(
                listOf(CardSurfaceLight.copy(alpha = 0.78f), Lagoon.copy(alpha = 0.38f), Lagoon.copy(alpha = 0.10f)),
                startY = y(0.0),
                endY = y(deepest),
            ),
        )
        if (hasDeco) {
            val forbidden = Path().apply {
                moveTo(x(0), y(0.0))
                for (i in 0 until n) lineTo(x(i), y(maxOf(profile.decoStopM[i], 0).toDouble()))
                lineTo(x(n - 1), y(0.0))
                close()
            }
            drawPath(forbidden, DecoColor.copy(alpha = 0.75f))
        }
        drawPath(line, Color.White, style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        for (i in 1 until n) {
            if (!colored) break
            val color = scale.colorFor(rates[i]) ?: continue
            drawLine(color, Offset(x(i - 1), y(profile.depthDm[i - 1] / 10.0)), Offset(x(i), y(profile.depthDm[i] / 10.0)), 3.dp.toPx(), StrokeCap.Round)
        }
    }
}

/**
 * Aperçu de la carte et envoi : la carte affichée est capturée telle quelle (une couche graphique), encodée en PNG puis
 * confiée à la feuille de partage du système (WhatsApp, messagerie, réseaux sociaux, enregistrement dans la galerie…).
 */
@Composable
internal fun ShareImageDialog(
    dive: Dive,
    site: DiveSite,
    number: Int,
    actions: PlatformActions,
    onDismiss: () -> Unit,
) {
    val s = LocalStrings.current
    val scope = rememberCoroutineScope()
    val layer = rememberGraphicsLayer()
    var busy by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            Modifier.padding(horizontal = 16.dp, vertical = 24.dp).fillMaxWidth(),
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surface,
        ) {
            Column(
                Modifier.verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(s.shareImageTitle, style = MaterialTheme.typography.titleLarge)
                Text(s.shareImageIntro, style = MaterialTheme.typography.bodyMedium)
                // Les coins arrondis ne sont que pour l'aperçu : la capture est enregistrée avant le découpage.
                Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))) {
                    DiveShareCard(
                        dive, site, number,
                        Modifier.fillMaxWidth().drawWithContent {
                            layer.record { this@drawWithContent.drawContent() }
                            drawLayer(layer)
                        },
                    )
                }
                if (failed) Text(s.shareImageFailed, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = onDismiss) { Text(s.cancel) }
                    Spacer(Modifier.padding(4.dp))
                    Button(
                        enabled = !busy,
                        onClick = {
                            busy = true
                            failed = false
                            scope.launch {
                                val png = runCatching { layer.toImageBitmap().toPngBytes() }.getOrNull()
                                val shared = png != null && actions.shareFile("subatlas-plongee-$number.png", "image/png", png)
                                if (!shared) failed = true
                                busy = false
                            }
                        },
                    ) { Text(s.shareImageAction) }
                }
            }
        }
    }
}
