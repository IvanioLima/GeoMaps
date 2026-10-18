package br.geomaps.core

import kotlin.math.*

/** Cálculos de tiles XYZ (Web Mercator) para download de mapas offline. */
object TileMath {
    fun lonToX(lon: Double, z: Int): Int = floor((lon + 180) / 360 * (1 shl z)).toInt().coerceIn(0, (1 shl z) - 1)

    fun latToY(lat: Double, z: Int): Int {
        val l = Math.toRadians(lat.coerceIn(-85.0511, 85.0511))
        return floor((1 - ln(tan(l) + 1 / cos(l)) / PI) / 2 * (1 shl z)).toInt().coerceIn(0, (1 shl z) - 1)
    }

    fun xToLon(x: Int, z: Int) = x.toDouble() / (1 shl z) * 360 - 180
    fun yToLat(y: Int, z: Int): Double {
        val n = PI - 2 * PI * y / (1 shl z)
        return Math.toDegrees(atan(sinh(n)))
    }

    /** Limites (sul, oeste, norte, leste) do tile. */
    fun tileBounds(x: Int, y: Int, z: Int) = GeoMath.Bounds(yToLat(y + 1, z), xToLon(x, z), yToLat(y, z), xToLon(x + 1, z))

    /** BBOX em EPSG:3857 (minx,miny,maxx,maxy) para WMS. */
    fun bbox3857(x: Int, y: Int, z: Int): String {
        val b = tileBounds(x, y, z)
        val (minx, miny) = WebMercator.forward(b.minLat, b.minLon)
        val (maxx, maxy) = WebMercator.forward(b.maxLat, b.maxLon)
        return String.format(java.util.Locale.US, "%.6f,%.6f,%.6f,%.6f", minx, miny, maxx, maxy)
    }

    data class Tile(val z: Int, val x: Int, val y: Int)

    /** Tiles que cobrem a área (retângulo, ou polígono livre se [ring] for informado). */
    fun tiles(b: GeoMath.Bounds, minZ: Int, maxZ: Int, ring: List<Coord>? = null): Sequence<Tile> = sequence {
        for (z in minZ..maxZ) {
            val x0 = lonToX(b.minLon, z); val x1 = lonToX(b.maxLon, z)
            val y0 = latToY(b.maxLat, z); val y1 = latToY(b.minLat, z)
            for (x in x0..x1) for (y in y0..y1) {
                if (ring == null || tileIntersectsRing(tileBounds(x, y, z), ring)) yield(Tile(z, x, y))
            }
        }
    }

    fun count(b: GeoMath.Bounds, minZ: Int, maxZ: Int, ring: List<Coord>? = null): Long {
        if (ring == null) {
            var n = 0L
            for (z in minZ..maxZ) {
                n += (lonToX(b.maxLon, z) - lonToX(b.minLon, z) + 1).toLong() * (latToY(b.minLat, z) - latToY(b.maxLat, z) + 1)
            }
            return n
        }
        return tiles(b, minZ, maxZ, ring).count().toLong()
    }

    fun tileIntersectsRing(t: GeoMath.Bounds, ring: List<Coord>): Boolean {
        if (ring.any { t.contains(it.lat, it.lon) }) return true
        val corners = listOf(t.minLat to t.minLon, t.minLat to t.maxLon, t.maxLat to t.minLon, t.maxLat to t.maxLon)
        if (corners.any { (la, lo) -> GeoMath.pointInRing(la, lo, ring) }) return true
        val edges = listOf(
            corners[0] to corners[1], corners[1] to corners[3], corners[3] to corners[2], corners[2] to corners[0],
        )
        for (i in ring.indices) {
            val a = ring[i]; val b = ring[(i + 1) % ring.size]
            for ((p, q) in edges) if (segIntersect(a.lon, a.lat, b.lon, b.lat, p.second, p.first, q.second, q.first)) return true
        }
        return false
    }

    private fun segIntersect(ax: Double, ay: Double, bx: Double, by: Double, cx: Double, cy: Double, dx: Double, dy: Double): Boolean {
        fun cross(ox: Double, oy: Double, px: Double, py: Double, qx: Double, qy: Double) = (px - ox) * (qy - oy) - (py - oy) * (qx - ox)
        val d1 = cross(cx, cy, dx, dy, ax, ay); val d2 = cross(cx, cy, dx, dy, bx, by)
        val d3 = cross(ax, ay, bx, by, cx, cy); val d4 = cross(ax, ay, bx, by, dx, dy)
        return ((d1 > 0 && d2 < 0) || (d1 < 0 && d2 > 0)) && ((d3 > 0 && d4 < 0) || (d3 < 0 && d4 > 0))
    }

    /** Substitui os marcadores do modelo de URL. */
    fun url(template: String, t: Tile): String {
        val sub = listOf("a", "b", "c")[(t.x + t.y) % 3]
        val quad = StringBuilder().also { sb ->
            for (i in t.z downTo 1) {
                var d = 0; val mask = 1 shl (i - 1)
                if (t.x and mask != 0) d += 1
                if (t.y and mask != 0) d += 2
                sb.append(d)
            }
        }.toString()
        return template.replace("{z}", t.z.toString()).replace("{x}", t.x.toString()).replace("{y}", t.y.toString())
            .replace("{-y}", ((1 shl t.z) - 1 - t.y).toString())
            .replace("{s}", sub).replace("{quadkey}", quad)
            .replace("{bbox-epsg-3857}", bbox3857(t.x, t.y, t.z))
            .replace("{TileMatrix}", t.z.toString()).replace("{TileCol}", t.x.toString()).replace("{TileRow}", t.y.toString())
    }
}
