package com.sensitivemobility.subatlas

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** « 🇫🇷 France » (le drapeau est omis quand le pays n'est pas reconnu). */
internal fun DiveSite.countryText(): String =
    listOfNotNull(countryFlag(country), country.takeIf { it.isNotBlank() }).joinToString(" ")

/** Ligne « 🇫🇷 France · [pictogramme du type] Nom du site ». */
@Composable
internal fun SiteLine(site: DiveSite, style: TextStyle, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        if (site.country.isNotBlank()) Text(site.countryText() + " · ", style = style, maxLines = 1)
        site.type?.let {
            SiteTypeIcon(it, Modifier.size(20.dp))
            Spacer(Modifier.width(4.dp))
        }
        Text(site.name, style = style, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

/** Choix du type de site ; toucher à nouveau le type choisi le retire. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SiteTypeChips(selected: SiteType?, onSelect: (SiteType?) -> Unit) {
    val s = LocalStrings.current
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        SiteType.entries.forEach { type ->
            FilterChip(
                selected = selected == type,
                onClick = { onSelect(if (selected == type) null else type) },
                label = { Text(s.siteType(type)) },
                leadingIcon = { SiteTypeIcon(type, Modifier.size(18.dp), LocalContentColor.current) },
            )
        }
    }
}

/** Pictogramme d'un type de site, dessiné en traits (pas de fichier image à embarquer). */
@Composable
internal fun SiteTypeIcon(type: SiteType, modifier: Modifier = Modifier, tint: Color = MaterialTheme.colorScheme.primary) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val stroke = Stroke(width = w * 0.09f, cap = StrokeCap.Round, join = StrokeJoin.Round)
        when (type) {
            SiteType.SEA -> {
                wave(w * 0.04f, w * 0.96f, h * 0.27f, h * 0.09f, tint, stroke)
                wave(w * 0.04f, w * 0.96f, h * 0.52f, h * 0.09f, tint, stroke)
                wave(w * 0.04f, w * 0.96f, h * 0.77f, h * 0.09f, tint, stroke)
            }
            SiteType.LAKE -> {
                drawOval(tint, Offset(w * 0.04f, h * 0.2f), Size(w * 0.92f, h * 0.6f), style = stroke)
                wave(w * 0.22f, w * 0.78f, h * 0.5f, h * 0.07f, tint, stroke)
            }
            SiteType.RIVER -> {
                for (dx in listOf(0.08f, 0.5f)) {
                    val p = Path().apply {
                        moveTo(w * (dx + 0.12f), 0f)
                        cubicTo(w * (dx + 0.45f), h * 0.3f, w * (dx - 0.1f), h * 0.7f, w * (dx + 0.12f), h)
                    }
                    drawPath(p, tint, style = stroke)
                }
            }
            SiteType.QUARRY -> {
                val p = Path().apply {
                    moveTo(w * 0.05f, h * 0.12f)
                    lineTo(w * 0.32f, h * 0.12f)
                    lineTo(w * 0.32f, h * 0.38f)
                    lineTo(w * 0.58f, h * 0.38f)
                    lineTo(w * 0.58f, h * 0.62f)
                    lineTo(w * 0.95f, h * 0.62f)
                }
                drawPath(p, tint, style = stroke)
                wave(w * 0.58f, w * 0.95f, h * 0.86f, h * 0.06f, tint, stroke)
            }
            SiteType.PIT -> {
                drawRoundRect(tint, Offset(w * 0.28f, h * 0.05f), Size(w * 0.44f, h * 0.9f), androidx.compose.ui.geometry.CornerRadius(w * 0.1f), style = stroke)
                wave(w * 0.28f, w * 0.72f, h * 0.3f, h * 0.05f, tint, stroke)
                for (y in listOf(0.5f, 0.68f, 0.86f)) drawLine(tint, Offset(w * 0.82f, h * y), Offset(w * 0.96f, h * y), stroke.width, StrokeCap.Round)
            }
            SiteType.CAVE -> {
                drawArc(tint, 180f, 180f, false, Offset(w * 0.1f, h * 0.12f), Size(w * 0.8f, h * 1.0f), style = stroke)
                drawLine(tint, Offset(w * 0.02f, h * 0.88f), Offset(w * 0.98f, h * 0.88f), stroke.width, StrokeCap.Round)
                drawCircle(tint, w * 0.07f, Offset(w * 0.5f, h * 0.62f), style = Fill)
            }
            SiteType.POOL -> {
                drawRoundRect(tint, Offset(w * 0.05f, h * 0.2f), Size(w * 0.9f, h * 0.6f), androidx.compose.ui.geometry.CornerRadius(w * 0.08f), style = stroke)
                wave(w * 0.2f, w * 0.8f, h * 0.45f, h * 0.06f, tint, stroke)
                wave(w * 0.2f, w * 0.8f, h * 0.63f, h * 0.06f, tint, stroke)
            }
            SiteType.OTHER -> {
                drawCircle(tint, w * 0.38f, Offset(w * 0.5f, h * 0.5f), style = stroke)
                drawCircle(tint, w * 0.1f, Offset(w * 0.5f, h * 0.5f), style = Fill)
            }
        }
    }
}

/** Vague de deux périodes entre x0 et x1, centrée sur la hauteur [y]. */
private fun DrawScope.wave(x0: Float, x1: Float, y: Float, amp: Float, color: Color, stroke: Stroke) {
    val seg = (x1 - x0) / 4
    val p = Path().apply {
        moveTo(x0, y)
        var x = x0
        for (i in 0 until 4) {
            val dir = if (i % 2 == 0) -1f else 1f
            quadraticTo(x + seg / 2, y + dir * amp * 2, x + seg, y)
            x += seg
        }
    }
    drawPath(p, color, style = stroke)
}
