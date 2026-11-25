package com.sensitivemobility.subatlas

import kotlin.test.Test
import kotlin.test.assertEquals

class ShearwaterBleImportTest {
    private fun record(type: Int, fill: ByteArray.() -> Unit = {}) = ByteArray(32).also { it[0] = type.toByte(); it.fill() }

    private fun ByteArray.put(from: Int, vararg values: Int) = values.forEachIndexed { i, v -> this[from + i] = v.toByte() }

    /** Une plongée au format de l'ordinateur : les dix gaz en mémoire, le masque de ceux qui sont activés, et des échantillons à 20 m. */
    private fun dive(mask: Int, breathed: Pair<Int, Int>): ByteArray {
        val records = listOf(
            record(0x10) { put(20, 90, 30, 21, 19, 11, 32, 21, 19, 11, 9) },
            record(0x11) { put(1, 0, 39, 69, 0, 0, 42, 69, 60) },
            record(0x14) { put(17, mask shr 8, mask and 0xFF) },
        ) + List(30) { record(0x01) { put(1, 0, 200); put(8, breathed.first, breathed.second); put(14, 20); put(28, 0xFF, 0xFF) } }
        return records.reduce { a, b -> a + b }
    }

    private fun tanks(raw: ByteArray): List<Pair<Int, Int>> {
        val manifest = ByteArray(32).apply { put(0, 0xA5, 0xC4); put(4, 0x6B, 0x00, 0x2A, 0x17); put(14, 0x01, 0x2C); put(16, 0, 200) }
        val entry = ShearwaterManifestEntry(0, manifest)
        val converted = ShearwaterBleImport.toDive(entry, raw, "00000000", 6)!!
        return converted.tanks.map { it.o2Percent to it.hePercent }
    }

    @Test
    fun disabledGasesGetNoTank() {
        // Un recycleur : diluant 2 (air) respiré, gaz 2 (30 %) activé en secours, les huit autres désactivés.
        assertEquals(listOf(21 to 0, 30 to 0), tanks(dive(mask = 0x0042, breathed = 21 to 0)))
    }

    @Test
    fun unusedEnabledGasesKeepTheirHelium() {
        // Gaz 4 (19/39), gaz 5 (11/69) et diluant 5 (9/60) activés, l'air respiré.
        assertEquals(listOf(21 to 0, 19 to 39, 11 to 69, 9 to 60), tanks(dive(mask = 0x0218, breathed = 21 to 0)))
    }

    @Test
    fun noEnabledGasLeavesOnlyTheBreathedOne() {
        assertEquals(listOf(32 to 0), tanks(dive(mask = 0, breathed = 32 to 0)))
    }
}
