package com.sensitivemobility.subatlas

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * État vide d'une liste : une petite scène sous-marine (un disque d'eau où affleure une vague, des bulles) portant le
 * pictogramme de la liste, puis un titre et, s'il y en a un, un texte d'explication. Le message est coupé à son premier
 * saut de ligne : la première ligne est le titre, le reste l'explication (c'est la forme des textes « aucun… pour
 * l'instant » de l'appli, dans les deux langues).
 */
@Composable
internal fun EmptyState(icon: Picto, message: String, modifier: Modifier = Modifier) {
    val title = message.substringBefore('\n')
    val body = message.substringAfter('\n', "")
    Column(
        modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        EmptyIllustration(icon)
        Spacer(Modifier.height(20.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        if (body.isNotBlank()) {
            Text(
                body,
                Modifier.padding(top = 8.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun EmptyIllustration(icon: Picto) {
    val color = MaterialTheme.colorScheme.primary
    Box(Modifier.size(width = 168.dp, height = 132.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(width = 168.dp, height = 132.dp)) {
            val c = Offset(size.width / 2f, size.height / 2f + 2.dp.toPx())
            val r = 56.dp.toPx()
            // Le disque d'eau, et deux vagues claires dans son tiers bas.
            drawCircle(color.copy(alpha = 0.12f), r, c)
            val disc = Path().apply { addOval(Rect(c.x - r, c.y - r, c.x + r, c.y + r)) }
            clipPath(disc) {
                for ((level, alpha) in listOf(0.18f to 0.16f, 0.36f to 0.12f)) {
                    val top = c.y + r * (0.55f + level)
                    drawPath(wavePath(c.x - r, c.x + r, top, 5.dp.toPx(), c.y + r), color.copy(alpha = alpha))
                }
            }
            // Des bulles qui montent.
            val line = Stroke(width = 1.6.dp.toPx())
            drawCircle(color.copy(alpha = 0.5f), 7.dp.toPx(), Offset(c.x + r * 0.95f, c.y - r * 0.55f), style = line)
            drawCircle(color.copy(alpha = 0.4f), 4.dp.toPx(), Offset(c.x + r * 1.2f, c.y - r * 0.95f), style = line)
            drawCircle(color.copy(alpha = 0.35f), 5.dp.toPx(), Offset(c.x - r * 1.05f, c.y - r * 0.4f), style = line)
            drawCircle(color.copy(alpha = 0.3f), 3.dp.toPx(), Offset(c.x - r * 0.8f, c.y - r * 0.85f), style = line)
        }
        Pictogram(icon, size = 52.dp, tint = color)
    }
}

/** Surface d'une vague entre [x0] et [x1] (deux périodes), à la hauteur [top], remplie jusqu'à [bottom]. */
private fun wavePath(x0: Float, x1: Float, top: Float, amplitude: Float, bottom: Float): Path = Path().apply {
    val segment = (x1 - x0) / 4f
    moveTo(x0, top)
    var x = x0
    for (i in 0 until 4) {
        val direction = if (i % 2 == 0) -1f else 1f
        quadraticTo(x + segment / 2f, top + direction * amplitude * 2f, x + segment, top)
        x += segment
    }
    lineTo(x1, bottom)
    lineTo(x0, bottom)
    close()
}
