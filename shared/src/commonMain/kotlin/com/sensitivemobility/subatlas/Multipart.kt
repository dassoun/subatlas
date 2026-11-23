package com.sensitivemobility.subatlas

/** Un champ d'un formulaire multipart : un fichier si [filename] est renseigné, sinon une valeur texte. */
class MultipartPart(
    val name: String,
    val bytes: ByteArray,
    val filename: String? = null,
    val contentType: String? = null,
)

/** Corps multipart/form-data prêt à envoyer : [contentType] contient la frontière. */
class MultipartBody(val contentType: String, val bytes: ByteArray)

object Multipart {
    /** Construit le corps (RFC 7578) ; [boundary] n'est fixée que par les tests. */
    fun build(parts: List<MultipartPart>, boundary: String = "SubAtlas" + newUuid().replace("-", "")): MultipartBody {
        val out = ArrayList<Byte>()
        fun text(s: String) = s.encodeToByteArray().forEach { out.add(it) }
        for (p in parts) {
            text("--$boundary\r\n")
            val disposition = StringBuilder("Content-Disposition: form-data; name=\"${quoted(p.name)}\"")
            if (p.filename != null) disposition.append("; filename=\"${quoted(p.filename)}\"")
            text(disposition.append("\r\n").toString())
            if (p.contentType != null) text("Content-Type: ${p.contentType}\r\n")
            text("\r\n")
            p.bytes.forEach { out.add(it) }
            text("\r\n")
        }
        text("--$boundary--\r\n")
        return MultipartBody("multipart/form-data; boundary=$boundary", out.toByteArray())
    }

    /** Un nom ou nom de fichier ne doit contenir ni guillemet ni saut de ligne. */
    private fun quoted(s: String) = s.replace("\"", "'").replace("\r", " ").replace("\n", " ")
}
