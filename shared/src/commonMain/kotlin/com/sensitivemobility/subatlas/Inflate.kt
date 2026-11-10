package com.sensitivemobility.subatlas

/**
 * Décompression gzip / DEFLATE (RFC 1951 et 1952) en Kotlin pur, sur le modèle de `puff.c` de zlib : sans dépendance de
 * plateforme, donc identique sur Android et iOS.
 */
internal object Inflate {
    class FormatException(message: String) : Exception(message)

    private val LENGTH_BASE = intArrayOf(3, 4, 5, 6, 7, 8, 9, 10, 11, 13, 15, 17, 19, 23, 27, 31, 35, 43, 51, 59, 67, 83, 99, 115, 131, 163, 195, 227, 258)
    private val LENGTH_EXTRA = intArrayOf(0, 0, 0, 0, 0, 0, 0, 0, 1, 1, 1, 1, 2, 2, 2, 2, 3, 3, 3, 3, 4, 4, 4, 4, 5, 5, 5, 5, 0)
    private val DIST_BASE = intArrayOf(1, 2, 3, 4, 5, 7, 9, 13, 17, 25, 33, 49, 65, 97, 129, 193, 257, 385, 513, 769, 1025, 1537, 2049, 3073, 4097, 6145, 8193, 12289, 16385, 24577)
    private val DIST_EXTRA = intArrayOf(0, 0, 0, 0, 1, 1, 2, 2, 3, 3, 4, 4, 5, 5, 6, 6, 7, 7, 8, 8, 9, 9, 10, 10, 11, 11, 12, 12, 13, 13)
    private val CODE_LENGTH_ORDER = intArrayOf(16, 17, 18, 0, 8, 7, 9, 6, 10, 5, 11, 4, 12, 3, 13, 2, 14, 1, 15)

    /** Décompresse un flux gzip commençant à [start] dans [src]. */
    fun gunzip(src: ByteArray, start: Int = 0): ByteArray {
        if (src.size < start + 18 || src[start] != 0x1f.toByte() || src[start + 1] != 0x8b.toByte()) {
            throw FormatException("not gzip")
        }
        if (src[start + 2].toInt() != 8) throw FormatException("unsupported gzip method")
        val flags = src[start + 3].toInt()
        var pos = start + 10
        if (flags and 4 != 0) pos += 2 + ((src[pos].toInt() and 0xFF) or ((src[pos + 1].toInt() and 0xFF) shl 8)) // FEXTRA
        if (flags and 8 != 0) { while (src[pos++].toInt() != 0) Unit } // FNAME
        if (flags and 16 != 0) { while (src[pos++].toInt() != 0) Unit } // FCOMMENT
        if (flags and 2 != 0) pos += 2 // FHCRC
        return inflate(src, pos)
    }

    private class Huffman(val count: IntArray, val symbol: IntArray)

    private class State(val src: ByteArray, var pos: Int) {
        var bitBuf = 0
        var bitCnt = 0
        var out = ByteArray(1024)
        var outLen = 0

        fun bits(n: Int): Int {
            var v = bitBuf
            while (bitCnt < n) {
                if (pos >= src.size) throw FormatException("unexpected end of data")
                v = v or ((src[pos++].toInt() and 0xFF) shl bitCnt)
                bitCnt += 8
            }
            bitBuf = v ushr n
            bitCnt -= n
            return v and ((1 shl n) - 1)
        }

        fun put(byte: Int) {
            if (outLen == out.size) out = out.copyOf(out.size * 2)
            out[outLen++] = byte.toByte()
        }
    }

    private fun construct(lengths: IntArray, n: Int): Huffman {
        val count = IntArray(16)
        for (i in 0 until n) count[lengths[i]]++
        val offs = IntArray(16)
        for (len in 1 until 15) offs[len + 1] = offs[len] + count[len]
        val symbol = IntArray(n)
        for (i in 0 until n) if (lengths[i] != 0) symbol[offs[lengths[i]]++] = i
        return Huffman(count, symbol)
    }

    private fun decode(s: State, h: Huffman): Int {
        var code = 0
        var first = 0
        var index = 0
        for (len in 1..15) {
            code = code or s.bits(1)
            val count = h.count[len]
            if (code - count < first) return h.symbol[index + (code - first)]
            index += count
            first += count
            first = first shl 1
            code = code shl 1
        }
        throw FormatException("invalid huffman code")
    }

    private fun codes(s: State, lencode: Huffman, distcode: Huffman) {
        while (true) {
            var sym = decode(s, lencode)
            if (sym < 256) {
                s.put(sym)
            } else if (sym == 256) {
                return
            } else {
                sym -= 257
                if (sym >= 29) throw FormatException("invalid length code")
                val len = LENGTH_BASE[sym] + s.bits(LENGTH_EXTRA[sym])
                val ds = decode(s, distcode)
                if (ds >= 30) throw FormatException("invalid distance code")
                val dist = DIST_BASE[ds] + s.bits(DIST_EXTRA[ds])
                if (dist > s.outLen) throw FormatException("distance too far back")
                repeat(len) { s.put(s.out[s.outLen - dist].toInt()) }
            }
        }
    }

    private val fixedLen: Huffman by lazy {
        val l = IntArray(288) { if (it < 144) 8 else if (it < 256) 9 else if (it < 280) 7 else 8 }
        construct(l, 288)
    }
    private val fixedDist: Huffman by lazy { construct(IntArray(30) { 5 }, 30) }

    /** Décompresse un flux DEFLATE brut commençant à [start] dans [src]. */
    fun inflate(src: ByteArray, start: Int): ByteArray {
        val s = State(src, start)
        do {
            val last = s.bits(1)
            when (s.bits(2)) {
                0 -> { // bloc stocké
                    s.bitBuf = 0
                    s.bitCnt = 0
                    if (s.pos + 4 > src.size) throw FormatException("unexpected end of data")
                    val len = (src[s.pos].toInt() and 0xFF) or ((src[s.pos + 1].toInt() and 0xFF) shl 8)
                    s.pos += 4
                    if (s.pos + len > src.size) throw FormatException("unexpected end of data")
                    repeat(len) { s.put(src[s.pos++].toInt()) }
                }
                1 -> codes(s, fixedLen, fixedDist)
                2 -> {
                    val nlen = s.bits(5) + 257
                    val ndist = s.bits(5) + 1
                    val ncode = s.bits(4) + 4
                    if (nlen > 286 || ndist > 30) throw FormatException("bad counts")
                    val lengths = IntArray(320)
                    for (i in 0 until ncode) lengths[CODE_LENGTH_ORDER[i]] = s.bits(3)
                    val lencodeLen = construct(lengths, 19)
                    var idx = 0
                    val all = IntArray(nlen + ndist)
                    while (idx < nlen + ndist) {
                        val sym = decode(s, lencodeLen)
                        if (sym < 16) {
                            all[idx++] = sym
                        } else {
                            var len = 0
                            val rep: Int
                            when (sym) {
                                16 -> { if (idx == 0) throw FormatException("no previous length"); len = all[idx - 1]; rep = 3 + s.bits(2) }
                                17 -> rep = 3 + s.bits(3)
                                else -> rep = 11 + s.bits(7)
                            }
                            if (idx + rep > nlen + ndist) throw FormatException("too many lengths")
                            repeat(rep) { all[idx++] = len }
                        }
                    }
                    val lencode = construct(all.copyOfRange(0, nlen), nlen)
                    val distcode = construct(all.copyOfRange(nlen, nlen + ndist), ndist)
                    codes(s, lencode, distcode)
                }
                else -> throw FormatException("invalid block type")
            }
        } while (last == 0)
        return s.out.copyOf(s.outLen)
    }
}
