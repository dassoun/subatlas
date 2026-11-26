package com.sensitivemobility.subatlas

import kotlinx.coroutines.CancellationException

/** Les ordinateurs Shearwater (Peregrine, Perdix, Teric, Tern, Petrel 3…). */
internal object ShearwaterDriver : ComputerDriver {
    override val family = DiveComputer.SHEARWATER
    override val hidesAfterConnection = true

    /** Le service « série » de Shearwater, ou un nom de la marque (l'appareil n'annonce pas toujours son service). */
    override fun recognizes(device: BleFound): Boolean =
        device.services.any { it in SERVICES } || NAMES.any { device.name.contains(it, ignoreCase = true) }

    override fun session(ble: BleTransport, address: String): ComputerSession = ShearwaterSession(ble, address)

    private val SERVICES = setOf("fe25c237-0ece-443c-b0aa-e02033e7029d", "1aa44039-1667-4b29-87cc-dfecaaf31d97")
    private val NAMES = listOf("peregrine", "perdix", "teric", "petrel")
}

/**
 * Une session avec un ordinateur Shearwater en Bluetooth : connexion, identité, sommaire des plongées, téléchargement d'une plongée et
 * conversion en plongée du carnet. Protocole : `ShearwaterBle.kt` ; décodage : `ShearwaterBleImport.kt`.
 */
internal class ShearwaterSession(
    private val ble: BleTransport,
    private val address: String,
) : ComputerSession {
    override var log: (String) -> Unit = { println("SubAtlas BLE : $it") }

    private fun note(line: String) = log(line)

    override var serial = ""
        private set
    override var firmware = ""
        private set
    private var modelId: Int? = null
    override val model: String get() = ShearwaterBleImport.modelName(modelId)
    private var base = 0L
    private var connection: BleConnection? = null
    private var link: ShearwaterBle? = null
    private var entries: Map<Int, ShearwaterManifestEntry> = emptyMap()

    /** Se connecte et lit l'identité de l'ordinateur et la base des adresses de son carnet. */
    override suspend fun open() {
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

    override suspend fun dives(progress: (Int) -> Unit): List<ComputerDive> {
        val raw = (link ?: error("session fermée")).manifest(progress)
        ble.saveDump("manifest.bin", raw)
        val list = ShearwaterManifestEntry.parse(raw).filter { it.valid }
        entries = list.associateBy { it.index }
        return list.map { e ->
            val (date, time) = ShearwaterBleImport.dateTime(e.fingerprint)
            // La taille attendue d'une plongée est celle de sa zone de mémoire (adresses de début et de fin du sommaire).
            ComputerDive(e.index, date, time, e.durationSeconds.toInt(), e.maxDepthDm / 10.0, maxOf(1L, e.endAddress - e.address))
        }
    }

    override fun isImported(dive: ComputerDive, logbook: List<Dive>): Boolean =
        ShearwaterBleImport.alreadyImported(entry(dive), serial, logbook)

    override suspend fun download(dive: ComputerDive, progress: (Long) -> Unit): ImportedDive? {
        val entry = entry(dive)
        val raw = (link ?: error("session fermée")).download(base + entry.address, ShearwaterBle.DIVE_MAX_SIZE, compression = true) { n ->
            progress(n.toLong())
        }
        ble.saveDump("dive-${entry.fingerprint.toString(16)}.bin", raw)
        return ShearwaterBleImport.toDive(entry, raw, serial, modelId)
    }

    override fun close() {
        connection?.close()
        connection = null
        link = null
    }

    private fun entry(dive: ComputerDive) = entries[dive.index] ?: error("plongée inconnue")

    private suspend fun <T> optional(block: suspend () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }
}
