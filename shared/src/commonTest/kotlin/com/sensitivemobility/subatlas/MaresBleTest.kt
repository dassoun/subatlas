package com.sensitivemobility.subatlas

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class MaresBleTest {
    /** Un faux Sirius : un seul objet, servi en paquets de [chunk] octets. */
    private class FakeSirius(private val content: ByteArray, private val chunk: Int = 241) : BleConnection {
        private val replies = ArrayDeque<ByteArray>()
        private var command = 0
        private var expectsData = false
        private var offset = 0
        override val description = "faux"

        override suspend fun write(bytes: ByteArray) {
            if (expectsData) {
                // Les données de la demande d'objet : on répond avec la taille, et la fin de la trame.
                expectsData = false
                val size = content.size
                val head = byteArrayOf(0x41, bytes[1], bytes[2], bytes[3], size.toByte(), (size shr 8).toByte(), 0, 0)
                replies.addLast(head + ByteArray(8) + 0xEA.toByte())
                offset = 0
                return
            }
            command = bytes[0].toInt() and 0xFF
            check(bytes[1].toInt() and 0xFF == command xor 0xA5)
            when (command) {
                0xBF -> {
                    expectsData = true
                    replies.addLast(byteArrayOf(0xAA.toByte()))
                }
                0xAC, 0xFE -> {
                    val data = content.copyOfRange(offset, minOf(offset + chunk, content.size))
                    offset += data.size
                    val toggle = if (command == 0xAC) 0x00 else 0x10
                    replies.addLast(byteArrayOf(0xAA.toByte(), toggle.toByte()) + data + 0xEA.toByte())
                }
            }
        }

        override suspend fun receive(timeoutMs: Long): ByteArray? = replies.removeFirstOrNull()
        override fun close() {}
    }

    @Test
    fun anObjectIsRebuiltFromItsSegments() = runTest {
        val content = ByteArray(700) { (it % 251).toByte() }
        var last = 0 to 0
        val read = MaresBle(FakeSirius(content)) {}.readObject(0x3000, 0x03) { done, size -> last = done to size }
        assertContentEquals(content, read)
        assertEquals(700 to 700, last)
    }

    @Test
    fun theProductNameIsReadFromTheVersionPacket() {
        val version = ByteArray(140)
        "Sirius".encodeToByteArray().copyInto(version, 0x46)
        assertEquals("Sirius", MaresBle.productName(version))
    }
}
