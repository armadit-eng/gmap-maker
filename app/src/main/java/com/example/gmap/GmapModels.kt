package com.example.gmap

import androidx.compose.ui.graphics.Color

data class LatLon(
    val lat: Double,
    val lon: Double
)

data class WayItem(
    val cls: Int,
    val name: String,
    val pts: List<LatLon>,
    val poly: Boolean = false
)

data class PlaceItem(
    val lat: Double,
    val lon: Double,
    val rank: Int,
    val name: String
)

data class LayerOptions(
    val track: Boolean = true,
    val paths: Boolean = false,
    val streams: Boolean = true,
    val ditch: Boolean = false,
    val areas: Boolean = true,
    val farm: Boolean = true,
    val built: Boolean = true
)

data class GmapConfig(
    val latC: Double,
    val lonC: Double,
    val radiusM: Double,
    val cellE6: Int,
    val tolM: Double
)

data class GmapStats(
    val names: Int,
    val places: Int,
    val cols: Int,
    val rows: Int,
    val size: Int,
    val maxCell: Int,
    val records: Int,
    val tol: Double,
    val passes: Int,
    val dropped: Int,
    val byCls: IntArray
)

data class BuildResult(
    val bytes: ByteArray,
    val stats: GmapStats
)

data class RoutePoint(
    val lat: Double,
    val lon: Double,
    val cumM: Double
)

data class RouteStep(
    val ty: Int,
    val idx: Int,
    val cumM: Double,
    val name: String
)

data class NavRoute(
    val pts: List<RoutePoint>,
    val steps: List<RouteStep>,
    val from: LatLon,
    val to: LatLon,
    val lenM: Double,
    val durationSec: Double
)

data class CorridorPlan(
    val latC: Double,
    val lonC: Double,
    val radiusM: Double,
    val blocks: Set<Pair<Int, Int>>
)

data class GmapDecodedItem(
    val cls: Int,
    val pts: List<Pair<Int, Int>> // [latE6, lonE6]
)

data class GmapDecoded(
    val oLat: Int,
    val oLon: Int,
    val cell: Int,
    val cols: Int,
    val rows: Int,
    val style: Int,
    val items: List<GmapDecodedItem>
)

data class PlaceDecoded(
    val latE6: Int,
    val lonE6: Int,
    val rank: Int,
    val name: String
)

data class NamesDecoded(
    val cells: List<List<String>?>,
    val places: List<PlaceDecoded>
)

object MapStyles {
    val styleNames = listOf(
        "Standar (mirip Carto)",
        "Malam (gelap)",
        "Kontras siang",
        "Alam / offroad",
        "Carto Voyager (berwarna)"
    )

    val presets: List<LayerOptions> = listOf(
        LayerOptions(track = true, paths = false, streams = true, ditch = false, areas = true, farm = true, built = true),
        LayerOptions(track = true, paths = false, streams = true, ditch = false, areas = true, farm = true, built = true),
        LayerOptions(track = true, paths = false, streams = false, ditch = false, areas = true, farm = false, built = false),
        LayerOptions(track = true, paths = true, streams = true, ditch = false, areas = true, farm = true, built = false),
        LayerOptions(track = true, paths = false, streams = true, ditch = false, areas = true, farm = true, built = true)
    )

    private fun c(hex: Long): Color = Color(hex or 0xFF000000L)

    val palettes: List<List<Color>> = listOf(
        // 0: Standar
        listOf(
            c(0xF1EFE8), c(0xE892A2), c(0xF9B26B), c(0xF6E27A),
            c(0x8C9299), c(0xB8BDC2), c(0xFFFFFF), c(0xAAD3DF),
            c(0x495057), c(0x1C7ED6), c(0xFFFFFF), c(0xE03131),
            c(0x212529), c(0xADD19E), c(0xCDEBB0), c(0xE0DFDF)
        ),
        // 1: Malam
        listOf(
            c(0x1E252E), c(0xD9667A), c(0xE0A050), c(0xC9B84A),
            c(0x0F141A), c(0x3A4552), c(0x56657A), c(0x1F3F57),
            c(0x7A8794), c(0x3DA5FF), c(0x0F141A), c(0xFF5A5A),
            c(0xF2F5F7), c(0x1F3A2A), c(0x24372C), c(0x2A323C)
        ),
        // 2: Kontras siang
        listOf(
            c(0xF7F7F2), c(0xE03131), c(0xF08C00), c(0xFAB005),
            c(0x343A40), c(0x868E96), c(0xFFFFFF), c(0x74B9E8),
            c(0x212529), c(0x1C7ED6), c(0xFFFFFF), c(0xE03131),
            c(0x000000), c(0x8FCB7F), c(0xC3E8A8), c(0xDEDDDD)
        ),
        // 3: Alam / offroad
        listOf(
            c(0xEFEBDD), c(0xC8553D), c(0xE0A050), c(0xF2D98A),
            c(0x7A6A55), c(0xA0714D), c(0xFFFDF5), c(0x9CCFE6),
            c(0x5C534A), c(0x1C7ED6), c(0xFFFFFF), c(0xE03131),
            c(0x2B2A26), c(0x9CC98A), c(0xD5E8B8), c(0xE3DCCB)
        ),
        // 4: Carto Voyager
        listOf(
            c(0xF3EFE6), c(0xE8667A), c(0xF5A35C), c(0xC0654A),
            c(0xB5A58F), c(0xDDD3C3), c(0x63B96B), c(0x9DCBE6),
            c(0x8A8F96), c(0x1C7ED6), c(0xFFFFFF), c(0xE03131),
            c(0x3B4350), c(0xA9D69B), c(0xBDE5A3), c(0xEADFCF)
        )
    )
}
