package br.geomaps.core

/**
 * Leitor/escritor JSON mínimo e sem dependências (funciona igual no Android e na JVM).
 * Objetos viram LinkedHashMap<String, Any?>, arrays viram ArrayList<Any?>,
 * números viram Double (ou Long quando inteiros sem expoente).
 */
object Json {

    fun parse(text: String): Any? {
        val p = Parser(text)
        p.ws()
        val v = p.value()
        p.ws()
        if (p.i < text.length) throw GeoFormatException("JSON: conteúdo extra na posição ${p.i}")
        return v
    }

    fun write(v: Any?): String = StringBuilder().also { write(v, it) }.toString()

    fun write(v: Any?, sb: StringBuilder) {
        when (v) {
            null -> sb.append("null")
            is String -> quote(v, sb)
            is Boolean -> sb.append(v)
            is Int, is Long, is Short, is Byte -> sb.append(v)
            is Double -> num(v, sb)
            is Float -> num(v.toDouble(), sb)
            is Number -> num(v.toDouble(), sb)
            is Map<*, *> -> {
                sb.append('{')
                var first = true
                for ((k, value) in v) {
                    if (!first) sb.append(',')
                    first = false
                    quote(k.toString(), sb)
                    sb.append(':')
                    write(value, sb)
                }
                sb.append('}')
            }
            is Iterable<*> -> {
                sb.append('[')
                var first = true
                for (e in v) {
                    if (!first) sb.append(',')
                    first = false
                    write(e, sb)
                }
                sb.append(']')
            }
            is Array<*> -> write(v.toList(), sb)
            is DoubleArray -> write(v.toList(), sb)
            else -> quote(v.toString(), sb)
        }
    }

    private fun num(d: Double, sb: StringBuilder) {
        if (d.isNaN() || d.isInfinite()) {
            sb.append("null"); return
        }
        if (d == Math.rint(d) && Math.abs(d) < 1e15) sb.append(d.toLong()) else sb.append(d)
    }

    private fun quote(s: String, sb: StringBuilder) {
        sb.append('"')
        for (ch in s) {
            when (ch) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                '\b' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                else -> if (ch < ' ') sb.append(String.format("\\u%04x", ch.code)) else sb.append(ch)
            }
        }
        sb.append('"')
    }

    private class Parser(val s: String) {
        var i = 0

        fun ws() {
            while (i < s.length && (s[i] == ' ' || s[i] == '\n' || s[i] == '\r' || s[i] == '\t' || s[i] == '﻿')) i++
        }

        fun value(): Any? {
            if (i >= s.length) throw GeoFormatException("JSON: fim inesperado")
            return when (val c = s[i]) {
                '{' -> obj()
                '[' -> arr()
                '"' -> str()
                't' -> lit("true", true)
                'f' -> lit("false", false)
                'n' -> lit("null", null)
                else -> if (c == '-' || c.isDigit()) number() else throw GeoFormatException("JSON: caractere inesperado '$c' na posição $i")
            }
        }

        fun lit(word: String, v: Any?): Any? {
            if (!s.startsWith(word, i)) throw GeoFormatException("JSON: esperado $word na posição $i")
            i += word.length
            return v
        }

        fun obj(): LinkedHashMap<String, Any?> {
            val m = LinkedHashMap<String, Any?>()
            i++
            ws()
            if (i < s.length && s[i] == '}') { i++; return m }
            while (true) {
                ws()
                if (i >= s.length || s[i] != '"') throw GeoFormatException("JSON: esperado nome de campo na posição $i")
                val k = str()
                ws()
                if (i >= s.length || s[i] != ':') throw GeoFormatException("JSON: esperado ':' na posição $i")
                i++
                ws()
                m[k] = value()
                ws()
                if (i >= s.length) throw GeoFormatException("JSON: objeto não fechado")
                if (s[i] == ',') { i++; continue }
                if (s[i] == '}') { i++; return m }
                throw GeoFormatException("JSON: esperado ',' ou '}' na posição $i")
            }
        }

        fun arr(): ArrayList<Any?> {
            val l = ArrayList<Any?>()
            i++
            ws()
            if (i < s.length && s[i] == ']') { i++; return l }
            while (true) {
                ws()
                l.add(value())
                ws()
                if (i >= s.length) throw GeoFormatException("JSON: array não fechado")
                if (s[i] == ',') { i++; continue }
                if (s[i] == ']') { i++; return l }
                throw GeoFormatException("JSON: esperado ',' ou ']' na posição $i")
            }
        }

        fun str(): String {
            i++
            val sb = StringBuilder()
            while (i < s.length) {
                val c = s[i++]
                when (c) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        if (i >= s.length) break
                        when (val e = s[i++]) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                sb.append(s.substring(i, i + 4).toInt(16).toChar())
                                i += 4
                            }
                            else -> sb.append(e)
                        }
                    }
                    else -> sb.append(c)
                }
            }
            throw GeoFormatException("JSON: texto não fechado")
        }

        fun number(): Any {
            val st = i
            if (s[i] == '-') i++
            while (i < s.length && s[i].isDigit()) i++
            var isInt = true
            if (i < s.length && s[i] == '.') {
                isInt = false; i++
                while (i < s.length && s[i].isDigit()) i++
            }
            if (i < s.length && (s[i] == 'e' || s[i] == 'E')) {
                isInt = false; i++
                if (i < s.length && (s[i] == '+' || s[i] == '-')) i++
                while (i < s.length && s[i].isDigit()) i++
            }
            val t = s.substring(st, i)
            if (isInt && t.length < 18) return t.toLong()
            return t.toDouble()
        }
    }
}

/* Utilitários para ler JSON dinamicamente. */
@Suppress("UNCHECKED_CAST")
fun Any?.jObj(): Map<String, Any?>? = this as? Map<String, Any?>

@Suppress("UNCHECKED_CAST")
fun Any?.jArr(): List<Any?>? = this as? List<Any?>
fun Any?.jNum(): Double? = (this as? Number)?.toDouble() ?: (this as? String)?.toDoubleOrNull()
fun Any?.jStr(): String? = when (this) {
    null -> null
    is String -> this
    is Double -> if (this == Math.rint(this)) this.toLong().toString() else this.toString()
    else -> this.toString()
}
