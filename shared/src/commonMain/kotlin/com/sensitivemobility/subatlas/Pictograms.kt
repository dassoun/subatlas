package com.sensitivemobility.subatlas

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em

// Pictogrammes de l'appli, tracés en traits : même épaisseur, bouts arrondis, couleur de contenu du thème. Ils remplacent les
// emoji, dont l'aspect change d'un téléphone à l'autre et qui jurent avec le reste de l'interface. Seuls les drapeaux de pays
// restent des emoji : ce sont des données (le pays saisi), pas des décors.
//
// Les tracés sont du SVG sur une grille de 24 x 24, lus par `PathParser` de Compose. La forme « principale » d'un pictogramme
// (marquée `accent`) peut recevoir un léger remplissage (onglet actif, pastilles de chiffres). Pour en ajouter un : une entrée
// dans [Picto] et ses tracés dans `Picto.shapes()`.

internal enum class Picto {
    // Onglets
    HOME, MASK, PIN, ANCHOR, LIST, BUOY, GEAR,

    // Carte et lieux
    MAP, SATELLITE, GLOBE,

    // Actions et états
    SEARCH, PERSON, WARNING, CHECK_CIRCLE, CLOSE, PLUS, MINUS, CHEVRON_UP, CHEVRON_DOWN,

    // Statistiques
    ARROW_DOWN, STOPWATCH, THERMOMETER, STAR, EYE, GAUGE, DEPTH_SPAN,

    // Partage et photos
    IMAGE, SHARE, REMOVE, TRASH,
}

/** Un tracé du pictogramme ; [accent] : forme principale, remplie légèrement quand on le demande. */
private class Shape(val path: String, val accent: Boolean = false, val evenOdd: Boolean = false)

/** Un tracé déjà lu. */
private class ParsedShape(val path: Path, val accent: Boolean)

/** Cercle de centre ([cx], [cy]) et de rayon [r], en données de tracé SVG. */
private fun circle(cx: Double, cy: Double, r: Double): String =
    "M${cx - r} ${cy}a$r $r 0 1 0 ${2 * r} 0a$r $r 0 1 0 ${-2 * r} 0z"

private fun Picto.shapes(): List<Shape> = when (this) {
    Picto.HOME -> listOf(
        Shape("M4 11 12 4l8 7v8.2a1.3 1.3 0 0 1-1.3 1.3H5.3A1.3 1.3 0 0 1 4 19.2z", accent = true),
        Shape("M10 20.5v-5a1 1 0 0 1 1-1h2a1 1 0 0 1 1 1v5"),
    )
    // Masque de plongée avec sa pointe de nez, ses sangles, un reflet sur la vitre et le tuba.
    Picto.MASK -> listOf(
        Shape(
            "M3 8.5a2 2 0 0 1 2-2H15a2 2 0 0 1 2 2V12C17 15 15 17 12.3 17C11.4 17 11 15.7 10 15.7S8.6 17 7.7 17C5 17 3 15 3 12z",
            accent = true,
        ),
        Shape("M3 10H1.5M17 10h3.5"),
        Shape("M20.5 3V14a2 2 0 0 1-2 2"),
        Shape("M19 3h3"),
        Shape("M5.8 11c.3-1.2 1.1-2 2.2-2.2"),
    )
    Picto.PIN -> listOf(
        Shape("M12 21.2s-6.5-5.6-6.5-11.2a6.5 6.5 0 0 1 13 0c0 5.6-6.5 11.2-6.5 11.2z", accent = true),
        Shape(circle(12.0, 10.0, 2.4)),
    )
    Picto.ANCHOR -> listOf(
        Shape(circle(12.0, 5.0, 2.2)),
        Shape("M12 7.2v13.3M8.5 10.5h7"),
        Shape("M4.5 13.5c.6 3.8 3.6 7 7.5 7s6.9-3.2 7.5-7"),
        Shape("M2.6 12.2l1.9 1.3 1.9-1.8M21.4 12.2l-1.9 1.3-1.9-1.8"),
    )
    Picto.LIST -> listOf(
        Shape("M7 3.5h10A2.5 2.5 0 0 1 19.5 6v12a2.5 2.5 0 0 1-2.5 2.5H7A2.5 2.5 0 0 1 4.5 18V6A2.5 2.5 0 0 1 7 3.5z", accent = true),
        Shape("M8 8.7l1.2 1.2 2.3-2.4M13.6 8.8h2.8M8 15.2l1.2 1.2 2.3-2.4M13.6 15.3h2.8"),
    )
    // Bouée : anneau (forme principale, évidée au centre) et quatre bandes.
    Picto.BUOY -> listOf(
        Shape("M12 3a9 9 0 1 0 0 18 9 9 0 0 0 0-18zm0 5.5a3.5 3.5 0 1 1 0 7 3.5 3.5 0 0 1 0-7z", accent = true, evenOdd = true),
        Shape("M5.64 5.64l3.89 3.89M18.36 5.64l-3.89 3.89M5.64 18.36l3.89-3.89M18.36 18.36l-3.89-3.89"),
    )
    // Engrenage à huit dents (contour calculé : dents alternant deux rayons) et son axe.
    Picto.GEAR -> listOf(
        Shape(
            "M10.53 5.87 L10.76 3.19 L13.24 3.19 L13.47 5.87 L15.29 6.63 L17.36 4.89 L19.11 6.64 L17.37 8.71 L18.13 10.53 " +
                "L20.81 10.76 L20.81 13.24 L18.13 13.47 L17.37 15.29 L19.11 17.36 L17.36 19.11 L15.29 17.37 L13.47 18.13 " +
                "L13.24 20.81 L10.76 20.81 L10.53 18.13 L8.71 17.37 L6.64 19.11 L4.89 17.36 L6.63 15.29 L5.87 13.47 " +
                "L3.19 13.24 L3.19 10.76 L5.87 10.53 L6.63 8.71 L4.89 6.64 L6.64 4.89 L8.71 6.63z",
            accent = true,
        ),
        Shape(circle(12.0, 12.0, 2.7)),
    )
    // Carte dépliée en trois panneaux.
    Picto.MAP -> listOf(
        Shape("M3 6.5l5.5-2 7 2 5.5-2v13l-5.5 2-7-2-5.5 2z", accent = true),
        Shape("M8.5 4.5v13M15.5 6.5v13"),
    )
    // Satellite : corps (losange), deux panneaux solaires, liaisons et antenne, le tout incliné à 45°.
    Picto.SATELLITE -> listOf(
        Shape("M8.18 12 L12 8.18 L15.82 12 L12 15.82z", accent = true),
        Shape("M3.37 17.23 L6.91 13.7 L10.3 17.09 L6.77 20.63z"),
        Shape("M13.7 6.91 L17.23 3.37 L20.63 6.77 L17.09 10.3z"),
        Shape("M8.61 15.39L10.09 13.91M13.91 10.09L15.39 8.61M13.91 13.91L15.96 15.96"),
    )
    Picto.GLOBE -> listOf(
        Shape(circle(12.0, 12.0, 9.0), accent = true),
        Shape("M3 12h18"),
        Shape("M12 3c-2.8 2.6-4 5.7-4 9s1.2 6.4 4 9c2.8-2.6 4-5.7 4-9s-1.2-6.4-4-9z"),
    )
    Picto.SEARCH -> listOf(
        Shape(circle(10.5, 10.5, 6.2), accent = true),
        Shape("M15.2 15.2 20.5 20.5"),
    )
    Picto.PERSON -> listOf(
        Shape(circle(12.0, 8.0, 3.6), accent = true),
        Shape("M5 20.5c0-3.9 3.1-6.6 7-6.6s7 2.7 7 6.6"),
    )
    Picto.WARNING -> listOf(
        Shape("M12 4 21.5 19.8H2.5z", accent = true),
        Shape("M12 9.8v4.4"),
        Shape("M12 17.2h.01"),
    )
    Picto.CHECK_CIRCLE -> listOf(
        Shape(circle(12.0, 12.0, 9.0), accent = true),
        Shape("M7.8 12.4l2.9 2.9 5.5-5.8"),
    )
    Picto.CLOSE -> listOf(Shape("M6 6l12 12M18 6 6 18"))
    Picto.PLUS -> listOf(Shape("M12 5v14M5 12h14"))
    Picto.MINUS -> listOf(Shape("M5 12h14"))
    Picto.CHEVRON_DOWN -> listOf(Shape("M6 9.5l6 6 6-6"))
    Picto.CHEVRON_UP -> listOf(Shape("M6 14.5l6-6 6 6"))
    Picto.ARROW_DOWN -> listOf(Shape("M12 3.5v12.5M7 11.5l5 5 5-5M5 20.5h14"))
    Picto.STOPWATCH -> listOf(
        Shape(circle(12.0, 13.6, 7.4), accent = true),
        Shape("M10 2.8h4M12 2.8v3.4M12 13.6V9.8M18.2 7.4l1.4-1.4"),
    )
    Picto.THERMOMETER -> listOf(
        Shape("M10 5a2 2 0 0 1 4 0v8.6a4.3 4.3 0 1 1-4 0z", accent = true),
        Shape("M12 9.5v7.6"),
        Shape(circle(12.0, 17.6, 1.3)),
    )
    Picto.STAR -> listOf(
        Shape("M12 3.4 L14.35 9.36 L20.75 9.76 L15.8 13.84 L17.41 20.04 L12 16.6 L6.59 20.04 L8.2 13.84 L3.25 9.76 L9.65 9.36z", accent = true),
    )
    // Œil : la visibilité.
    Picto.EYE -> listOf(
        Shape("M2.4 12s3.5-6.6 9.6-6.6S21.6 12 21.6 12s-3.5 6.6-9.6 6.6S2.4 12 2.4 12z", accent = true),
        Shape(circle(12.0, 12.0, 3.1)),
    )
    // Manomètre : un arc de cadran, son aiguille et son axe (la consommation d'air).
    Picto.GAUGE -> listOf(
        Shape("M4.2 18A8.6 8.6 0 1 1 19.8 18z", accent = true),
        Shape("M12 13.8 15.7 9.1"),
        Shape(circle(12.0, 13.8, 1.3)),
        Shape("M12 5.2v1.7M5.3 9.1l1.5.9M18.7 9.1l-1.5.9"),
    )
    // Image : un cadre, un soleil et deux collines (partage d'une plongée en image).
    Picto.IMAGE -> listOf(
        Shape("M5.5 4h13A1.5 1.5 0 0 1 20 5.5v13a1.5 1.5 0 0 1-1.5 1.5h-13A1.5 1.5 0 0 1 4 18.5v-13A1.5 1.5 0 0 1 5.5 4z", accent = true),
        Shape(circle(9.0, 9.2, 1.7)),
        Shape("M4 16.5l4.6-4.4 3.4 3.2 3-2.8 5 4.7"),
    )
    // Partager : une flèche qui sort d'une boîte ouverte.
    Picto.SHARE -> listOf(
        Shape("M12 15V3.6M8 7.4l4-3.8 4 3.8"),
        Shape("M7.5 10.5H6.5A1.5 1.5 0 0 0 5 12v6.5A1.5 1.5 0 0 0 6.5 20h11a1.5 1.5 0 0 0 1.5-1.5V12a1.5 1.5 0 0 0-1.5-1.5h-1"),
    )
    // Corbeille : couvercle, poignée, cuve et deux sillons (on efface pour de bon).
    Picto.TRASH -> listOf(
        Shape("M4.5 7h15M9.3 7V4.9a.9.9 0 0 1 .9-.9h3.6a.9.9 0 0 1 .9.9V7"),
        Shape("M6.6 7l.9 11.8A1.6 1.6 0 0 0 9.1 20.3h5.8a1.6 1.6 0 0 0 1.6-1.5L17.4 7", accent = true),
        Shape("M10.2 11v5.4M13.8 11v5.4"),
    )
    // Retirer : un disque barré d'un trait (on détache, on n'efface pas).
    Picto.REMOVE -> listOf(
        Shape(circle(12.0, 12.0, 9.0), accent = true),
        Shape("M7.8 12h8.4"),
    )
    // Double flèche verticale entre deux repères : la profondeur moyenne, entre la surface et le fond.
    Picto.DEPTH_SPAN -> listOf(
        Shape("M12 4v16M8.4 7.6 12 4l3.6 3.6M8.4 16.4 12 20l3.6-3.6"),
        Shape("M4 4h3M17 4h3M4 20h3M17 20h3"),
    )
}

/** Tracés déjà lus, gardés pour toute l'appli (lus et dessinés par le fil principal seulement). */
private val parsedShapes = HashMap<Picto, List<ParsedShape>>()

private fun Picto.parsed(): List<ParsedShape> = parsedShapes.getOrPut(this) {
    shapes().map { shape ->
        ParsedShape(
            PathParser().parsePathString(shape.path).toPath().apply { if (shape.evenOdd) fillType = PathFillType.EvenOdd },
            shape.accent,
        )
    }
}

/**
 * Un pictogramme de [size] (ou de la taille que lui donne [modifier] si [size] est [Dp.Unspecified]), dans la couleur [tint]
 * (par défaut la couleur de contenu du moment : celle du bouton, de l'onglet, du texte qui l'entoure). [filled] ajoute un
 * léger remplissage sur la forme principale.
 */
@Composable
internal fun Pictogram(
    icon: Picto,
    modifier: Modifier = Modifier,
    size: Dp = 24.dp,
    tint: Color = LocalContentColor.current,
    filled: Boolean = false,
) {
    val shapes = icon.parsed()
    Canvas(if (size == Dp.Unspecified) modifier else modifier.size(size)) {
        val unit = this.size.width / 24f
        scale(unit, unit, pivot = Offset.Zero) {
            for (shape in shapes) {
                if (filled && shape.accent) drawPath(shape.path, tint.copy(alpha = 0.22f), style = Fill)
                drawPath(shape.path, tint, style = Stroke(width = 1.8f, cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
        }
    }
}

/** Un texte précédé (ou suivi, avec [iconAtEnd]) d'un pictogramme à la taille de la police, dans la même couleur. */
@Composable
internal fun PictoText(
    text: String,
    icon: Picto,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalTextStyle.current,
    color: Color = Color.Unspecified,
    iconAtEnd: Boolean = false,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip,
) {
    val tint = if (color != Color.Unspecified) color else if (style.color != Color.Unspecified) style.color else LocalContentColor.current
    val content = buildAnnotatedString {
        if (!iconAtEnd) {
            appendInlineContent("picto", "•")
            append("  ")
        }
        append(text)
        if (iconAtEnd) {
            append("  ")
            appendInlineContent("picto", "•")
        }
    }
    Text(
        content,
        modifier,
        color = color,
        style = style,
        maxLines = maxLines,
        overflow = overflow,
        inlineContent = mapOf(
            "picto" to InlineTextContent(Placeholder(1.2.em, 1.2.em, PlaceholderVerticalAlign.TextCenter)) {
                Pictogram(icon, Modifier.fillMaxSize(), size = Dp.Unspecified, tint = tint)
            },
        ),
    )
}
