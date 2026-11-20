package com.sensitivemobility.subatlas

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.sensitivemobility.subatlas.shared.resources.Res
import kotlinx.coroutines.CancellationException
import androidx.compose.ui.graphics.drawscope.withTransform
import kotlin.math.abs
import kotlin.math.floor

// Fond de carte embarqué : les terres émergées du monde (Natural Earth 1:50m, domaine public, « Made with Natural Earth »),
// dessinées sous les tuiles. Il sert quand le réseau manque et pendant le chargement des tuiles ; il situe les sites
// (continents, côtes, grandes îles) mais n'a ni routes ni noms. Le fichier `files/land.bin` est produit par
// `tools/make_land.py` (format décrit dans ce script).

private val SeaColor = Color(0xFFCADFE8)
private val LandColor = Color(0xFFF1EEE6)
private val CoastColor = Color(0xFFB4C2C8)

/** Couleur de la mer : c'est aussi le fond de la carte avant que les terres soient lues. */
internal val MapSeaColor: Color = SeaColor

/** Fond neutre, sans terres ni mer, quand les terres embarquées ne sont plus assez précises. */
internal val MapNeutralColor = Color(0xFFE4E9EA)

/**
 * Zoom au-delà duquel les terres ne sont plus dessinées. Natural Earth 1:50m généralise les côtes de l'ordre du kilomètre :
 * au zoom 9, un kilomètre fait environ dix pixels, et un site de bord de mer se retrouverait en pleine mer. Plus près,
 * seuls les tuiles (avec réseau) donnent un fond fiable.
 */
internal const val LAND_MAX_ZOOM = 9.0

internal class WorldLand(
    /** Points de tous les anneaux bout à bout, en coordonnées Mercator normalisées (0 à 1). */
    private val xs: DoubleArray,
    private val ys: DoubleArray,
    /** Index du premier point de chaque anneau, suivi de la fin du dernier. */
    private val starts: IntArray,
    /** Boîte de chaque anneau : minX, minY, maxX, maxY. */
    private val boxes: DoubleArray,
) {
    private val ringCount get() = starts.size - 1

    /**
     * Contour des terres dans un rectangle de [halfW] x [halfH] pixels de demi-côtés autour du point ([originX], [originY])
     * (coordonnées normalisées), le monde faisant [world] pixels de large. Les coordonnées du tracé sont en pixels relatifs
     * à ce point. Les anneaux hors du rectangle ou plus petits qu'un pixel sont ignorés, ainsi que les points à moins d'un
     * pixel du précédent : à petite échelle, on ne trace que ce qui se voit.
     */
    fun pathFor(originX: Double, originY: Double, world: Double, halfW: Double, halfH: Double): Path {
        val path = Path().apply { fillType = PathFillType.EvenOdd }
        val minX = originX - halfW / world
        val maxX = originX + halfW / world
        val minY = originY - halfH / world
        val maxY = originY + halfH / world
        for (r in 0 until ringCount) {
            val b = r * 4
            if (boxes[b + 2] < minX || boxes[b] > maxX || boxes[b + 3] < minY || boxes[b + 1] > maxY) continue
            if ((boxes[b + 2] - boxes[b]) * world < 1.0 && (boxes[b + 3] - boxes[b + 1]) * world < 1.0) continue
            var lastX = 0.0
            var lastY = 0.0
            for (i in starts[r] until starts[r + 1]) {
                val sx = (xs[i] - originX) * world
                val sy = (ys[i] - originY) * world
                if (i == starts[r]) {
                    path.moveTo(sx.toFloat(), sy.toFloat())
                } else if (abs(sx - lastX) < 0.75 && abs(sy - lastY) < 0.75) {
                    continue
                } else {
                    path.lineTo(sx.toFloat(), sy.toFloat())
                }
                lastX = sx
                lastY = sy
            }
            path.close()
        }
        return path
    }
}

/**
 * Dessin des terres. Le tracé est construit pour un niveau de zoom entier et une zone trois fois plus large et haute que la
 * vue, puis seulement déplacé et mis à l'échelle à chaque image : il n'est reconstruit que quand le niveau de zoom entier
 * change, qu'on s'éloigne de la zone construite ou que la vue change de taille. Reconstruit à chaque image, il rendait
 * le déplacement saccadé.
 */
internal class LandLayer(private val land: WorldLand) {
    private var path: Path? = null
    private var level = 0
    private var originX = 0.0
    private var originY = 0.0
    private var builtWidth = 0f
    private var builtHeight = 0f

    fun draw(scope: DrawScope, camera: MapCamera, tilePx: Float) = with(scope) {
        val level0 = floor(camera.zoom).toInt()
        val world0 = worldSize(level0.toDouble(), tilePx)
        var p = path
        if (p == null || level != level0 || builtWidth != size.width || builtHeight != size.height ||
            abs(camera.cx - originX) * world0 > size.width * 0.9 || abs(camera.cy - originY) * world0 > size.height * 0.9
        ) {
            originX = camera.cx
            originY = camera.cy
            level = level0
            builtWidth = size.width
            builtHeight = size.height
            p = land.pathFor(originX, originY, world0, size.width * 1.5, size.height * 1.5)
            path = p
        }
        val world = worldSize(camera.zoom, tilePx)
        val scale = (world / world0).toFloat()
        val tx = ((originX - camera.cx) * world + size.width / 2.0).toFloat()
        val ty = ((originY - camera.cy) * world + size.height / 2.0).toFloat()
        withTransform({
            translate(tx, ty)
            scale(scale, scale, Offset.Zero)
        }) {
            drawPath(p, LandColor)
            drawPath(p, CoastColor, style = Stroke(width = 1.dp.toPx() / scale))
        }
    }
}

/** Terres déjà lues, gardées pour les ouvertures suivantes de la carte. */
internal object WorldLandCache {
    var land: WorldLand? = null
}

/** Lit les terres embarquées ; `null` si le fichier manque ou est abîmé (la carte fonctionne alors sans ce fond). */
internal suspend fun loadWorldLand(): WorldLand? {
    WorldLandCache.land?.let { return it }
    return try {
        decodeWorldLand(Res.readBytes("files/land.bin")).also { WorldLandCache.land = it }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }
}

/** Décode le format de `tools/make_land.py` (écarts « zigzag » en varints, en millièmes de degré). */
internal fun decodeWorldLand(bytes: ByteArray): WorldLand {
    var pos = 0
    fun varint(): Long {
        var result = 0L
        var shift = 0
        while (true) {
            val b = bytes[pos++].toInt() and 0xFF
            result = result or ((b and 0x7F).toLong() shl shift)
            if (b and 0x80 == 0) return result
            shift += 7
            require(shift < 64) { "entier trop long" }
        }
    }
    fun signed(): Long = varint().let { (it ushr 1) xor -(it and 1) }

    val rings = varint().toInt()
    require(rings in 0..100_000) { "nombre d'anneaux invalide" }
    val starts = IntArray(rings + 1)
    var xs = DoubleArray(1 shl 16)
    var ys = DoubleArray(1 shl 16)
    val boxes = DoubleArray(rings * 4)
    var total = 0
    for (r in 0 until rings) {
        val count = varint().toInt()
        require(count in 0..1_000_000) { "anneau invalide" }
        starts[r] = total
        if (total + count > xs.size) {
            val size = maxOf(xs.size * 2, total + count)
            xs = xs.copyOf(size)
            ys = ys.copyOf(size)
        }
        var lon = 0L
        var lat = 0L
        var minX = 1.0
        var minY = 1.0
        var maxX = 0.0
        var maxY = 0.0
        for (k in 0 until count) {
            lon += signed()
            lat += signed()
            val x = mercatorX(lon / 1000.0)
            val y = mercatorY(lat / 1000.0)
            xs[total] = x
            ys[total] = y
            total++
            if (x < minX) minX = x
            if (x > maxX) maxX = x
            if (y < minY) minY = y
            if (y > maxY) maxY = y
        }
        boxes[r * 4] = minX
        boxes[r * 4 + 1] = minY
        boxes[r * 4 + 2] = maxX
        boxes[r * 4 + 3] = maxY
    }
    starts[rings] = total
    return WorldLand(xs, ys, starts, boxes)
}
