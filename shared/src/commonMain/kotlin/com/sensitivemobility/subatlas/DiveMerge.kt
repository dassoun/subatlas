package com.sensitivemobility.subatlas

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.unit.dp

/** Deux plongées séparées de moins de ce nombre de minutes peuvent être fusionnées (plongée interrompue par une remontée). */
internal const val MERGE_GAP_MINUTES = 30

/** Identifiants de source d'une plongée (plusieurs après une fusion de plongées importées). */
internal fun Dive.importKeys(): List<String> = importKey.split('\n').filter { it.isNotBlank() }

/**
 * Fusionne [later] dans [earlier] (la plus ancienne) : l'heure de début est celle de la première, la fin celle de la
 * seconde, la courbe est mise bout à bout (l'intervalle de surface comptant pour une profondeur nulle). Pour chaque
 * champ, la valeur de la première plongée est conservée, à défaut celle de la seconde.
 */
internal fun mergeDives(earlier: Dive, later: Dive): Dive {
    val a = earlier
    val b = later
    val aMin = a.durationMinutes
    val bMin = b.durationMinutes
    val aAvg = a.avgDepthMeters
    val bAvg = b.avgDepthMeters
    val maxDepth = listOfNotNull(a.maxDepth, b.maxDepth).maxOrNull()
    val avgDepth = if (aAvg != null && bAvg != null && aMin != null && bMin != null && aMin + bMin > 0) {
        (aAvg * aMin + bAvg * bMin) / (aMin + bMin)
    } else {
        aAvg ?: bAvg
    }
    val tanks = if (a.tanks.size == b.tanks.size) {
        // Mêmes bouteilles : la pression de départ est celle de la première plongée, la pression finale celle de la seconde.
        a.tanks.zip(b.tanks) { x, y ->
            val xu = x.usedMinutes
            val yu = y.usedMinutes
            x.copy(
                volumeLiters = x.volumeLiters ?: y.volumeLiters,
                pressureStartBar = x.pressureStartBar ?: y.pressureStartBar,
                pressureEndBar = y.pressureEndBar ?: x.pressureEndBar,
                avgDepthMeters = x.avgDepthMeters ?: y.avgDepthMeters,
                usedMinutes = if (xu != null && yu != null) xu + yu else xu ?: yu,
            )
        }
    } else {
        a.tanks + b.tanks
    }
    val gapSeconds = run {
        val e = minutesOfDay(a.endTime)
        val s = minutesOfDay(b.startTime)
        if (e != null && s != null && s > e) (s - e) * 60 else 0
    }
    return a.copy(
        endTime = b.endTime ?: a.endTime,
        tanks = tanks,
        maxDepthMeters = maxDepth,
        avgDepthMeters = avgDepth,
        waterTempC = a.waterTempC ?: b.waterTempC,
        visibilityMeters = a.visibilityMeters ?: b.visibilityMeters,
        weightKg = a.weightKg ?: b.weightKg,
        suit = a.suit.ifBlank { b.suit },
        suitType = a.suitType ?: b.suitType,
        suitThicknessMm = a.suitThicknessMm ?: b.suitThicknessMm,
        buddy = a.buddy.ifBlank { b.buddy },
        buddyFromContacts = if (a.buddy.isNotBlank()) a.buddyFromContacts else b.buddyFromContacts,
        buddyPhone = a.buddyPhone.ifBlank { b.buddyPhone },
        buddyEmail = a.buddyEmail.ifBlank { b.buddyEmail },
        notes = listOf(a.notes, b.notes).filter { it.isNotBlank() }.distinct().joinToString("\n"),
        kind = a.kind ?: b.kind,
        kindOther = a.kindOther.ifBlank { b.kindOther },
        group = (a.group + b.group).distinctBy { it.name },
        computer = a.computer.ifBlank { b.computer },
        importKey = (a.importKeys() + b.importKeys()).distinct().joinToString("\n"),
        importModified = a.importModified + b.importModified,
        profile = mergeProfiles(a.profile, b.profile, gapSeconds) ?: a.profile ?: b.profile,
    )
}

private fun mergeProfiles(a: DiveProfile?, b: DiveProfile?, gapSeconds: Int): DiveProfile? {
    if (a == null || b == null || a.intervalSeconds != b.intervalSeconds || a.intervalSeconds <= 0) return null
    val gap = gapSeconds / a.intervalSeconds
    fun series(x: List<Int>, y: List<Int>, fill: Int): List<Int> =
        if (x.size == a.depthDm.size && y.size == b.depthDm.size) x + List(gap) { fill } + y else emptyList()
    return DiveProfile(
        intervalSeconds = a.intervalSeconds,
        depthDm = a.depthDm + List(gap) { 0 } + b.depthDm,
        tempDc = series(a.tempDc, b.tempDc, a.tempDc.lastOrNull() ?: 0),
        pressureBar = series(a.pressureBar, b.pressureBar, a.pressureBar.lastOrNull() ?: 0),
        ndlMin = series(a.ndlMin, b.ndlMin, 99),
        decoStopM = series(a.decoStopM, b.decoStopM, 0),
        ttsMin = series(a.ttsMin, b.ttsMin, 0),
        ppo2Cb = series(a.ppo2Cb, b.ppo2Cb, 21),
        cnsPct = series(a.cnsPct, b.cnsPct, a.cnsPct.lastOrNull() ?: 0),
    )
}

/** Pictogramme de fusion (deux branches qui se rejoignent en une), barré quand il signifie « ne pas fusionner ». */
@Composable
internal fun MergeIcon(crossed: Boolean, modifier: Modifier = Modifier, iconSize: Dp = 24.dp, tint: Color = MaterialTheme.colorScheme.primary) {
    Canvas(modifier.size(iconSize)) {
        val w = size.width
        val h = size.height
        val stroke = (iconSize.value / 12f).dp.toPx().coerceAtLeast(1.5f)
        fun line(x1: Float, y1: Float, x2: Float, y2: Float, width: Float = stroke) =
            drawLine(tint, Offset(x1 * w, y1 * h), Offset(x2 * w, y2 * h), width, StrokeCap.Round)
        line(0.15f, 0.08f, 0.5f, 0.45f)
        line(0.85f, 0.08f, 0.5f, 0.45f)
        line(0.5f, 0.45f, 0.5f, 0.92f)
        line(0.34f, 0.76f, 0.5f, 0.94f)
        line(0.66f, 0.76f, 0.5f, 0.94f)
        if (crossed) line(0.05f, 0.95f, 0.95f, 0.05f, stroke * 1.3f)
    }
}

/** Petite pastille ronde discrète portant un [MergeIcon]. */
@Composable
internal fun MergeChip(crossed: Boolean, description: String, onClick: () -> Unit) {
    Box(
        Modifier
            .size(30.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.16f))
            .clickable(onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        MergeIcon(crossed, iconSize = 16.dp)
    }
}

/** Pastille unique à côté de la date : un menu propose de fusionner avec la plongée précédente ou de ne plus le proposer. */
@Composable
internal fun MergeMenuChip(onMerge: () -> Unit, onNoMerge: () -> Unit) {
    val s = LocalStrings.current
    var open by remember { mutableStateOf(false) }
    Box {
        MergeChip(crossed = false, description = s.mergePrevious) { open = true }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text(s.mergePrevious) },
                leadingIcon = { MergeIcon(crossed = false, iconSize = 18.dp) },
                onClick = { open = false; onMerge() },
            )
            DropdownMenuItem(
                text = { Text(s.noMerge) },
                leadingIcon = { MergeIcon(crossed = true, iconSize = 18.dp) },
                onClick = { open = false; onNoMerge() },
            )
        }
    }
}
