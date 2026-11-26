package com.example

import com.example.gmap.GmapCodec
import com.example.gmap.GmapConfig
import com.example.gmap.LatLon
import com.example.gmap.LayerOptions
import com.example.gmap.NavRoute
import com.example.gmap.PlaceItem
import com.example.gmap.RoutePoint
import com.example.gmap.RouteStep
import com.example.gmap.WayItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GmapCodecTest {

    @Test
    fun buildAndReadGmapWithNamesAndRoute() {
        val ways = listOf(
            WayItem(
                cls = 1,
                name = "Jalan Raya Solo",
                pts = listOf(
                    LatLon(-7.340, 111.060),
                    LatLon(-7.333, 111.072),
                    LatLon(-7.326, 111.084)
                )
            ),
            WayItem(
                cls = 8,
                name = "",
                pts = listOf(
                    LatLon(-7.338, 111.065),
                    LatLon(-7.338, 111.075),
                    LatLon(-7.330, 111.075),
                    LatLon(-7.330, 111.065),
                    LatLon(-7.338, 111.065)
                ),
                poly = true
            )
        )
        val places = listOf(
            PlaceItem(lat = -7.3331, lon = 111.0719, rank = 3, name = "Sragen")
        )
        val res = GmapCodec.buildGmap(
            ways = ways,
            cfg = GmapConfig(latC = -7.3331, lonC = 111.0719, radiusM = 3000.0, cellE6 = 9000, tolM = 3.0),
            places = places
        )
        res.bytes[30] = 4.toByte()

        val nav = NavRoute(
            pts = listOf(
                RoutePoint(-7.340, 111.060, 0.0),
                RoutePoint(-7.326, 111.084, 3100.0)
            ),
            steps = listOf(
                RouteStep(ty = 8, idx = 0, cumM = 0.0, name = "Jalan Raya Solo"),
                RouteStep(ty = 7, idx = 1, cumM = 3100.0, name = "")
            ),
            from = LatLon(-7.340, 111.060),
            to = LatLon(-7.326, 111.084),
            lenM = 3100.0,
            durationSec = 240.0
        )
        val packaged = GmapCodec.withRoute(res.bytes, nav)

        val decoded = GmapCodec.readGmap(packaged)
        assertEquals(4, decoded.style)
        assertTrue(decoded.items.isNotEmpty())

        val names = GmapCodec.readNames(packaged)
        assertNotNull(names)
        assertEquals(1, names!!.places.size)
        assertEquals("Sragen", names.places[0].name)
        assertTrue(names.cells.any { it?.contains("Jalan Raya Solo") == true })
    }

    @Test
    fun classOfAndAreaClassMatchSpec() {
        val opts = LayerOptions(track = true, paths = false, streams = true, ditch = false, areas = true, farm = true, built = false)
        assertEquals(0, GmapCodec.classOf(mapOf("highway" to "motorway_link"), opts))
        assertEquals(5, GmapCodec.classOf(mapOf("highway" to "track"), opts))
        assertEquals(-1, GmapCodec.classOf(mapOf("highway" to "footway"), opts))
        assertEquals(6, GmapCodec.classOf(mapOf("waterway" to "stream"), opts))
        assertEquals(8, GmapCodec.areaClass(mapOf("natural" to "water"), opts))
        assertEquals(10, GmapCodec.areaClass(mapOf("landuse" to "farmland"), opts))
        assertEquals(-1, GmapCodec.areaClass(mapOf("landuse" to "residential"), opts))
    }

    @Test
    fun assembleRingsClosesMultipolygonWays() {
        val seg1 = listOf(LatLon(0.0, 0.0), LatLon(0.0, 1.0))
        val seg2 = listOf(LatLon(1.0, 1.0), LatLon(0.0, 1.0)) // reversed
        val seg3 = listOf(LatLon(1.0, 1.0), LatLon(1.0, 0.0), LatLon(0.0, 0.0))
        val rings = GmapCodec.assembleRings(listOf(seg1, seg2, seg3))
        assertEquals(1, rings.size)
        assertEquals(rings[0].first(), rings[0].last())
    }
}
