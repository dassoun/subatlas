package com.sensitivemobility.subatlas

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.sensitivemobility.subatlas.shared.resources.Res
import com.sensitivemobility.subatlas.shared.resources.logo_symbol
import com.sensitivemobility.subatlas.shared.resources.logo_wordmark
import org.jetbrains.compose.resources.painterResource

/** Le bleu nuit du logo : le même que celui de l'écran de démarrage système, pour que la transition soit invisible. */
val SplashBackground = Color(0xFF08111C)

// Cadre du logo (dessin de 1024 unités), à la même échelle que l'icône de l'écran de démarrage système
// (androidApp/.../ic_splash_mark.xml : anneau de 150 dp de diamètre, soit 150 / 410 = 0,366 dp par unité).
// Valeurs produites par tools/svg_to_vector.py. Le décalage place le centre de l'anneau au centre de l'écran, comme
// l'icône système : le symbole ne bouge pas quand l'appli prend le relais.
private val LogoSize = 374.6.dp
private val LogoOffsetY = 51.2.dp

/**
 * Écran de démarrage de l'appli : le logo complet (avec « Sensitive Mobility ») sur le fond du logo. Il prend le relais de
 * l'écran de démarrage système, qui ne peut afficher que le symbole (Android le découpe dans un disque) : le symbole reste
 * exactement en place et seul le nom apparaît, en fondu.
 */
@Composable
fun SplashScreen() {
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { shown = true }
    val wordmarkAlpha by animateFloatAsState(targetValue = if (shown) 1f else 0f, animationSpec = tween(durationMillis = 450))

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(SplashBackground)
            // L'écran couvre l'appli : on absorbe les appuis pour qu'ils ne la traversent pas.
            .pointerInput(Unit) {},
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(LogoSize).offset(y = LogoOffsetY)) {
            Image(
                painter = painterResource(Res.drawable.logo_symbol),
                contentDescription = "Sensitive Mobility",
                modifier = Modifier.fillMaxSize(),
            )
            Image(
                painter = painterResource(Res.drawable.logo_wordmark),
                contentDescription = null,
                modifier = Modifier.fillMaxSize().alpha(wordmarkAlpha),
            )
        }
    }
}
