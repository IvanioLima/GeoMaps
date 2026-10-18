package br.geomaps.data

import br.geomaps.core.Coord
import br.geomaps.core.GeoMath
import br.geomaps.core.Geom
import br.geomaps.core.Utm
import org.locationtech.jts.geom.Coordinate
import org.locationtech.jts.geom.Geometry
import org.locationtech.jts.geom.GeometryFactory
import org.locationtech.jts.geom.MultiPolygon
import org.locationtech.jts.geom.Polygon
import org.locationtech.jts.geom.LinearRing

/** Buffer (área de influência) calculado em metros na projeção UTM do elemento, com JTS. */
object GeoBuffer {
    fun compute(g: Geom, distance: Double): Geom.Poly {
        val all = g.allCoords()
        val c = GeoMath.centroid(all)
        val zone = Utm.zoneFor(c.lat, c.lon)
        val south = c.lat < 0
        val tm = Utm.tm(zone, south)
        val gf = GeometryFactory()
        fun jc(p: Coord): Coordinate { val (x, y) = tm.forward(p.lat, p.lon); return Coordinate(x, y) }
        val jts: Geometry = when (g) {
            is Geom.Pt -> gf.createPoint(jc(g.c))
            is Geom.Line -> gf.createMultiLineString(g.parts.map { p -> gf.createLineString(p.map { jc(it) }.toTypedArray()) }.toTypedArray())
            is Geom.Poly -> gf.createMultiPolygon(g.polys.map { poly ->
                val rings = poly.map { r -> gf.createLinearRing((r + r.first()).map { jc(it) }.toTypedArray()) }
                gf.createPolygon(rings.first(), rings.drop(1).toTypedArray<LinearRing>())
            }.toTypedArray())
        }
        val buf = jts.buffer(distance, 16)
        fun ring(cs: Array<Coordinate>): List<Coord> = cs.map { k -> val (la, lo) = tm.inverse(k.x, k.y); Coord(la, lo) }.let { Geom.Poly.openRing(it) }
        fun poly(p: Polygon): List<List<Coord>> =
            listOf(ring(p.exteriorRing.coordinates)) + (0 until p.numInteriorRing).map { ring(p.getInteriorRingN(it).coordinates) }
        val polys = when (buf) {
            is Polygon -> listOf(poly(buf))
            is MultiPolygon -> (0 until buf.numGeometries).map { poly(buf.getGeometryN(it) as Polygon) }
            else -> emptyList()
        }
        return Geom.Poly(polys)
    }
}
