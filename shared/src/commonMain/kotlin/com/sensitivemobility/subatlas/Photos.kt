package com.sensitivemobility.subatlas

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.decodeToImageBitmap

// Photos des plongées : une couverture en tête de la fiche, une rangée de vignettes, une visionneuse plein écran où l'on
// feuillette, partage, change la couverture et supprime. Les fichiers (JPEG réduits) sont dans le stockage de l'appli ;
// `AppState.photoBytes` les lit, ici on les décode et on garde les plus récents en mémoire.

/** Mémoire réservée aux images décodées (une image décodée pèse largeur x hauteur x 4 octets : 7,7 Mo pour 1600 x 1200). */
private const val PHOTO_CACHE_BYTES = 64L * 1024 * 1024

/** Images décodées, la moins récemment utilisée partant la première quand la mémoire réservée est pleine. Fil principal seulement. */
private object PhotoCache {
    private val items = LinkedHashMap<String, ImageBitmap>()
    private var bytes = 0L

    private fun size(image: ImageBitmap) = image.width.toLong() * image.height * 4

    operator fun get(key: String): ImageBitmap? {
        val image = items.remove(key) ?: return null
        items[key] = image
        return image
    }

    operator fun set(key: String, image: ImageBitmap) {
        items.remove(key)?.let { bytes -= size(it) }
        items[key] = image
        bytes += size(image)
        while (bytes > PHOTO_CACHE_BYTES && items.size > 1) {
            val oldest = items.keys.first()
            items.remove(oldest)?.let { bytes -= size(it) }
        }
    }
}

/**
 * Une photo de plongée : sa vignette ([thumb]) ou l'image réduite. Le fichier est lu et décodé hors du fil principal ; en
 * attendant, ou si le fichier manque, un fond neutre ([placeholder]) ou rien.
 */
@Composable
internal fun PhotoImage(
    state: AppState,
    photo: DivePhoto,
    thumb: Boolean,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
    placeholder: Boolean = true,
) {
    val key = (if (thumb) "t:" else "f:") + photo.id
    val bitmap by produceState(PhotoCache[key], key) {
        if (value == null) {
            val decoded = withContext(Dispatchers.Default) {
                runCatching { state.photoBytes(photo, thumb)?.decodeToImageBitmap() }.getOrNull()
            }
            if (decoded != null) {
                PhotoCache[key] = decoded
                value = decoded
            }
        }
    }
    val image = bitmap
    if (image != null) {
        Image(image, contentDescription = null, modifier = modifier, contentScale = contentScale)
    } else if (placeholder) {
        Box(modifier.background(MaterialTheme.colorScheme.surfaceVariant))
    }
}

/** La couverture, en tête de la fiche d'une plongée : la première photo, en bandeau 16 par 9. */
@Composable
internal fun PhotoCover(state: AppState, photo: DivePhoto, description: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .semantics { contentDescription = description },
    ) {
        // L'image arrive tout de suite en vignette, puis en pleine définition.
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f)) {
            PhotoImage(state, photo, thumb = true, Modifier.fillMaxSize())
            PhotoImage(state, photo, thumb = false, Modifier.fillMaxSize(), placeholder = false)
        }
    }
}

/**
 * Rangée de vignettes. [onAdd] ajoute une tuile « + » en bout de rangée (la galerie de la plateforme), [adding] la remplace
 * par un indicateur d'attente pendant la lecture des photos. Un appui sur une vignette appelle [onOpen] avec son rang.
 */
@Composable
internal fun PhotoStrip(
    state: AppState,
    photos: List<DivePhoto>,
    title: String,
    onOpen: (Int) -> Unit,
    onAdd: (() -> Unit)? = null,
    adding: Boolean = false,
) {
    val s = LocalStrings.current
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            Text(s.photoCount(photos.size), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            itemsIndexed(photos, key = { _, p -> p.id }) { index, photo ->
                Box(Modifier.size(96.dp).clip(RoundedCornerShape(12.dp)).clickable { onOpen(index) }) {
                    PhotoImage(state, photo, thumb = true, Modifier.fillMaxSize())
                }
            }
            if (onAdd != null) {
                item(key = "add") {
                    Box(
                        Modifier
                            .size(96.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .clickable(enabled = !adding, onClick = onAdd)
                            .semantics { contentDescription = s.photosAdd },
                        contentAlignment = Alignment.Center,
                    ) {
                        if (adding) CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 3.dp)
                        else Pictogram(Picto.PLUS, size = 30.dp, tint = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
    }
}

/**
 * Visionneuse plein écran : on feuillette d'un glissé, un compteur dit où l'on en est. Les actions qui modifient la plongée ne
 * sont proposées que si elles sont fournies (la visionneuse d'un site est en lecture seule) : [onMakeCover] (étoile),
 * [onUnlink] (disque barré : on retire la photo de la plongée, l'originale reste dans la galerie) et [onDeleteFromPhone]
 * (corbeille : on supprime aussi l'originale de la galerie ; elle renvoie `false` si la suppression n'a pas eu lieu, la photo
 * reste alors dans la plongée). Chacune demande confirmation.
 */
@Composable
internal fun PhotoViewer(
    state: AppState,
    photos: List<DivePhoto>,
    startIndex: Int,
    actions: PlatformActions,
    onClose: () -> Unit,
    onMakeCover: ((DivePhoto) -> Unit)? = null,
    onUnlink: ((DivePhoto) -> Unit)? = null,
    onDeleteFromPhone: (suspend (DivePhoto) -> Boolean)? = null,
) {
    val s = LocalStrings.current
    val scope = rememberCoroutineScope()
    var confirmUnlink by remember { mutableStateOf<DivePhoto?>(null) }
    var confirmDeleteDevice by remember { mutableStateOf<DivePhoto?>(null) }
    var deleteFailed by remember { mutableStateOf(false) }
    var followId by remember { mutableStateOf<String?>(null) }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        // La dernière photo supprimée : plus rien à voir, la visionneuse se ferme.
        LaunchedEffect(photos.isEmpty()) { if (photos.isEmpty()) onClose() }
        if (photos.isEmpty()) return@Dialog
        val pager = rememberPagerState(initialPage = startIndex.coerceIn(0, photos.lastIndex)) { photos.size }
        val current = photos[pager.currentPage.coerceIn(0, photos.lastIndex)]
        // Une photo devenue la couverture change de rang : la visionneuse la suit (une fois la nouvelle liste reçue).
        LaunchedEffect(photos, followId) {
            val id = followId ?: return@LaunchedEffect
            val index = photos.indexOfFirst { it.id == id }
            if (index >= 0) pager.scrollToPage(index)
            followId = null
        }
        Surface(Modifier.fillMaxSize(), color = Color.Black) {
            Box(Modifier.fillMaxSize()) {
                HorizontalPager(pager, Modifier.fillMaxSize(), key = { photos.getOrNull(it)?.id ?: it }) { page ->
                    val photo = photos[page]
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        PhotoImage(state, photo, thumb = true, Modifier.fillMaxSize(), ContentScale.Fit, placeholder = false)
                        PhotoImage(state, photo, thumb = false, Modifier.fillMaxSize(), ContentScale.Fit, placeholder = false)
                    }
                }
                // Barre du haut : fermer, rang, actions. Un voile sombre garde les icônes lisibles sur une photo claire.
                Row(
                    Modifier
                        .align(Alignment.TopCenter)
                        .fillMaxWidth()
                        .background(Color.Black.copy(alpha = 0.45f))
                        .padding(horizontal = 4.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ViewerButton(Picto.CLOSE, s.photoClose, onClose)
                    Text(
                        "${pager.currentPage + 1} / ${photos.size}",
                        Modifier.weight(1f).padding(start = 8.dp),
                        color = Color.White,
                        fontWeight = FontWeight.SemiBold,
                    )
                    if (onMakeCover != null && pager.currentPage != 0) {
                        ViewerButton(Picto.STAR, s.photoMakeCover) {
                            onMakeCover(current)
                            followId = current.id
                        }
                    }
                    ViewerButton(Picto.SHARE, s.photoShare) {
                        scope.launch {
                            val bytes = withContext(Dispatchers.Default) { state.photoBytes(current, thumb = false) }
                            if (bytes != null) actions.shareFile("subatlas-photo-${current.id}.jpg", "image/jpeg", bytes)
                        }
                    }
                    if (onUnlink != null) ViewerButton(Picto.REMOVE, s.photoUnlink) { confirmUnlink = current }
                    if (onDeleteFromPhone != null) ViewerButton(Picto.TRASH, s.photoDeleteDevice) { confirmDeleteDevice = current }
                }
            }
        }
        confirmUnlink?.let { photo ->
            ConfirmDialog(
                s.photoUnlinkConfirm,
                onConfirm = {
                    confirmUnlink = null
                    onUnlink?.invoke(photo)
                },
                onDismiss = { confirmUnlink = null },
                confirmLabel = s.photoUnlinkAction,
            )
        }
        confirmDeleteDevice?.let { photo ->
            ConfirmDialog(
                s.photoDeleteDeviceConfirm,
                onConfirm = {
                    confirmDeleteDevice = null
                    // Le système demande sa propre confirmation ; refusée ou impossible, la photo reste dans la plongée.
                    scope.launch { if (onDeleteFromPhone?.invoke(photo) == false) deleteFailed = true }
                },
                onDismiss = { confirmDeleteDevice = null },
            )
        }
        if (deleteFailed) {
            AlertDialog(
                onDismissRequest = { deleteFailed = false },
                text = { Text(s.photoDeleteDeviceFailed) },
                confirmButton = { TextButton(onClick = { deleteFailed = false }) { Text(s.ok) } },
            )
        }
    }
}

@Composable
private fun ViewerButton(icon: Picto, description: String, onClick: () -> Unit) {
    Box(
        Modifier
            .size(48.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Pictogram(icon, size = 24.dp, tint = Color.White)
    }
}
