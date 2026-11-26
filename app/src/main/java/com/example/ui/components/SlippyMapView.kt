package com.example.ui.components

import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.TileSettings
import com.example.data.TileSource
import com.example.gmap.LatLon
import com.example.gmap.NavRoute
import com.example.ui.theme.Blue600
import com.example.ui.theme.Green700
import com.example.ui.theme.Red600
import com.example.ui.theme.Slate700
import com.example.ui.theme.Slate800
import com.example.ui.theme.Slate900
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import java.util.Collections
import kotlin.math.PI
import kotlin.math.asinh
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sinh
import kotlin.math.tan

private class TileCacheManager(private val client: OkHttpClient) {
    private val cache = object : LruCache<String, ImageBitmap>(120) {}
    private val inFlight = Collections.synchronizedSet(HashSet<String>())

    fun get(key: String): ImageBitmap? = cache.get(key)

    fun clear() {
        cache.evictAll()
    }

    suspend fun fetchTile(
        key: String,
        url: String,
        onLoaded: () -> Unit,
        onError: () -> Unit
    ) {
        if (cache.get(key) != null || !inFlight.add(key)) return
        try {
            val bmp = withContext(Dispatchers.IO) {
                val req = Request.Builder().url(url).get().build()
                client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@withContext null
                    val bytes = resp.body?.bytes() ?: return@withContext null
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
                }
            }
            if (bmp != null) {
                cache.put(key, bmp)
                onLoaded()
            } else {
                onError()
            }
        } catch (_: Exception) {
            onError()
        } finally {
            inFlight.remove(key)
        }
    }
}

private fun latToWorldY(lat: Double, worldPx: Double): Double {
    val clamped = lat.coerceIn(-85.0511, 85.0511)
    val rad = Math.toRadians(clamped)
    return ((1.0 - ln(tan(rad) + 1.0 / cos(rad)) / PI) / 2.0) * worldPx
}

private fun lonToWorldX(lon: Double, worldPx: Double): Double {
    return ((lon + 180.0) / 360.0) * worldPx
}

private fun worldYToLat(y: Double, worldPx: Double): Double {
    val n = PI - (2.0 * PI * y) / worldPx
    return Math.toDegrees(atan(sinh(n)))
}

private fun worldXToLon(x: Double, worldPx: Double): Double {
    return (x / worldPx) * 360.0 - 180.0
}

@Composable
fun SlippyMapView(
    tileClient: OkHttpClient,
    tileSettings: TileSettings,
    isNavigationMode: Boolean,
    centerMarker: LatLon?,
    fromMarker: LatLon?,
    toMarker: LatLon?,
    navRoute: NavRoute?,
    viewportTarget: LatLon,
    viewportTrigger: Int,
    onMapTap: (LatLon) -> Unit,
    onTileError: () -> Unit,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    val tilePx = remember(density) { with(density) { 256.dp.toPx().toDouble() } }
    val tileCache = remember(tileClient) { TileCacheManager(tileClient) }
    val scope = rememberCoroutineScope()
    val textMeasurer = rememberTextMeasurer()

    var mapLat by remember { mutableDoubleStateOf(viewportTarget.lat) }
    var mapLon by remember { mutableDoubleStateOf(viewportTarget.lon) }
    var mapZoom by remember { mutableDoubleStateOf(12.0) }
    var tileInvalidate by remember { mutableIntStateOf(0) }
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    var cartoErrorReported by remember(tileSettings) { mutableStateOf(false) }

    LaunchedEffect(tileSettings) {
        tileCache.clear()
        tileInvalidate++
    }

    LaunchedEffect(viewportTrigger) {
        if (navRoute != null && navRoute.pts.size >= 2 && canvasSize.width > 0 && canvasSize.height > 0) {
            var minLat = 90.0
            var maxLat = -90.0
            var minLon = 180.0
            var maxLon = -180.0
            for (pt in navRoute.pts) {
                minLat = min(minLat, pt.lat)
                maxLat = max(maxLat, pt.lat)
                minLon = min(minLon, pt.lon)
                maxLon = max(maxLon, pt.lon)
            }
            mapLat = (minLat + maxLat) / 2.0
            mapLon = (minLon + maxLon) / 2.0
            val latSpan = max(0.005, maxLat - minLat)
            val lonSpan = max(0.005, maxLon - minLon)
            val targetZoomLon = ln((canvasSize.width * 0.78) / (tilePx * lonSpan / 360.0)) / ln(2.0)
            val yMin = latToWorldY(maxLat, 1.0)
            val yMax = latToWorldY(minLat, 1.0)
            val ySpan = max(1e-6, yMax - yMin)
            val targetZoomLat = ln((canvasSize.height * 0.78) / (tilePx * ySpan)) / ln(2.0)
            mapZoom = min(targetZoomLon, targetZoomLat).coerceIn(4.0, 17.0)
        } else {
            mapLat = viewportTarget.lat
            mapLon = viewportTarget.lon
        }
    }

    val latestOnMapTap by rememberUpdatedState(onMapTap)
    val latestOnTileError by rememberUpdatedState(onTileError)

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(Slate800)
            .border(1.dp, Slate700, RoundedCornerShape(10.dp))
            .testTag("interactive_map")
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .onSizeChanged { canvasSize = it }
                .pointerInput(tilePx) {
                    detectTapGestures { offset ->
                        val w = size.width.toDouble()
                        val h = size.height.toDouble()
                        if (w <= 0.0 || h <= 0.0) return@detectTapGestures
                        val worldPx = tilePx * 2.0.pow(mapZoom)
                        val centerX = lonToWorldX(mapLon, worldPx)
                        val centerY = latToWorldY(mapLat, worldPx)
                        val tapX = centerX - w / 2.0 + offset.x
                        val tapY = centerY - h / 2.0 + offset.y
                        val lat = worldYToLat(tapY, worldPx).coerceIn(-85.0, 85.0)
                        val lon = worldXToLon(tapX, worldPx).coerceIn(-180.0, 180.0)
                        latestOnMapTap(LatLon(lat, lon))
                    }
                }
                .pointerInput(tilePx) {
                    detectTransformGestures { _, pan, zoomChange, _ ->
                        val newZoom = (mapZoom + ln(zoomChange.toDouble()) / ln(2.0)).coerceIn(3.0, 18.0)
                        val worldPx = tilePx * 2.0.pow(newZoom)
                        val centerX = lonToWorldX(mapLon, worldPx) - pan.x
                        val centerY = latToWorldY(mapLat, worldPx) - pan.y
                        mapZoom = newZoom
                        mapLat = worldYToLat(centerY, worldPx).coerceIn(-85.0, 85.0)
                        mapLon = worldXToLon(centerX, worldPx).coerceIn(-180.0, 180.0)
                    }
                }
        ) {
            // Read tileInvalidate so Canvas recomposes when new tiles arrive
            val tick = tileInvalidate
            val w = size.width.toDouble()
            val h = size.height.toDouble()
            if (w <= 0.0 || h <= 0.0) return@Canvas

            val intZoom = mapZoom.roundToInt().coerceIn(3, 18)
            val worldPx = tilePx * 2.0.pow(mapZoom)
            val centerX = lonToWorldX(mapLon, worldPx)
            val centerY = latToWorldY(mapLat, worldPx)
            val topLeftX = centerX - w / 2.0
            val topLeftY = centerY - h / 2.0

            val scaleFactor = 2.0.pow(mapZoom - intZoom)
            val scaledTilePx = tilePx * scaleFactor
            val numTiles = 1 shl intZoom

            val minTx = floor(topLeftX / scaledTilePx).toInt()
            val maxTx = floor((topLeftX + w) / scaledTilePx).toInt()
            val minTy = max(0, floor(topLeftY / scaledTilePx).toInt())
            val maxTy = min(numTiles - 1, floor((topLeftY + h) / scaledTilePx).toInt())

            val subdomains = arrayOf("a", "b", "c", "d")
            val useCarto = tileSettings.source == TileSource.CARTO && tileSettings.cartoKey.isNotBlank()

            for (ty in minTy..maxTy) {
                for (tx in minTx..maxTx) {
                    val wrappedTx = ((tx % numTiles) + numTiles) % numTiles
                    val sub = subdomains[(wrappedTx + ty) and 3]
                    val url = if (useCarto) {
                        val encodedKey = URLEncoder.encode(tileSettings.cartoKey, "UTF-8")
                        tileSettings.urlTemplate
                            .replace("{s}", sub)
                            .replace("{z}", intZoom.toString())
                            .replace("{x}", wrappedTx.toString())
                            .replace("{y}", ty.toString())
                            .replace("{r}", "@2x")
                            .replace("{key}", encodedKey)
                    } else {
                        "https://tile.openstreetmap.org/$intZoom/$wrappedTx/$ty.png"
                    }
                    val cacheKey = "${if (useCarto) "carto" else "osm"}_${intZoom}_${wrappedTx}_${ty}_$tick".substringBeforeLast("_")
                    val left = (tx * scaledTilePx - topLeftX).roundToInt()
                    val top = (ty * scaledTilePx - topLeftY).roundToInt()
                    val right = ((tx + 1) * scaledTilePx - topLeftX).roundToInt()
                    val bottom = ((ty + 1) * scaledTilePx - topLeftY).roundToInt()

                    val bmp = tileCache.get(cacheKey)
                    if (bmp != null) {
                        drawImage(
                            image = bmp,
                            dstOffset = IntOffset(left, top),
                            dstSize = IntSize(max(1, right - left), max(1, bottom - top))
                        )
                    } else {
                        drawRect(
                            color = Color(0xFF1A2433),
                            topLeft = Offset(left.toFloat(), top.toFloat()),
                            size = androidx.compose.ui.geometry.Size(
                                (right - left).toFloat(),
                                (bottom - top).toFloat()
                            )
                        )
                        scope.launch {
                            tileCache.fetchTile(
                                key = cacheKey,
                                url = url,
                                onLoaded = { tileInvalidate++ },
                                onError = {
                                    if (useCarto && !cartoErrorReported) {
                                        cartoErrorReported = true
                                        latestOnTileError()
                                    }
                                }
                            )
                        }
                    }
                }
            }

            fun toScreen(pt: LatLon): Offset {
                val sx = (lonToWorldX(pt.lon, worldPx) - topLeftX).toFloat()
                val sy = (latToWorldY(pt.lat, worldPx) - topLeftY).toFloat()
                return Offset(sx, sy)
            }

            // Route polyline in navigation mode
            if (isNavigationMode && navRoute != null && navRoute.pts.size >= 2) {
                val routePath = Path()
                navRoute.pts.forEachIndexed { i, rp ->
                    val pt = toScreen(LatLon(rp.lat, rp.lon))
                    if (i == 0) routePath.moveTo(pt.x, pt.y) else routePath.lineTo(pt.x, pt.y)
                }
                drawPath(
                    path = routePath,
                    color = Color.White,
                    style = Stroke(width = 7.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
                )
                drawPath(
                    path = routePath,
                    color = Blue600,
                    style = Stroke(width = 4.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
                )
            }

            // Markers
            if (!isNavigationMode && centerMarker != null) {
                drawLabeledMarker(toScreen(centerMarker), Blue600, "Pusat", textMeasurer)
            }
            if (isNavigationMode) {
                fromMarker?.let { drawLabeledMarker(toScreen(it), Green700, "Asal", textMeasurer) }
                toMarker?.let { drawLabeledMarker(toScreen(it), Red600, "Tujuan", textMeasurer) }
            }
        }

        // Zoom Controls
        Column(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            FilledTonalIconButton(
                onClick = { mapZoom = min(18.0, mapZoom + 1.0) },
                colors = IconButtonDefaults.filledTonalIconButtonColors(
                    containerColor = Slate900.copy(alpha = 0.88f),
                    contentColor = Color.White
                ),
                modifier = Modifier
                    .size(48.dp)
                    .testTag("map_zoom_in")
            ) {
                Icon(Icons.Default.Add, contentDescription = "Perbesar peta")
            }
            FilledTonalIconButton(
                onClick = { mapZoom = max(3.0, mapZoom - 1.0) },
                colors = IconButtonDefaults.filledTonalIconButtonColors(
                    containerColor = Slate900.copy(alpha = 0.88f),
                    contentColor = Color.White
                ),
                modifier = Modifier
                    .size(48.dp)
                    .testTag("map_zoom_out")
            ) {
                Icon(Icons.Default.Remove, contentDescription = "Perkecil peta")
            }
        }

        // Attribution chip
        Surface(
            color = Slate900.copy(alpha = 0.78f),
            shape = RoundedCornerShape(6.dp),
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(6.dp)
        ) {
            val attr = if (tileSettings.source == TileSource.CARTO && tileSettings.cartoKey.isNotBlank()) {
                "© OpenStreetMap © CARTO"
            } else {
                "© OpenStreetMap"
            }
            Text(
                text = attr,
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 10.sp),
                color = Color.White.copy(alpha = 0.85f),
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
            )
        }
    }
}

private fun DrawScope.drawLabeledMarker(
    pos: Offset,
    fillColor: Color,
    label: String,
    textMeasurer: TextMeasurer
) {
    val radius = 8.dp.toPx()
    drawCircle(
        color = Color.White,
        radius = radius + 2.5.dp.toPx(),
        center = pos
    )
    drawCircle(
        color = fillColor,
        radius = radius,
        center = pos
    )
    val textResult = textMeasurer.measure(
        text = label,
        style = TextStyle(
            color = Color.White,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold
        )
    )
    val padH = 6.dp.toPx()
    val padV = 2.dp.toPx()
    val boxW = textResult.size.width + padH * 2
    val boxH = textResult.size.height + padV * 2
    val topLeft = Offset(pos.x - boxW / 2f, pos.y - radius - boxH - 5.dp.toPx())
    drawRoundRect(
        color = Slate900.copy(alpha = 0.86f),
        topLeft = topLeft,
        size = androidx.compose.ui.geometry.Size(boxW, boxH),
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(4.dp.toPx())
    )
    drawText(
        textLayoutResult = textResult,
        topLeft = Offset(topLeft.x + padH, topLeft.y + padV)
    )
}
