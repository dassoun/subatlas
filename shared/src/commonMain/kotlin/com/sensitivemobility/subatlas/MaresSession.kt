package com.sensitivemobility.subatlas

import kotlin.math.abs

/** Les ordinateurs Mares de la famille « Sirius » (Sirius, Quad Ci, Puck 4…). */
internal object MaresDriver : ComputerDriver {
    override val family = DiveComputer.MARES
    override val hidesAfterConnection = false

    /** Le service Bluetooth de Mares, ou le nom « Sirius ». */
    override fun recognizes(device: BleFound): Boolean =
        MaresBle.SERVICE in device.services || device.name.contains("sirius", ignoreCase = true)

    override fun session(ble: BleTransport, address: String): ComputerSession = MaresSession(ble, address)
}

/**
 * Une session avec un Mares en Bluetooth : connexion, identité, liste des plongées (leurs en-têtes), téléchargement d'une plongée et
 * conversion en plongée du carnet. Protocole : `MaresBle.kt` ; décodage : `MaresDive.kt`. Les relevés bruts sont enregistrés dans
 * `files/ble/` pour pouvoir contrôler le décodage.
 */
internal class MaresSession(
    private val ble: BleTransport,
    private val address: String,
) : ComputerSession {
    override var log: (String) -> Unit = { println("SubAtlas BLE : $it") }

    private fun note(line: String) = log(line)

    override var serial = ""
        private set
    override val firmware = ""
    override var model = ""
        private set
    private var connection: BleConnection? = null
    private var link: MaresBle? = null

    /** Les en-têtes lus, bruts et décodés, par rang dans la liste. */
    private val headers = HashMap<Int, Pair<ByteArray, MaresDive.Header>>()

    override suspend fun open() {
        val c = ble.connect(address, ::note)
        connection = c
        val l = MaresBle(c, ::note)
        link = l
        val version = l.version()
        ble.saveDump("mares-version.bin", version)
        model = MaresBle.productName(version)
        serial = l.serial()
        note("Mares $model, série $serial")
    }

    /** L'ordinateur n'a pas de sommaire : on lit l'en-tête de chaque plongée (rang 0 : la plus récente). */
    override suspend fun dives(progress: (Int) -> Unit): List<ComputerDive> {
        val l = link ?: error("session fermée")
        val count = l.diveCount()
        note("$count plongée(s) sur l'ordinateur")
        headers.clear()
        return (0 until count).mapNotNull { i ->
            val raw = l.diveHeader(i)
            progress(i + 1)
            val h = MaresDive.header(raw)
            if (h == null) {
                note("plongée ${i + 1} : en-tête illisible")
                ble.saveDump("mares-entete-illisible-$i.bin", raw)
                null
            } else {
                headers[i] = raw to h
                // Une mesure de 34 octets toutes les 5 s, plus la pression et l'état des tissus : une cinquantaine d'octets par mesure.
                ComputerDive(i, h.date, h.time, h.durationSeconds(), h.maxDepth, maxOf(1L, h.sampleCount * 50L))
            }
        }
    }

    /**
     * Déjà dans le carnet : une plongée porte sa clé, ou une plongée importée (d'un fichier Mares par exemple) commence le même jour à
     * moins de 5 minutes, avec la même profondeur maximale (à 0,3 m près).
     */
    override fun isImported(dive: ComputerDive, logbook: List<Dive>): Boolean {
        val key = MaresDive.key(serial, header(dive).second)
        if (logbook.any { key in it.importKeys() }) return true
        val start = minutesOfDay(dive.startTime) ?: return false
        return logbook.any { d ->
            val at = minutesOfDay(d.startTime)
            val max = d.maxDepth
            d.date == dive.date && d.importKey.isNotEmpty() && at != null && abs(at - start) <= 5 &&
                max != null && abs(max - dive.maxDepthMeters) <= 0.3
        }
    }

    override suspend fun download(dive: ComputerDive, progress: (Long) -> Unit): ImportedDive? {
        val (head, h) = header(dive)
        val data = head + (link ?: error("session fermée")).diveData(dive.index) { done, _ -> progress(done.toLong()) }
        ble.saveDump("mares-${h.fingerprint.toString(16)}.bin", data)
        return MaresDive.toDive(data, serial, model)
    }

    override fun close() {
        connection?.close()
        connection = null
        link = null
    }

    private fun header(dive: ComputerDive) = headers[dive.index] ?: error("plongée inconnue")
}
