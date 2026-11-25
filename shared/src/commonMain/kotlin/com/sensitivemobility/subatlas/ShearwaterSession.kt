package com.sensitivemobility.subatlas

import kotlinx.coroutines.CancellationException

/**
 * Une session avec un ordinateur Shearwater en Bluetooth : connexion, identité, sommaire des plongées, téléchargement d'une plongée et
 * conversion en plongée du carnet. Sans interface : la détection et l'import automatiques (`ComputerImport`) s'en servent.
 */
internal class ShearwaterSession(
    private val ble: BleTransport,
    val address: String,
    /** Le journal des échanges ; il change quand une session ouverte à l'association passe à l'import. */
    var log: (String) -> Unit = { println("SubAtlas BLE : $it") },
) {
    private fun note(line: String) = log(line)

    var serial = ""
        private set
    var firmware = ""
        private set
    var modelId: Int? = null
        private set
    private var base = 0L
    private var connection: BleConnection? = null
    private var link: ShearwaterBle? = null

    /** Se connecte et lit l'identité de l'ordinateur et la base des adresses de son carnet. Lève une exception en cas d'échec. */
    suspend fun open() {
        val c = ble.connect(address, ::note)
        connection = c
        val l = ShearwaterBle(c, ::note)
        link = l
        serial = l.read(0x8010).decodeToString().trim()
        firmware = optional { l.read(0x8011).decodeToString().trim() }.orEmpty()
        modelId = optional { l.read(0x8060).firstOrNull()?.toInt()?.and(0xFF) }
        val format = optional { l.read(0x8021) }
        val formatId = if (format != null && format.size >= 5) {
            ((format[1].toLong() and 0xFF) shl 24) or ((format[2].toLong() and 0xFF) shl 16) or ((format[3].toLong() and 0xFF) shl 8) or (format[4].toLong() and 0xFF)
        } else {
            0L
        }
        // Les anciens formats (0xDD000000, 0xC0000000, 0x90000000) comptent depuis 0xC0000000 ; le format natif (0x80000000) depuis sa propre valeur.
        base = when (formatId) {
            0xDD000000L, 0xC0000000L, 0x90000000L -> 0xC0000000L
            else -> formatId
        }
    }

    /** Les plongées présentes dans le sommaire de l'ordinateur, de la plus récente à la plus ancienne. */
    suspend fun manifest(progress: (Int) -> Unit = {}): List<ShearwaterManifestEntry> {
        val raw = (link ?: error("session fermée")).manifest(progress)
        ble.saveDump("manifest.bin", raw)
        return ShearwaterManifestEntry.parse(raw).filter { it.valid }
    }

    /** Les données brutes d'une plongée ; [progress] reçoit le nombre d'octets décompressés déjà lus. */
    suspend fun download(entry: ShearwaterManifestEntry, progress: (Int) -> Unit = {}): ByteArray {
        val raw = (link ?: error("session fermée")).download(base + entry.address, ShearwaterBle.DIVE_MAX_SIZE, compression = true, progress = progress)
        ble.saveDump("dive-${entry.fingerprint.toString(16)}.bin", raw)
        return raw
    }

    fun toDive(entry: ShearwaterManifestEntry, raw: ByteArray): ShearwaterDive? = ShearwaterBleImport.toDive(entry, raw, serial, modelId)

    /** Les plongées du sommaire qui ne sont pas encore dans le carnet. */
    fun fresh(entries: List<ShearwaterManifestEntry>, dives: List<Dive>): List<ShearwaterManifestEntry> =
        entries.filter { !ShearwaterBleImport.alreadyImported(it, serial, dives) }

    fun close() {
        connection?.close()
        connection = null
        link = null
    }

    private suspend fun <T> optional(block: suspend () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    companion object {
        /** La taille attendue (en octets décompressés, à peu près) d'une plongée : celle de sa zone de mémoire, pour la jauge. */
        fun expectedSize(entry: ShearwaterManifestEntry): Long = maxOf(1L, entry.endAddress - entry.address)
    }
}

/** Une cause d'échec lisible : l'utilisateur ne doit pas lire un code Bluetooth. */
internal fun describeBleFailure(e: Exception): String {
    val m = e.message.orEmpty()
    return when {
        "déconnecté" in m -> "appareil hors de portée ou éteint"
        "pas de réponse" in m -> "l'ordinateur ne répond plus"
        "délai dépassé" in m -> "ordinateur introuvable"
        m.isNotBlank() -> m
        else -> e::class.simpleName.orEmpty()
    }
}
