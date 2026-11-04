package com.spotifyautoplaylist.auth

import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class LoopbackCallbackServerTest {
    @Test fun `listener binds IPv4 loopback only`() {
        LoopbackCallbackServer.bind(0).use {
            assertEquals("127.0.0.1", it.boundAddress.hostAddress)
            assertFalse(it.boundAddress.isAnyLocalAddress)
        }
    }

    @Test fun `port collision has sanitized actionable error`() {
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { occupied ->
            val error = assertThrows(ProbeException::class.java) { LoopbackCallbackServer.bind(occupied.localPort) }
            assertEquals(ProbeFailure.PORT_IN_USE, error.reason)
        }
    }

    @Test fun `invalid requests are ignored then valid callback completes and closes port`() {
        val server = LoopbackCallbackServer.bind(0)
        val port = server.localPort
        val executor = Executors.newSingleThreadExecutor()
        try {
            val session = PkceSession.create(DUMMY_CLIENT_ID)
            val pending = executor.submit<TokenExchangeInput> { server.await(session) }
            assertTrue(request(port, "/favicon.ico").startsWith("HTTP/1.1 404"))
            assertTrue(request(port, session.callback(), host = "evil.example").startsWith("HTTP/1.1 400"))
            assertTrue(request(port, session.callback(), method = "POST").startsWith("HTTP/1.1 405"))
            assertTrue(request(port, "/callback?code=dummy&state=bad").startsWith("HTTP/1.1 400"))
            val response = request(port, session.callback("secret-dummy-code"))
            assertTrue(response.startsWith("HTTP/1.1 200"))
            assertTrue(response.contains("Cache-Control: no-store"))
            assertFalse(response.contains("secret-dummy-code"))
            assertFalse(response.contains(session.parameters().getValue("state")))
            assertEquals("secret-dummy-code", pending.get(3, TimeUnit.SECONDS).code)
            assertThrows(java.io.IOException::class.java) { Socket("127.0.0.1", port).close() }
        } finally {
            server.close()
            executor.shutdownNow()
        }
    }

    @Test fun `cancel immediately releases listener`() {
        val server = LoopbackCallbackServer.bind(0)
        val executor = Executors.newSingleThreadExecutor()
        try {
            val future = executor.submit<TokenExchangeInput> { server.await(PkceSession.create(DUMMY_CLIENT_ID)) }
            server.close()
            val error = assertThrows(ExecutionException::class.java) { future.get(3, TimeUnit.SECONDS) }
            assertEquals(ProbeFailure.CANCELLED, (error.cause as ProbeException).reason)
        } finally {
            server.close()
            executor.shutdownNow()
        }
    }

    @Test fun `no callback times out and closes listener`() {
        LoopbackCallbackServer.bind(0).use { server ->
            val error = assertThrows(ProbeException::class.java) {
                server.await(PkceSession.create(DUMMY_CLIENT_ID, timeoutMillis = 30))
            }
            assertEquals(ProbeFailure.CALLBACK_TIMEOUT, error.reason)
        }
    }

    @Test fun `bounded request parser rejects oversized or malformed request lines`() {
        for (line in listOf("A".repeat(8_194) + "\r\n", "GET / HTTP/1.1\n", "GET\u0000\r\n")) {
            assertThrows(IllegalArgumentException::class.java) {
                LoopbackCallbackServer.readLine(line.byteInputStream())
            }
        }
    }

    private fun request(port: Int, target: String, host: String = "127.0.0.1:$port", method: String = "GET"): String =
        Socket("127.0.0.1", port).use { socket ->
            socket.soTimeout = 3_000
            socket.getOutputStream().write("$method $target HTTP/1.1\r\nHost: $host\r\n\r\n".toByteArray())
            socket.getInputStream().bufferedReader().readText()
        }
}
