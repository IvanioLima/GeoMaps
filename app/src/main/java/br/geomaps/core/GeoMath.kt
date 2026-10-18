package br.geomaps.core

import kotlin.math.*

object GeoMath {
    const val R = 6371008.8

    /** Distância geodésica (Vincenty no elipsoide GRS80; recorre à haversine se não convergir). */
    fun distance(a: Coord, b: Coord) = distance(a.lat, a.lon, b.lat, b.lon)

    fun distance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        if (lat1 == lat2 && lon1 == lon2) return 0.0
        val ae = 6378137.0; val f = 1 / 298.257222101; val b = ae * (1 - f)
        val L = Math.toRadians(lon2 - lon1)
        val u1 = atan((1 - f) * tan(Math.toRadians(lat1)))
        val u2 = atan((1 - f) * tan(Math.toRadians(lat2)))
        val sinU1 = sin(u1); val cosU1 = cos(u1); val sinU2 = sin(u2); val cosU2 = cos(u2)
        var lambda = L
        var iter = 0
        var sinSigma: Double; var cosSigma: Double; var sigma: Double; var cos2Alpha: Double; var cos2SigmaM: Double
        do {
            val sinL = sin(lambda); val cosL = cos(lambda)
            sinSigma = sqrt((cosU2 * sinL).pow(2) + (cosU1 * sinU2 - sinU1 * cosU2 * cosL).pow(2))
            if (sinSigma == 0.0) return 0.0
            cosSigma = sinU1 * sinU2 + cosU1 * cosU2 * cosL
            sigma = atan2(sinSigma, cosSigma)
            val sinAlpha = cosU1 * cosU2 * sinL / sinSigma
            cos2Alpha = 1 - sinAlpha * sinAlpha
            cos2SigmaM = if (cos2Alpha != 0.0) cosSigma - 2 * sinU1 * sinU2 / cos2Alpha else 0.0
            val c = f / 16 * cos2Alpha * (4 + f * (4 - 3 * cos2Alpha))
            val prev = lambda
            lambda = L + (1 - c) * f * sinAlpha * (sigma + c * sinSigma * (cos2SigmaM + c * cosSigma * (-1 + 2 * cos2SigmaM * cos2SigmaM)))
            iter++
            if (abs(lambda - prev) < 1e-12) break
        } while (iter < 100)
        if (iter >= 100) return haversine(lat1, lon1, lat2, lon2)
        val uSq = cos2Alpha * (ae * ae - b * b) / (b * b)
        val A = 1 + uSq / 16384 * (4096 + uSq * (-768 + uSq * (320 - 175 * uSq)))
        val B = uSq / 1024 * (256 + uSq * (-128 + uSq * (74 - 47 * uSq)))
        val dSigma = B * sinSigma * (cos2SigmaM + B / 4 * (cosSigma * (-1 + 2 * cos2SigmaM * cos2SigmaM) -
            B / 6 * cos2SigmaM * (-3 + 4 * sinSigma * sinSigma) * (-3 + 4 * cos2SigmaM * cos2SigmaM)))
        return b * A * (sigma - dSigma)
    }

    fun haversine(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1); val dLon = Math.toRadians(lon2 - lon1)
        val h = sin(dLat / 2).pow(2) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2)
        return 2 * R * asin(min(1.0, sqrt(h)))
    }

    /** Rumo inicial (azimute verdadeiro) de a para b, 0..360. */
    fun bearing(a: Coord, b: Coord) = bearing(a.lat, a.lon, b.lat, b.lon)

    fun bearing(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val p1 = Math.toRadians(lat1); val p2 = Math.toRadians(lat2); val dl = Math.toRadians(lon2 - lon1)
        val y = sin(dl) * cos(p2)
        val x = cos(p1) * sin(p2) - sin(p1) * cos(p2) * cos(dl)
        return (Math.toDegrees(atan2(y, x)) + 360) % 360
    }

    fun destination(lat: Double, lon: Double, bearingDeg: Double, dist: Double): Pair<Double, Double> {
        val d = dist / R; val b = Math.toRadians(bearingDeg)
        val p1 = Math.toRadians(lat); val l1 = Math.toRadians(lon)
        val p2 = asin(sin(p1) * cos(d) + cos(p1) * sin(d) * cos(b))
        val l2 = l1 + atan2(sin(b) * sin(d) * cos(p1), cos(d) - sin(p1) * sin(p2))
        return Math.toDegrees(p2) to ((Math.toDegrees(l2) + 540) % 360 - 180)
    }

    fun length(c: List<Coord>): Double {
        var s = 0.0
        for (i in 1 until c.size) s += distance(c[i - 1], c[i])
        return s
    }

    fun length(g: Geom): Double = when (g) {
        is Geom.Pt -> 0.0
        is Geom.Line -> g.parts.sumOf { length(it) }
        is Geom.Poly -> perimeter(g)
    }

    fun perimeter(g: Geom.Poly): Double = g.polys.sumOf { poly -> poly.sumOf { r -> if (r.size < 2) 0.0 else length(r + r.first()) } }

    /** Área plana na projeção UTM (como no CAD), em m². Usa o fuso indicado ou o do centróide. */
    fun area(g: Geom.Poly, zone: Int? = null, south: Boolean? = null): Double {
        val pts = g.allCoords()
        if (pts.size < 3) return 0.0
        val c = centroid(pts)
        val z = zone ?: Utm.zoneFor(c.lat, c.lon)
        val s = south ?: (c.lat < 0)
        val tm = Utm.tm(z, s)
        var total = 0.0
        for (poly in g.polys) {
            poly.forEachIndexed { idx, ring ->
                val a = abs(ringAreaPlanar(ring.map { tm.forward(it.lat, it.lon) }))
                total += if (idx == 0) a else -a
            }
        }
        return total
    }

    fun ringAreaPlanar(p: List<Pair<Double, Double>>): Double {
        if (p.size < 3) return 0.0
        var s = 0.0
        for (i in p.indices) {
            val (x1, y1) = p[i]; val (x2, y2) = p[(i + 1) % p.size]
            s += x1 * y2 - x2 * y1
        }
        return s / 2
    }

    /** Área assinada no plano lon/lat (positiva = anti-horário). */
    fun signedAreaLonLat(r: List<Coord>): Double = ringAreaPlanar(r.map { it.lon to it.lat })

    fun centroid(c: List<Coord>): Coord {
        if (c.isEmpty()) return Coord(0.0, 0.0)
        return Coord(c.sumOf { it.lat } / c.size, c.sumOf { it.lon } / c.size)
    }

    data class Bounds(val minLat: Double, val minLon: Double, val maxLat: Double, val maxLon: Double) {
        val center get() = Coord((minLat + maxLat) / 2, (minLon + maxLon) / 2)
        fun contains(lat: Double, lon: Double) = lat in minLat..maxLat && lon in minLon..maxLon
        fun union(o: Bounds) = Bounds(min(minLat, o.minLat), min(minLon, o.minLon), max(maxLat, o.maxLat), max(maxLon, o.maxLon))
        fun intersects(o: Bounds) = !(o.minLat > maxLat || o.maxLat < minLat || o.minLon > maxLon || o.maxLon < minLon)
    }

    fun bounds(c: List<Coord>): Bounds? {
        if (c.isEmpty()) return null
        return Bounds(c.minOf { it.lat }, c.minOf { it.lon }, c.maxOf { it.lat }, c.maxOf { it.lon })
    }

    /** Projeção local equiretangular (metros) centrada em ref — boa para distâncias de até dezenas de km. */
    class Local(val ref: Coord) {
        private val kx = cos(Math.toRadians(ref.lat)) * PI / 180 * R
        private val ky = PI / 180 * R
        fun x(c: Coord) = (c.lon - ref.lon) * kx
        fun y(c: Coord) = (c.lat - ref.lat) * ky
        fun toCoord(x: Double, y: Double) = Coord(ref.lat + y / ky, ref.lon + x / kx)
    }

    data class Nearest(val part: Int, val segIndex: Int, val t: Double, val dist: Double, val point: Coord, val alongDist: Double)

    /** Ponto mais próximo de p sobre a linha (distância lateral, posição ao longo da linha). */
    fun nearestOnLine(p: Coord, parts: List<List<Coord>>): Nearest? {
        val local = Local(p)
        var best: Nearest? = null
        var along = 0.0
        parts.forEachIndexed { pi, line ->
            if (line.size == 1) {
                val d = distance(p, line[0])
                if (best == null || d < best!!.dist) best = Nearest(pi, 0, 0.0, d, line[0], along)
            }
            for (i in 1 until line.size) {
                val a = line[i - 1]; val b = line[i]
                val ax = local.x(a); val ay = local.y(a); val bx = local.x(b); val by = local.y(b)
                val dx = bx - ax; val dy = by - ay
                val l2 = dx * dx + dy * dy
                val t = if (l2 == 0.0) 0.0 else ((-ax) * dx + (-ay) * dy) / l2
                val tc = t.coerceIn(0.0, 1.0)
                val px = ax + tc * dx; val py = ay + tc * dy
                val d = sqrt(px * px + py * py)
                val segLen = distance(a, b)
                if (best == null || d < best!!.dist) {
                    best = Nearest(pi, i - 1, tc, d, local.toCoord(px, py), along + segLen * tc)
                }
                along += segLen
            }
        }
        return best
    }

    /** Distâncias acumuladas ao longo da lista de vértices. */
    fun cumulative(c: List<Coord>): DoubleArray {
        val out = DoubleArray(c.size)
        for (i in 1 until c.size) out[i] = out[i - 1] + distance(c[i - 1], c[i])
        return out
    }

    fun pointInRing(lat: Double, lon: Double, ring: List<Coord>): Boolean {
        var inside = false
        var j = ring.size - 1
        for (i in ring.indices) {
            val yi = ring[i].lat; val xi = ring[i].lon; val yj = ring[j].lat; val xj = ring[j].lon
            if ((yi > lat) != (yj > lat) && lon < (xj - xi) * (lat - yi) / (yj - yi) + xi) inside = !inside
            j = i
        }
        return inside
    }

    fun pointInPoly(lat: Double, lon: Double, g: Geom.Poly): Boolean = g.polys.any { poly ->
        poly.isNotEmpty() && pointInRing(lat, lon, poly[0]) && poly.drop(1).none { pointInRing(lat, lon, it) }
    }
}
