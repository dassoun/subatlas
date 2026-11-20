package com.sensitivemobility.subatlas

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp

// Transition entre écrans : le nouvel écran apparaît en fondu, en glissant légèrement depuis la droite quand on ouvre une
// fiche (on avance dans la pile) et depuis la gauche quand on revient. L'ancien écran disparaît aussitôt, sans être gardé à
// l'écran le temps de l'animation : il n'a donc pas à être recomposé (une fiche supprimée, par exemple, ne se « referme »
// pas deux fois).

/** Sens d'entrée d'un écran : -1 on revient (arrive de la gauche), 1 on avance (arrive de la droite), 0 fondu seul. */
private class Entry(val direction: Int, val first: Boolean)

/** Profondeur de la pile au dernier écran affiché, pour savoir si le suivant avance ou recule. */
private class StackDepth(var last: Int = -1)

/**
 * Affiche [content] pour l'écran [screen] avec l'animation d'entrée. [depth] est la taille de la pile de navigation. Le tout
 * premier écran apparaît sans animation (l'écran de démarrage fait déjà son fondu) ; [animated] à `false` désactive aussi
 * l'animation (carte Google : une vue native ne s'anime pas bien sous une transparence).
 */
@Composable
internal fun ScreenTransition(screen: Any, depth: Int, animated: Boolean = true, content: @Composable () -> Unit) {
    val previous = remember { StackDepth() }
    // Calculé pendant la composition qui affiche l'écran pour la première fois : l'animation part de zéro, sans image de trop.
    val entry = remember(screen) {
        val first = previous.last < 0
        val direction = when {
            first -> 0
            depth > previous.last -> 1
            depth < previous.last -> -1
            else -> 0
        }
        previous.last = depth
        Entry(direction, first)
    }
    val progress = remember(screen) { Animatable(if (entry.first || !animated) 1f else 0f) }
    LaunchedEffect(screen) {
        if (progress.value < 1f) progress.animateTo(1f, tween(240, easing = FastOutSlowInEasing))
    }
    val shift = with(LocalDensity.current) { 36.dp.toPx() }
    Box(
        Modifier.fillMaxSize().graphicsLayer {
            val p = progress.value
            alpha = p
            translationX = (1f - p) * entry.direction * shift
        },
    ) { content() }
}
