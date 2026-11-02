package com.xmaci.tesladroid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.DataInputStream
import java.net.Socket
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class CarServerTest {

    private fun startServer(listener: CarServer.Listener): CarServer {
        val s = CarServer({ name -> if (name == "index.html") "<html>car</html>".toByteArray() else null }, listener)
        assertTrue(s.start(intArrayOf(18765, 18766)))
        assertEquals(listOf(18765, 18766), s.ports)
        return s
    }

    private fun http(port: Int, path: String): String = Socket("127.0.0.1", port).use { s ->
        s.getOutputStream().write("GET $path HTTP/1.1\r\nHost: x\r\n\r\n".toByteArray())
        s.getInputStream().bufferedReader().readText()
    }

    @Test fun servesPageAndPingOnEveryPort() {
        val srv = startServer(object : CarServer.Listener {
            override fun onOpen(conn: CarServer.Conn) {}
            override fun onText(conn: CarServer.Conn, text: String) {}
            override fun onClose(conn: CarServer.Conn) {}
        })
        try {
            val page = http(18765, "/")
            assertTrue(page, page.startsWith("HTTP/1.1 200") && page.endsWith("<html>car</html>"))
            assertTrue(http(18766, "/ping").contains("TeslaDroid OK"))
            assertTrue(http(18765, "/nope").startsWith("HTTP/1.1 404"))
            assertTrue(srv.connLog.any { it.contains("GET /ping") })
        } finally { srv.stop() }
    }

    @Test fun websocketTextInAndBinaryOut() {
        val got = CountDownLatch(1)
        var received = ""
        val srv = startServer(object : CarServer.Listener {
            override fun onOpen(conn: CarServer.Conn) {}
            override fun onText(conn: CarServer.Conn, text: String) {
                received = text
                conn.sendBinary(byteArrayOf(1, 2, 3), ByteArray(70000) { 7 })   // >65535 -> 64-bit length form
                got.countDown()
            }
            override fun onClose(conn: CarServer.Conn) {}
        })
        try {
            Socket("127.0.0.1", 18765).use { s ->
                val key = Base64.getEncoder().encodeToString(ByteArray(16) { it.toByte() })
                s.getOutputStream().write(("GET /ws HTTP/1.1\r\nHost: x\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n" +
                    "Sec-WebSocket-Key: $key\r\nSec-WebSocket-Version: 13\r\n\r\n").toByteArray())
                val input = DataInputStream(s.getInputStream())
                val head = StringBuilder()
                while (!head.endsWith("\r\n\r\n")) head.append(input.readByte().toInt().toChar())
                assertTrue(head.toString(), head.startsWith("HTTP/1.1 101"))
                // masked client text frame "ack:5:12"
                val payload = "ack:5:12".toByteArray()
                val mask = byteArrayOf(9, 8, 7, 6)
                val frame = byteArrayOf(0x81.toByte(), (0x80 or payload.size).toByte()) + mask +
                    ByteArray(payload.size) { (payload[it].toInt() xor mask[it % 4].toInt()).toByte() }
                s.getOutputStream().write(frame)
                assertTrue(got.await(3, TimeUnit.SECONDS))
                assertEquals("ack:5:12", received)
                assertEquals(0x82, input.readUnsignedByte())
                assertEquals(127, input.readUnsignedByte())
                assertEquals(70003L, input.readLong())
                val body = ByteArray(70003); input.readFully(body)
                assertEquals(1, body[0].toInt()); assertEquals(7, body[70002].toInt())
            }
        } finally { srv.stop() }
    }
}
