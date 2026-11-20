package com.sensitivemobility.subatlas

import android.graphics.Bitmap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import java.io.ByteArrayOutputStream

internal actual fun ImageBitmap.toPngBytes(): ByteArray? = runCatching {
    ByteArrayOutputStream().use { out ->
        if (asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, out)) out.toByteArray() else null
    }
}.getOrNull()
