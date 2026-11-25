package com.sensitivemobility.subatlas

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class ShearwaterBleTest {
    /** Un faux ordinateur : chaque début de téléchargement sert la page suivante du sommaire, en blocs de 128 octets. */
    private class FakeComputer(private val pages: List<ByteArray>) : BleConnection {
        var downloads = 0
            private set
        private var page = ByteArray(0)
        private var offset = 0
        private val replies = ArrayDeque<ByteArray>()
        private val frames = ArrayList<Byte>()
        override val description = "faux"

        override suspend fun write(bytes: ByteArray) {
            frames.addAll(bytes.drop(2))
            if (bytes[1] + 1 < bytes[0]) return
            // Paquet complet : sans la fin SLIP ni les quatre octets d'en-tête (aucun octet à échapper dans ces requêtes).
            val request = frames.toByteArray().let { it.copyOfRange(4, it.size - 1) }
            frames.clear()
            when (request[0].toInt()) {
                0x35 -> {
                    page = pages[minOf(downloads, pages.size - 1)]
                    downloads++
                    offset = 0
                    reply(byteArrayOf(0x75, 0x10, 0x82.toByte()))
                }
                0x36 -> {
                    val data = page.copyOfRange(offset, minOf(offset + 128, page.size))
                    offset += data.size
                    reply(byteArrayOf(0x76, request[1]) + data)
                }
                else -> reply(byteArrayOf(0x77, 0x00))
            }
        }

        private fun reply(payload: ByteArray) {
            val packet = byteArrayOf(0x01, 0xFF.toByte(), (payload.size + 1).toByte(), 0x00) + payload
            val slip = ArrayList<Byte>()
            for (b in packet) {
                when (b.toInt() and 0xFF) {
                    0xC0 -> { slip += 0xDB.toByte(); slip += 0xDC.toByte() }
                    0xDB -> { slip += 0xDB.toByte(); slip += 0xDD.toByte() }
                    else -> slip += b
                }
            }
            slip += 0xC0.toByte()
            replies.addLast(byteArrayOf(1, 0) + slip.toByteArray())
        }

        override suspend fun receive(timeoutMs: Long): ByteArray? = replies.removeFirstOrNull()
        override fun close() {}
    }

    /** Une page de 48 emplacements dont les [count] premiers sont remplis, à partir de la plongée [first]. */
    private fun page(first: Int, count: Int, deleted: Set<Int> = emptySet()): ByteArray {
        val data = ByteArray(ShearwaterBle.MANIFEST_SIZE)
        for (i in 0 until count) {
            val o = i * ShearwaterBle.RECORD_SIZE
            val gone = first + i in deleted
            data[o] = (if (gone) 0x5A else 0xA5).toByte()
            data[o + 1] = (if (gone) 0x23 else 0xC4).toByte()
            data[o + 18] = ((first + i) shr 8).toByte()
            data[o + 19] = (first + i).toByte()
        }
        return data
    }

    private suspend fun numbers(computer: FakeComputer): List<Int> =
        ShearwaterManifestEntry.parse(ShearwaterBle(computer) {}.manifest()).filter { it.valid }.map { it.logNumber }

    @Test
    fun allPagesAreReadUntilTheIncompleteOne() = runTest {
        // Une plongée effacée compte dans la page : la lecture continue.
        val computer = FakeComputer(listOf(page(0, 48, deleted = setOf(5)), page(48, 48), page(96, 7)))
        assertEquals((0 until 103).filter { it != 5 }, numbers(computer))
        assertEquals(3, computer.downloads)
    }

    @Test
    fun aFullLastPageIsFollowedByAnEmptyOne() = runTest {
        val computer = FakeComputer(listOf(page(0, 48), page(0, 0)))
        assertEquals((0 until 48).toList(), numbers(computer))
        assertEquals(2, computer.downloads)
    }

    @Test
    fun aRepeatedPageStopsTheReading() = runTest {
        val computer = FakeComputer(listOf(page(0, 48)))
        assertEquals((0 until 48).toList(), numbers(computer))
        assertEquals(2, computer.downloads)
    }
}
