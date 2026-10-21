package br.geomaps.core

/**
 * Separa a descrição "de verdade" dos atributos que muitos programas repetem dentro dela
 * (tabelas HTML do ArcGIS/QGIS, linhas "campo: valor" do Google My Maps etc.).
 */
object DescCleaner {
    private val TABLE = Regex("<table[^>]*>.*?</table>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val ROW = Regex("<tr[^>]*>(.*?)</tr>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val CELL = Regex("<t[dh][^>]*>(.*?)</t[dh]>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val BREAKS = Regex("<br\\s*/?>|</p>|</div>|</li>|</h\\d>", RegexOption.IGNORE_CASE)
    private val TAG = Regex("<[^>]+>")
    private val PAIR = Regex("^([^:=]{1,60}?)\\s*[:=]\\s*(.*)$")

    fun text(html: String): String = TAG.replace(BREAKS.replace(html, "\n"), "")
        .replace("&nbsp;", " ").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
        .replace("&#39;", "'").replace("&apos;", "'").replace("&amp;", "&")

    /** Devolve (descrição limpa, atributos encontrados na descrição que ainda não existiam). */
    fun split(desc: String, attrs: Map<String, String>): Pair<String, Map<String, String>> {
        if (desc.isBlank()) return "" to emptyMap()
        val found = LinkedHashMap<String, String>()
        val known = attrs.keys.map { it.trim().lowercase() }.toMutableSet()
        // 1) Tabelas HTML com duas colunas (campo | valor)
        for (t in TABLE.findAll(desc)) {
            for (r in ROW.findAll(t.value)) {
                val cells = CELL.findAll(r.groupValues[1]).map { text(it.groupValues[1]).trim() }.toList()
                if (cells.size == 2 && cells[0].isNotBlank()) {
                    val k = cells[0]
                    if (k.lowercase() !in known) { found[k] = cells[1]; known += k.lowercase() }
                }
            }
        }
        val rest = text(TABLE.replace(desc, "\n"))
        // 2) Linhas "campo: valor"
        val lines = rest.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val pairs = lines.mapNotNull { l -> PAIR.find(l)?.let { m -> l to (m.groupValues[1].trim() to m.groupValues[2].trim()) } }.toMap()
        val manyPairs = pairs.size >= 2
        val keep = ArrayList<String>()
        for (l in lines) {
            val p = pairs[l]
            when {
                p != null && p.first.lowercase() in known -> {}
                p != null && manyPairs -> { found[p.first] = p.second; known += p.first.lowercase() }
                else -> keep += l
            }
        }
        return keep.joinToString("\n") to found
    }
}
