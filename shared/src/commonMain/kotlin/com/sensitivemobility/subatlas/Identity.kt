package com.sensitivemobility.subatlas

import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/** Nouvel identifiant stable (UUID v4, en minuscules) : celui d'une plongée ou d'un site partagé avec SubAtlas.com. */
@OptIn(ExperimentalUuidApi::class)
internal fun newUuid(): String = Uuid.random().toString()

/** Instant courant en UTC, ISO 8601 (« 2026-11-23T10:15:00.123Z »). */
internal fun nowIso(): String = Clock.System.now().toString()

/** Lit un instant ISO 8601 ; `null` s'il est vide ou illisible. */
internal fun parseInstant(text: String): Instant? = if (text.isBlank()) null else runCatching { Instant.parse(text) }.getOrNull()

/** `true` si l'instant [a] est strictement postérieur à [b] (un instant illisible est considéré comme le plus ancien). */
internal fun isLater(a: String, b: String): Boolean {
    val x = parseInstant(a)
    val y = parseInstant(b)
    return when {
        x == null -> false
        y == null -> true
        else -> x > y
    }
}
