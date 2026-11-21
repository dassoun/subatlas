package com.sensitivemobility.subatlas

/**
 * Dialogue avec un ordinateur Shearwater (Peregrine, Perdix, Teric, Tern, Petrel 3…) en Bluetooth LE : le protocole de la
 * bibliothèque libre libdivecomputer (`shearwater_common.c`, `shearwater_petrel.c`), en Kotlin pur.
 *
 * Couches : l'appareil se comporte comme un port série (une caractéristique d'écriture, une de notifications) ; les octets
 * forment des paquets SLIP (fin de paquet 0xC0), précédés en Bluetooth de deux octets de trame (nombre de trames, rang) ; un
 * paquet porte un en-tête (0xFF 0x01 longueur 0x00) puis une requête de type diagnostic (lecture d'un identifiant 0x22,
 * téléchargement 0x35 / 0x36 / 0x37).
 *
 * Ce protocole n'a pas été vérifié sur un appareil au moment de l'écriture : les journaux ([log]) servent à le mettre au point.
 */
internal class ShearwaterBle(private val conn: BleConnection, private val log: (String) -> Unit) {
    class ProtocolException(message: String) : Exception(message)

    // --- SLIP ---
    private val decoded = ArrayList<Byte>()
    private var escaped = false
    private val packets = ArrayDeque<ByteArray>()

    private fun encode(packet: ByteArray): ByteArray {
        val out = ArrayList<Byte>(packet.size + 4)
        for (b in packet) {
            when (b.toInt() and 0xFF) {
                END -> { out += ESC.toByte(); out += ESC_END.toByte() }
                ESC -> { out += ESC.toByte(); out += ESC_ESC.toByte() }
                else -> out += b
            }
        }
        out += END.toByte()
        return out.toByteArray()
    }

    private fun feed(bytes: ByteArray, from: Int) {
        for (i in from until bytes.size) {
            val c = bytes[i].toInt() and 0xFF
            when {
                escaped -> {
                    escaped = false
                    decoded += when (c) {
                        ESC_END -> END.toByte()
                        ESC_ESC -> ESC.toByte()
                        else -> throw ProtocolException("séquence d'échappement inconnue : ${hex(byteArrayOf(c.toByte()))}")
                    }
                }
                c == ESC -> escaped = true
                c == END -> {
                    if (decoded.isNotEmpty()) packets.addLast(decoded.toByteArray())
                    decoded.clear()
                }
                else -> decoded += c.toByte()
            }
        }
    }

    // --- Paquets ---

    /** Envoie [payload] : en-tête, SLIP, puis trames de 20 octets (2 d'en-tête de trame et 18 de données). */
    private suspend fun send(payload: ByteArray) {
        val packet = byteArrayOf(0xFF.toByte(), 0x01, (payload.size + 1).toByte(), 0x00) + payload
        val slip = encode(packet)
        val frames = slip.toList().chunked(FRAME_DATA)
        log("→ ${hex(payload)}")
        frames.forEachIndexed { i, f -> conn.write(byteArrayOf(frames.size.toByte(), i.toByte()) + f.toByteArray()) }
    }

    /** Le prochain paquet complet reçu, sans son en-tête. */
    private suspend fun receive(timeoutMs: Long): ByteArray {
        while (packets.isEmpty()) {
            val n = conn.receive(timeoutMs) ?: throw ProtocolException("pas de réponse de l'appareil (${timeoutMs / 1000} s)")
            if (n.size < 2) throw ProtocolException("notification trop courte : ${hex(n)}")
            feed(n, 2)
        }
        val p = packets.removeFirst()
        if (p.size < 4 || p[0] != 0x01.toByte() || p[1] != 0xFF.toByte() || p[3] != 0.toByte() || (p[2].toInt() and 0xFF) - 1 + 4 != p.size) {
            throw ProtocolException("paquet inattendu : ${hex(p, 24)}")
        }
        return p.copyOfRange(4, p.size)
    }

    private suspend fun transfer(request: ByteArray, timeoutMs: Long = 5000): ByteArray {
        send(request)
        val r = receive(timeoutMs)
        if (r.isNotEmpty() && r[0] == NAK) {
            throw ProtocolException("refus de l'appareil (requête ${if (r.size > 1) hex(byteArrayOf(r[1])) else "?"}, code ${if (r.size > 2) hex(byteArrayOf(r[2])) else "?"})")
        }
        return r
    }

    /** Lit l'identifiant [id] (RDBI) et renvoie ses données. */
    suspend fun read(id: Int): ByteArray {
        val r = transfer(byteArrayOf(0x22, (id shr 8).toByte(), id.toByte()))
        if (r.size < 3 || r[0] != 0x62.toByte() || (r[1].toInt() and 0xFF) != id shr 8 || (r[2].toInt() and 0xFF) != id and 0xFF) {
            throw ProtocolException("réponse inattendue à la lecture de ${id.toString(16)} : ${hex(r, 24)}")
        }
        return r.copyOfRange(3, r.size)
    }

    // --- Téléchargement ---

    /**
     * Télécharge [size] octets à l'adresse [address] de la mémoire de l'appareil. Avec [compression], le flux reçu est compressé
     * (codage de plages de zéros sur 9 bits, puis XOR avec l'enregistrement précédent de 32 octets) et se termine par un marqueur :
     * [size] n'est alors qu'une limite haute.
     */
    suspend fun download(address: Long, size: Int, compression: Boolean, progress: (Int) -> Unit = {}): ByteArray {
        val start = transfer(
            byteArrayOf(
                0x35, if (compression) 0x10 else 0x00, 0x34,
                (address shr 24).toByte(), (address shr 16).toByte(), (address shr 8).toByte(), address.toByte(),
                (size shr 16).toByte(), (size shr 8).toByte(), size.toByte(),
            ),
            timeoutMs = 10_000,
        )
        if (start.isEmpty() || start[0] != 0x75.toByte()) throw ProtocolException("réponse inattendue au début du téléchargement : ${hex(start, 24)}")
        val lengthBytes = ((start.getOrElse(1) { 0 }.toInt() and 0xF0) shr 4)
        var maxLength = 0
        for (i in 0 until lengthBytes) maxLength = (maxLength shl 8) or (start.getOrElse(2 + i) { 0 }.toInt() and 0xFF)
        log("téléchargement à ${address.toString(16)} : taille de bloc ${maxLength}, réponse ${hex(start, 16)}")

        val raw = ArrayList<Byte>()
        val lre = if (compression) LreDecoder() else null
        var block = 1
        while (true) {
            val r = transfer(byteArrayOf(0x36, block.toByte()), timeoutMs = 10_000)
            if (r.size < 2 || r[0] != 0x76.toByte() || (r[1].toInt() and 0xFF) != (block and 0xFF)) {
                throw ProtocolException("bloc $block inattendu : ${hex(r, 24)}")
            }
            val data = r.copyOfRange(2, r.size)
            if (lre != null) {
                lre.feed(data)
                progress(lre.size)
                if (lre.complete) break
            } else {
                raw.addAll(data.toList())
                progress(raw.size)
                if (raw.size >= size) break
            }
            if (data.isEmpty()) throw ProtocolException("bloc $block vide avant la fin des données")
            block++
        }
        val exit = transfer(byteArrayOf(0x37))
        if (exit.size != 2 || exit[0] != 0x77.toByte()) log("fin de téléchargement inattendue : ${hex(exit)}")

        if (lre != null) return lre.result()
        return raw.toByteArray().copyOf(size)
    }

    /** Décodeur du flux compressé : valeurs de 9 bits, bit 0x100 = octet littéral, sinon nombre d'octets à zéro (0 = fin). */
    private class LreDecoder {
        private val out = ArrayList<Byte>()
        private var acc = 0
        private var bits = 0
        var complete = false
            private set
        val size: Int get() = out.size

        fun feed(data: ByteArray) {
            for (b in data) {
                if (complete) return
                acc = (acc shl 8) or (b.toInt() and 0xFF)
                bits += 8
                while (bits >= 9 && !complete) {
                    val v = (acc shr (bits - 9)) and 0x1FF
                    bits -= 9
                    acc = acc and ((1 shl bits) - 1)
                    when {
                        v and 0x100 != 0 -> out += (v and 0xFF).toByte()
                        v == 0 -> complete = true
                        else -> repeat(v) { out.add(0.toByte()) }
                    }
                }
            }
        }

        /** Les octets décodés, avec le XOR de chaque octet (à partir du 33e) par celui 32 octets plus tôt. */
        fun result(): ByteArray {
            val data = out.toByteArray()
            for (i in 32 until data.size) data[i] = (data[i].toInt() xor data[i - 32].toInt()).toByte()
            return data
        }
    }

    companion object {
        private const val END = 0xC0
        private const val ESC = 0xDB
        private const val ESC_END = 0xDC
        private const val ESC_ESC = 0xDD
        private const val FRAME_DATA = 18
        private const val NAK: Byte = 0x7F

        const val MANIFEST_ADDRESS = 0xE0000000L
        const val MANIFEST_SIZE = 0x600
        const val RECORD_SIZE = 0x20
        const val DIVE_MAX_SIZE = 0xFFFFFF

        fun hex(bytes: ByteArray, max: Int = 64): String {
            val s = bytes.take(max).joinToString(" ") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }
            return if (bytes.size > max) "$s … (${bytes.size} octets)" else s
        }
    }
}

/** Une entrée du sommaire des plongées d'un ordinateur Shearwater (32 octets). */
internal class ShearwaterManifestEntry(val index: Int, val raw: ByteArray) {
    private fun u16(p: Int) = ((raw[p].toInt() and 0xFF) shl 8) or (raw[p + 1].toInt() and 0xFF)
    private fun u32(p: Int) = (u16(p).toLong() shl 16) or u16(p + 2).toLong()

    /** 0xA5C4 : plongée présente ; 0x5A23 : effacée. */
    val header: Int get() = u16(0)
    val valid: Boolean get() = header == 0xA5C4
    val deleted: Boolean get() = header == 0x5A23

    /** Les quatre octets qui identifient la plongée (son « fingerprint » dans libdivecomputer) ; sans doute l'heure de début, en secondes depuis 1970. */
    val fingerprint: Long get() = u32(4)

    /** Heure de fin (secondes depuis 1970), à la suite de l'heure de début. */
    val endTime: Long get() = u32(8)

    /** Durée de la plongée en secondes. */
    val durationSeconds: Long get() = u32(12)

    /** Profondeur maximale en dixièmes de mètre. */
    val maxDepthDm: Int get() = u16(16)

    /** Numéro de journal de la plongée sur l'ordinateur (décroît du plus récent au plus ancien dans le sommaire). */
    val logNumber: Int get() = u16(18)

    /** Adresse de début de la plongée dans la mémoire de l'appareil. */
    val address: Long get() = u32(20)

    /** Adresse de fin de la plongée : la taille des données est la différence. */
    val endAddress: Long get() = u32(24)

    companion object {
        fun parse(manifest: ByteArray): List<ShearwaterManifestEntry> =
            (0 until manifest.size / ShearwaterBle.RECORD_SIZE).map {
                ShearwaterManifestEntry(it, manifest.copyOfRange(it * ShearwaterBle.RECORD_SIZE, (it + 1) * ShearwaterBle.RECORD_SIZE))
            }.filter { it.valid || it.deleted }
    }
}
