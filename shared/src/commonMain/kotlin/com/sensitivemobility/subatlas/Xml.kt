package com.sensitivemobility.subatlas

/** Un élément d'un document XML : son nom, ses attributs, ses enfants et son texte (CDATA et entités déjà traités). */
internal class XmlNode(val name: String, val attrs: Map<String, String>) {
    val children = ArrayList<XmlNode>()
    var text = ""

    fun child(name: String): XmlNode? = children.firstOrNull { it.name == name }
    fun all(name: String): List<XmlNode> = children.filter { it.name == name }

    /** Le texte du premier enfant [name], sans blancs autour ; `null` s'il est absent ou vide. */
    fun str(name: String): String? = child(name)?.text?.trim()?.takeIf { it.isNotEmpty() }

    fun num(name: String): Double? = str(name)?.toDoubleOrNull()

    /** Tous les descendants (à n'importe quelle profondeur) nommés [name], dans l'ordre du document. */
    fun descendants(name: String): List<XmlNode> {
        val out = ArrayList<XmlNode>()
        fun walk(n: XmlNode) {
            for (c in n.children) {
                if (c.name == name) out += c
                walk(c)
            }
        }
        walk(this)
        return out
    }
}

/**
 * Lecteur XML minimal en Kotlin pur (identique sur Android et iOS) : éléments, attributs, texte, CDATA, commentaires et
 * entités usuelles. Pas de validation, pas d'espaces de noms (le préfixe éventuel est conservé dans le nom). Suffit aux fichiers
 * d'échange de plongées (UDDF).
 */
internal object Xml {
    class XmlException(message: String) : Exception(message)

    private val ATTRIBUTE = Regex("([^\\s=]+)\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)')")

    fun parse(source: String): XmlNode {
        var root: XmlNode? = null
        val stack = ArrayList<XmlNode>()
        var i = 0
        while (i < source.length) {
            val lt = source.indexOf('<', i)
            if (lt < 0) break
            if (lt > i && stack.isNotEmpty()) stack.last().text += decode(source.substring(i, lt))
            when {
                source.startsWith("<!--", lt) -> {
                    val end = source.indexOf("-->", lt)
                    if (end < 0) throw XmlException("comment")
                    i = end + 3
                }
                source.startsWith("<![CDATA[", lt) -> {
                    val end = source.indexOf("]]>", lt)
                    if (end < 0 || stack.isEmpty()) throw XmlException("cdata")
                    stack.last().text += source.substring(lt + 9, end)
                    i = end + 3
                }
                source.startsWith("<?", lt) || source.startsWith("<!", lt) -> {
                    val end = source.indexOf('>', lt)
                    if (end < 0) throw XmlException("declaration")
                    i = end + 1
                }
                source.startsWith("</", lt) -> {
                    val end = source.indexOf('>', lt)
                    if (end < 0) throw XmlException("closing tag")
                    if (stack.isNotEmpty()) stack.removeAt(stack.lastIndex)
                    i = end + 1
                }
                else -> {
                    val end = tagEnd(source, lt)
                    var body = source.substring(lt + 1, end)
                    val selfClosing = body.endsWith("/")
                    if (selfClosing) body = body.dropLast(1)
                    val nameEnd = body.indexOfFirst { it.isWhitespace() }.let { if (it < 0) body.length else it }
                    val attrs = HashMap<String, String>()
                    for (m in ATTRIBUTE.findAll(body.substring(nameEnd))) {
                        attrs[m.groupValues[1]] = decode(m.groupValues[2].ifEmpty { m.groupValues[3] })
                    }
                    val node = XmlNode(body.substring(0, nameEnd), attrs)
                    if (stack.isEmpty()) {
                        if (root == null) root = node
                    } else {
                        stack.last().children += node
                    }
                    if (!selfClosing) stack += node
                    i = end + 1
                }
            }
        }
        return root ?: throw XmlException("empty document")
    }

    /** Position du `>` qui ferme la balise ouverte en [start] (un `>` dans une valeur d'attribut entre guillemets ne compte pas). */
    private fun tagEnd(s: String, start: Int): Int {
        var quote = '\u0000'
        var j = start + 1
        while (j < s.length) {
            val c = s[j]
            if (quote != '\u0000') {
                if (c == quote) quote = '\u0000'
            } else if (c == '"' || c == '\'') {
                quote = c
            } else if (c == '>') {
                return j
            }
            j++
        }
        throw XmlException("unterminated tag")
    }

    private fun decode(text: String): String {
        if ('&' !in text) return text
        return text.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'")
            .replace(Regex("&#x([0-9a-fA-F]+);")) { it.groupValues[1].toInt(16).toChar().toString() }
            .replace(Regex("&#(\\d+);")) { it.groupValues[1].toInt().toChar().toString() }
            .replace("&amp;", "&")
    }

    /** Texte échappé pour une valeur ou un contenu d'élément. */
    fun escape(text: String): String = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
}
