package com.sensitivemobility.subatlas

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Côté le plus long d'une photo gardée dans l'appli, en pixels : assez pour un plein écran de téléphone, 10 fois moins lourd qu'une photo d'appareil. */
internal const val PHOTO_MAX_SIDE = 1600

/** Côté le plus long d'une vignette (grille de la fiche, aperçus). */
internal const val PHOTO_THUMB_SIDE = 400

private const val PHOTO_QUALITY = 85
private const val THUMB_QUALITY = 80

/**
 * Lit la photo [uri] (obtenue de la galerie du système) et la réduit : image JPEG d'au plus [PHOTO_MAX_SIDE] pixels et sa
 * vignette. L'orientation de l'appareil (EXIF) est appliquée aux pixels, puis le fichier est réencodé : l'image gardée est
 * toujours « droite » et ne porte plus de métadonnées (lieu GPS de la prise de vue compris). `null` si le fichier n'est pas
 * une image lisible. Ne garde jamais en mémoire l'image d'origine en pleine taille : elle est décodée directement réduite.
 */
internal fun preparePhoto(context: Context, uri: Uri): PreparedPhoto? = runCatching {
    val resolver = context.contentResolver

    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null

    val orientation = resolver.openInputStream(uri)?.use {
        ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
    } ?: ExifInterface.ORIENTATION_NORMAL

    // Décodage directement réduit (puissance de deux) : la plus grande réduction qui laisse encore au moins PHOTO_MAX_SIDE.
    var sample = 1
    while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= PHOTO_MAX_SIDE) sample *= 2
    val decoded = resolver.openInputStream(uri)?.use {
        BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
    } ?: return@runCatching null

    // Orientation de l'appareil, puis mise à l'échelle finale, en une seule opération.
    val matrix = Matrix()
    when (orientation) {
        ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
        ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
        ExifInterface.ORIENTATION_FLIP_VERTICAL -> { matrix.postRotate(180f); matrix.postScale(-1f, 1f) }
        ExifInterface.ORIENTATION_TRANSPOSE -> { matrix.postRotate(90f); matrix.postScale(-1f, 1f) }
        ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
        ExifInterface.ORIENTATION_TRANSVERSE -> { matrix.postRotate(-90f); matrix.postScale(-1f, 1f) }
        ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(-90f)
    }
    val scale = min(1f, PHOTO_MAX_SIDE.toFloat() / max(decoded.width, decoded.height))
    matrix.postScale(scale, scale)
    val image = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
    if (image !== decoded) decoded.recycle()

    val full = image.toJpeg(PHOTO_QUALITY)
    val thumbScale = PHOTO_THUMB_SIDE.toFloat() / max(image.width, image.height)
    val thumbImage = if (thumbScale < 1f) {
        Bitmap.createScaledBitmap(image, (image.width * thumbScale).roundToInt().coerceAtLeast(1), (image.height * thumbScale).roundToInt().coerceAtLeast(1), true)
    } else {
        image
    }
    val thumb = thumbImage.toJpeg(THUMB_QUALITY)
    val result = PreparedPhoto(full, thumb, image.width, image.height)
    if (thumbImage !== image) thumbImage.recycle()
    image.recycle()
    result
}.getOrNull()

private fun Bitmap.toJpeg(quality: Int): ByteArray =
    ByteArrayOutputStream().also { compress(Bitmap.CompressFormat.JPEG, quality, it) }.toByteArray()
