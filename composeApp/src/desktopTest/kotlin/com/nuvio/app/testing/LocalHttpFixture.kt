package com.nuvio.app.testing

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.Closeable
import java.net.InetSocketAddress
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertNotNull

/** Loopback-only server. Handlers may use bounded latches to control a response or disconnect. */
internal class LocalHttpFixture : Closeable {
    data class Request(val method: String, val path: String, val headers: Map<String, List<String>>, val body: String)

    private val executor = Executors.newCachedThreadPool()
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    private val requests = LinkedBlockingQueue<Request>()
    private val failure = AtomicReference<Throwable?>()
    val url: String get() = "http://127.0.0.1:${server.address.port}"

    fun route(path: String, handler: (HttpExchange, Request) -> Unit) {
        server.createContext(path) { exchange ->
            val request = Request(exchange.requestMethod, exchange.requestURI.toString(),
                exchange.requestHeaders.mapKeys { it.key.lowercase() }.mapValues { it.value.toList() },
                exchange.requestBody.bufferedReader().use { it.readText() })
            requests.put(request)
            try {
                handler(exchange, request)
            } catch (error: Throwable) {
                // A closed client is expected for probes, cancellation and rejected content.
                if (error !is java.io.IOException) failure.compareAndSet(null, error)
            } finally {
                exchange.close()
            }
        }
    }

    fun start() {
        server.executor = executor
        server.start()
    }

    fun nextRequest(): Request = assertNotNull(requests.poll(12, TimeUnit.SECONDS), "No localhost request arrived")
    fun drainRequests(): List<Request> = buildList { requests.drainTo(this) }
    fun assertHealthy() { failure.get()?.let { throw AssertionError("Local HTTP handler failed", it) } }

    override fun close() {
        server.stop(0)
        executor.shutdownNow()
        check(executor.awaitTermination(5, TimeUnit.SECONDS)) { "Local HTTP handlers did not stop" }
        assertHealthy()
    }
}

internal fun HttpExchange.respond(status: Int, body: ByteArray, contentType: String = "application/json") {
    responseHeaders.add("Content-Type", contentType)
    sendResponseHeaders(status, body.size.toLong())
    responseBody.write(body)
}
