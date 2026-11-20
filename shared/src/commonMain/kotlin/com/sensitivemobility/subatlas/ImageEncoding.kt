package com.sensitivemobility.subatlas

import androidx.compose.ui.graphics.ImageBitmap

/** Encode l'image en PNG (sans perte) ; `null` si l'encodage échoue. Sert au partage de l'image d'une plongée. */
internal expect fun ImageBitmap.toPngBytes(): ByteArray?
