package com.example.pacproxyvpn

import android.net.VpnService
import android.os.ParcelFileDescriptor
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

private const val FIN = 1
private const val SYN = 2
private const val RST = 4
private const val PSH = 8
private const val ACK = 16

/**
 * Engine userspace: đọc gói IP từ TUN, kết thúc TCP tại chỗ rồi chuyển HTTP thường
 * theo absolute-form tới HTTP proxy; TLS dùng CONNECT tunnel. UDP đi thẳng.
 */
class TunEngine(
    val svc: VpnService,
    tun: ParcelFileDescriptor,
    val proxyHost: String,
    val proxyPort: Int
) {
    private val input = FileInputStream(tun.fileDescriptor)
    private val output = FileOutputStream(tun.fileDescriptor)
    private val wlock = Any()
    @Volatile var running = true
    val pool: ExecutorService = Executors.newCachedThreadPool()
    val tcpConns = ConcurrentHashMap<String, TcpConn>()
    val udpFlows = ConcurrentHashMap<String, UdpFlow>()
    private val sweeper = Executors.newSingleThreadScheduledExecutor()

    fun scheduleOnce(delayMs: Long, task: () -> Unit) {
        sweeper.schedule(task, delayMs, TimeUnit.MILLISECONDS)
    }

    fun start() {
        sweeper.scheduleAtFixedRate({
            val now = System.currentTimeMillis()
            tcpConns.values.forEach { if (now - it.last > 300_000) it.close() }
        }, 30, 30, TimeUnit.SECONDS)
        Thread({ loop() }, "tun-loop").start()
    }

    fun stop() {
        running = false
        sweeper.shutdownNow()
        tcpConns.values.forEach { it.close() }
        udpFlows.values.forEach { it.close() }
        pool.shutdownNow()
    }

    fun write(p: ByteArray) {
        synchronized(wlock) {
            try { output.write(p) } catch (x: IOException) {
                if (running) Stats.log("TUN write lỗi: ${x.message}")
            }
        }
    }

    private fun loop() {
        val buf = ByteArray(32768)
        while (running) {
            val n = try { input.read(buf) } catch (e: IOException) {
                if (running) Stats.log("TUN read lỗi: ${e.message}")
                break
            }
            if (n < 0) break
            if (n < 20) continue
            try { handle(buf, n) } catch (e: Exception) { Stats.log("Lỗi xử lý gói: $e") }
        }
    }

    private fun handle(b: ByteArray, n: Int) {
        Stats.pkts.incrementAndGet()
        if (((Net.u8(b, 0)) ushr 4) != 4) {
            if (Stats.v6.incrementAndGet() == 1L) Stats.log("App gửi gói IPv6 (bị bỏ)")
            return
        }
        val ihl = (Net.u8(b, 0) and 15) * 4
        val total = minOf(Net.u16(b, 2), n)
        if (ihl < 20 || total < ihl) return
        if ((Net.u16(b, 6) and 0x3FFF) != 0) return // bỏ gói phân mảnh
        val src = Net.i32(b, 12)
        val dst = Net.i32(b, 16)
        if ((dst ushr 24) >= 224) return // multicast / broadcast
        when (Net.u8(b, 9)) {
            6 -> handleTcp(b, ihl, total, src, dst)
            17 -> handleUdp(b, ihl, total, src, dst)
        }
    }

    private fun handleTcp(b: ByteArray, o: Int, total: Int, src: Int, dst: Int) {
        if (total - o < 20) return
        val sp = Net.u16(b, o)
        val dp = Net.u16(b, o + 2)
        val seq = Net.i32(b, o + 4)
        val ack = Net.i32(b, o + 8)
        val doff = (Net.u8(b, o + 12) ushr 4) * 4
        val flags = Net.u8(b, o + 13) and 0x3F
        val win = Net.u16(b, o + 14)
        val po = o + doff
        val pl = total - po
        if (doff < 20 || pl < 0) return
        val key = "$src:$sp>$dst:$dp"
        val c = tcpConns[key]
        if (c == null) {
            if ((flags and SYN) != 0 && (flags and ACK) == 0) {
                val nc = TcpConn(this, key, src, sp, dst, dp, seq)
                tcpConns[key] = nc
                nc.begin()
            } else if ((flags and RST) == 0) {
                val rs = if ((flags and ACK) != 0) ack else 0
                val ra = seq + pl + (if ((flags and SYN) != 0) 1 else 0) + (if ((flags and FIN) != 0) 1 else 0)
                write(Net.tcp(dst, src, dp, sp, rs, ra, RST or ACK, null, 0, 0, false))
            }
            return
        }
        c.onSegment(seq, ack, flags, win, b, po, pl)
    }

    private fun handleUdp(b: ByteArray, o: Int, total: Int, src: Int, dst: Int) {
        if (total - o < 8) return
        val sp = Net.u16(b, o)
        val dp = Net.u16(b, o + 2)
        val po = o + 8
        val pl = minOf(Net.u16(b, o + 4) - 8, total - po)
        if (pl < 0) return
        Stats.udp.incrementAndGet()

        // Chặn truy vấn AAAA để app dùng IPv4 (tránh rò rỉ / chờ timeout IPv6)
        if (dp == 53) {
            val len = aaaaLen(b, po, pl)
            if (len > 0) {
                val r = ByteArray(len)
                System.arraycopy(b, po, r, 0, len)
                r[2] = (r[2].toInt() or 0x80).toByte()
                r[3] = 0x80.toByte()
                for (k in 6..11) r[k] = 0
                write(Net.udp(dst, src, dp, sp, r, 0, len))
                return
            }
        }

        val key = "$src:$sp>$dst:$dp"
        var f = udpFlows[key]
        if (f == null || f.closed) {
            f = UdpFlow(this, key, src, sp, dst, dp)
            if (!f.open()) return
            if (dp != 53 && Stats.udpNew.incrementAndGet() <= 8) Stats.log("UDP ${Net.ipStr(dst)}:$dp (đi thẳng, không qua proxy)")
            udpFlows[key] = f
        }
        f.send(b, po, pl)
    }

    /** Nếu là truy vấn DNS loại AAAA trả về độ dài header+question, ngược lại -1. */
    private fun aaaaLen(b: ByteArray, po: Int, pl: Int): Int {
        if (pl < 17 || Net.u16(b, po + 4) != 1 || (Net.u8(b, po + 2) and 0x80) != 0) return -1
        var i = po + 12
        val end = po + pl
        while (i < end) {
            val l = Net.u8(b, i)
            if (l == 0) break
            if ((l and 0xC0) != 0) return -1
            i += l + 1
        }
        if (i + 5 > end) return -1
        return if (Net.u16(b, i + 1) == 28) (i + 5) - po else -1
    }
}

class UdpFlow(
    private val e: TunEngine, private val key: String,
    private val cIp: Int, private val cPort: Int, private val sIp: Int, private val sPort: Int
) {
    @Volatile var closed = false
    private var sock: DatagramSocket? = null

    fun open(): Boolean = try {
        val s = DatagramSocket()
        e.svc.protect(s)
        s.connect(InetAddress.getByName(Net.ipStr(sIp)), sPort)
        s.soTimeout = if (sPort == 53) 5000 else 60000
        sock = s
        e.pool.execute { loop(s) }
        true
    } catch (x: Exception) {
        Stats.log("UDP lỗi ${Net.ipStr(sIp)}:$sPort — ${x.message}")
        false
    }

    fun send(b: ByteArray, off: Int, len: Int) {
        try {
            sock?.send(DatagramPacket(b.copyOfRange(off, off + len), len))
            Stats.up.addAndGet(len.toLong())
        } catch (x: Exception) { close() }
    }

    private fun loop(s: DatagramSocket) {
        val buf = ByteArray(4096)
        try {
            while (!closed) {
                val pk = DatagramPacket(buf, buf.size)
                s.receive(pk)
                if (pk.length <= 1372) {
                    e.write(Net.udp(sIp, cIp, sPort, cPort, buf, 0, pk.length))
                    Stats.down.addAndGet(pk.length.toLong())
                }
            }
        } catch (_: Exception) {
        }
        close()
    }

    fun close() {
        if (closed) return
        closed = true
        try { sock?.close() } catch (_: Exception) {}
        e.udpFlows.remove(key)
    }
}

/** Nhận diện request HTTP và đổi origin-form thành absolute-form cho proxy Python. */
private object HttpProxyRequest {
    enum class Kind { HTTP, NOT_HTTP, NEED_MORE }
    private val methods = listOf("GET ", "POST ", "HEAD ", "PUT ", "PATCH ", "DELETE ", "OPTIONS ", "TRACE ", "CONNECT ")

    fun classify(bytes: ByteArray): Kind {
        if (bytes.isEmpty()) return Kind.NEED_MORE
        val prefix = String(bytes, 0, minOf(bytes.size, 16), Charsets.US_ASCII)
        if (methods.any { it.startsWith(prefix) || prefix.startsWith(it) }) {
            return if (prefix.contains(" ")) Kind.HTTP else Kind.NEED_MORE
        }
        if (methods.any { prefix.startsWith(it) }) return Kind.HTTP
        return if (bytes.size < 16 && methods.any { it.startsWith(prefix) }) Kind.NEED_MORE else Kind.NOT_HTTP
    }

    fun hasHeaderEnd(bytes: ByteArray): Boolean {
        for (i in 3 until bytes.size) {
            if (bytes[i - 3] == 13.toByte() && bytes[i - 2] == 10.toByte() &&
                bytes[i - 1] == 13.toByte() && bytes[i] == 10.toByte()) return true
        }
        return false
    }

    fun toAbsoluteForm(bytes: ByteArray): ByteArray {
        val text = String(bytes, Charsets.ISO_8859_1)
        val lineEnd = text.indexOf("\r\n")
        if (lineEnd <= 0) return bytes
        val requestLine = text.substring(0, lineEnd)
        val parts = requestLine.split(' ', limit = 3)
        if (parts.size != 3 || !parts[1].startsWith("/")) return bytes
        val hostMatch = Regex("""(?im)^Host:\s*([^\r\n]+)""").find(text) ?: return bytes
        val host = hostMatch.groupValues[1].trim()
        val absolute = "http://$host${parts[1]}"
        val rewritten = "${parts[0]} $absolute ${parts[2]}" + text.substring(lineEnd)
        return rewritten.toByteArray(Charsets.ISO_8859_1)
    }
}

class TcpConn(
    private val e: TunEngine, private val key: String,
    private val cIp: Int, private val cPort: Int,
    private val sIp: Int, private val sPort: Int,
    clientIsn: Int
) {
    private val lock = ReentrantLock()
    private val cond = lock.newCondition()
    private var state = 0 // 0 đang quyết định / đang nối, 1 đã mở, 2 đã đóng
    private val iss = java.util.Random().nextInt()
    private var rcvNxt = clientIsn + 1
    private var sndNxt = iss + 1
    private var sndUna = iss + 1
    private var cWin = 65535
    private var sock: Socket? = null
    private var clientFin = false
    private var finSent = false
    private var finAcked = false
    private val wq = LinkedBlockingQueue<ByteArray>()
    @Volatile var last = System.currentTimeMillis()

    // Chờ payload đầu để phân biệt HTTP request với TLS / TCP khác.
    private var decided = false
    private var connecting = false
    private val pending = java.io.ByteArrayOutputStream()
    private val PENDING_CAP = 16384

    private fun pkt(flags: Int, seq: Int, ack: Int, d: ByteArray? = null, off: Int = 0, len: Int = 0, mss: Boolean = false) =
        e.write(Net.tcp(sIp, cIp, sPort, cPort, seq, ack, flags, d, off, len, mss))

    private var forwardHttp = false

    /** Bắt tay TCP trước; đợi payload đầu để phân biệt HTTP thường với TLS. */
    fun begin() {
        pkt(SYN or ACK, iss, rcvNxt, mss = true)
    }

    /** Chốt đường đi: HTTP thường dùng forward-proxy, TLS dùng CONNECT. */
    private fun decide(viaProxy: Boolean, httpForward: Boolean = false) {
        if (decided || connecting) return
        decided = true
        connecting = true
        forwardHttp = httpForward
        val initial = pending.toByteArray()
        pending.reset()
        if (initial.isNotEmpty()) wq.offer(initial)
        connectNow(viaProxy, httpForward)
    }

    private fun connectNow(viaProxy: Boolean, httpForward: Boolean) {
        val target = "${Net.ipStr(sIp)}:$sPort"
        e.pool.execute {
            val s: Socket
            try {
                s = if (viaProxy && httpForward) {
                    ProxyUtil.openForwardProxy(e.proxyHost, e.proxyPort) { e.svc.protect(it) }
                } else if (viaProxy) {
                    ProxyUtil.openTunnel(e.proxyHost, e.proxyPort, target) { e.svc.protect(it) }
                } else {
                    val sock = Socket()
                    e.svc.protect(sock)
                    sock.tcpNoDelay = true
                    sock.connect(java.net.InetSocketAddress(Net.ipStr(sIp), sPort), 6000)
                    sock
                }
            } catch (x: Exception) {
                Stats.tcpFail.incrementAndGet()
                Stats.log("✗ $target (${if (viaProxy) "proxy" else "thẳng"}) — ${x.message}")
                lock.withLock { if (state == 0) { pkt(RST or ACK, sndNxt, rcvNxt); closeLocked() } }
                return@execute
            }
            var ok = false
            lock.withLock {
                if (state == 0) { sock = s; state = 1; ok = true }
            }
            if (!ok) { try { s.close() } catch (_: Exception) {}; return@execute }
            Stats.tcpOk.incrementAndGet()
            Stats.tcpActive.incrementAndGet()
            if (viaProxy) Stats.viaProxyConns.incrementAndGet() else Stats.viaDirectConns.incrementAndGet()
            if (Stats.tcpOk.get() <= 20) {
                val route = when { !viaProxy -> "đi thẳng"; httpForward -> "HTTP forward-proxy"; else -> "HTTPS CONNECT tunnel" }
                Stats.log("✓ $target qua $route")
            }
            e.pool.execute { writerLoop(s, httpForward) }
            readerLoop(s)
        }
    }

    fun onSegment(seq: Int, ack: Int, flags: Int, win: Int, d: ByteArray, off: Int, len: Int) {
        lock.withLock {
            if (state == 2) return
            last = System.currentTimeMillis()
            if ((flags and RST) != 0) { closeLocked(); return }
            if ((flags and SYN) != 0) return
            if ((flags and ACK) != 0) {
                if (ack - sndUna > 0 && ack - sndNxt <= 0) sndUna = ack
                cWin = win
                if (finSent && ack == sndNxt) finAcked = true
                cond.signalAll()
            }
            val fin = (flags and FIN) != 0
            var o = off
            var l = len
            var s = seq
            val dup = rcvNxt - s
            if (dup > 0) {
                if (dup >= l + (if (fin) 1 else 0)) {
                    pkt(ACK, sndNxt, rcvNxt)
                    return
                }
                o += dup; l -= dup; s += dup
            }
            if (l > 0 || fin) {
                if (s != rcvNxt) { pkt(ACK, sndNxt, rcvNxt); return }
                if (l > 0) {
                    if (!decided) {
                        if (pending.size() < PENDING_CAP) pending.write(d, o, l)
                        when (val r = TlsSni.check(pending.toByteArray())) {
                            is TlsSni.Found -> decide(true, false)
                            TlsSni.NotTls -> {
                                val bytes = pending.toByteArray()
                                when (HttpProxyRequest.classify(bytes)) {
                                    HttpProxyRequest.Kind.HTTP -> decide(true, true)
                                    HttpProxyRequest.Kind.NOT_HTTP -> decide(true, false)
                                    HttpProxyRequest.Kind.NEED_MORE -> if (pending.size() >= PENDING_CAP) decide(true, false)
                                }
                            }
                            TlsSni.NoSni -> decide(true, false)
                            TlsSni.NeedMore -> if (pending.size() >= PENDING_CAP) decide(true, false)
                        }
                    } else {
                        wq.offer(d.copyOfRange(o, o + l))
                    }
                    rcvNxt += l
                    Stats.up.addAndGet(l.toLong())
                }
                if (fin && !clientFin) {
                    rcvNxt += 1
                    clientFin = true
                    if (!decided) decide(true, false)
                    wq.offer(ByteArray(0))
                }
                pkt(ACK, sndNxt, rcvNxt)
            }
            checkDone()
        }
    }

    private fun writerLoop(s: Socket, httpForward: Boolean) {
        try {
            val o = s.getOutputStream()
            val headerBuffer = java.io.ByteArrayOutputStream()
            var headerDone = !httpForward
            while (true) {
                val d = wq.take()
                if (d.isEmpty()) {
                    if (!headerDone && headerBuffer.size() > 0) o.write(headerBuffer.toByteArray())
                    try { s.shutdownOutput() } catch (_: Exception) {}
                    break
                }
                if (!headerDone) {
                    headerBuffer.write(d)
                    val buffered = headerBuffer.toByteArray()
                    if (HttpProxyRequest.hasHeaderEnd(buffered) || headerBuffer.size() >= 65536) {
                        val outgoing = if (HttpProxyRequest.hasHeaderEnd(buffered)) HttpProxyRequest.toAbsoluteForm(buffered) else buffered
                        o.write(outgoing)
                        o.flush()
                        headerBuffer.reset()
                        headerDone = true
                    }
                } else {
                    o.write(d)
                    o.flush()
                }
            }
        } catch (x: Exception) {
            lock.withLock {
                if (state == 1) { pkt(RST or ACK, sndNxt, rcvNxt); closeLocked() }
            }
        }
    }

    private fun readerLoop(s: Socket) {
        val buf = ByteArray(16384)
        try {
            val ins = s.getInputStream()
            while (true) {
                val n = ins.read(buf)
                if (n < 0) break
                Stats.down.addAndGet(n.toLong())
                last = System.currentTimeMillis()
                var off = 0
                while (off < n) {
                    val chunk = minOf(Net.MSS, n - off)
                    lock.withLock {
                        while (state == 1 && (sndNxt - sndUna) >= maxOf(cWin, 1)) cond.await(500, TimeUnit.MILLISECONDS)
                        if (state != 1) return
                        pkt(PSH or ACK, sndNxt, rcvNxt, buf, off, chunk)
                        sndNxt += chunk
                    }
                    off += chunk
                }
            }
            lock.withLock {
                if (state == 1 && !finSent) {
                    pkt(FIN or ACK, sndNxt, rcvNxt)
                    sndNxt += 1
                    finSent = true
                    checkDone()
                }
            }
        } catch (x: IOException) {
            lock.withLock {
                if (state == 1) { pkt(RST or ACK, sndNxt, rcvNxt); closeLocked() }
            }
        }
    }

    private fun checkDone() {
        if (clientFin && finSent && finAcked) closeLocked()
    }

    private fun closeLocked() {
        if (state == 2) return
        if (state == 1) Stats.tcpActive.decrementAndGet()
        state = 2
        try { sock?.close() } catch (_: Exception) {}
        wq.offer(ByteArray(0))
        e.tcpConns.remove(key)
        cond.signalAll()
    }

    fun close() = lock.withLock { closeLocked() }
}
