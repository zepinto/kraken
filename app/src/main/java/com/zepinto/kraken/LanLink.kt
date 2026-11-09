package com.zepinto.kraken

import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** One JSON message per line over a TCP socket. A ping every few seconds notices a dead connection. */
private const val PING_SECONDS = 5L
private const val READ_TIMEOUT_MS = 20_000
private const val HELLO_TIMEOUT_MS = 5_000
private const val CONNECT_TIMEOUT_MS = 5_000
private const val MAX_LINE = 64 * 1024
private const val MAX_PIN_FAILURES = 5
private const val LOCKOUT_MS = 60_000L

/** readLine() with a cap, so a peer cannot make the phone buffer an endless line. */
private fun BufferedReader.readLimitedLine(): String? {
    val sb = StringBuilder()
    while (true) {
        val c = read()
        if (c < 0) return if (sb.isEmpty()) null else sb.toString()
        if (c == '\n'.code) return sb.toString()
        if (sb.length >= MAX_LINE) throw IOException("line too long")
        sb.append(c.toChar())
    }
}

/** The IPv4 addresses of this phone on its networks, for showing to the person typing them on the other phone. */
fun localAddresses(): List<String> = try {
    NetworkInterface.getNetworkInterfaces().toList()
        .filter { it.isUp && !it.isLoopback }
        .flatMap { it.inetAddresses.toList() }
        .filterIsInstance<Inet4Address>()
        .filter { !it.isLoopbackAddress }
        .mapNotNull { it.hostAddress }
} catch (_: Exception) {
    emptyList()
}

/** Splits "host:port" (the port is optional) into its parts, or null when it is not an address. */
fun parseAddress(text: String): Pair<String, Int>? {
    val t = text.trim()
    if (t.isEmpty()) return null
    val i = t.lastIndexOf(':')
    if (i < 0) return t to LanHostLink.DEFAULT_PORT
    val host = t.substring(0, i).trim()
    val port = t.substring(i + 1).trim().toIntOrNull() ?: return null
    return if (host.isEmpty() || port !in 1..65535) null else host to port
}

class LanHostLink(private val pin: String) : HostLink {
    @Volatile var port: Int = 0
        private set

    private class Conn(val socket: Socket) {
        private val out = socket.getOutputStream()

        @Synchronized
        fun write(text: String) {
            out.write((text + "\n").toByteArray(Charsets.UTF_8))
            out.flush()
        }
    }

    private val conns = ConcurrentHashMap<String, Conn>()

    /** Wrong PINs per address: five misses lock that address out for a minute, so a 6-digit PIN cannot be brute-forced. */
    private val failures = ConcurrentHashMap<String, Pair<Int, Long>>()
    private val writer = Executors.newSingleThreadExecutor { r -> Thread(r, "lan-host-write").apply { isDaemon = true } }
    private val scheduler = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "lan-host-ping").apply { isDaemon = true } }
    @Volatile private var server: ServerSocket? = null
    @Volatile private var stopped = false

    override fun start(listener: HostListener) {
        val s = bind()
        if (s == null) {
            listener.onError("Wi-Fi: no free port")
            return
        }
        server = s
        port = s.localPort
        thread(name = "lan-accept", isDaemon = true) {
            while (!stopped) {
                val socket = try {
                    s.accept()
                } catch (_: IOException) {
                    break
                }
                thread(name = "lan-peer", isDaemon = true) { serve(socket, listener) }
            }
        }
        scheduler.scheduleWithFixedDelay({ send(Protocol.ping()) }, PING_SECONDS, PING_SECONDS, TimeUnit.SECONDS)
    }

    private fun bind(): ServerSocket? {
        for (p in DEFAULT_PORT until DEFAULT_PORT + 10) {
            try {
                return ServerSocket().apply {
                    reuseAddress = true
                    bind(InetSocketAddress(p))
                }
            } catch (_: IOException) {
                // port taken, try the next one
            }
        }
        return null
    }

    private fun isLockedOut(ip: String): Boolean {
        val (count, since) = failures[ip] ?: return false
        if (System.currentTimeMillis() - since > LOCKOUT_MS) {
            failures.remove(ip)
            return false
        }
        return count >= MAX_PIN_FAILURES
    }

    private fun recordFailure(ip: String) {
        failures.merge(ip, 1 to System.currentTimeMillis()) { old, _ -> (old.first + 1) to old.second }
    }

    private fun serve(socket: Socket, listener: HostListener) {
        val id = "lan-${socket.remoteSocketAddress}"
        val ip = (socket.remoteSocketAddress as? InetSocketAddress)?.address?.hostAddress ?: "?"
        var registered = false
        try {
            socket.tcpNoDelay = true
            socket.soTimeout = HELLO_TIMEOUT_MS
            val conn = Conn(socket)
            if (isLockedOut(ip)) {
                runCatching { conn.write(Protocol.denied("locked")) }
                return
            }
            val input = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
            val hello = Protocol.parse(input.readLimitedLine() ?: return) as? Message.Hello
            if (hello == null || hello.pin != pin) {
                recordFailure(ip)
                runCatching { conn.write(Protocol.denied("pin")) }
                return
            }
            failures.remove(ip)
            socket.soTimeout = READ_TIMEOUT_MS
            // The welcome goes out before the connection is registered for broadcasts, so it is always the first thing the phone reads.
            conn.write(Protocol.welcome())
            conns[id] = conn
            registered = true
            listener.onConnected(id, hello.name.ifBlank { "Wi-Fi" })
            while (true) {
                val line = input.readLimitedLine() ?: break
                listener.onMessage(id, line)
            }
        } catch (_: IOException) {
            // connection lost: handled below
        } finally {
            if (registered) {
                conns.remove(id)
                listener.onDisconnected(id)
            }
            runCatching { socket.close() }
        }
    }

    override fun send(text: String) {
        if (stopped || conns.isEmpty()) return
        writer.execute {
            for ((id, conn) in conns) {
                try {
                    conn.write(text)
                } catch (_: IOException) {
                    runCatching { conn.socket.close() } // makes the reader thread finish and report the loss
                }
            }
        }
    }

    override fun stop() {
        stopped = true
        scheduler.shutdownNow()
        writer.shutdown()
        runCatching { server?.close() }
        conns.values.forEach { runCatching { it.socket.close() } }
        conns.clear()
    }

    companion object {
        const val DEFAULT_PORT = 8765
    }
}

class LanClientLink(private val deviceName: String) : ClientLink {
    private var listener: ClientListener? = null
    @Volatile private var socket: Socket? = null
    @Volatile private var out: java.io.OutputStream? = null
    @Volatile private var stopped = false
    private val writer = Executors.newSingleThreadExecutor { r -> Thread(r, "lan-client-write").apply { isDaemon = true } }
    private val scheduler = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "lan-client-ping").apply { isDaemon = true } }

    override fun start(listener: ClientListener) {
        this.listener = listener
        scheduler.scheduleWithFixedDelay({ send(Protocol.ping()) }, PING_SECONDS, PING_SECONDS, TimeUnit.SECONDS)
    }

    override fun connect(target: String, pin: String?) {
        val l = listener ?: return
        val address = parseAddress(target)
        if (address == null) {
            l.onError(LinkError.OTHER, "address")
            return
        }
        thread(name = "lan-connect", isDaemon = true) {
            val s = Socket()
            var connected = false
            try {
                s.connect(InetSocketAddress(address.first, address.second), CONNECT_TIMEOUT_MS)
                s.tcpNoDelay = true
                s.soTimeout = READ_TIMEOUT_MS
                val input = BufferedReader(InputStreamReader(s.getInputStream(), Charsets.UTF_8))
                val stream = s.getOutputStream()
                stream.write((Protocol.hello(pin, deviceName) + "\n").toByteArray(Charsets.UTF_8))
                stream.flush()
                val reply = Protocol.parse(input.readLimitedLine() ?: throw IOException("closed"))
                if (reply !is Message.Welcome) {
                    l.onError(LinkError.DENIED, (reply as? Message.Denied)?.reason ?: "denied")
                    return@thread
                }
                if (stopped) return@thread
                socket = s
                out = stream
                connected = true
                l.onConnected()
                while (!stopped) {
                    val line = input.readLimitedLine() ?: break
                    l.onMessage(line)
                }
            } catch (e: IOException) {
                if (!connected) {
                    if (!stopped) l.onError(LinkError.UNREACHABLE, e.message ?: "unreachable")
                    return@thread
                }
            } finally {
                runCatching { s.close() }
                if (socket === s) {
                    socket = null
                    out = null
                }
            }
            if (connected && !stopped) l.onDisconnected("lost")
        }
    }

    override fun send(text: String) {
        if (stopped) return
        writer.execute {
            val stream = out ?: return@execute
            try {
                synchronized(stream) {
                    stream.write((text + "\n").toByteArray(Charsets.UTF_8))
                    stream.flush()
                }
            } catch (_: IOException) {
                runCatching { socket?.close() }
            }
        }
    }

    override fun stop() {
        stopped = true
        scheduler.shutdownNow()
        writer.shutdown()
        runCatching { socket?.close() }
    }
}
