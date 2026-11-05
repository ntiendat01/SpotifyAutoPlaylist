package com.spotifyautoplaylist.auth

import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.net.BindException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException

interface CallbackListener : Closeable {
    fun await(session: PkceSession): TokenExchangeInput
}

/** One temporary listener. No request URLs or query strings are ever logged or reflected. */
class LoopbackCallbackServer private constructor(private val server: ServerSocket) : CallbackListener {
    private val lock = Any()
    private var closed = false
    private var activeSocket: Socket? = null

    override fun await(session: PkceSession): TokenExchangeInput {
        try {
            while (true) {
                val remaining = session.remainingMillis()
                if (remaining == 0L) throw ProbeException(ProbeFailure.CALLBACK_TIMEOUT)
                server.soTimeout = remaining.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                val socket = try { server.accept() } catch (_: SocketTimeoutException) {
                    throw ProbeException(ProbeFailure.CALLBACK_TIMEOUT)
                }
                synchronized(lock) {
                    if (closed) {
                        socket.close()
                        throw ProbeException(ProbeFailure.CANCELLED)
                    }
                    activeSocket = socket
                }
                val decision = socket.use {
                    socket.soTimeout = session.remainingMillis().coerceIn(1, 1_000).toInt()
                    val result = try { readRequest(socket, session) } catch (_: IOException) {
                        CallbackDecision.Rejected(400)
                    } catch (_: IllegalArgumentException) {
                        CallbackDecision.Rejected(400)
                    }
                    // The callback has already been consumed; a closed browser connection must
                    // not discard its valid code or cause the user to reuse that code.
                    try { respond(socket, result) } catch (_: IOException) { /* no private data */ }
                    result
                }
                synchronized(lock) { activeSocket = null }
                when (decision) {
                    is CallbackDecision.Accepted -> return decision.input
                    is CallbackDecision.Failed -> throw ProbeException(decision.reason)
                    is CallbackDecision.Rejected -> Unit // e.g. favicon, invalid state: keep waiting
                }
            }
        } catch (failure: IOException) {
            val wasClosed = synchronized(lock) { closed }
            throw ProbeException(if (wasClosed) ProbeFailure.CANCELLED else ProbeFailure.NETWORK)
        } finally {
            close()
        }
    }

    private fun readRequest(socket: Socket, session: PkceSession): CallbackDecision {
        val input = socket.getInputStream()
        fun nextLine() = readLine(input) {
            val remaining = session.remainingMillis()
            if (remaining == 0L) throw ProbeException(ProbeFailure.CALLBACK_TIMEOUT)
            socket.soTimeout = remaining.coerceIn(1, 1_000).toInt()
        }
        val request = nextLine().split(' ')
        if (request.size != 3 || request[2] != "HTTP/1.1") return CallbackDecision.Rejected(400)
        val headers = mutableMapOf<String, String>()
        var headerBytes = 0
        while (true) {
            val line = nextLine()
            headerBytes += line.length
            require(headerBytes <= 16_384 && headers.size < 64)
            if (line.isEmpty()) break
            val parts = line.split(':', limit = 2)
            require(parts.size == 2)
            val name = parts[0].lowercase(java.util.Locale.ROOT)
            require(headers.put(name, parts[1].trim()) == null)
        }
        // Enforce literal loopback Host too: reject DNS rebinding / absolute-form requests.
        if (headers["host"] != "127.0.0.1:${server.localPort}") return CallbackDecision.Rejected(400)
        if (headers.containsKey("transfer-encoding") ||
            headers["content-length"]?.let { it != "0" } == true
        ) return CallbackDecision.Rejected(400)
        return session.consumeCallback(request[0], request[1])
    }

    private fun respond(socket: Socket, decision: CallbackDecision) {
        val status = when (decision) {
            is CallbackDecision.Rejected -> decision.httpStatus
            is CallbackDecision.Failed -> 400
            is CallbackDecision.Accepted -> 200
        }
        val body = "<!doctype html><html lang=\"vi\"><meta charset=\"utf-8\">" +
            "<meta name=\"viewport\" content=\"width=device-width\"><title>replay.</title>" +
            "<p>Hãy quay lại ứng dụng replay. để xem kết quả.</p></html>"
        val bytes = body.toByteArray(Charsets.UTF_8)
        val header = "HTTP/1.1 $status Result\r\nContent-Type: text/html; charset=utf-8\r\n" +
            "Content-Length: ${bytes.size}\r\nCache-Control: no-store\r\n" +
            "Content-Security-Policy: default-src 'none'; frame-ancestors 'none'\r\n" +
            "Referrer-Policy: no-referrer\r\nX-Content-Type-Options: nosniff\r\nConnection: close\r\n\r\n"
        socket.getOutputStream().apply {
            write(header.toByteArray(Charsets.US_ASCII))
            write(bytes)
            flush()
        }
    }

    override fun close() {
        synchronized(lock) {
            closed = true
            try { activeSocket?.close() } catch (_: IOException) { /* best effort */ }
            try { server.close() } catch (_: IOException) { /* best effort */ }
        }
    }

    companion object {
        // Port 0 is reserved for isolated JVM socket tests. The Android caller always uses 8765.
        fun bind(port: Int = SpotifyAuth.PORT): LoopbackCallbackServer {
            val socket = ServerSocket()
            try {
                socket.reuseAddress = false
                socket.bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), port), 4)
                return LoopbackCallbackServer(socket)
            } catch (_: BindException) {
                socket.close()
                throw ProbeException(ProbeFailure.PORT_IN_USE)
            } catch (_: IOException) {
                socket.close()
                throw ProbeException(ProbeFailure.NETWORK)
            }
        }

        internal fun readLine(input: InputStream, beforeRead: () -> Unit = {}): String {
            val bytes = java.io.ByteArrayOutputStream()
            while (bytes.size() <= 8_192) {
                beforeRead()
                val char = input.read()
                require(char != -1)
                if (char == 10) {
                    val line = bytes.toByteArray()
                    require(line.isNotEmpty() && line.last() == 13.toByte())
                    return String(line, 0, line.size - 1, Charsets.US_ASCII)
                }
                require(char in 13..126)
                bytes.write(char)
            }
            throw IllegalArgumentException("Request line too long")
        }
    }

    internal val localPort: Int get() = server.localPort
    internal val boundAddress: InetAddress get() = server.inetAddress
}
