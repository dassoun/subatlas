package com.sensitivemobility.subatlas

/**
 * Dialogue avec un ordinateur Mares de la famille « Sirius » (Sirius, Quad Ci, Puck 4…) en Bluetooth LE : le protocole de la
 * bibliothèque libre libdivecomputer (`mares_iconhd.c`, variante à paquets de taille variable), en Kotlin pur.
 *
 * Une commande est faite de deux octets (la commande, puis la commande XOR 0xA5), suivis au besoin de ses données ; la réponse est
 * encadrée par 0xAA et 0xEA. L'ordinateur expose des « objets » (index, sous-index) : son identité, le nombre de plongées, et pour
 * chacune un en-tête et des données. **Seules des commandes de lecture sont envoyées.**
 */
internal class MaresBle(private val conn: BleConnection, private val log: (String) -> Unit) {
    class ProtocolException(message: String) : Exception(message)

    /** La réponse à une commande, sans son encadrement. Avec [data], l'ordinateur accuse d'abord réception de la commande. */
    private suspend fun transfer(command: Int, data: ByteArray = ByteArray(0)): ByteArray {
        val head = byteArrayOf(command.toByte(), (command xor XOR).toByte())
        log("→ ${ShearwaterBle.hex(head + data)}")
        conn.write(head)
        var packet = receive()
        if (data.isNotEmpty()) {
            conn.write(data)
            packet += receive()
        }
        if (packet.size < 2 || packet.first() != ACK || packet.last() != END) {
            throw ProtocolException("réponse inattendue : ${ShearwaterBle.hex(packet, 24)}")
        }
        return packet.copyOfRange(1, packet.size - 1)
    }

    private suspend fun receive(): ByteArray =
        conn.receive(TIMEOUT_MS) ?: throw ProtocolException("pas de réponse de l'appareil (${TIMEOUT_MS / 1000} s)")

    /** Le paquet d'identité (140 octets) ; le nom du produit commence à l'octet 0x46. */
    suspend fun version(): ByteArray = transfer(CMD_VERSION).also {
        if (it.size != VERSION_SIZE) throw ProtocolException("identité de ${it.size} octets au lieu de $VERSION_SIZE")
    }

    /** L'objet ([index], [subIndex]) ; [progress] reçoit le nombre d'octets lus et la taille totale. */
    suspend fun readObject(index: Int, subIndex: Int, progress: (Int, Int) -> Unit = { _, _ -> }): ByteArray {
        val request = ByteArray(16)
        request[0] = 0x40
        request[1] = index.toByte()
        request[2] = (index shr 8).toByte()
        request[3] = subIndex.toByte()
        val first = transfer(CMD_OBJ_INIT, request)
        if (first.size != 16 || !first.copyOfRange(1, 4).contentEquals(request.copyOfRange(1, 4))) {
            throw ProtocolException("début d'objet inattendu : ${ShearwaterBle.hex(first, 24)}")
        }
        when (first[0].toInt() and 0xFF) {
            // Un petit objet tient dans la première réponse.
            0x42 -> return first.copyOfRange(4, first.size)
            0x41 -> {}
            0x80 -> throw ProtocolException("refus de l'appareil (code ${littleEndian(first, 4, 4).toString(16)})")
            else -> throw ProtocolException("type de réponse inconnu : ${ShearwaterBle.hex(first, 24)}")
        }
        val size = littleEndian(first, 4, 4)
        if (size !in 0..OBJECT_MAX) throw ProtocolException("taille d'objet invraisemblable : $size")
        val out = ByteArray(size.toInt())
        var done = 0
        var packet = 0
        while (done < out.size) {
            val toggle = packet % 2
            val segment = transfer(if (toggle == 0) CMD_OBJ_EVEN else CMD_OBJ_ODD)
            // Le premier octet répète la parité du paquet ; le reste est la suite de l'objet.
            if (segment.size < 2 || (segment[0].toInt() and 0xF0) shr 4 != toggle || done + segment.size - 1 > out.size) {
                throw ProtocolException("segment $packet inattendu : ${ShearwaterBle.hex(segment, 24)}")
            }
            segment.copyInto(out, done, 1, segment.size)
            done += segment.size - 1
            packet++
            progress(done, out.size)
        }
        return out
    }

    /** Le numéro de série : six chiffres à partir de l'octet 10 de l'objet. */
    suspend fun serial(): String = readObject(OBJ_DEVICE, 0x04).let { it.copyOfRange(10, minOf(16, it.size)).decodeToString().trim() }

    suspend fun diveCount(): Int = littleEndian(readObject(OBJ_LOGBOOK, 0x01), 0, 2).toInt()

    /** L'en-tête de la plongée [i] (0 : la plus récente). */
    suspend fun diveHeader(i: Int): ByteArray = readObject(OBJ_DIVE + i, 0x02)

    /** Les mesures de la plongée [i]. */
    suspend fun diveData(i: Int, progress: (Int, Int) -> Unit = { _, _ -> }): ByteArray = readObject(OBJ_DIVE + i, 0x03, progress)

    companion object {
        /** Le service Bluetooth « série » que les Mares annoncent. */
        const val SERVICE = "544e326b-5b72-c6b0-1c46-41c1bc448118"

        private const val TIMEOUT_MS = 5000L
        private const val ACK = 0xAA.toByte()
        private const val END = 0xEA.toByte()
        private const val XOR = 0xA5
        private const val CMD_VERSION = 0xC2
        private const val CMD_OBJ_INIT = 0xBF
        private const val CMD_OBJ_EVEN = 0xAC
        private const val CMD_OBJ_ODD = 0xFE
        private const val OBJ_DEVICE = 0x2000
        private const val OBJ_LOGBOOK = 0x2008
        private const val OBJ_DIVE = 0x3000
        private const val VERSION_SIZE = 140
        private const val OBJECT_MAX = 0x1000000L

        /** Le nom du produit lu dans le paquet d'identité (« Sirius », « Quad Ci »…). */
        fun productName(version: ByteArray): String =
            version.copyOfRange(0x46, 0x56).takeWhile { it != 0.toByte() }.toByteArray().decodeToString().trim()

        fun littleEndian(bytes: ByteArray, from: Int, size: Int): Long =
            (0 until size).fold(0L) { v, i -> v or ((bytes[from + i].toLong() and 0xFF) shl (8 * i)) }
    }
}
