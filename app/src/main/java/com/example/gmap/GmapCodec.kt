package com.example.gmap

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

object GmapCodec {
    const val MLAT = 110574.0
    const val CELL_MAX_BYTES = 8000

    private val NON_ASCII_REGEX = Regex("[^\\x20-\\x7e]")
    private val MULTI_SPACE_REGEX = Regex("\\s+")

    fun ascii(s: String?): String {
        if (s.isNullOrEmpty()) return ""
        return s.replace(NON_ASCII_REGEX, "")
            .replace(MULTI_SPACE_REGEX, " ")
            .trim()
            .take(40)
    }

    fun classOf(t: Map<String, String>, o: LayerOptions): Int {
        val hw = t["highway"]
        if (hw != null) {
            val h = hw.removeSuffix("_link")
            return when (h) {
                "motorway", "trunk" -> 0
                "primary" -> 1
                "secondary" -> 2
                "tertiary" -> 3
                "residential", "unclassified", "living_street", "road" -> 4
                "service" -> 5
                "track" -> if (o.track) 5 else -1
                "path", "footway", "cycleway", "bridleway", "pedestrian" -> if (o.paths) 5 else -1
                else -> -1
            }
        }
        val ww = t["waterway"]
        if (ww != null) {
            return when (ww) {
                "river", "canal" -> 6
                "stream" -> if (o.streams) 6 else -1
                "drain", "ditch" -> if (o.ditch) 6 else -1
                else -> -1
            }
        }
        if (t["railway"] == "rail") return 7
        return -1
    }

    fun areaClass(t: Map<String, String>, o: LayerOptions): Int {
        if (!o.areas) return -1
        val nat = t["natural"]
        val ww = t["waterway"]
        val lu = t["landuse"]
        val lei = t["leisure"]
        if (nat == "water" || ww == "riverbank" || lu == "reservoir" || lu == "basin") return 8
        if (nat == "wood" || lu == "forest") return 9
        if (lei in setOf("park", "garden", "pitch", "golf_course", "nature_reserve", "playground") ||
            lu in setOf("grass", "meadow", "village_green", "recreation_ground", "orchard", "vineyard", "plant_nursery", "allotments") ||
            nat in setOf("scrub", "grassland", "heath")
        ) return 10
        if (o.farm && lu == "farmland") return 10
        if (o.built && lu in setOf("residential", "commercial", "industrial", "retail", "construction", "garages")) return 11
        return -1
    }

    private fun peqLatLon(a: LatLon, b: LatLon): Boolean = a.lat == b.lat && a.lon == b.lon

    private fun peqInt(a: IntArray, b: IntArray): Boolean = a[0] == b[0] && a[1] == b[1]

    fun assembleRings(members: List<List<LatLon>>): List<List<LatLon>> {
        val segs = members.filter { it.size > 1 }.map { it.toMutableList() }.toMutableList()
        val rings = mutableListOf<List<LatLon>>()
        while (segs.isNotEmpty()) {
            var cur = segs.removeAt(segs.lastIndex)
            var guard = 0
            while (!(cur.size > 3 && peqLatLon(cur.first(), cur.last())) && guard++ < 5000) {
                val end = cur.last()
                var f = -1
                var rev = false
                for (i in segs.indices) {
                    if (peqLatLon(segs[i].first(), end)) {
                        f = i
                        break
                    }
                    if (peqLatLon(segs[i].last(), end)) {
                        f = i
                        rev = true
                        break
                    }
                }
                if (f < 0) break
                val nx = segs.removeAt(f)
                if (rev) nx.reverse()
                cur.addAll(nx.drop(1))
            }
            if (cur.size > 3 && peqLatLon(cur.first(), cur.last())) {
                rings.add(cur)
            }
        }
        return rings
    }

    private fun clipRect(
        pts: List<DoubleArray>,
        x0: Double,
        y0: Double,
        x1: Double,
        y1: Double
    ): List<DoubleArray> {
        var out = pts
        val planes = arrayOf(
            doubleArrayOf(0.0, x0, 1.0),
            doubleArrayOf(0.0, x1, -1.0),
            doubleArrayOf(1.0, y0, 1.0),
            doubleArrayOf(1.0, y1, -1.0)
        )
        for (plane in planes) {
            val ax = plane[0].toInt()
            val b = plane[1]
            val sd = plane[2]
            val inp = out
            out = ArrayList(inp.size)
            if (inp.isEmpty()) break
            for (i in inp.indices) {
                val a = inp[i]
                val c = inp[(i + 1) % inp.size]
                val ia = if (sd > 0) a[ax] >= b else a[ax] <= b
                val ic = if (sd > 0) c[ax] >= b else c[ax] <= b
                if (ia) out.add(a)
                if (ia != ic) {
                    val t = (b - a[ax]) / (c[ax] - a[ax])
                    val q = DoubleArray(2)
                    q[ax] = b
                    q[1 - ax] = a[1 - ax] + t * (c[1 - ax] - a[1 - ax])
                    out.add(q)
                }
            }
        }
        return out
    }

    data class DpPoint(val mx: Double, val my: Double, val x: Int, val y: Int)

    fun dp(pts: List<DpPoint>, tol: Double): List<DpPoint> {
        val n = pts.size
        if (n < 3) return pts.toList()
        val keep = BooleanArray(n)
        keep[0] = true
        keep[n - 1] = true
        val stack = ArrayDeque<Pair<Int, Int>>()
        stack.addLast(0 to (n - 1))
        while (stack.isNotEmpty()) {
            val (a, b) = stack.removeLast()
            var md = 0.0
            var mi = -1
            val ax = pts[a].mx
            val ay = pts[a].my
            val dx = pts[b].mx - ax
            val dy = pts[b].my - ay
            val len = hypot(dx, dy)
            for (i in (a + 1) until b) {
                val d = if (len == 0.0) {
                    hypot(pts[i].mx - ax, pts[i].my - ay)
                } else {
                    abs(dy * (pts[i].mx - ax) - dx * (pts[i].my - ay)) / len
                }
                if (d > md) {
                    md = d
                    mi = i
                }
            }
            if (md > tol && mi > 0) {
                keep[mi] = true
                stack.addLast(a to mi)
                stack.addLast(mi to b)
            }
        }
        val out = ArrayList<DpPoint>(n)
        for (i in 0 until n) {
            if (keep[i]) out.add(pts[i])
        }
        return out
    }

    private class CellRecord(
        val cls: Int,
        val poly: MutableList<IntArray>,
        val name: String,
        val isPoly: Boolean = false,
        var nameIdx: Int = 0
    )

    private fun addPolygon(
        ringInput: List<IntArray>,
        cls: Int,
        cell: Int,
        cols: Int,
        rows: Int,
        cells: Array<MutableList<CellRecord>?>,
        kx: Double,
        ky: Double,
        tol: Double
    ) {
        var ring = ringInput
        if (ring.size > 1 && peqInt(ring.first(), ring.last())) {
            ring = ring.dropLast(1)
        }
        if (ring.size < 3) return
        var minx = Long.MAX_VALUE
        var maxx = Long.MIN_VALUE
        var miny = Long.MAX_VALUE
        var maxy = Long.MIN_VALUE
        for (p in ring) {
            if (p[0] < minx) minx = p[0].toLong()
            if (p[0] > maxx) maxx = p[0].toLong()
            if (p[1] < miny) miny = p[1].toLong()
            if (p[1] > maxy) maxy = p[1].toLong()
        }
        if ((maxx - minx) * kx < 8.0 && (maxy - miny) * ky < 8.0) return

        val cx0 = max(0, floor(minx.toDouble() / cell).toInt())
        val cx1 = min(cols - 1, floor(maxx.toDouble() / cell).toInt())
        val cy0 = max(0, floor(miny.toDouble() / cell).toInt())
        val cy1 = min(rows - 1, floor(maxy.toDouble() / cell).toInt())

        val ringDoubles = ring.map { doubleArrayOf(it[0].toDouble(), it[1].toDouble()) }
        for (cy in cy0..cy1) {
            for (cx in cx0..cx1) {
                val clipped = clipRect(
                    ringDoubles,
                    (cx * cell).toDouble(),
                    (cy * cell).toDouble(),
                    ((cx + 1) * cell).toDouble(),
                    ((cy + 1) * cell).toDouble()
                )
                val c = ArrayList<IntArray>(clipped.size)
                for (p in clipped) {
                    val rx = java.lang.Math.round(p[0]).toInt()
                    val ry = java.lang.Math.round(p[1]).toInt()
                    if (c.isEmpty() || c.last()[0] != rx || c.last()[1] != ry) {
                        c.add(intArrayOf(rx, ry))
                    }
                }
                if (c.size > 1 && peqInt(c.first(), c.last())) {
                    c.removeAt(c.lastIndex)
                }
                if (c.size < 3) continue
                var cur = c
                var t = tol
                while (cur.size > 200 && t < 300.0) {
                    val m = ArrayList<DpPoint>(cur.size + 1)
                    for (q in cur) m.add(DpPoint(q[0] * kx, q[1] * ky, q[0], q[1]))
                    m.add(DpPoint(cur[0][0] * kx, cur[0][1] * ky, cur[0][0], cur[0][1]))
                    val simplified = dp(m, t).dropLast(1)
                    cur = ArrayList(simplified.map { intArrayOf(it.x, it.y) })
                    t *= 2.0
                }
                if (cur.size < 3 || cur.size > 200) continue
                val ci = cy * cols + cx
                val list = cells[ci] ?: mutableListOf<CellRecord>().also { cells[ci] = it }
                list.add(CellRecord(cls = cls, poly = cur, name = "", isPoly = true))
            }
        }
    }

    private fun splitToCells(
        poly: List<IntArray>,
        cls: Int,
        cell: Int,
        cols: Int,
        rows: Int,
        cells: Array<MutableList<CellRecord>?>,
        name: String
    ) {
        var lastCi = -1
        var lastEx = 0
        var lastEy = 0
        var lastPoly: MutableList<IntArray>? = null

        for (i in 1 until poly.size) {
            val x0 = poly[i - 1][0].toDouble()
            val y0 = poly[i - 1][1].toDouble()
            val x1 = poly[i][0].toDouble()
            val y1 = poly[i][1].toDouble()
            val dx = x1 - x0
            val dy = y1 - y0
            val ts = mutableListOf(0.0, 1.0)
            if (dx != 0.0) {
                val a = floor(min(x0, x1) / cell).toInt() + 1
                val b = floor(max(x0, x1) / cell).toInt()
                for (g in a..b) {
                    val t = (g * cell - x0) / dx
                    if (t > 0.0 && t < 1.0) ts.add(t)
                }
            }
            if (dy != 0.0) {
                val a = floor(min(y0, y1) / cell).toInt() + 1
                val b = floor(max(y0, y1) / cell).toInt()
                for (g in a..b) {
                    val t = (g * cell - y0) / dy
                    if (t > 0.0 && t < 1.0) ts.add(t)
                }
            }
            ts.sort()
            for (k in 1 until ts.size) {
                val ta = ts[k - 1]
                val tb = ts[k]
                if (tb - ta < 1e-9) continue
                val sx = java.lang.Math.round(x0 + dx * ta).toInt()
                val sy = java.lang.Math.round(y0 + dy * ta).toInt()
                val ex = java.lang.Math.round(x0 + dx * tb).toInt()
                val ey = java.lang.Math.round(y0 + dy * tb).toInt()
                val cx = floor((x0 + dx * (ta + tb) / 2.0) / cell).toInt()
                val cy = floor((y0 + dy * (ta + tb) / 2.0) / cell).toInt()
                if (cx < 0 || cy < 0 || cx >= cols || cy >= rows) {
                    lastPoly = null
                    continue
                }
                if (sx == ex && sy == ey) continue
                val ci = cy * cols + cx
                if (lastPoly != null && lastCi == ci && lastEx == sx && lastEy == sy && lastPoly.size < 1500) {
                    lastPoly.add(intArrayOf(ex, ey))
                } else {
                    val newPoly = mutableListOf(intArrayOf(sx, sy), intArrayOf(ex, ey))
                    val list = cells[ci] ?: mutableListOf<CellRecord>().also { cells[ci] = it }
                    list.add(CellRecord(cls = cls, poly = newPoly, name = name, isPoly = false))
                    lastCi = ci
                    lastPoly = newPoly
                }
                lastEx = ex
                lastEy = ey
            }
        }
    }

    private fun cellBytes(list: List<CellRecord>): Int {
        var b = 0
        for (r in list) b += 4 + 4 * r.poly.size
        return b
    }

    private data class PreWay(
        val cls: Int,
        val poly: Boolean,
        val name: String,
        val p: List<IntArray>
    )

    fun buildGmap(ways: List<WayItem>, cfg: GmapConfig, places: List<PlaceItem>): BuildResult {
        val latC = cfg.latC
        val lonC = cfg.lonC
        val rM = cfg.radiusM
        val cell = cfg.cellE6
        val mLon = 111320.0 * cos(latC * Math.PI / 180.0)
        val oLat = java.lang.Math.round((latC - rM / MLAT) * 1e6).toInt()
        val oLon = java.lang.Math.round((lonC - rM / mLon) * 1e6).toInt()
        val rows = ceil(2.0 * rM / MLAT * 1e6 / cell).toInt()
        val cols = ceil(2.0 * rM / mLon * 1e6 / cell).toInt()
        if (cols * rows > 40000) {
            throw IllegalStateException("Terlalu banyak sel (${cols * rows}). Kurangi radius atau pakai sel 1 km.")
        }
        val kx = mLon / 1e6
        val ky = MLAT / 1e6

        val pre = ways.map { w ->
            PreWay(
                cls = w.cls,
                poly = w.poly,
                name = if (w.cls <= 5) ascii(w.name) else "",
                p = w.pts.map { q ->
                    intArrayOf(
                        (java.lang.Math.round(q.lon * 1e6) - oLon).toInt(),
                        (java.lang.Math.round(q.lat * 1e6) - oLat).toInt()
                    )
                }
            )
        }

        var tol = cfg.tolM
        var cells: Array<MutableList<CellRecord>?>
        var dropped = 0
        var passes = 0
        while (true) {
            cells = arrayOfNulls(cols * rows)
            passes++
            for (w in pre) {
                val m = w.p.map { q -> DpPoint(q[0] * kx, q[1] * ky, q[0], q[1]) }
                val s = dp(m, tol).map { intArrayOf(it.x, it.y) }
                if (w.poly) {
                    addPolygon(s, w.cls, cell, cols, rows, cells, kx, ky, tol)
                } else if (s.size >= 2) {
                    splitToCells(s, w.cls, cell, cols, rows, cells, w.name)
                }
            }
            var worst = 0
            for (l in cells) {
                if (l != null) worst = max(worst, cellBytes(l))
            }
            if (worst <= CELL_MAX_BYTES || passes >= 4) break
            tol *= 1.6
        }

        val dropOrder = intArrayOf(11, 10, 5, 4, 9)
        for (ci in cells.indices) {
            val l = cells[ci] ?: continue
            l.sortWith { a, b ->
                val polyCmp = (if (b.isPoly) 1 else 0) - (if (a.isPoly) 1 else 0)
                if (polyCmp != 0) polyCmp else if (a.isPoly) b.cls - a.cls else 0
            }
            for (drop in dropOrder) {
                if (cellBytes(l) <= CELL_MAX_BYTES) break
                for (i in l.lastIndex downTo 0) {
                    if (l[i].cls == drop) {
                        l.removeAt(i)
                        dropped++
                    }
                }
            }
        }

        val namesOf = arrayOfNulls<List<String>>(cols * rows)
        for (ci in cells.indices) {
            val l = cells[ci] ?: continue
            val idx = HashMap<String, Int>()
            val list = mutableListOf<String>()
            var bytes = 1
            for (r in l) {
                r.nameIdx = 0
                if (r.name.isEmpty() || r.cls > 5) continue
                if (!idx.containsKey(r.name)) {
                    if (list.size >= 255 || bytes + 1 + r.name.length > 700) continue
                    list.add(r.name)
                    idx[r.name] = list.size
                    bytes += 1 + r.name.length
                }
                r.nameIdx = idx[r.name] ?: 0
            }
            namesOf[ci] = list
        }

        val tableOff = 32
        val dataOff = 32 + cols * rows * 8
        var total = 0
        var maxCell = 0
        var nRec = 0
        val byCls = IntArray(12)
        for (l in cells) {
            if (l != null) {
                val b = cellBytes(l)
                total += b
                maxCell = max(maxCell, b)
                nRec += l.size
                for (r in l) byCls[r.cls] += r.poly.size - 1
            }
        }
        val dataEnd = dataOff + total

        val pl = places.mapNotNull { q ->
            val nm = ascii(q.name)
            if (nm.isEmpty()) null else PlaceDecoded(
                latE6 = java.lang.Math.round(q.lat * 1e6).toInt(),
                lonE6 = java.lang.Math.round(q.lon * 1e6).toInt(),
                rank = q.rank,
                name = nm
            )
        }.sortedByDescending { it.rank }.take(400)

        val nTabOff = dataEnd
        val nTabLen = cols * rows * 8
        var nBlobLen = 0
        for (l in namesOf) {
            if (!l.isNullOrEmpty()) {
                nBlobLen += 1 + l.sumOf { 1 + it.length }
            }
        }
        val placesOff = nTabOff + nTabLen + nBlobLen
        val placesLen = pl.sumOf { 10 + it.name.length }

        val totalSize = placesOff + placesLen + 16
        val out = ByteArray(totalSize)
        val dv = ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN)

        // Header GMP1
        out[0] = 'G'.code.toByte()
        out[1] = 'M'.code.toByte()
        out[2] = 'P'.code.toByte()
        out[3] = '1'.code.toByte()
        dv.putInt(4, totalSize)
        dv.putInt(8, oLat)
        dv.putInt(12, oLon)
        dv.putShort(16, cell.toShort())
        dv.putShort(18, cols.toShort())
        dv.putShort(20, rows.toShort())
        dv.putInt(22, tableOff)
        dv.putInt(26, dataOff)

        var p = dataOff
        for (cy in 0 until rows) {
            for (cx in 0 until cols) {
                val ci = cy * cols + cx
                val l = cells[ci]
                val e = tableOff + ci * 8
                if (l == null || l.isEmpty()) {
                    dv.putInt(e, 0)
                    dv.putInt(e + 4, 0)
                    continue
                }
                dv.putInt(e, p - dataOff)
                dv.putInt(e + 4, cellBytes(l))
                for (r in l) {
                    out[p] = r.cls.toByte()
                    out[p + 1] = (r.nameIdx and 0xFF).toByte()
                    dv.putShort(p + 2, r.poly.size.toShort())
                    p += 4
                    for (q in r.poly) {
                        val dx = max(0, min(cell, q[0] - cx * cell))
                        val dy = max(0, min(cell, q[1] - cy * cell))
                        dv.putShort(p, dx.toShort())
                        dv.putShort(p + 2, dy.toShort())
                        p += 4
                    }
                }
            }
        }

        // Names table + blob
        var nb = nTabOff + nTabLen
        for (ci in 0 until cols * rows) {
            val list = namesOf[ci]
            val e = nTabOff + ci * 8
            if (list.isNullOrEmpty()) {
                dv.putInt(e, 0)
                dv.putInt(e + 4, 0)
                continue
            }
            val start = nb
            out[nb++] = list.size.toByte()
            for (n in list) {
                out[nb++] = n.length.toByte()
                for (k in n.indices) {
                    out[nb++] = n[k].code.toByte()
                }
            }
            dv.putInt(e, start)
            dv.putInt(e + 4, nb - start)
        }

        // Places
        var pp = placesOff
        for (q in pl) {
            dv.putInt(pp, q.latE6)
            dv.putInt(pp + 4, q.lonE6)
            out[pp + 8] = q.rank.toByte()
            out[pp + 9] = q.name.length.toByte()
            pp += 10
            for (k in q.name.indices) {
                out[pp++] = q.name[k].code.toByte()
            }
        }

        // GNM1 trailer
        val tr = totalSize - 16
        out[tr] = 'G'.code.toByte()
        out[tr + 1] = 'N'.code.toByte()
        out[tr + 2] = 'M'.code.toByte()
        out[tr + 3] = '1'.code.toByte()
        dv.putInt(tr + 4, nTabOff)
        dv.putInt(tr + 8, placesOff)
        dv.putShort(tr + 12, pl.size.toShort())
        dv.putInt(4, totalSize)

        val totalNames = namesOf.sumOf { it?.size ?: 0 }
        return BuildResult(
            bytes = out,
            stats = GmapStats(
                names = totalNames,
                places = pl.size,
                cols = cols,
                rows = rows,
                size = totalSize,
                maxCell = maxCell,
                records = nRec,
                tol = tol,
                passes = passes,
                dropped = dropped,
                byCls = byCls
            )
        )
    }

    fun withRoute(bytes: ByteArray, nav: NavRoute): ByteArray {
        val nPts = nav.pts.size
        val nSteps = nav.steps.size
        val out = ByteArray(bytes.size + nPts * 12 + nSteps * 32 + 16)
        System.arraycopy(bytes, 0, out, 0, bytes.size)
        val dv = ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN)
        var p = bytes.size
        for (q in nav.pts) {
            dv.putInt(p, java.lang.Math.round(q.lat * 1e6).toInt())
            dv.putInt(p + 4, java.lang.Math.round(q.lon * 1e6).toInt())
            dv.putInt(p + 8, java.lang.Math.round(q.cumM).toInt())
            p += 12
        }
        for (s in nav.steps) {
            dv.putShort(p, s.idx.toShort())
            out[p + 2] = s.ty.toByte()
            dv.putInt(p + 4, java.lang.Math.round(s.cumM).toInt())
            val len = min(23, s.name.length)
            for (i in 0 until len) {
                out[p + 8 + i] = s.name[i].code.toByte()
            }
            p += 32
        }
        out[p] = 'G'.code.toByte()
        out[p + 1] = 'R'.code.toByte()
        out[p + 2] = 'T'.code.toByte()
        out[p + 3] = '1'.code.toByte()
        dv.putInt(p + 4, bytes.size)
        dv.putInt(p + 8, nPts)
        dv.putShort(p + 12, nSteps.toShort())
        return out
    }

    fun readGmap(buf: ByteArray): GmapDecoded {
        if (buf.size < 32 ||
            buf[0] != 'G'.code.toByte() ||
            buf[1] != 'M'.code.toByte() ||
            buf[2] != 'P'.code.toByte() ||
            buf[3] != '1'.code.toByte()
        ) {
            throw IllegalArgumentException("bukan GMP1")
        }
        val dv = ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN)
        val oLat = dv.getInt(8)
        val oLon = dv.getInt(12)
        val cell = dv.getShort(16).toInt() and 0xFFFF
        val cols = dv.getShort(18).toInt() and 0xFFFF
        val rows = dv.getShort(20).toInt() and 0xFFFF
        val tO = dv.getInt(22)
        val dO = dv.getInt(26)
        val style = buf[30].toInt() and 0xFF
        val items = mutableListOf<GmapDecodedItem>()

        for (cy in 0 until rows) {
            for (cx in 0 until cols) {
                val e = tO + (cy * cols + cx) * 8
                val off = dv.getInt(e)
                val len = dv.getInt(e + 4)
                if (len <= 0) continue
                var p = dO + off
                val end = p + len
                val bLat = oLat + cy * cell
                val bLon = oLon + cx * cell
                while (p + 4 <= end) {
                    val cls = buf[p].toInt() and 0xFF
                    val n = dv.getShort(p + 2).toInt() and 0xFFFF
                    p += 4
                    val pts = ArrayList<Pair<Int, Int>>(n)
                    for (i in 0 until n) {
                        val dx = dv.getShort(p + 4 * i).toInt()
                        val dy = dv.getShort(p + 4 * i + 2).toInt()
                        pts.add((bLat + dy) to (bLon + dx))
                    }
                    p += 4 * n
                    items.add(GmapDecodedItem(cls = cls, pts = pts))
                }
            }
        }
        return GmapDecoded(
            oLat = oLat,
            oLon = oLon,
            cell = cell,
            cols = cols,
            rows = rows,
            style = style,
            items = items
        )
    }

    fun readNames(buf: ByteArray): NamesDecoded? {
        if (buf.size < 32) return null
        val dv = ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN)
        // If GRT1 trailer is present at end, locate baseBytesLen; otherwise GNM1 is at buf.size - 16
        val endTagPos = buf.size - 16
        val gnmEnd = if (buf[endTagPos] == 'G'.code.toByte() &&
            buf[endTagPos + 1] == 'R'.code.toByte() &&
            buf[endTagPos + 2] == 'T'.code.toByte() &&
            buf[endTagPos + 3] == '1'.code.toByte()
        ) {
            dv.getInt(endTagPos + 4)
        } else {
            buf.size
        }
        val tr = gnmEnd - 16
        if (tr < 32 ||
            buf[tr] != 'G'.code.toByte() ||
            buf[tr + 1] != 'N'.code.toByte() ||
            buf[tr + 2] != 'M'.code.toByte() ||
            buf[tr + 3] != '1'.code.toByte()
        ) return null

        val tab = dv.getInt(tr + 4)
        val pOff = dv.getInt(tr + 8)
        val nP = dv.getShort(tr + 12).toInt() and 0xFFFF
        val cols = dv.getShort(18).toInt() and 0xFFFF
        val rows = dv.getShort(20).toInt() and 0xFFFF

        fun readStr(pos: Int, len: Int): String {
            return String(buf, pos, len, Charsets.US_ASCII)
        }

        val cells = MutableList<List<String>?>(cols * rows) { null }
        for (ci in 0 until cols * rows) {
            val off = dv.getInt(tab + ci * 8)
            val len = dv.getInt(tab + ci * 8 + 4)
            if (len <= 0) continue
            var p = off
            val count = buf[p++].toInt() and 0xFF
            val list = ArrayList<String>(count)
            for (i in 0 until count) {
                val n = buf[p++].toInt() and 0xFF
                list.add(readStr(p, n))
                p += n
            }
            cells[ci] = list
        }
        val places = ArrayList<PlaceDecoded>(nP)
        var p = pOff
        for (i in 0 until nP) {
            val n = buf[p + 9].toInt() and 0xFF
            places.add(
                PlaceDecoded(
                    latE6 = dv.getInt(p),
                    lonE6 = dv.getInt(p + 4),
                    rank = buf[p + 8].toInt() and 0xFF,
                    name = readStr(p + 10, n)
                )
            )
            p += 10 + n
        }
        return NamesDecoded(cells = cells, places = places)
    }

    fun hv(a: LatLon, b: LatLon): Double {
        val x = (b.lon - a.lon) * 111320.0 * cos((a.lat + b.lat) * Math.PI / 360.0)
        val y = (b.lat - a.lat) * MLAT
        return hypot(x, y)
    }

    fun stepType(t: String, m: String): Int {
        if (t == "depart") return 8
        if (t == "arrive") return 7
        if (t.contains("roundabout") || t.contains("rotary")) return 6
        return when (m) {
            "uturn" -> 5
            "right", "sharp right" -> 1
            "slight right" -> 3
            "left", "sharp left" -> 2
            "slight left" -> 4
            "straight" -> 0
            else -> -1
        }
    }

    fun corridor(pts: List<RoutePoint>, bufM: Double): CorridorPlan {
        val step = 3000.0
        var la0 = 1e9
        var la1 = -1e9
        var lo0 = 1e9
        var lo1 = -1e9
        for (q in pts) {
            la0 = min(la0, q.lat)
            la1 = max(la1, q.lat)
            lo0 = min(lo0, q.lon)
            lo1 = max(lo1, q.lon)
        }
        val latC = (la0 + la1) / 2.0
        val lonC = (lo0 + lo1) / 2.0
        val mLon = 111320.0 * cos(latC * Math.PI / 180.0)
        val rM = max((la1 - la0) * MLAT, (lo1 - lo0) * mLon) / 2.0 + bufM + step
        if (2.0 * rM / 1000.0 > 199.0) {
            throw IllegalStateException("Rute terlalu jauh untuk satu peta (maks ~190 km bentangan).")
        }
        val s0 = latC - rM / MLAT
        val w0 = lonC - rM / mLon
        val nA = ceil(2.0 * rM / step).toInt()
        val set = HashSet<Pair<Int, Int>>()

        fun mk(la: Double, lo: Double) {
            val i = floor((la - s0) * MLAT / step).toInt()
            val j = floor((lo - w0) * mLon / step).toInt()
            if (i in 0 until nA && j in 0 until nA) {
                set.add(i to j)
            }
        }

        val offsets = doubleArrayOf(-bufM, 0.0, bufM)
        for (k in 1 until pts.size) {
            val a = LatLon(pts[k - 1].lat, pts[k - 1].lon)
            val b = LatLon(pts[k].lat, pts[k].lon)
            val n = max(1, ceil(hv(a, b) / 400.0).toInt())
            for (t in 0..n) {
                val la = a.lat + (b.lat - a.lat) * t / n
                val lo = a.lon + (b.lon - a.lon) * t / n
                for (dy in offsets) {
                    for (dx in offsets) {
                        mk(la + dy / MLAT, lo + dx / mLon)
                    }
                }
            }
        }
        return CorridorPlan(latC = latC, lonC = lonC, radiusM = rM, blocks = set)
    }
}
