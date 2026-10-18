package br.geomaps.core

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/** Operações de edição de geometria (todas puras: devolvem novas geometrias). */
object TrackOps {

    data class MergeItem(val line: List<Coord>, val reversed: Boolean)

    /**
     * Fusão de trilhas na ordem dada. [connect] = true une tudo num só segmento contínuo;
     * false mantém cada trecho como segmento separado do mesmo elemento.
     */
    fun merge(items: List<MergeItem>, connect: Boolean): Geom.Line {
        val parts = items.map { if (it.reversed) it.line.reversed() else it.line }.filter { it.isNotEmpty() }
        if (!connect) return Geom.Line(parts)
        val out = ArrayList<Coord>()
        for (p in parts) {
            if (out.isNotEmpty() && p.isNotEmpty() && GeoMath.distance(out.last(), p.first()) < 0.01) out.addAll(p.drop(1)) else out.addAll(p)
        }
        return Geom.Line.of(out)
    }

    /** Sugere a melhor orientação de cada trecho para minimizar os saltos entre pontas. */
    fun autoOrient(lines: List<List<Coord>>): List<Boolean> {
        if (lines.isEmpty()) return emptyList()
        val rev = BooleanArray(lines.size)
        if (lines.size >= 2) {
            val a = lines[0]; val b = lines[1]
            val opts = listOf(
                Triple(false, false, GeoMath.distance(a.last(), b.first())),
                Triple(false, true, GeoMath.distance(a.last(), b.last())),
                Triple(true, false, GeoMath.distance(a.first(), b.first())),
                Triple(true, true, GeoMath.distance(a.first(), b.last())),
            ).minByOrNull { it.third }!!
            rev[0] = opts.first; rev[1] = opts.second
        }
        for (i in 2 until lines.size) {
            val prevEnd = if (rev[i - 1]) lines[i - 1].first() else lines[i - 1].last()
            rev[i] = GeoMath.distance(prevEnd, lines[i].last()) < GeoMath.distance(prevEnd, lines[i].first())
        }
        return rev.toList()
    }

    /** Divide o segmento [part] no vértice [index] (o vértice fica nas duas metades). */
    fun split(g: Geom.Line, part: Int, index: Int): Pair<Geom.Line, Geom.Line>? {
        val p = g.parts.getOrNull(part) ?: return null
        if (index <= 0 || index >= p.size - 1) return null
        val first = g.parts.take(part) + listOf(p.subList(0, index + 1))
        val second = listOf(p.subList(index, p.size)) + g.parts.drop(part + 1)
        return Geom.Line(first) to Geom.Line(second)
    }

    /** Divide em vários pontos de uma vez (índices de vértice do primeiro segmento). */
    fun splitMany(line: List<Coord>, indices: List<Int>): List<List<Coord>> {
        val idx = indices.filter { it > 0 && it < line.size - 1 }.distinct().sorted()
        val out = ArrayList<List<Coord>>()
        var start = 0
        for (i in idx) { out += line.subList(start, i + 1); start = i }
        out += line.subList(start, line.size)
        return out
    }

    enum class TimeMode { DROP, MIRROR }

    /**
     * Inverte o sentido. Horários: DROP descarta; MIRROR recalcula para continuarem crescentes,
     * preservando os intervalos entre os pontos.
     */
    fun reverse(c: List<Coord>, mode: TimeMode): List<Coord> {
        val r = c.reversed()
        if (mode == TimeMode.DROP || r.none { it.time != null }) return r.map { it.copy(time = null) }
        val t0 = c.firstOrNull { it.time != null }?.time ?: return r
        val tEnd = c.lastOrNull { it.time != null }?.time ?: return r
        return r.map { p -> p.time?.let { p.copy(time = t0 + (tEnd - it)) } ?: p }
    }

    fun reverseGeom(g: Geom, mode: TimeMode): Geom = when (g) {
        is Geom.Line -> Geom.Line(g.parts.reversed().map { reverse(it, mode) })
        is Geom.Poly -> Geom.Poly(g.polys.map { p -> p.map { it.reversed() } })
        else -> g
    }

    /** Douglas-Peucker com tolerância em metros. */
    fun simplify(c: List<Coord>, tolerance: Double): List<Coord> {
        if (c.size < 3 || tolerance <= 0) return c
        val local = GeoMath.Local(c[c.size / 2])
        val xs = DoubleArray(c.size) { local.x(c[it]) }
        val ys = DoubleArray(c.size) { local.y(c[it]) }
        val keep = BooleanArray(c.size)
        keep[0] = true; keep[c.size - 1] = true
        val stack = ArrayDeque<Pair<Int, Int>>()
        stack.addLast(0 to c.size - 1)
        while (stack.isNotEmpty()) {
            val (a, b) = stack.removeLast()
            var maxD = 0.0; var idx = -1
            val dx = xs[b] - xs[a]; val dy = ys[b] - ys[a]
            val len2 = dx * dx + dy * dy
            for (i in a + 1 until b) {
                val d = if (len2 == 0.0) sqrt((xs[i] - xs[a]).let { it * it } + (ys[i] - ys[a]).let { it * it })
                else {
                    val t = (((xs[i] - xs[a]) * dx + (ys[i] - ys[a]) * dy) / len2).coerceIn(0.0, 1.0)
                    val px = xs[a] + t * dx - xs[i]; val py = ys[a] + t * dy - ys[i]
                    sqrt(px * px + py * py)
                }
                if (d > maxD) { maxD = d; idx = i }
            }
            if (maxD > tolerance && idx > 0) {
                keep[idx] = true
                stack.addLast(a to idx); stack.addLast(idx to b)
            }
        }
        return c.filterIndexed { i, _ -> keep[i] }
    }

    fun simplifyGeom(g: Geom, tol: Double): Geom = when (g) {
        is Geom.Line -> Geom.Line(g.parts.map { simplify(it, tol) })
        is Geom.Poly -> Geom.Poly(g.polys.map { p -> p.map { r -> simplify(r + r.first(), tol).dropLast(1).takeIf { it.size >= 3 } ?: r } })
        else -> g
    }

    /**
     * Remove pontos ruins de GPS: precisão pior que [maxAcc] metros e saltos que exigiriam
     * velocidade acima de [maxSpeedMs] (quando há horário) ou distância acima de [maxJumpM].
     */
    fun filterGps(c: List<Coord>, maxAcc: Double?, maxSpeedMs: Double?, maxJumpM: Double? = null): List<Coord> {
        val out = ArrayList<Coord>()
        for (p in c) {
            if (maxAcc != null && p.acc != null && p.acc > maxAcc) continue
            val prev = out.lastOrNull()
            if (prev != null) {
                val d = GeoMath.distance(prev, p)
                if (maxSpeedMs != null && prev.time != null && p.time != null) {
                    val dt = (p.time - prev.time) / 1000.0
                    if (dt > 0 && d / dt > maxSpeedMs) continue
                    if (dt <= 0 && d > 5) continue
                } else if (maxJumpM != null && d > maxJumpM) continue
            }
            out += p
        }
        return out
    }

    fun insertVertex(c: List<Coord>, index: Int, p: Coord) = c.toMutableList().also { it.add(index.coerceIn(0, c.size), p) }
    fun moveVertex(c: List<Coord>, index: Int, p: Coord) = c.toMutableList().also { it[index] = p.copy(time = c[index].time) }
    fun deleteVertices(c: List<Coord>, indices: Set<Int>) = c.filterIndexed { i, _ -> i !in indices }
}

/** Estatísticas de trilha. */
data class TrackStats(
    val length: Double,
    val ascent: Double,
    val descent: Double,
    val minEle: Double?,
    val maxEle: Double?,
    val duration: Long?,
    val movingTime: Long?,
    val stoppedTime: Long?,
    val avgSpeed: Double?,
    val avgMovingSpeed: Double?,
    val maxSpeed: Double?,
    val points: Int,
)

object Stats {
    /** Subida/descida com histerese (ignora oscilações menores que [threshold] metros). */
    fun ascentDescent(ele: List<Double>, threshold: Double): Pair<Double, Double> {
        if (ele.size < 2) return 0.0 to 0.0
        var up = 0.0; var down = 0.0
        var ref = ele[0]
        for (e in ele) {
            val d = e - ref
            if (d >= threshold) { up += d; ref = e } else if (d <= -threshold) { down -= d; ref = e }
        }
        return up to down
    }

    fun track(parts: List<List<Coord>>, eleOverride: List<Double?>? = null, threshold: Double = 3.0): TrackStats {
        val all = parts.flatten()
        val length = parts.sumOf { GeoMath.length(it) }
        val eles = (eleOverride ?: all.map { it.ele }).filterNotNull()
        val (up, down) = ascentDescent(eles, threshold)
        val times = all.mapNotNull { it.time }
        var moving = 0L; var stopped = 0L; var maxSpeed = 0.0
        if (times.size >= 2) {
            for (p in parts) {
                // velocidade suavizada em janela de ~3 pontos
                for (i in 1 until p.size) {
                    val a = p[i - 1]; val b = p[i]
                    if (a.time == null || b.time == null) continue
                    val dt = b.time - a.time
                    if (dt <= 0) continue
                    val d = GeoMath.distance(a, b)
                    val v = d / (dt / 1000.0)
                    if (v > 0.4 && dt < 5 * 60_000) moving += dt else stopped += dt
                    val j = (i - 3).coerceAtLeast(0)
                    val pj = p[j]
                    if (pj.time != null && b.time - pj.time > 0) {
                        val vs = GeoMath.length(p.subList(j, i + 1)) / ((b.time - pj.time) / 1000.0)
                        if (vs < 120) maxSpeed = max(maxSpeed, vs)
                    }
                }
            }
        }
        val duration = if (times.size >= 2) times.last() - times.first() else null
        return TrackStats(
            length = length, ascent = up, descent = down,
            minEle = eles.minOrNull(), maxEle = eles.maxOrNull(),
            duration = duration,
            movingTime = if (duration != null) moving else null,
            stoppedTime = if (duration != null) stopped else null,
            avgSpeed = duration?.takeIf { it > 0 }?.let { length / (it / 1000.0) },
            avgMovingSpeed = moving.takeIf { it > 0 }?.let { length / (it / 1000.0) },
            maxSpeed = if (duration != null) maxSpeed else null,
            points = all.size,
        )
    }
}

/** Perfil longitudinal: distância acumulada × altitude. */
data class Profile(val dist: DoubleArray, val ele: DoubleArray, val coords: List<Coord>) {
    val size get() = dist.size
    val total get() = if (dist.isEmpty()) 0.0 else dist.last()

    companion object {
        /**
         * Monta o perfil. [sampler] fornece altitude do MDE (ou null). Se [useDem] for false
         * usa a altitude gravada nos vértices. Vértices sem altitude são interpolados.
         */
        fun build(parts: List<List<Coord>>, useDem: Boolean, sampler: ((Double, Double) -> Double?)?, maxStep: Double = 0.0): Profile? {
            val coords = ArrayList<Coord>()
            for (p in parts) {
                for ((i, c) in p.withIndex()) {
                    if (maxStep > 0 && i > 0) {
                        val a = p[i - 1]
                        val d = GeoMath.distance(a, c)
                        val n = (d / maxStep).toInt()
                        for (k in 1 until n) {
                            val t = k.toDouble() / n
                            coords += Coord(a.lat + (c.lat - a.lat) * t, a.lon + (c.lon - a.lon) * t,
                                if (a.ele != null && c.ele != null) a.ele + (c.ele - a.ele) * t else null)
                        }
                    }
                    coords += c
                }
            }
            if (coords.size < 2) return null
            val raw: List<Double?> = coords.map { c -> if (useDem && sampler != null) sampler(c.lat, c.lon) else c.ele }
            if (raw.all { it == null }) return null
            val filled = DoubleArray(coords.size)
            var lastIdx = -1
            for (i in raw.indices) {
                val v = raw[i]
                if (v != null) {
                    if (lastIdx == -1) for (k in 0 until i) filled[k] = v
                    else if (i - lastIdx > 1) {
                        val a = raw[lastIdx]!!
                        for (k in lastIdx + 1 until i) filled[k] = a + (v - a) * (k - lastIdx) / (i - lastIdx)
                    }
                    filled[i] = v; lastIdx = i
                }
            }
            for (k in lastIdx + 1 until raw.size) filled[k] = raw[lastIdx]!!
            return Profile(GeoMath.cumulative(coords), filled, coords)
        }
    }

    fun indexAt(distance: Double): Int {
        var lo = 0; var hi = dist.size - 1
        while (lo < hi) {
            val m = (lo + hi) / 2
            if (dist[m] < distance) lo = m + 1 else hi = m
        }
        if (lo > 0 && abs(dist[lo - 1] - distance) < abs(dist[lo] - distance)) return lo - 1
        return lo
    }
}
