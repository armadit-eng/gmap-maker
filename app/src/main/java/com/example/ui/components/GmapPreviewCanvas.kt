package com.example.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.gmap.GmapDecoded
import com.example.gmap.MapStyles
import com.example.gmap.NavRoute
import com.example.ui.theme.Slate700
import kotlin.math.min
import kotlin.math.roundToInt

@Composable
fun GmapPreviewCanvas(
    decoded: GmapDecoded,
    styleIndex: Int,
    navRoute: NavRoute?,
    isNavigationMode: Boolean,
    modifier: Modifier = Modifier
) {
    val palette = MapStyles.palettes.getOrElse(styleIndex) { MapStyles.palettes[0] }

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .clip(RoundedCornerShape(10.dp))
            .border(1.dp, Slate700, RoundedCornerShape(10.dp))
            .testTag("gmap_preview_canvas")
    ) {
        val canvasW = size.width
        val canvasH = size.height
        if (canvasW <= 0f || canvasH <= 0f) return@Canvas

        drawRect(color = palette[0])

        val hE6 = (decoded.rows * decoded.cell).toFloat()
        val wE6 = (decoded.cols * decoded.cell).toFloat()
        if (hE6 <= 0f || wE6 <= 0f) return@Canvas

        val sc = min(canvasW / wE6, canvasH / hE6)
        val pxScale = canvasW / 560f

        val widths = floatArrayOf(7f, 6f, 5.5f, 5f, 4f, 2.2f, 3.2f, 1.5f)
        val fc = intArrayOf(1, 2, 3, 6, if (styleIndex == 4) 10 else 6, 5, 7, 8)
        val cs = intArrayOf(4, 4, 4, 4, 5)
        val fills = mapOf(8 to 7, 9 to 13, 10 to 14, 11 to 15)

        fun mapX(lonE6: Double): Float = ((lonE6 - decoded.oLon) * sc).toFloat()
        fun mapY(latE6: Double): Float = (canvasH - (latE6 - decoded.oLat) * sc).toFloat()

        // 1. Filled area polygons: 11 (built), 10 (green/farm), 9 (forest), 8 (water)
        for (cl in intArrayOf(11, 10, 9, 8)) {
            val fillIdx = fills[cl] ?: continue
            val fillColor = palette[fillIdx]
            for (it in decoded.items) {
                if (it.cls != cl || it.pts.size < 3) continue
                val path = Path()
                it.pts.forEachIndexed { idx, q ->
                    val x = mapX(q.second.toDouble())
                    val y = mapY(q.first.toDouble())
                    if (idx == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                path.close()
                drawPath(path = path, color = fillColor, style = Fill)
            }
        }

        fun drawSmoothedPolyline(pts: List<Pair<Int, Int>>, colorIdx: Int, strokeW: Float) {
            if (pts.size < 2) return
            val path = Path()
            path.moveTo(mapX(pts[0].second.toDouble()), mapY(pts[0].first.toDouble()))
            for (i in 1 until pts.size - 1) {
                val ax = mapX(pts[i].second.toDouble())
                val ay = mapY(pts[i].first.toDouble())
                val bx = mapX(pts[i + 1].second.toDouble())
                val by = mapY(pts[i + 1].first.toDouble())
                path.quadraticTo(ax, ay, (ax + bx) / 2f, (ay + by) / 2f)
            }
            val last = pts.last()
            path.lineTo(mapX(last.second.toDouble()), mapY(last.first.toDouble()))
            drawPath(
                path = path,
                color = palette[colorIdx],
                style = Stroke(
                    width = strokeW * pxScale,
                    cap = StrokeCap.Round,
                    join = StrokeJoin.Round
                )
            )
        }

        fun runPass(classes: IntArray, casing: Boolean) {
            for (cl in classes) {
                for (it in decoded.items) {
                    if (it.cls != cl || it.pts.size < 2) continue
                    if (casing) {
                        if (widths[cl] < 2.5f) continue
                        drawSmoothedPolyline(it.pts, cs[cl], widths[cl] + 2f)
                    } else {
                        val colorIdx = if ((cl == 3 || cl == 4) && widths[cl] < 2.5f) {
                            if (cl == 3) 4 else 5
                        } else {
                            fc[cl]
                        }
                        drawSmoothedPolyline(it.pts, colorIdx, widths[cl])
                    }
                }
            }
        }

        // 2. Streams, railways, service/tracks
        runPass(intArrayOf(6, 7, 5), casing = false)
        // 3. Road casings
        runPass(intArrayOf(4, 3, 2, 1, 0), casing = true)
        // 4. Road cores
        runPass(intArrayOf(4, 3), casing = false)
        runPass(intArrayOf(2, 1, 0), casing = false)

        // 5. Navigation route overlay
        if (isNavigationMode && navRoute != null && navRoute.pts.size >= 2) {
            val routePath = Path()
            navRoute.pts.forEachIndexed { idx, rp ->
                val x = mapX((rp.lon * 1e6).roundToInt().toDouble())
                val y = mapY((rp.lat * 1e6).roundToInt().toDouble())
                if (idx == 0) routePath.moveTo(x, y) else routePath.lineTo(x, y)
            }
            drawPath(
                path = routePath,
                color = palette[10],
                style = Stroke(width = 7f * pxScale, cap = StrokeCap.Round, join = StrokeJoin.Round)
            )
            drawPath(
                path = routePath,
                color = palette[9],
                style = Stroke(width = 4f * pxScale, cap = StrokeCap.Round, join = StrokeJoin.Round)
            )
        }
    }
}
