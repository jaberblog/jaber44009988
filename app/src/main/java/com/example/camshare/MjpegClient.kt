package com.example.camshare

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.BufferedInputStream
import java.io.EOFException
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.concurrent.thread

class MjpegClient(
    private val host: String,
    private val port: Int,
    private val onFrame: (Bitmap) -> Unit,
    private val onState: (State) -> Unit
) {
    enum class State { CONNECTING, CONNECTED, FAILED }

    @Volatile private var running = false
    @Volatile private var socket: Socket? = null

    fun start() {
        running = true
        thread(isDaemon = true, name = "mjpeg-receiver") { run() }
    }

    fun stop() {
        running = false
        try { socket?.close() } catch (_: Exception) {}
    }

    private fun run() {
        onState(State.CONNECTING)
        val s = Socket()
        socket = s
        try {
            s.connect(InetSocketAddress(host, port), 5000)
            s.tcpNoDelay = true
            s.soTimeout = 15000
            val out = s.getOutputStream()
            out.write("GET / HTTP/1.0\r\nHost: $host\r\n\r\n".toByteArray())
            out.flush()
            val inp = BufferedInputStream(s.getInputStream(), 64 * 1024)

            // هدر پاسخ
            while (true) {
                val l = readLine(inp) ?: throw EOFException()
                if (l.isEmpty()) break
            }
            onState(State.CONNECTED)

            while (running) {
                val line = readLine(inp) ?: break
                if (!line.startsWith("--")) continue
                var len = -1
                while (true) {
                    val h = readLine(inp) ?: throw EOFException()
                    if (h.isEmpty()) break
                    if (h.startsWith("Content-Length:", ignoreCase = true)) {
                        len = h.substring(15).trim().toIntOrNull() ?: -1
                    }
                }
                if (len <= 0 || len > 10_000_000) continue
                val buf = ByteArray(len)
                var off = 0
                while (off < len) {
                    val n = inp.read(buf, off, len - off)
                    if (n < 0) throw EOFException()
                    off += n
                }
                val bmp = BitmapFactory.decodeByteArray(buf, 0, len)
                if (bmp != null) onFrame(bmp)
            }
            if (running) onState(State.FAILED)
        } catch (e: Exception) {
            if (running) onState(State.FAILED)
        } finally {
            try { s.close() } catch (_: Exception) {}
        }
    }

    private fun readLine(inp: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val b = inp.read()
            if (b == -1) return if (sb.isEmpty()) null else sb.toString().trimEnd('\r')
            if (b == '\n'.code) return sb.toString().trimEnd('\r')
            if (sb.length < 4096) sb.append(b.toChar())
        }
    }
}
