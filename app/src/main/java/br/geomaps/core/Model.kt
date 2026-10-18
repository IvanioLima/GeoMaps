package br.geomaps.core

/**
 * Modelo de dados geoespacial do GeoMaps (Kotlin puro, sem dependências do Android).
 * Coordenadas sempre em WGS84/SIRGAS 2000 geográficas (graus decimais).
 */
data class Coord(
    val lat: Double,
    val lon: Double,
    /** Altitude ortométrica (nível do mar) em metros, quando conhecida. */
    val ele: Double? = null,
    /** Instante em milissegundos (epoch UTC). */
    val time: Long? = null,
    /** Precisão horizontal estimada em metros. */
    val acc: Double? = null,
) {
    fun withEle(e: Double?) = copy(ele = e)
}

enum class GeomType(val label: String) {
    POINT("Ponto"), LINE("Trilha"), POLYGON("Área")
}

sealed class Geom {
    abstract val type: GeomType

    /** Todos os vértices, achatados. */
    abstract fun allCoords(): List<Coord>

    data class Pt(val c: Coord) : Geom() {
        override val type get() = GeomType.POINT
        override fun allCoords() = listOf(c)
    }

    /** Linha com um ou mais segmentos (MultiLineString / trkseg). */
    data class Line(val parts: List<List<Coord>>) : Geom() {
        override val type get() = GeomType.LINE
        override fun allCoords() = parts.flatten()
        val main: List<Coord> get() = parts.firstOrNull() ?: emptyList()

        companion object {
            fun of(c: List<Coord>) = Line(listOf(c))
        }
    }

    /**
     * Polígonos: lista de polígonos; cada polígono é uma lista de anéis (o primeiro é o externo,
     * os demais são buracos). Os anéis NÃO repetem o primeiro vértice no fim.
     */
    data class Poly(val polys: List<List<List<Coord>>>) : Geom() {
        override val type get() = GeomType.POLYGON
        override fun allCoords() = polys.flatten().flatten()
        val outer: List<Coord> get() = polys.firstOrNull()?.firstOrNull() ?: emptyList()

        companion object {
            fun of(ring: List<Coord>) = Poly(listOf(listOf(openRing(ring))))
            fun openRing(r: List<Coord>): List<Coord> =
                if (r.size > 1 && r.first().lat == r.last().lat && r.first().lon == r.last().lon) r.dropLast(1) else r
        }
    }
}

/** Estilo visual de um elemento. Cores em ARGB. */
data class FeatureStyle(
    val color: Int = DEFAULT_LINE,
    val width: Float = 4f,
    val fill: Int = DEFAULT_FILL,
    /** Chave do ícone: "e:<emoji>" (ícone interno) ou "img:<arquivo>" (imagem importada). */
    val icon: String = DEFAULT_ICON,
    val iconScale: Float = 1f,
) {
    companion object {
        const val DEFAULT_LINE = 0xFFE53935.toInt()
        const val DEFAULT_FILL = 0x55E53935
        const val DEFAULT_ICON = "e:📍"
    }
}

data class GeoFeature(
    val id: Long = 0,
    val projectId: Long = 0,
    val name: String = "",
    val description: String = "",
    val category: String = "",
    val geom: Geom,
    val style: FeatureStyle = FeatureStyle(),
    val attrs: Map<String, String> = emptyMap(),
    val photos: List<String> = emptyList(),
    /** Pasta de origem (KML Folder / nome do shapefile), separada por "/". */
    val folder: String = "",
    /** Fonte da altitude para trilhas gravadas: "gps", "dem" ou null (automático). */
    val eleSource: String? = null,
    val recorded: Boolean = false,
    val created: Long = System.currentTimeMillis(),
    val updated: Long = System.currentTimeMillis(),
) {
    val type: GeomType get() = geom.type
}

data class Project(
    val id: Long = 0,
    val name: String,
    val description: String = "",
    val visible: Boolean = true,
    val form: List<FormField> = emptyList(),
    val created: Long = System.currentTimeMillis(),
    val updated: Long = System.currentTimeMillis(),
    val featureCount: Int = 0,
)

enum class FieldType(val label: String) {
    TEXT("Texto"), NUMBER("Número"), DATE("Data"), CHOICE("Lista de opções"), BOOL("Sim/Não")
}

data class FormField(
    val name: String,
    val type: FieldType = FieldType.TEXT,
    val options: List<String> = emptyList(),
    val required: Boolean = false,
)

/** Resultado da leitura de um arquivo antes de gravar no banco. */
data class ImportResult(
    val sourceName: String,
    val features: List<GeoFeature>,
    /** Imagens embutidas (ícones / fotos) indexadas pelo caminho usado nos estilos ("href:<caminho>"). */
    val resources: Map<String, ByteArray> = emptyMap(),
    val warnings: List<String> = emptyList(),
)

class GeoFormatException(msg: String, cause: Throwable? = null) : Exception(msg, cause)
