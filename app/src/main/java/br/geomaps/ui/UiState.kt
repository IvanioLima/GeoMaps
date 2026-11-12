package br.geomaps.ui

import br.geomaps.core.Coord
import br.geomaps.core.ExportFormat
import br.geomaps.core.GeoFeature
import br.geomaps.core.GeomType
import br.geomaps.core.ImportResult
import br.geomaps.core.Profile

/** Modo de interação com o mapa. */
sealed interface Mode {
    data object Browse : Mode

    /** Desenho manual de trilha ou área. */
    data class Draw(val type: GeomType, val pts: List<Coord> = emptyList()) : Mode

    /**
     * Edição de vértices de um elemento (trilha, área ou ponto). Funciona como a criação de trilhas:
     * a ferramenta ativa decide o que o toque no mapa (ou "+ Centro" / "Coordenada") faz.
     */
    data class EditVertices(
        val featureId: Long,
        val part: Int,
        val pts: List<Coord>,
        val closed: Boolean,
        val selected: Set<Int> = emptySet(),
        val isPoint: Boolean = false,
        val tool: VertexTool = VertexTool.MOVE,
        /** Estados anteriores desta edição (para "Desfazer"). */
        val history: List<List<Coord>> = emptyList(),
    ) : Mode

    /** Seleção, tocando no mapa, das trilhas a mesclar (na ordem dos toques). */
    data class MergeSelect(val order: List<Long>) : Mode

    data class Measure(val pts: List<Coord> = emptyList(), val area: Boolean = false) : Mode

    data class Split(val featureId: Long, val pts: List<Coord>, val cuts: Set<Int> = emptySet()) : Mode

    data object Freehand : Mode

    /** Seleção de área para download offline ou do MDE. */
    data class PickArea(val purpose: String, val free: Boolean, val pts: List<Coord> = emptyList()) : Mode
}

enum class Screen { NONE, PROJECTS, LIST, SETTINGS, OFFLINE, VERSIONS, FORM, ABOUT }

/** Ferramentas da edição de vértices. */
enum class VertexTool(val label: String) { MOVE("Mover"), INSERT("Inserir"), DELETE("Excluir") }

enum class FollowMode { OFF, FOLLOW, HEADING }

sealed interface Dialog {
    /** Criação/edição de ponto. [feature].id == 0 → novo. */
    data class Waypoint(val feature: GeoFeature) : Dialog
    /** Edição de propriedades de trilha/área (novo se id == 0). */
    data class FeatureProps(val feature: GeoFeature) : Dialog
    data class Import(val result: ImportResult, val targetProject: Long?) : Dialog
    data class Export(val projectId: Long?, val featureIds: List<Long>?, val title: String) : Dialog
    data class Merge(val baseId: Long, val initial: List<Long> = listOf(baseId)) : Dialog
    data class Reverse(val featureId: Long) : Dialog
    data class Simplify(val featureId: Long) : Dialog
    data class FilterGps(val featureId: Long) : Dialog
    data class Buffer(val featureId: Long) : Dialog
    data class CoordInput(val purpose: String) : Dialog
    data class NewProject(val thenImport: Boolean = false) : Dialog
    data class RenameProject(val projectId: Long) : Dialog
    data class MergeProjects(val projectId: Long) : Dialog
    data class Confirm(val title: String, val text: String, val action: () -> Unit) : Dialog
    data object FinishRecording : Dialog
    data class OfflineDownload(val purpose: String, val ring: List<Coord>, val free: Boolean) : Dialog
    data object Converter : Dialog
    data class Photo(val path: String) : Dialog
    data class MoveToProject(val featureId: Long) : Dialog
}

data class ProfileState(
    val featureId: Long,
    val name: String,
    val profile: Profile?,
    val useDem: Boolean,
    val demAvailable: Boolean,
    val highlight: Int? = null,
    val recorded: Boolean,
)

sealed interface NavState {
    data class GoTo(val target: Coord, val name: String) : NavState
    data class FollowTrack(val featureId: Long, val name: String, val line: List<List<Coord>>) : NavState
}

data class ExportRequest(val bytes: ByteArray, val fileName: String, val mime: String, val share: Boolean, val format: ExportFormat)
