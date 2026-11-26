package com.sensitivemobility.subatlas

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MaresDiveTest {
    private fun ByteArray.u16(at: Int, v: Int) {
        this[at] = v.toByte()
        this[at + 1] = (v shr 8).toByte()
    }

    private fun ByteArray.u32(at: Int, v: Long) {
        u16(at, (v and 0xFFFF).toInt())
        u16(at + 2, (v shr 16).toInt())
    }

    /** Un en-tête de version 1.0 : le 5 novembre 2026 à 22:15, deux gaz activés (air et 50 %), 3 minutes de surface. */
    private fun header(samples: Int): ByteArray = ByteArray(0xB8 + 16).apply {
        u16(0, 1)
        this[3] = 1
        u32(0x08, 22L or (15L shl 5) or (5L shl 11) or (11L shl 16) or (2026L shl 20))
        u32(0x0C, 3L shl 13)
        u16(0x20, samples)
        u16(0x22, 214)
        u16(0x24, 120)
        this[0x34] = 1
        fun mix(o2: Int, state: Int) = (o2 or ((100 - o2) shl 7) or (state shl 21)).toLong()
        u32(0x54, mix(21, 2))
        u16(0x58, 20000)
        u16(0x5A, 5000)
        u16(0x5C, 12)
        u32(0x54 + 20, mix(50, 1))
    }

    private fun record(name: String, size: Int, fill: ByteArray.() -> Unit = {}): ByteArray = ByteArray(size).apply {
        name.encodeToByteArray().copyInto(this, 0)
        name.encodeToByteArray().copyInto(this, size - 4)
        fill()
        u16(size - 6, MaresDive.crc16(this, 4, size - 10))
    }

    private fun sample(depthDm: Int) = record("DPRS", 34) {
        u16(4, depthDm)
        u16(4 + 4, 245)
        u16(4 + 0x0A, 40)
    }

    private fun dive(): ByteArray {
        // Une mesure en surface, 40 sous l'eau (200 s), puis 36 en surface (le délai de fin de plongée).
        val depths = listOf(0) + List(40) { if (it == 20) 214 else 120 } + List(36) { 0 }
        val profile = byteArrayOf(0, 0, 0, 1) + record("DSTR", 58) +
            depths.map { sample(it) }.reduce { a, b -> a + b } + record("AIRS", 16) { u16(4, 5000) } + record("DEND", 162)
        return header(depths.size) + profile
    }

    @Test
    fun theHeaderGivesTheDateDepthAndGases() {
        val h = MaresDive.header(dive())!!
        assertEquals("2026-11-05" to "22:15", h.date to h.time)
        assertEquals(21.4, h.maxDepth)
        assertEquals(listOf(21, 50), h.gases.filter { it.state != 0 }.map { it.o2Percent })
        assertEquals(77 * 5 - 180, h.durationSeconds(1))
    }

    @Test
    fun aDiveIsConvertedWithItsCurveAndTanks() {
        val d = MaresDive.toDive(dive(), "123456", "Sirius")!!
        assertEquals("Mares Sirius (123456)", d.computer)
        assertEquals("22:15" to "22:18", d.startTime to d.endTime)
        assertEquals(21.4, d.maxDepth)
        assertEquals(24.5, d.waterTempC)
        // Les 40 mesures sous l'eau et un point de surface de chaque côté.
        assertEquals(42, d.profile!!.depthDm.size)
        assertEquals(listOf(21, 50), d.tanks.map { it.o2Percent })
        assertEquals(200 to 50, d.tanks[0].pressureStartBar to d.tanks[0].pressureEndBar)
        assertEquals(12.0, d.tanks[0].volumeLiters)
        assertTrue(d.fromDevice)
    }

    @Test
    fun aDamagedRecordMakesTheDiveUnreadable() {
        val data = dive()
        // Un octet d'une mesure change : sa somme de contrôle ne correspond plus.
        data[0xB8 + 16 + 4 + 58 + 6] = 99
        assertNull(MaresDive.toDive(data, "123456", "Sirius"))
    }

    @Test
    fun theChecksumIsCrc16Xmodem() {
        assertEquals(0x31C3, MaresDive.crc16("123456789".encodeToByteArray(), 0, 9))
    }
}
