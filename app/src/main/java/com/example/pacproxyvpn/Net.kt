package com.example.pacproxyvpn

import android.util.Log
import java.io.IOException
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/** Trạng thái + log dùng chung giữa service và giao diện. */
object Stats {
    @Volatile var state = "OFF" // OFF, STARTING, ON, ERROR
    val up = AtomicLong()
    val down = AtomicLong()
    val udp = AtomicLong()
    val tcpActive = AtomicInteger()
    val tcpOk = AtomicInteger()
    val tcpFail = AtomicInteger()
    val pkts = AtomicLong()
    val v6 = AtomicLong()
    val udpNew = AtomicInteger()
    val viaProxyConns = AtomicInteger()
    val viaDirectConns = AtomicInteger()

    private val lines = java.util.ArrayDeque<String>()
    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.US)

    @Synchronized fun log(m: String) {
        Log.i("PACProxyVPN", m)
        lines.addLast("${fmt.format(Date())}  $m")
        while (lines.size > 60) lines.removeFirst()
    }

    @Synchronized fun tail(n: Int): String = lines.toList().takeLast(n).joinToString("\n")

    fun resetCounters() {
        up.set(0); down.set(0); udp.set(0)
        tcpActive.set(0); tcpOk.set(0); tcpFail.set(0)
        pkts.set(0); v6.set(0); udpNew.set(0)
        viaProxyConns.set(0); viaDirectConns.set(0)
    }
}

/** Tiện ích đóng/mở gói IPv4. */
object Net {
    const val MSS = 1360

    fun u8(b: ByteArray, i: Int) = b[i].toInt() and 0xFF
    fun u16(b: ByteArray, i: Int) = (u8(b, i) shl 8) or u8(b, i + 1)
    fun i32(b: ByteArray, i: Int) = (u8(b, i) shl 24) or (u8(b, i + 1) shl 16) or (u8(b, i + 2) shl 8) or u8(b, i + 3)
    fun put16(b: ByteArray, i: Int, v: Int) { b[i] = (v ushr 8).toByte(); b[i + 1] = v.toByte() }
    fun put32(b: ByteArray, i: Int, v: Int) { put16(b, i, v ushr 16); put16(b, i + 2, v and 0xFFFF) }

    fun ipStr(ip: Int) = "${ip ushr 24}.${(ip ushr 16) and 255}.${(ip ushr 8) and 255}.${ip and 255}"

    fun csum(b: ByteArray, off: Int, len: Int, init: Long): Int {
        var sum = init
        var i = off
        val end = off + len
        while (i + 1 < end) { sum += u16(b, i).toLong(); i += 2 }
        if (i < end) sum += (u8(b, i) shl 8).toLong()
        while ((sum ushr 16) != 0L) sum = (sum and 0xFFFF) + (sum ushr 16)
        return (sum.inv() and 0xFFFF).toInt()
    }

    private fun pseudo(p: ByteArray, proto: Int, len: Int): Long {
        var s = 0L
        var i = 12
        while (i < 20) { s += u16(p, i).toLong(); i += 2 }
        return s + proto + len
    }

    private fun ipHeader(p: ByteArray, proto: Int, src: Int, dst: Int) {
        p[0] = 0x45.toByte()
        put16(p, 2, p.size)
        put16(p, 6, 0x4000)
        p[8] = 64
        p[9] = proto.toByte()
        put32(p, 12, src)
        put32(p, 16, dst)
        put16(p, 10, csum(p, 0, 20, 0))
    }

    fun tcp(
        src: Int, dst: Int, sp: Int, dp: Int, seq: Int, ack: Int, flags: Int,
        data: ByteArray?, off: Int, len: Int, mss: Boolean
    ): ByteArray {
        val opt = if (mss) 4 else 0
        val tl = 20 + opt + len
        val p = ByteArray(20 + tl)
        ipHeader(p, 6, src, dst)
        put16(p, 20, sp); put16(p, 22, dp)
        put32(p, 24, seq); put32(p, 28, ack)
        p[32] = (((20 + opt) / 4) shl 4).toByte()
        p[33] = flags.toByte()
        put16(p, 34, 65535)
        if (mss) { p[40] = 2; p[41] = 4; put16(p, 42, MSS) }
        if (len > 0 && data != null) System.arraycopy(data, off, p, 40 + opt, len)
        put16(p, 36, csum(p, 20, tl, pseudo(p, 6, tl)))
        return p
    }

    fun udp(src: Int, dst: Int, sp: Int, dp: Int, data: ByteArray, off: Int, len: Int): ByteArray {
        val p = ByteArray(28 + len)
        ipHeader(p, 17, src, dst)
        put16(p, 20, sp); put16(p, 22, dp); put16(p, 24, 8 + len)
        System.arraycopy(data, off, p, 28, len) // UDP checksum = 0 hợp lệ với IPv4
        return p
    }
}

/** Luật lọc rút ra từ file PAC: các chuỗi con dùng trong host.indexOf("..."). */
object PacRules {
    @Volatile var active: List<String> = emptyList()

    fun parse(pacBody: String) {
        val found = Regex("""indexOf\(\s*["']([^"']+)["']""")
            .findAll(pacBody).map { it.groupValues[1].lowercase() }.filter { it.isNotBlank() }.toList()
        active = found
        if (found.isNotEmpty()) Stats.log("PAC: ${found.size} luật lọc (vd: ${found.take(4).joinToString(", ")})")
    }

    fun clear() { active = emptyList() }

    /** true nếu s (host hoặc IP dạng text) khớp một luật, hoặc nếu không có luật nào (không lọc). */
    fun shouldProxy(s: String?): Boolean {
        if (active.isEmpty()) return true
        if (s == null) return false
        val low = s.lowercase()
        return active.any { low.contains(it) }
    }
}

/** Dò tên máy chủ (SNI) trong gói TLS ClientHello đầu tiên, không giải mã nội dung. */
object TlsSni {
    sealed class Result
    object NeedMore : Result()
    object NotTls : Result()
    data class Found(val host: String) : Result()
    object NoSni : Result()

    fun check(b: ByteArray): Result {
        val n = b.size
        if (n < 5) return NeedMore
        if (b[0] != 0x16.toByte()) return NotTls
        val recLen = Net.u16(b, 3)
        val need = 5 + recLen
        if (n < need) return if (need > 16384) NoSni else NeedMore
        try {
            var p = 5
            if (p >= n || b[p] != 0x01.toByte()) return NotTls
            p += 4 + 2 + 32
            if (p >= n) return NoSni
            val sidLen = Net.u8(b, p); p += 1 + sidLen
            if (p + 2 > n) return NoSni
            val csLen = Net.u16(b, p); p += 2 + csLen
            if (p + 1 > n) return NoSni
            val cmLen = Net.u8(b, p); p += 1 + cmLen
            if (p + 2 > n) return NoSni
            val extTotal = Net.u16(b, p); p += 2
            val extEnd = minOf(p + extTotal, n)
            while (p + 4 <= extEnd) {
                val type = Net.u16(b, p)
                val len = Net.u16(b, p + 2)
                val dataStart = p + 4
                if (type == 0 && dataStart + 2 <= extEnd) {
                    var q = dataStart + 2
                    if (q + 3 <= extEnd) {
                        val nameType = Net.u8(b, q)
                        val nameLen = Net.u16(b, q + 1)
                        q += 3
                        if (nameType == 0 && q + nameLen <= extEnd) {
                            return Found(String(b, q, nameLen, Charsets.US_ASCII).lowercase())
                        }
                    }
                }
                p = dataStart + len
            }
            return NoSni
        } catch (e: Exception) {
            return NoSni
        }
    }
}

object ProxyUtil {
    /** Nhận "host:port" hoặc URL file PAC, trả về địa chỉ proxy thật. */
    fun resolve(input: String): Pair<String, Int> {
        val s = input.trim()
        if (s.isEmpty()) throw IllegalArgumentException("Chưa nhập địa chỉ proxy / PAC")
        if (!s.startsWith("http", true)) {
            PacRules.clear() // nhập thẳng host:port = không lọc, proxy toàn bộ
            val port = s.substringAfterLast(":", "").toIntOrNull()
                ?: throw IllegalArgumentException("Định dạng phải là host:port hoặc URL PAC")
            return s.substringBeforeLast(":") to port
        }
        val u = URL(s)
        var host = u.host
        var port = if (u.port > 0) u.port else 80
        try {
            val c = u.openConnection() as HttpURLConnection
            c.connectTimeout = 4000
            c.readTimeout = 4000
            val body = c.inputStream.bufferedReader().use { it.readText() }
            c.disconnect()
            val m = Regex("""(?:PROXY|HTTPS?)\s+([A-Za-z0-9._\-]+):(\d+)""", RegexOption.IGNORE_CASE).find(body)
            if (m != null) {
                host = m.groupValues[1]
                port = m.groupValues[2].toInt()
                Stats.log("PAC → proxy $host:$port")
            } else {
                Stats.log("PAC không có dòng PROXY, dùng $host:$port")
            }
            PacRules.parse(body)
        } catch (e: Exception) {
            Stats.log("Không tải được PAC (${e.message}), dùng $host:$port")
            PacRules.clear()
        }
        return host to port
    }

    /** Mở kết nối tới HTTP forward proxy (không gửi CONNECT). */
    fun openForwardProxy(host: String, port: Int, protect: (Socket) -> Unit): Socket {
        val s = Socket()
        try {
            protect(s)
            s.tcpNoDelay = true
            s.connect(InetSocketAddress(host, port), 6000)
            s.soTimeout = 0
            return s
        } catch (e: Exception) {
            try { s.close() } catch (_: Exception) {}
            throw if (e is IOException) e else IOException(e.message, e)
        }
    }

    /** Mở đường hầm HTTP CONNECT qua proxy. */
    fun openTunnel(host: String, port: Int, target: String, protect: (Socket) -> Unit): Socket {
        val s = Socket()
        try {
            protect(s)
            s.tcpNoDelay = true
            s.connect(InetSocketAddress(host, port), 6000)
            s.soTimeout = 10000
            val out = s.getOutputStream()
            out.write("CONNECT $target HTTP/1.1\r\nHost: $target\r\n\r\n".toByteArray())
            out.flush()
            val ins = s.getInputStream()
            val sb = StringBuilder()
            while (sb.length < 8192 && !sb.endsWith("\r\n\r\n")) {
                val c = ins.read()
                if (c < 0) throw IOException("Proxy đóng kết nối khi chưa trả lời")
                sb.append(c.toChar())
            }
            val first = sb.lineSequence().first()
            val parts = first.split(" ")
            if (parts.size < 2 || parts[1] != "200") {
                throw IOException(if (parts.getOrNull(1) == "407") "Proxy yêu cầu đăng nhập (407)" else "Proxy trả về: $first")
            }
            s.soTimeout = 0
            return s
        } catch (e: Exception) {
            try { s.close() } catch (_: Exception) {}
            throw if (e is IOException) e else IOException(e.message, e)
        }
    }

    /** Kiểm tra proxy: bắt buộc mở được TCP; CONNECT thử chỉ là cảnh báo. */
    fun probe(host: String, port: Int): String {
        val t = Socket()
        try {
            t.connect(InetSocketAddress(host, port), 4000)
        } catch (e: Exception) {
            throw IOException("Không kết nối được proxy $host:$port (${e.message}). Python bridge đã chạy chưa?")
        } finally {
            try { t.close() } catch (_: Exception) {}
        }
        return try {
            openTunnel(host, port, "1.1.1.1:443") {}.close()
            "✓ Proxy $host:$port hoạt động (CONNECT thử thành công)"
        } catch (e: Exception) {
            "⚠ Proxy mở được nhưng CONNECT thử thất bại: ${e.message}"
        }
    }
}
