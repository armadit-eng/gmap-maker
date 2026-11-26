package com.example.ui

import android.Manifest
import android.annotation.SuppressLint
import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.net.Uri
import android.os.CancellationSignal
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.OsmApiClient
import com.example.data.TileSettings
import com.example.data.TileSettingsRepository
import com.example.data.TileSource
import com.example.gmap.GmapCodec
import com.example.gmap.GmapConfig
import com.example.gmap.GmapDecoded
import com.example.gmap.LatLon
import com.example.gmap.LayerOptions
import com.example.gmap.MapStyles
import com.example.gmap.NavRoute
import com.example.gmap.PlaceItem
import com.example.gmap.WayItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToInt

enum class AppMode {
    RADIUS,
    NAVIGATION
}

enum class PickTarget {
    FROM,
    TO
}

data class GmapUiState(
    val mode: AppMode = AppMode.RADIUS,
    val latInput: String = "-7.3331",
    val lonInput: String = "111.0719",
    val centerMarker: LatLon? = LatLon(-7.3331, 111.0719),
    val fromQuery: String = "",
    val toQuery: String = "",
    val fromMarker: LatLon? = null,
    val toMarker: LatLon? = null,
    val pickTarget: PickTarget = PickTarget.TO,
    val avoidToll: Boolean = true,
    val corridorBufM: Int = 3000,
    val routeInfo: String = "",
    val navRoute: NavRoute? = null,
    val styleIndex: Int = 4,
    val radiusKmInput: String = "10",
    val cellSizeE6: Int = 9000,
    val toleranceInput: String = "3",
    val layers: LayerOptions = LayerOptions(
        track = true,
        paths = false,
        streams = true,
        ditch = false,
        areas = true,
        farm = true,
        built = false
    ),
    val tileSettings: TileSettings = TileSettings(),
    val tileMessage: String = "Kunci hanya disimpan di HP ini. Hanya memengaruhi tampilan peta pilih titik; warna file .gmap diatur lewat Tipe peta.",
    val viewportTarget: LatLon = LatLon(-7.3331, 111.0719),
    val viewportTrigger: Int = 0,
    val isWorking: Boolean = false,
    val isFindingRoute: Boolean = false,
    val isUploading: Boolean = false,
    val logs: String = "",
    val generatedBytes: ByteArray? = null,
    val decodedMap: GmapDecoded? = null
)

class GmapViewModel(application: Application) : AndroidViewModel(application) {
    val apiClient = OsmApiClient()
    private val tileRepo = TileSettingsRepository(application)

    private val _uiState = MutableStateFlow(GmapUiState())
    val uiState: StateFlow<GmapUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            tileRepo.settingsFlow.collect { settings ->
                _uiState.update { it.copy(tileSettings = settings) }
            }
        }
    }

    private fun appendLog(line: String) {
        _uiState.update { state ->
            val next = if (state.logs.isEmpty()) line else "${state.logs}\n$line"
            state.copy(logs = next)
        }
    }

    private fun clearLog() {
        _uiState.update { it.copy(logs = "") }
    }

    fun setMode(mode: AppMode) {
        _uiState.update { state ->
            state.copy(
                mode = mode,
                navRoute = null,
                routeInfo = ""
            )
        }
    }

    fun setLatInput(value: String) {
        _uiState.update { state ->
            val lat = value.toDoubleOrNull()
            val lon = state.lonInput.toDoubleOrNull()
            if (lat != null && lon != null && lat.isFinite() && lon.isFinite()) {
                val pt = LatLon(lat, lon)
                state.copy(
                    latInput = value,
                    centerMarker = pt,
                    viewportTarget = pt,
                    viewportTrigger = state.viewportTrigger + 1
                )
            } else {
                state.copy(latInput = value)
            }
        }
    }

    fun setLonInput(value: String) {
        _uiState.update { state ->
            val lat = state.latInput.toDoubleOrNull()
            val lon = value.toDoubleOrNull()
            if (lat != null && lon != null && lat.isFinite() && lon.isFinite()) {
                val pt = LatLon(lat, lon)
                state.copy(
                    lonInput = value,
                    centerMarker = pt,
                    viewportTarget = pt,
                    viewportTrigger = state.viewportTrigger + 1
                )
            } else {
                state.copy(lonInput = value)
            }
        }
    }

    fun setFromQuery(value: String) {
        _uiState.update { it.copy(fromQuery = value, navRoute = null, routeInfo = "") }
    }

    fun setToQuery(value: String) {
        _uiState.update { it.copy(toQuery = value, navRoute = null, routeInfo = "") }
    }

    fun setPickTarget(target: PickTarget) {
        _uiState.update { it.copy(pickTarget = target) }
    }

    fun setAvoidToll(avoid: Boolean) {
        _uiState.update { it.copy(avoidToll = avoid, navRoute = null, routeInfo = "") }
    }

    fun setCorridorBufM(bufM: Int) {
        _uiState.update { it.copy(corridorBufM = bufM) }
    }

    fun setRadiusKmInput(value: String) {
        _uiState.update { it.copy(radiusKmInput = value) }
    }

    fun setCellSizeE6(cellE6: Int) {
        _uiState.update { it.copy(cellSizeE6 = cellE6) }
    }

    fun setToleranceInput(value: String) {
        _uiState.update { it.copy(toleranceInput = value) }
    }

    fun setStyleIndex(index: Int) {
        val safeIdx = index.coerceIn(0, MapStyles.presets.lastIndex)
        val preset = MapStyles.presets[safeIdx]
        _uiState.update { state ->
            val updatedBytes = state.generatedBytes?.copyOf()?.also { it[30] = safeIdx.toByte() }
            val updatedDecoded = state.decodedMap?.copy(style = safeIdx)
            state.copy(
                styleIndex = safeIdx,
                layers = preset,
                generatedBytes = updatedBytes,
                decodedMap = updatedDecoded
            )
        }
    }

    fun updateLayers(transform: (LayerOptions) -> LayerOptions) {
        _uiState.update { it.copy(layers = transform(it.layers)) }
    }

    fun onMapTap(pt: LatLon) {
        val latStr = String.format(Locale.US, "%.5f", pt.lat)
        val lonStr = String.format(Locale.US, "%.5f", pt.lon)
        _uiState.update { state ->
            if (state.mode == AppMode.NAVIGATION) {
                val coordText = "$latStr,$lonStr"
                if (state.pickTarget == PickTarget.FROM) {
                    state.copy(
                        fromMarker = pt,
                        fromQuery = coordText,
                        navRoute = null,
                        routeInfo = ""
                    )
                } else {
                    state.copy(
                        toMarker = pt,
                        toQuery = coordText,
                        navRoute = null,
                        routeInfo = ""
                    )
                }
            } else {
                state.copy(
                    centerMarker = pt,
                    latInput = latStr,
                    lonInput = lonStr
                )
            }
        }
    }

    fun applyTileSettings(source: TileSource, key: String, urlTemplate: String) {
        viewModelScope.launch {
            val saved = tileRepo.save(
                TileSettings(
                    source = source,
                    cartoKey = key,
                    urlTemplate = urlTemplate
                )
            )
            val msg = if (saved.source == TileSource.CARTO && saved.cartoKey.isBlank()) {
                "API key kosong: sementara memakai OpenStreetMap."
            } else {
                "Tersimpan."
            }
            _uiState.update { it.copy(tileSettings = saved, tileMessage = msg) }
        }
    }

    fun onTileError() {
        _uiState.update {
            it.copy(
                tileMessage = "Tile Carto gagal dimuat: cek API key / template URL, atau pilih OpenStreetMap."
            )
        }
    }

    fun useDeviceLocation() {
        viewModelScope.launch {
            val loc = acquireLocation(getApplication())
            if (loc != null) {
                val latStr = String.format(Locale.US, "%.5f", loc.lat)
                val lonStr = String.format(Locale.US, "%.5f", loc.lon)
                _uiState.update { state ->
                    state.copy(
                        latInput = latStr,
                        lonInput = lonStr,
                        centerMarker = loc,
                        viewportTarget = loc,
                        viewportTrigger = state.viewportTrigger + 1
                    )
                }
                appendLog("Lokasi HP dipakai.")
            } else {
                appendLog("Gagal ambil lokasi (izin?).")
            }
        }
    }

    fun onLocationPermissionDenied() {
        appendLog("Gagal ambil lokasi (izin?).")
    }

    fun findRoute() {
        if (_uiState.value.isWorking || _uiState.value.isFindingRoute) return
        viewModelScope.launch {
            clearLog()
            _uiState.update { it.copy(isFindingRoute = true) }
            try {
                findRouteInternal()
            } catch (e: Exception) {
                appendLog("GAGAL: ${e.message ?: e::class.simpleName}")
            } finally {
                _uiState.update { it.copy(isFindingRoute = false) }
            }
        }
    }

    private suspend fun findRouteInternal(): NavRoute {
        val state = _uiState.value
        appendLog("Mencari rute ...")
        val from = if (state.fromQuery.isNotBlank()) {
            apiClient.geocode(state.fromQuery, ::appendLog)
        } else {
            acquireLocation(getApplication())
                ?: throw IllegalStateException("Asal kosong & lokasi HP tidak tersedia")
        }
        if (state.toQuery.isBlank()) {
            throw IllegalStateException("Tujuan belum diisi")
        }
        val to = apiClient.geocode(state.toQuery, ::appendLog)
        val route = apiClient.findRoute(from, to, state.avoidToll, ::appendLog)
        val info = String.format(
            Locale.US,
            "Rute %.1f km, %d titik, %d instruksi (~%d mnt mobil)",
            route.lenM / 1000.0,
            route.pts.size,
            route.steps.size,
            (route.durationSec / 60.0).roundToInt()
        )
        _uiState.update {
            it.copy(
                navRoute = route,
                fromMarker = route.from,
                toMarker = route.to,
                routeInfo = info,
                viewportTrigger = it.viewportTrigger + 1
            )
        }
        appendLog(info)
        return route
    }

    fun generatePackage() {
        if (_uiState.value.isWorking || _uiState.value.isFindingRoute) return
        viewModelScope.launch {
            clearLog()
            _uiState.update {
                it.copy(
                    isWorking = true,
                    generatedBytes = null,
                    decodedMap = null
                )
            }
            try {
                val state = _uiState.value
                var latC = state.latInput.toDoubleOrNull()
                    ?: throw IllegalStateException("Lintang tidak valid")
                var lonC = state.lonInput.toDoubleOrNull()
                    ?: throw IllegalStateException("Bujur tidak valid")
                var rk = (state.radiusKmInput.toDoubleOrNull() ?: 10.0).coerceIn(1.0, 30.0)
                val cell = state.cellSizeE6
                val tol = (state.toleranceInput.toDoubleOrNull() ?: 3.0).coerceIn(1.0, 15.0)
                val style = state.styleIndex
                val o = state.layers

                var activeRoute = state.navRoute
                var corrBlocks: Set<Pair<Int, Int>>? = null
                if (state.mode == AppMode.NAVIGATION) {
                    if (activeRoute == null) {
                        activeRoute = findRouteInternal()
                    }
                    val plan = GmapCodec.corridor(activeRoute.pts, state.corridorBufM.toDouble())
                    latC = plan.latC
                    lonC = plan.lonC
                    rk = plan.radiusM / 1000.0
                    corrBlocks = plan.blocks
                    appendLog("Koridor: ${corrBlocks.size} blok 3 km")
                }

                val mLon = 111320.0 * cos(latC * Math.PI / 180.0)
                val rM = rk * 1000.0
                val hw = buildString {
                    append("motorway|motorway_link|trunk|trunk_link|primary|primary_link|secondary|secondary_link|tertiary|tertiary_link|unclassified|residential|living_street|road|service")
                    if (o.track) append("|track")
                    if (o.paths) append("|path|footway|cycleway|bridleway|pedestrian")
                }
                val ww = buildString {
                    append("river|canal")
                    if (o.streams) append("|stream")
                    if (o.ditch) append("|drain|ditch")
                }

                val step = 3000.0
                val nA = ceil(2.0 * rM / step).toInt()
                val nB = nA
                val tot = corrBlocks?.size ?: (nA * nB)
                if (tot > 36) {
                    appendLog("Radius besar: ${nA * nB} blok. Bisa lama / dibatasi server. Disarankan radius 10 km atau kurang.")
                }

                val s0 = latC - rM / GmapCodec.MLAT
                val w0 = lonC - rM / mLon
                val rankMap = mapOf(
                    "city" to 4,
                    "town" to 3,
                    "village" to 2,
                    "suburb" to 2,
                    "neighbourhood" to 2,
                    "quarter" to 2,
                    "hamlet" to 1,
                    "isolated_dwelling" to 1
                )

                val ways = LinkedHashMap<String, WayItem>()
                val places = LinkedHashMap<Long, PlaceItem>()

                fun addRoadsAndPlaces(data: JSONObject) {
                    val elements = data.optJSONArray("elements") ?: return
                    for (idx in 0 until elements.length()) {
                        val el = elements.getJSONObject(idx)
                        val type = el.optString("type")
                        val tagsObj = el.optJSONObject("tags")
                        val tags = jsonToMap(tagsObj)
                        if (type == "way") {
                            val geom = el.optJSONArray("geometry") ?: continue
                            if (geom.length() <= 1) continue
                            val c = GmapCodec.classOf(tags, o)
                            val key = el.getLong("id").toString()
                            if (c >= 0 && !ways.containsKey(key)) {
                                val pts = ArrayList<LatLon>(geom.length())
                                for (gi in 0 until geom.length()) {
                                    val g = geom.getJSONObject(gi)
                                    pts.add(LatLon(g.getDouble("lat"), g.getDouble("lon")))
                                }
                                ways[key] = WayItem(cls = c, name = tags["name"].orEmpty(), pts = pts)
                            }
                        } else if (type == "node") {
                            val placeTag = tags["place"] ?: continue
                            val name = tags["name"] ?: continue
                            val rank = rankMap[placeTag] ?: continue
                            val id = el.getLong("id")
                            if (!places.containsKey(id)) {
                                places[id] = PlaceItem(
                                    lat = el.getDouble("lat"),
                                    lon = el.getDouble("lon"),
                                    rank = rank,
                                    name = name
                                )
                            }
                        }
                    }
                }

                fun addAreas(data: JSONObject) {
                    val elements = data.optJSONArray("elements") ?: return
                    for (idx in 0 until elements.length()) {
                        val el = elements.getJSONObject(idx)
                        val tags = jsonToMap(el.optJSONObject("tags"))
                        val c = GmapCodec.areaClass(tags, o)
                        if (c < 0) continue
                        val type = el.optString("type")
                        if (type == "way") {
                            val geom = el.optJSONArray("geometry") ?: continue
                            if (geom.length() <= 3) continue
                            val first = geom.getJSONObject(0)
                            val last = geom.getJSONObject(geom.length() - 1)
                            val key = "w${el.getLong("id")}"
                            if (first.getDouble("lat") == last.getDouble("lat") &&
                                first.getDouble("lon") == last.getDouble("lon") &&
                                !ways.containsKey(key)
                            ) {
                                val pts = ArrayList<LatLon>(geom.length())
                                for (gi in 0 until geom.length()) {
                                    val g = geom.getJSONObject(gi)
                                    pts.add(LatLon(g.getDouble("lat"), g.getDouble("lon")))
                                }
                                ways[key] = WayItem(cls = c, name = "", pts = pts, poly = true)
                            }
                        } else if (type == "relation") {
                            val members = el.optJSONArray("members") ?: continue
                            val outers = mutableListOf<List<LatLon>>()
                            for (mi in 0 until members.length()) {
                                val m = members.getJSONObject(mi)
                                if (m.optString("type") == "way" && m.optString("role") == "outer") {
                                    val geom = m.optJSONArray("geometry") ?: continue
                                    val pts = ArrayList<LatLon>(geom.length())
                                    for (gi in 0 until geom.length()) {
                                        val g = geom.getJSONObject(gi)
                                        pts.add(LatLon(g.getDouble("lat"), g.getDouble("lon")))
                                    }
                                    outers.add(pts)
                                }
                            }
                            val relId = el.getLong("id")
                            GmapCodec.assembleRings(outers).forEachIndexed { rIdx, ring ->
                                val key = "r$relId:$rIdx"
                                if (!ways.containsKey(key)) {
                                    ways[key] = WayItem(cls = c, name = "", pts = ring, poly = true)
                                }
                            }
                        }
                    }
                }

                var k = 0
                for (i in 0 until nA) {
                    for (j in 0 until nB) {
                        if (corrBlocks != null && !corrBlocks.contains(i to j)) continue
                        k++
                        val s = s0 + i * step / GmapCodec.MLAT
                        val n = s0 + min((i + 1) * step, 2.0 * rM) / GmapCodec.MLAT
                        val w = w0 + j * step / mLon
                        val e = w0 + min((j + 1) * step, 2.0 * rM) / mLon
                        appendLog("Blok $k/$tot ...")
                        apiClient.fetchBlock(
                            s = s, w = w, n = n, e = e,
                            makeQuery = { bb ->
                                "[out:json][timeout:50];(way[\"highway\"~\"^($hw)$\"]$bb;way[\"waterway\"~\"^($ww)$\"]$bb;way[\"railway\"=\"rail\"]$bb;node[\"place\"~\"^(city|town|village|hamlet|suburb|neighbourhood|quarter|isolated_dwelling)$\"]$bb;);out geom qt;"
                            },
                            onData = ::addRoadsAndPlaces,
                            optional = false,
                            onLog = ::appendLog
                        )
                        if (o.areas) {
                            appendLog("  area ...")
                            delay(500L)
                            apiClient.fetchBlock(
                                s = s, w = w, n = n, e = e,
                                makeQuery = { bb ->
                                    "[out:json][timeout:60];(way[\"natural\"~\"^(water|wood|scrub|grassland)$\"]$bb;way[\"landuse\"~\"^(forest|reservoir|basin|grass|meadow|farmland|orchard|vineyard|residential|commercial|industrial|retail|recreation_ground|village_green|allotments)$\"]$bb;way[\"leisure\"~\"^(park|garden|pitch|golf_course|nature_reserve|playground)$\"]$bb;way[\"waterway\"=\"riverbank\"]$bb;relation[\"type\"=\"multipolygon\"][\"natural\"~\"^(water|wood)$\"]$bb;relation[\"type\"=\"multipolygon\"][\"landuse\"~\"^(forest|reservoir|basin|farmland|meadow|grass)$\"]$bb;relation[\"type\"=\"multipolygon\"][\"waterway\"=\"riverbank\"]$bb;);out geom qt;"
                                },
                                onData = ::addAreas,
                                optional = true,
                                onLog = ::appendLog
                            )
                        }
                        appendLog("  total jalur: ${ways.size}")
                        delay(700L)
                    }
                }

                appendLog("Menyusun paket ...")
                val (finalBytes, st, decoded) = withContext(Dispatchers.Default) {
                    val res = GmapCodec.buildGmap(
                        ways = ways.values.toList(),
                        cfg = GmapConfig(
                            latC = latC,
                            lonC = lonC,
                            radiusM = rM,
                            cellE6 = cell,
                            tolM = tol
                        ),
                        places = places.values.toList()
                    )
                    res.bytes[30] = style.toByte()
                    val packaged = if (corrBlocks != null && activeRoute != null) {
                        GmapCodec.withRoute(res.bytes, activeRoute)
                    } else {
                        res.bytes
                    }
                    Triple(packaged, res.stats, GmapCodec.readGmap(packaged))
                }

                if (corrBlocks != null && activeRoute != null) {
                    appendLog("Rute ikut dikemas: ${activeRoute.pts.size} titik, ${activeRoute.steps.size} instruksi.")
                }
                appendLog(
                    String.format(
                        Locale.US,
                        "Selesai: %.0f KB, %dx%d sel, sel terbesar %d B (batas 8000), simplifikasi %.1f m",
                        st.size / 1024.0, st.cols, st.rows, st.maxCell, st.tol
                    )
                )
                appendLog("Nama jalan: ${st.names} | tempat: ${st.places}")
                appendLog("Segmen per kelas [tol,primer,sekunder,tersier,permukiman,service,sungai,rel,A-air,A-hutan,A-hijau,A-terbangun]: ${st.byCls.joinToString(", ")}")
                if (st.dropped > 0) {
                    appendLog("PERINGATAN: ${st.dropped} jalur kecil dibuang di sel yang terlalu padat. Pakai sel 0,5 km atau radius lebih kecil.")
                }
                if (st.size / 1048576.0 > 30.0) {
                    appendLog("Catatan: berkas besar, unggah via WiFi bisa lama.")
                }

                _uiState.update {
                    it.copy(
                        generatedBytes = finalBytes,
                        decodedMap = decoded
                    )
                }
            } catch (e: Exception) {
                appendLog("GAGAL: ${e.message ?: e::class.simpleName}\nSemua server Overpass menolak. Coba: WiFi lain / matikan VPN & DNS pribadi, kurangi radius (mis. 5 km), matikan opsi path & parit, atau ulangi beberapa menit lagi.")
            } finally {
                _uiState.update { it.copy(isWorking = false) }
            }
        }
    }

    fun saveGmapToUri(uri: Uri) {
        val bytes = _uiState.value.generatedBytes ?: return
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    getApplication<Application>().contentResolver.openOutputStream(uri)?.use { out ->
                        out.write(bytes)
                        out.flush()
                    } ?: throw IllegalStateException("Tidak dapat membuka berkas tujuan")
                }
                appendLog("Berkas map.gmap disimpan (${(bytes.size / 1024.0).roundToInt()} KB).")
            } catch (e: Exception) {
                appendLog("Gagal menyimpan map.gmap: ${e.message}")
            }
        }
    }

    fun uploadToEsp32() {
        val bytes = _uiState.value.generatedBytes ?: return
        if (_uiState.value.isUploading) return
        viewModelScope.launch {
            _uiState.update { it.copy(isUploading = true) }
            appendLog("Mengirim ke 192.168.4.1 ... (HP harus tersambung ke WiFi GESITS-MAP)")
            try {
                val resp = apiClient.uploadToEsp32(bytes)
                appendLog("ESP32: $resp")
            } catch (e: Exception) {
                appendLog("Gagal kirim langsung (${e.message}). Unduh berkasnya lalu unggah lewat http://192.168.4.1")
            } finally {
                _uiState.update { it.copy(isUploading = false) }
            }
        }
    }

    private fun jsonToMap(obj: JSONObject?): Map<String, String> {
        if (obj == null) return emptyMap()
        val map = HashMap<String, String>()
        val keys = obj.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            map[k] = obj.optString(k)
        }
        return map
    }

    fun hasLocationPermission(context: Context): Boolean {
        val fine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        return fine || coarse
    }

    @SuppressLint("MissingPermission")
    private suspend fun acquireLocation(context: Context): LatLon? {
        if (!hasLocationPermission(context)) return null
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null
        val provider = when {
            lm.isProviderEnabled(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
            lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
            else -> null
        }
        if (provider != null) {
            val fresh = withTimeoutOrNull(15000L) {
                suspendCancellableCoroutine<Location?> { cont ->
                    val signal = CancellationSignal()
                    cont.invokeOnCancellation { signal.cancel() }
                    try {
                        LocationManagerCompat.getCurrentLocation(
                            lm,
                            provider,
                            signal,
                            ContextCompat.getMainExecutor(context)
                        ) { location ->
                            if (cont.isActive) cont.resume(location)
                        }
                    } catch (_: Exception) {
                        if (cont.isActive) cont.resume(null)
                    }
                }
            }
            if (fresh != null) return LatLon(fresh.latitude, fresh.longitude)
        }
        val fallbackProviders = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)
        for (p in fallbackProviders) {
            val last = try { lm.getLastKnownLocation(p) } catch (_: Exception) { null }
            if (last != null) return LatLon(last.latitude, last.longitude)
        }
        return null
    }
}
