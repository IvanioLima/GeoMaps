package br.geomaps.core

import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.ByteArrayInputStream
import java.time.Instant
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import javax.xml.parsers.DocumentBuilderFactory

/** Utilitários de XML via DOM (disponível tanto no Android quanto na JVM). */
object Xml {
    fun parse(bytes: ByteArray): Document {
        val f = DocumentBuilderFactory.newInstance()
        f.isNamespaceAware = false
        f.isValidating = false
        runCatching { f.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false) }
        val b = f.newDocumentBuilder()
        // Remove BOM e lixo antes do prólogo
        var start = 0
        while (start < bytes.size && bytes[start] != '<'.code.toByte()) start++
        return b.parse(ByteArrayInputStream(bytes, start, bytes.size - start))
    }

    fun local(n: Node): String = n.nodeName.substringAfter(':')

    fun children(e: Element): List<Element> {
        val out = ArrayList<Element>()
        val nl = e.childNodes
        for (i in 0 until nl.length) {
            val n = nl.item(i)
            if (n is Element) out.add(n)
        }
        return out
    }

    fun child(e: Element, name: String): Element? = children(e).firstOrNull { local(it).equals(name, true) }
    fun childrenNamed(e: Element, name: String) = children(e).filter { local(it).equals(name, true) }
    fun text(e: Element?): String? = e?.textContent?.trim()
    fun childText(e: Element, name: String): String? = text(child(e, name))?.takeIf { it.isNotEmpty() }

    /** Todos os descendentes com o nome local informado (busca em profundidade). */
    fun descendants(e: Element, name: String): List<Element> {
        val out = ArrayList<Element>()
        fun walk(x: Element) {
            for (c in children(x)) {
                if (local(c).equals(name, true)) out.add(c)
                walk(c)
            }
        }
        walk(e)
        return out
    }

    fun esc(s: String): String {
        val sb = StringBuilder(s.length + 16)
        for (c in s) when (c) {
            '<' -> sb.append("&lt;")
            '>' -> sb.append("&gt;")
            '&' -> sb.append("&amp;")
            '"' -> sb.append("&quot;")
            '\'' -> sb.append("&apos;")
            else -> if (c < ' ' && c != '\n' && c != '\r' && c != '\t') Unit else sb.append(c)
        }
        return sb.toString()
    }

    fun parseTime(s: String?): Long? {
        if (s.isNullOrBlank()) return null
        val t = s.trim()
        return runCatching { Instant.parse(t).toEpochMilli() }.getOrNull()
            ?: runCatching { OffsetDateTime.parse(t).toInstant().toEpochMilli() }.getOrNull()
            ?: runCatching { Instant.parse(t + "Z").toEpochMilli() }.getOrNull()
            ?: runCatching { OffsetDateTime.parse(t, DateTimeFormatter.ISO_OFFSET_DATE_TIME).toInstant().toEpochMilli() }.getOrNull()
    }

    fun formatTime(ms: Long): String = DateTimeFormatter.ISO_INSTANT.format(Instant.ofEpochMilli(ms))
}
