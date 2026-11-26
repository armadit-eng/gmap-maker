package com.example.data

import com.example.gmap.GmapCodec
import com.example.gmap.LatLon
import com.example.gmap.NavRoute
import com.example.gmap.RoutePoint
import com.example.gmap.RouteStep
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

class OsmApiClient {
    companion object {
        private const val USER_AGENT = "GMAPMaker-Android/1.0 (https://github.com/armadit-eng/gmap-maker)"
        private val COORD_REGEX = Regex("""^\s*(-?\d+(?:\.\d+)?)\s*,\s*(-?\d+(?:\.\d+)?)\s*$""")
        private val REMARK_ERR_REGEX = Regex("""runtime error|timed out|out of memory""", RegexOption.IGNORE_CASE)

        val OVERPASS_ENDPOINTS = listOf(
            "https://overpass-api.de/api/interpreter",
            "https://overpass.private.coffee/api/interpreter",
            "https://overpass.openstreetmap.fr/api/interpreter",
            "https://overpass.kumi.systems/api/interpreter",
            "https://maps.mail.ru/osm/tools/overpass/api/interpreter"
        )
    }

    val tileClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .addInterceptor { chain ->
            chain.proceed(
                chain.request().newBuilder()
                    .header("User-Agent", USER_AGENT)
                    .build()
            )
        }
        .build()

    private val apiClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(75, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .addInterceptor { chain ->
            chain.proceed(
                chain.request().newBuilder()
                    .header("User-Agent", USER_AGENT)
                    .build()
            )
        }
        .build()

    private val espClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private var epStart = 0

    suspend fun geocode(query: String, onLog: (String) -> Unit): LatLon = withContext(Dispatchers.IO) {
        val m = COORD_REGEX.matchEntire(query)
        if (m != null) {
            return@withContext LatLon(m.groupValues[1].toDouble(), m.groupValues[2].toDouble())
        }
        val encoded = URLEncoder.encode(query.trim(), "UTF-8")
        val url = "https://nominatim.openstreetmap.org/search?format=json&limit=1&countrycodes=id&q=$encoded"
        val req = Request.Builder().url(url).get().build()
        val body = apiClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IllegalStateException("Nominatim HTTP ${resp.code}")
            resp.body?.string() ?: "[]"
        }
        val arr = JSONArray(body)
        if (arr.length() == 0) {
            throw IllegalStateException("Tempat tidak ditemukan: $query")
        }
        val first = arr.getJSONObject(0)
        val displayName = first.optString("display_name", query).take(60)
        onLog("  $query -> $displayName")
        LatLon(first.getString("lat").toDouble(), first.getString("lon").toDouble())
    }

    suspend fun findRoute(
        from: LatLon,
        to: LatLon,
        avoidToll: Boolean,
        onLog: (String) -> Unit
    ): NavRoute = withContext(Dispatchers.IO) {
        fun buildUrl(extra: String): String = String.format(
            Locale.US,
            "https://router.project-osrm.org/route/v1/driving/%.6f,%.6f;%.6f,%.6f?overview=full&geometries=geojson&steps=true%s",
            from.lon, from.lat, to.lon, to.lat, extra
        )

        fun fetchJson(url: String): JSONObject {
            val req = Request.Builder().url(url).get().build()
            val text = apiClient.newCall(req).execute().use { resp ->
                resp.body?.string() ?: throw IllegalStateException("Respons OSRM kosong")
            }
            return JSONObject(text)
        }

        var json = fetchJson(buildUrl(if (avoidToll) "&exclude=toll" else ""))
        if (json.optString("code") != "Ok" && avoidToll) {
            onLog("  server menolak \"hindari tol\", mencoba tanpa filter (periksa sendiri rutenya)")
            json = fetchJson(buildUrl(""))
        }
        if (json.optString("code") != "Ok") {
            val msg = json.optString("message").ifBlank { json.optString("code") }
            throw IllegalStateException("Rute gagal: $msg")
        }

        val rt = json.getJSONArray("routes").getJSONObject(0)
        val coords = rt.getJSONObject("geometry").getJSONArray("coordinates")
        val raw = ArrayList<LatLon>(coords.length())
        for (i in 0 until coords.length()) {
            val c = coords.getJSONArray(i)
            raw.add(LatLon(c.getDouble(1), c.getDouble(0)))
        }

        data class RawManeuver(val ty: Int, val loc: LatLon, val name: String, var rawIdx: Int = 0)
        val mans = mutableListOf<RawManeuver>()
        val legs = rt.getJSONArray("legs")
        for (li in 0 until legs.length()) {
            val stepsArr = legs.getJSONObject(li).getJSONArray("steps")
            for (si in 0 until stepsArr.length()) {
                val s = stepsArr.getJSONObject(si)
                val man = s.getJSONObject("maneuver")
                val ty = GmapCodec.stepType(man.optString("type"), man.optString("modifier"))
                if (ty < 0) continue
                val locArr = man.getJSONArray("location")
                mans.add(
                    RawManeuver(
                        ty = ty,
                        loc = LatLon(locArr.getDouble(1), locArr.getDouble(0)),
                        name = GmapCodec.ascii(s.optString("name")).take(23)
                    )
                )
            }
        }

        val manIdx = HashSet<Int>()
        var fi = 0
        for (m in mans) {
            var best = -1
            var bd = Double.MAX_VALUE
            for (i in fi until raw.size) {
                val d = GmapCodec.hv(raw[i], m.loc)
                if (d < bd) {
                    bd = d
                    best = i
                }
                if (d > bd + 500.0 && bd < 30.0) break
            }
            m.rawIdx = maxOf(best, fi)
            fi = m.rawIdx
            manIdx.add(m.rawIdx)
        }

        val pts = mutableListOf<RoutePoint>()
        val idxMap = IntArray(raw.size)
        var cum = 0.0
        for (i in raw.indices) {
            val p = raw[i]
            if (pts.isEmpty()) {
                pts.add(RoutePoint(p.lat, p.lon, 0.0))
                idxMap[i] = 0
            } else {
                val last = pts.last()
                val d = GmapCodec.hv(LatLon(last.lat, last.lon), p)
                if (d >= 8.0 || manIdx.contains(i) || i == raw.lastIndex) {
                    cum += d
                    pts.add(RoutePoint(p.lat, p.lon, cum))
                }
                idxMap[i] = pts.lastIndex
            }
        }

        val steps = mans.map { m ->
            val mapped = idxMap[m.rawIdx]
            RouteStep(ty = m.ty, idx = mapped, cumM = pts[mapped].cumM, name = m.name)
        }

        if (pts.size > 14000) {
            throw IllegalStateException("Rute terlalu panjang (${pts.size} titik)")
        }

        NavRoute(
            pts = pts,
            steps = steps,
            from = from,
            to = to,
            lenM = cum,
            durationSec = rt.optDouble("duration", 0.0)
        )
    }

    suspend fun overpass(query: String, tries: Int, onLog: (String) -> Unit): JSONObject {
        var lastErr: Throwable = IllegalStateException("Gagal terhubung ke Overpass")
        for (a in 0 until tries) {
            val ep = OVERPASS_ENDPOINTS[(epStart + a) % OVERPASS_ENDPOINTS.size]
            val host = URI(ep).host
            try {
                val json = withContext(Dispatchers.IO) {
                    val body = FormBody.Builder()
                        .add("data", query)
                        .build()
                    val req = Request.Builder().url(ep).post(body).build()
                    val text = apiClient.newCall(req).execute().use { resp ->
                        if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code}")
                        resp.body?.string() ?: throw IllegalStateException("Respons kosong")
                    }
                    val obj = JSONObject(text)
                    val remark = obj.optString("remark", "")
                    if (remark.isNotEmpty() && REMARK_ERR_REGEX.containsMatchIn(remark)) {
                        throw IllegalStateException("server: ${remark.take(50)}")
                    }
                    obj
                }
                epStart = (epStart + a) % OVERPASS_ENDPOINTS.size
                return json
            } catch (e: Exception) {
                lastErr = e
                onLog("  $host: ${e.message ?: e::class.simpleName}")
                delay(1500L * (a + 1))
            }
        }
        throw lastErr
    }

    suspend fun fetchBlock(
        s: Double,
        w: Double,
        n: Double,
        e: Double,
        makeQuery: (String) -> String,
        onData: (JSONObject) -> Unit,
        optional: Boolean,
        onLog: (String) -> Unit
    ) {
        val bb = String.format(Locale.US, "(%.6f,%.6f,%.6f,%.6f)", s, w, n, e)
        try {
            onData(overpass(makeQuery(bb), 4, onLog))
        } catch (err: Exception) {
            val hM = (n - s) * GmapCodec.MLAT
            if (hM < 900.0) {
                if (optional) {
                    onLog("  dilewati: ${err.message}")
                    return
                }
                throw err
            }
            onLog("  blok dipecah: ${hM.roundToInt()} m -> ${(hM / 2.0).roundToInt()} m")
            val ms = (s + n) / 2.0
            val mw = (w + e) / 2.0
            val quads = arrayOf(
                doubleArrayOf(s, w, ms, mw),
                doubleArrayOf(s, mw, ms, e),
                doubleArrayOf(ms, w, n, mw),
                doubleArrayOf(ms, mw, n, e)
            )
            for (t in quads) {
                fetchBlock(t[0], t[1], t[2], t[3], makeQuery, onData, optional, onLog)
                delay(700L)
            }
        }
    }

    suspend fun uploadToEsp32(bytes: ByteArray): String = withContext(Dispatchers.IO) {
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart(
                "f",
                "map.gmap",
                bytes.toRequestBody("application/octet-stream".toMediaType())
            )
            .build()
        val req = Request.Builder()
            .url("http://192.168.4.1/upload")
            .post(body)
            .build()
        espClient.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                throw IllegalStateException("HTTP ${resp.code}: $text")
            }
            text
        }
    }
}
