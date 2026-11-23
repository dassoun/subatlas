package com.sensitivemobility.subatlas

/** Stockage en mémoire pour les tests (fichiers texte et binaires). */
class MemoryStorage : Storage {
    val files = HashMap<String, String>()
    val binaries = HashMap<String, ByteArray>()
    override fun read(name: String): String? = files[name]
    override fun write(name: String, text: String) { files[name] = text }
    override fun readBytes(name: String): ByteArray? = binaries[name]
    override fun writeBytes(name: String, bytes: ByteArray): Boolean { binaries[name] = bytes; return true }
    override fun delete(name: String) { binaries.remove(name); files.remove(name) }
}
