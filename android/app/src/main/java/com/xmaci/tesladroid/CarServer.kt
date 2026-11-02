package com.xmaci.tesladroid

import java.io.BufferedInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.Base64

/**
 * Minimal HTTP + WebSocket server for the car browser. No external libraries.
 *
 * Listens on 7777 (car link), plus 7778/8080 as spares.
 * GET /            -> assets/index.html (the car client)
 * GET /<file>      -> other files from assets
 * GET /ws          -> WebSocket; only the newest connection is kept (one car at a time)
 */
class CarServer(
    private val openAsset: (String) -> ByteArray?,
    private val listener: Listener,
    private val log: (String) -> Unit = {},
) {
    interface Listener {
        fun onOpen(conn: Conn)
        fun onText(conn: Conn, text: String)
        fun onClose(conn: Conn)
    }

    companion object {
        private const val GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"
        private const val MAX_FRAME = 64 * 1024
    }

    /** First port that bound (used for the car link); all bound ports listen. */
    @Volatile var port = 0
        private set
    @Volatile var ports: List<Int> = emptyList()
        private set
    @Volatile var lastError = ""
        private set
    private val servers = ArrayList<ServerSocket>()
    @Volatile var current: Conn? = null
        private set

    /** Every incoming connection, newest first (diagnostics shown in the app). */
    val connLog = java.util.concurrent.ConcurrentLinkedDeque<String>()

    /** Listens on every port in [want] that can be bound; the first one is the car link (7777). */
    fun start(want: IntArray = intArrayOf(Config.DEFAULT_PORT, 7778, 8080)): Boolean {
        val ok = ArrayList<Int>()
        val errs = ArrayList<String>()
        for (p in want) {
            try {
                val s = ServerSocket()
                s.reuseAddress = true
                s.bind(InetSocketAddress("0.0.0.0", p), 16)
                synchronized(servers) { servers.add(s) }
                ok.add(p)
                Thread({ acceptLoop(s) }, "car-accept-$p").apply { isDaemon = true; start() }
            } catch (e: IOException) {
                errs.add("port $p: ${e.message}")
            }
        }
        ports = ok
        port = ok.firstOrNull() ?: 0
        lastError = errs.joinToString("; ")
        log("listening on $ok ${if (errs.isEmpty()) "" else errs}")
        return ok.isNotEmpty()
    }

    fun stop() {
        synchronized(servers) {
            for (s in servers) try { s.close() } catch (_: IOException) {}
            servers.clear()
        }
        current?.close()
        current = null
    }

    private fun note(s: String) {
        val t = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date())
        connLog.addFirst("$t $s")
        while (connLog.size > 12) connLog.pollLast()
        log(s)
    }

    private fun acceptLoop(s: ServerSocket) {
        while (!s.isClosed) {
            val sock = try { s.accept() } catch (_: IOException) { break }
            Thread({ handle(sock) }, "car-conn").apply { isDaemon = true; start() }
        }
    }

    private fun handle(sock: Socket) {
        val who = "${sock.inetAddress?.hostAddress} → ${sock.localAddress?.hostAddress}:${sock.localPort}"
        note("connect $who")
        try {
            sock.tcpNoDelay = true
            sock.soTimeout = 15000
            val input = BufferedInputStream(sock.getInputStream())
            val out = sock.getOutputStream()
            val requestLine = readLine(input) ?: return sock.close()
            val headers = HashMap<String, String>()
            while (true) {
                val line = readLine(input) ?: break
                if (line.isEmpty()) break
                val i = line.indexOf(':')
                if (i > 0) headers[line.substring(0, i).trim().lowercase()] = line.substring(i + 1).trim()
            }
            val parts = requestLine.split(" ")
            if (parts.size < 2 || parts[0] != "GET") return reply(sock, out, 405, "text/plain", "method".toByteArray())
            val path = parts[1].substringBefore('?')
            note("GET $path from ${sock.inetAddress?.hostAddress}")
            if (path == "/ws" && headers["upgrade"]?.lowercase() == "websocket") {
                upgrade(sock, input, out, headers["sec-websocket-key"] ?: return sock.close())
                return
            }
            serveAsset(sock, out, if (path == "/") "index.html" else path.trimStart('/'))
        } catch (e: Exception) {
            try { sock.close() } catch (_: IOException) {}
        }
    }

    private fun serveAsset(sock: Socket, out: OutputStream, name: String) {
        if (name.contains("..") || name.isEmpty()) return reply(sock, out, 404, "text/plain", "not found".toByteArray())
        if (name == "ping") return reply(sock, out, 200, "text/plain", "TeslaDroid OK".toByteArray())
        val bytes = openAsset(name) ?: return reply(sock, out, 404, "text/plain", "not found".toByteArray())
        val type = when (name.substringAfterLast('.')) {
            "html" -> "text/html; charset=utf-8"
            "js" -> "application/javascript; charset=utf-8"
            "css" -> "text/css"
            "png" -> "image/png"
            "svg" -> "image/svg+xml"
            else -> "application/octet-stream"
        }
        reply(sock, out, 200, type, bytes)
    }

    private fun reply(sock: Socket, out: OutputStream, code: Int, type: String, body: ByteArray) {
        val status = mapOf(200 to "OK", 404 to "Not Found", 405 to "Method Not Allowed")[code] ?: "Error"
        out.write(("HTTP/1.1 $code $status\r\nContent-Type: $type\r\nContent-Length: ${body.size}\r\n" +
            "Cache-Control: no-store\r\nConnection: close\r\n\r\n").toByteArray())
        out.write(body)
        out.flush()
        sock.close()
    }

    private fun upgrade(sock: Socket, input: InputStream, out: OutputStream, key: String) {
        val accept = Base64.getEncoder().encodeToString(
            MessageDigest.getInstance("SHA-1").digest((key + GUID).toByteArray()))
        out.write(("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n" +
            "Sec-WebSocket-Accept: $accept\r\n\r\n").toByteArray())
        out.flush()
        sock.soTimeout = 0
        val conn = Conn(sock, out)
        current?.close()            // newest car connection wins
        current = conn
        listener.onOpen(conn)
        try {
            while (true) {
                val (op, data) = readFrame(input) ?: break
                when (op) {
                    0x1 -> listener.onText(conn, String(data, Charsets.UTF_8))
                    0x8 -> break
                    0x9 -> conn.send(0xA, data)
                }
            }
        } catch (_: Exception) {
        } finally {
            conn.close()
            if (current === conn) current = null
            listener.onClose(conn)
        }
    }

    /** Reads one client frame (always masked, small text control messages). */
    private fun readFrame(input: InputStream): Pair<Int, ByteArray>? {
        val b1 = input.read()
        val b2 = input.read()
        if (b1 < 0 || b2 < 0) return null
        var len = (b2 and 0x7F).toLong()
        if (len == 126L) {
            val e = readN(input, 2)
            len = ((e[0].toLong() and 0xFF) shl 8) or (e[1].toLong() and 0xFF)
        } else if (len == 127L) {
            val e = readN(input, 8)
            len = 0
            for (b in e) len = (len shl 8) or (b.toLong() and 0xFF)
        }
        if (len > MAX_FRAME) throw IOException("frame too large")
        val mask = if (b2 and 0x80 != 0) readN(input, 4) else null
        val data = readN(input, len.toInt())
        if (mask != null) for (i in data.indices) data[i] = (data[i].toInt() xor mask[i % 4].toInt()).toByte()
        return Pair(b1 and 0x0F, data)
    }

    private fun readN(input: InputStream, n: Int): ByteArray {
        val buf = ByteArray(n)
        var off = 0
        while (off < n) {
            val r = input.read(buf, off, n - off)
            if (r < 0) throw IOException("eof")
            off += r
        }
        return buf
    }

    private fun readLine(input: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val c = input.read()
            if (c < 0) return if (sb.isEmpty()) null else sb.toString()
            if (c == '\n'.code) return sb.toString().trimEnd('\r')
            if (sb.length > 8192) return null
            sb.append(c.toChar())
        }
    }

    class Conn(private val sock: Socket, private val out: OutputStream) {
        @Volatile var open = true
            private set

        fun sendText(s: String) = send(0x1, s.toByteArray(Charsets.UTF_8))
        fun sendBinary(header: ByteArray, body: ByteArray) = send(0x2, header, body)

        @Synchronized
        fun send(opcode: Int, vararg parts: ByteArray): Boolean {
            if (!open) return false
            val n = parts.sumOf { it.size }
            val h = when {
                n < 126 -> byteArrayOf((0x80 or opcode).toByte(), n.toByte())
                n < 65536 -> byteArrayOf((0x80 or opcode).toByte(), 126, (n shr 8).toByte(), n.toByte())
                else -> ByteArray(10).also {
                    it[0] = (0x80 or opcode).toByte(); it[1] = 127
                    for (i in 0 until 8) it[9 - i] = (n.toLong() shr (8 * i)).toByte()
                }
            }
            return try {
                out.write(h)
                for (p in parts) out.write(p)
                out.flush()
                true
            } catch (_: IOException) {
                close(); false
            }
        }

        fun close() {
            open = false
            try { sock.close() } catch (_: IOException) {}
        }
    }
}
