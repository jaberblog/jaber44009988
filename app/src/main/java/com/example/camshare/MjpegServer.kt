package com.example.camshare

import java.io.BufferedOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * یک سرور ساده‌ی HTTP که تصاویر JPEG را به‌صورت MJPEG (multipart/x-mixed-replace) پخش می‌کند.
 * هم اپ گیرنده و هم هر مرورگری می‌تواند به آن وصل شود.
 */
class MjpegServer(private val port: Int) {

    @Volatile private var running = false
    private var serverSocket: ServerSocket? = null
    private val sockets = Collections.synchronizedSet(mutableSetOf<Socket>())

    private val lock = Object()
    private var latest: ByteArray? = null
    private var frameId = 0L

    private val clients = AtomicInteger(0)
    fun clientCount(): Int = clients.get()

    var onClientsChanged: ((Int) -> Unit)? = null
    var onError: ((Exception) -> Unit)? = null

    fun start() {
        running = true
        thread(isDaemon = true, name = "mjpeg-accept") {
            try {
                val ss = ServerSocket()
                ss.reuseAddress = true
                ss.bind(InetSocketAddress(port))
                serverSocket = ss
                while (running) {
                    val s = ss.accept()
                    thread(isDaemon = true, name = "mjpeg-client") { handle(s) }
                }
            } catch (e: Exception) {
                if (running) onError?.invoke(e)
            }
        }
    }

    fun publish(jpeg: ByteArray) {
        synchronized(lock) {
            latest = jpeg
            frameId++
            lock.notifyAll()
        }
    }

    fun stop() {
        running = false
        try { serverSocket?.close() } catch (_: IOException) {}
        synchronized(sockets) { sockets.forEach { try { it.close() } catch (_: IOException) {} } }
        synchronized(lock) { lock.notifyAll() }
    }

    private fun handle(s: Socket) {
        sockets.add(s)
        onClientsChanged?.invoke(clients.incrementAndGet())
        try {
            s.tcpNoDelay = true
            s.soTimeout = 5000
            // خواندن هدر درخواست تا خط خالی
            val inp = s.getInputStream()
            val sb = StringBuilder()
            while (true) {
                val b = inp.read()
                if (b == -1) return
                sb.append(b.toChar())
                if (sb.endsWith("\r\n\r\n") || sb.endsWith("\n\n") || sb.length > 8192) break
            }
            s.soTimeout = 0

            val out = BufferedOutputStream(s.getOutputStream(), 64 * 1024)
            out.write(
                ("HTTP/1.0 200 OK\r\n" +
                    "Content-Type: multipart/x-mixed-replace; boundary=frame\r\n" +
                    "Cache-Control: no-cache, no-store\r\n" +
                    "Pragma: no-cache\r\n\r\n").toByteArray()
            )
            out.flush()

            var last = 0L
            while (running && !s.isClosed) {
                var data: ByteArray? = null
                synchronized(lock) {
                    if (frameId == last) lock.wait(1000)
                    if (frameId != last) {
                        data = latest
                        last = frameId
                    }
                }
                val d = data ?: continue
                out.write("--frame\r\nContent-Type: image/jpeg\r\nContent-Length: ${d.size}\r\n\r\n".toByteArray())
                out.write(d)
                out.write("\r\n".toByteArray())
                out.flush()
            }
        } catch (_: Exception) {
            // کلاینت قطع شد
        } finally {
            sockets.remove(s)
            try { s.close() } catch (_: IOException) {}
            onClientsChanged?.invoke(clients.decrementAndGet())
        }
    }
}
