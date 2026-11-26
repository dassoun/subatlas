package com.sensitivemobility.subatlas

/*
 * Les ordinateurs de plongée que SubAtlas sait lire en Bluetooth, vus par le reste de l'appli : l'association, la détection et
 * l'import ne connaissent que les types de ce fichier. Chaque marque a son pilote (`ShearwaterSession.kt`, `MaresSession.kt`) et ses
 * propres fichiers de protocole et de décodage. Ajouter une marque : écrire son pilote, puis l'inscrire dans [computerDrivers].
 */

/** Une plongée de la liste d'un ordinateur, avant son téléchargement. */
internal class ComputerDive(
    /** Le rang de la plongée dans la liste de l'ordinateur : c'est par lui que la session la retrouve. */
    val index: Int,
    /** Date (`yyyy-MM-dd`) et heure (`HH:mm`) de début, à l'horloge de l'ordinateur. */
    val date: String,
    val startTime: String,
    val durationSeconds: Int,
    val maxDepthMeters: Double,
    /** Taille attendue du téléchargement en octets (à peu près), pour la jauge. */
    val expectedBytes: Long,
)

/** Une connexion à un ordinateur : identité, liste des plongées, téléchargement. Seules des lectures lui sont demandées. */
internal interface ComputerSession {
    /** Le journal des échanges ; il change quand une session ouverte à l'association passe à l'import. */
    var log: (String) -> Unit

    /** Identité lue par [open]. */
    val serial: String
    val firmware: String
    val model: String

    /** Se connecte et lit l'identité de l'ordinateur. Lève une exception en cas d'échec. */
    suspend fun open()

    /** Les plongées de l'ordinateur, de la plus récente à la plus ancienne ; [progress] reçoit le nombre déjà listé. */
    suspend fun dives(progress: (Int) -> Unit): List<ComputerDive>

    /** La plongée est-elle déjà dans le carnet ? */
    fun isImported(dive: ComputerDive, logbook: List<Dive>): Boolean

    /** Télécharge et convertit une plongée ; `null` si elle est illisible. [progress] reçoit le nombre d'octets déjà lus. */
    suspend fun download(dive: ComputerDive, progress: (Long) -> Unit): ImportedDive?

    fun close()
}

/** Une marque d'ordinateurs : reconnaître ses appareils et ouvrir une session avec eux. */
internal interface ComputerDriver {
    /** Identifiant enregistré avec l'association ([DiveComputer.family]). */
    val family: String

    /** L'appareil vu à la recherche est-il de cette marque ? */
    fun recognizes(device: BleFound): Boolean

    fun session(ble: BleTransport, address: String): ComputerSession
}

internal val computerDrivers: List<ComputerDriver> = listOf(ShearwaterDriver, MaresDriver)

/** Le pilote qui reconnaît [device] ; `null` si aucun (liste « tous les appareils »). */
internal fun driverFor(device: BleFound): ComputerDriver? = computerDrivers.firstOrNull { it.recognizes(device) }

/** Le pilote d'un ordinateur associé. */
internal fun driverFor(computer: DiveComputer): ComputerDriver? = computerDrivers.firstOrNull { it.family == computer.family }

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
