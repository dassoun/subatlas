package com.sensitivemobility.subatlas

/**
 * Lecteur SQLite en lecture seule, en Kotlin pur : il fonctionne à l'identique sur Android et iOS, sans dépendre d'une
 * bibliothèque de la plateforme. Il lit les tables ordinaires (B-tree de tables, avec pages de débordement), pas les index
 * ni le journal WAL. Suffisant pour lire un fichier exporté par une appli (un fichier `.db` autonome).
 *
 * Valeurs rendues : `Long`, `Double`, `String`, `ByteArray` ou `null`.
 */
internal class SqliteFile(private val b: ByteArray) {
    private val pageSize: Int
    private val usable: Int

    init {
        require(b.size >= 100 && b.decodeToString(0, 15) == "SQLite format 3") { "not a SQLite file" }
        val ps = u16(16)
        pageSize = if (ps == 1) 65536 else ps
        usable = pageSize - (b[20].toInt() and 0xFF)
        require(u32(56) <= 1L) { "unsupported text encoding" }
        require(b[18].toInt() != 2) { "WAL mode not supported" }
    }

    /** Une table : nom des colonnes dans l'ordre, page racine et colonne alias du rowid (« integer primary key »). */
    class Table(val name: String, val root: Int, val columns: List<String>, val rowidAlias: String?)

    private class RawRow(val rowid: Long, val values: List<Any?>)

    private fun u16(o: Int) = ((b[o].toInt() and 0xFF) shl 8) or (b[o + 1].toInt() and 0xFF)

    private fun u32(o: Int): Long =
        ((b[o].toLong() and 0xFF) shl 24) or ((b[o + 1].toLong() and 0xFF) shl 16) or
            ((b[o + 2].toLong() and 0xFF) shl 8) or (b[o + 3].toLong() and 0xFF)

    val tables: Map<String, Table> by lazy {
        val out = LinkedHashMap<String, Table>()
        // sqlite_master (page 1) : type, name, tbl_name, rootpage, sql
        for (row in scan(1)) {
            if (row.values.size < 5 || row.values[0] != "table") continue
            val name = row.values[1] as? String ?: continue
            val root = (row.values[3] as? Long)?.toInt() ?: continue
            val sql = row.values[4] as? String ?: continue
            val (columns, alias) = parseColumns(sql)
            out[name] = Table(name, root, columns, alias)
        }
        out
    }

    /** Lignes d'une table, sous forme `nom de colonne -> valeur`. */
    fun rows(table: String): List<Map<String, Any?>> {
        val t = tables[table] ?: return emptyList()
        return scan(t.root).map { r ->
            val m = LinkedHashMap<String, Any?>()
            t.columns.forEachIndexed { i, col ->
                val v = if (i < r.values.size) r.values[i] else null
                // Une colonne « integer primary key » est stockée à NULL : sa valeur est le rowid.
                m[col] = if (v == null && col == t.rowidAlias) r.rowid else v
            }
            m
        }
    }

    private fun scan(rootPage: Int): List<RawRow> = ArrayList<RawRow>().also { walk(rootPage, it, 0) }

    private fun walk(page: Int, out: MutableList<RawRow>, depth: Int) {
        require(depth < 64) { "corrupt b-tree" }
        val base = (page - 1) * pageSize
        require(base >= 0 && base + pageSize <= b.size) { "page out of range" }
        val hdr = if (page == 1) base + 100 else base
        val cells = u16(hdr + 3)
        when (b[hdr].toInt() and 0xFF) {
            0x0D -> for (i in 0 until cells) out += leafCell(base + u16(hdr + 8 + 2 * i))
            0x05 -> {
                for (i in 0 until cells) walk(u32(base + u16(hdr + 12 + 2 * i)).toInt(), out, depth + 1)
                walk(u32(hdr + 8).toInt(), out, depth + 1)
            }
            else -> Unit // page d'index ou autre : ignorée
        }
    }

    private fun leafCell(start: Int): RawRow {
        val pos = intArrayOf(start)
        val payloadSize = varint(b, pos).toInt()
        val rowid = varint(b, pos)
        val x = usable - 35
        val local = if (payloadSize <= x) {
            payloadSize
        } else {
            val m = ((usable - 12) * 32 / 255) - 23
            val k = m + ((payloadSize - m) % (usable - 4))
            if (k <= x) k else m
        }
        val payload = if (local == payloadSize) {
            b.copyOfRange(pos[0], pos[0] + payloadSize)
        } else {
            // Charge utile en partie dans des pages de débordement chaînées.
            val buf = ByteArray(payloadSize)
            b.copyInto(buf, 0, pos[0], pos[0] + local)
            var filled = local
            var next = u32(pos[0] + local).toInt()
            while (filled < payloadSize && next != 0) {
                val po = (next - 1) * pageSize
                val take = minOf(payloadSize - filled, usable - 4)
                b.copyInto(buf, filled, po + 4, po + 4 + take)
                filled += take
                next = u32(po).toInt()
            }
            buf
        }
        return RawRow(rowid, decodeRecord(payload))
    }

    private companion object {
        /** Varint SQLite (1 à 9 octets) lu dans [d] à `pos[0]` ; avance `pos[0]`. */
        fun varint(d: ByteArray, pos: IntArray): Long {
            var result = 0L
            for (i in 0 until 8) {
                val v = d[pos[0]++].toInt() and 0xFF
                result = (result shl 7) or (v and 0x7F).toLong()
                if (v and 0x80 == 0) return result
            }
            return (result shl 8) or (d[pos[0]++].toLong() and 0xFF) // 9e octet : 8 bits entiers
        }

        /** Décode un enregistrement : en-tête (types de colonnes) puis valeurs. */
        fun decodeRecord(r: ByteArray): List<Any?> {
            val pos = intArrayOf(0)
            val headerEnd = varint(r, pos).toInt()
            val types = ArrayList<Long>()
            while (pos[0] < headerEnd) types += varint(r, pos)
            var o = headerEnd
            fun int(n: Int): Long {
                var v = if (r[o].toInt() < 0) -1L else 0L // extension de signe
                for (i in 0 until n) v = (v shl 8) or (r[o + i].toLong() and 0xFF)
                o += n
                return v
            }
            val out = ArrayList<Any?>(types.size)
            for (t in types) {
                out += when {
                    t == 0L -> null
                    t in 1L..4L -> int(t.toInt())
                    t == 5L -> int(6)
                    t == 6L -> int(8)
                    t == 7L -> Double.fromBits(int(8))
                    t == 8L -> 0L
                    t == 9L -> 1L
                    t >= 12L && t % 2 == 0L -> {
                        val n = ((t - 12) / 2).toInt()
                        r.copyOfRange(o, o + n).also { o += n }
                    }
                    t >= 13L -> {
                        val n = ((t - 13) / 2).toInt()
                        r.decodeToString(o, o + n).also { o += n }
                    }
                    else -> null
                }
            }
            return out
        }

        /** Noms de colonnes d'un `CREATE TABLE` et, le cas échéant, celle qui est un alias du rowid. */
        fun parseColumns(sql: String): Pair<List<String>, String?> {
            val open = sql.indexOf('(')
            val close = sql.lastIndexOf(')')
            if (open < 0 || close <= open) return emptyList<String>() to null
            val body = sql.substring(open + 1, close)
            val defs = ArrayList<String>()
            var depth = 0
            var quote: Char? = null
            val cur = StringBuilder()
            for (ch in body) {
                if (quote != null) {
                    cur.append(ch)
                    if (ch == quote) quote = null
                    continue
                }
                when (ch) {
                    '"', '\'', '`' -> { quote = ch; cur.append(ch) }
                    '[' -> { quote = ']'; cur.append(ch) }
                    '(' -> { depth++; cur.append(ch) }
                    ')' -> { depth--; cur.append(ch) }
                    ',' -> if (depth == 0) { defs += cur.toString(); cur.clear() } else cur.append(ch)
                    else -> cur.append(ch)
                }
            }
            if (cur.isNotBlank()) defs += cur.toString()
            val names = ArrayList<String>()
            var alias: String? = null
            for (d in defs) {
                val t = d.trim()
                if (t.substringBefore(' ').uppercase() in setOf("PRIMARY", "CONSTRAINT", "UNIQUE", "FOREIGN", "CHECK")) continue
                val name = when {
                    t.startsWith("\"") -> t.substring(1, t.indexOf('"', 1))
                    t.startsWith("`") -> t.substring(1, t.indexOf('`', 1))
                    t.startsWith("[") -> t.substring(1, t.indexOf(']'))
                    else -> t.substringBefore(' ')
                }
                names += name
                if (Regex("integer\\s+primary\\s+key", RegexOption.IGNORE_CASE).containsMatchIn(t)) alias = name
            }
            return names to alias
        }
    }
}
