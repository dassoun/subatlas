package com.sensitivemobility.subatlas

/** Un appareil Bluetooth LE vu pendant la recherche. */
class BleFound(
    /** Nom Bluetooth ; vide si l'appareil n'en annonce pas. */
    val name: String,
    val address: String,
    val rssi: Int,
    /** Services annoncés (UUID en minuscules), s'il y en a : un ordinateur de plongée se reconnaît au sien (voir `ComputerDriver`). */
    val services: List<String> = emptyList(),
)

/** Une connexion ouverte à un appareil : un canal d'écriture et un canal de notifications (le « port série » Bluetooth). */
interface BleConnection {
    /** Les services et caractéristiques utilisés, pour le journal. */
    val description: String

    /** Écrit [bytes] (au plus 20 octets à la fois) et attend l'accusé de l'appareil. */
    suspend fun write(bytes: ByteArray)

    /** La prochaine notification reçue ; `null` si rien n'arrive dans le délai. Lève une exception si l'appareil se déconnecte. */
    suspend fun receive(timeoutMs: Long): ByteArray?

    fun close()
}

/** Bluetooth LE de la plateforme : recherche des appareils et connexion. `null` dans [PlatformActions.ble] si la plateforme ne sait pas le faire. */
interface BleTransport {
    /** Demande à l'utilisateur les permissions Bluetooth si besoin ; `true` si elles sont accordées. */
    suspend fun ensurePermissions(): Boolean

    /** Cherche des appareils pendant [durationMs] (en mode rapide) ; [onFound] reçoit chaque appareil une seule fois, ceux sans nom ni service à la fin. */
    suspend fun scan(durationMs: Long, onFound: (BleFound) -> Unit)

    /**
     * Guette l'appareil d'adresse [address] (recherche filtrée sur cette adresse, sans demander de permission) : `true` dès qu'il est
     * à portée, `false` si [timeoutMs] s'écoule sans le voir (ou si le Bluetooth est coupé). Annulable.
     */
    suspend fun waitForDevice(address: String, timeoutMs: Long): Boolean

    /** Se connecte à l'appareil [address], découvre ses services et active les notifications. Lève une exception en cas d'échec. */
    suspend fun connect(address: String, log: (String) -> Unit): BleConnection

    /** Enregistre un fichier d'essai (relevé brut d'un ordinateur de plongée) dans le stockage de l'appli ; renvoie son chemin, `null` si impossible. */
    fun saveDump(name: String, bytes: ByteArray): String?
}
