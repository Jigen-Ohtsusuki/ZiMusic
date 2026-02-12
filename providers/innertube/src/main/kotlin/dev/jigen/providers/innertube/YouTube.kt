package dev.jigen.providers.innertube

import java.io.File
import java.net.Proxy
import java.util.concurrent.TimeUnit
import okhttp3.Cache
import okhttp3.ConnectionPool
import okhttp3.ConnectionSpec
import okhttp3.OkHttpClient
import okhttp3.Protocol

object YouTube {
    private var _client: OkHttpClient? = null

    var proxy: Proxy? = null
        set(value) {
            field = value
            _client = buildClient()
        }

    val client: OkHttpClient
        get() {
            if (_client == null) {
                _client = buildClient()
            }
            return _client!!
        }

    private fun buildClient(): OkHttpClient {
        return OkHttpClient.Builder()
            .proxy(proxy)
            // 1. Connection Pool: Keeps sockets open. Essential for emulators and slow networks.
            .connectionPool(ConnectionPool(10, 5, TimeUnit.MINUTES))
            // 2. Protocols: HTTP/2 support for concurrent downloads
            .protocols(listOf(Protocol.HTTP_2, Protocol.HTTP_1_1))
            .connectionSpecs(listOf(ConnectionSpec.MODERN_TLS, ConnectionSpec.CLEARTEXT))
            .retryOnConnectionFailure(true)
            // 3. Timeouts: Generous timeouts for poor mobile connections
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            // 4. Cache: 50MB disk cache
            .cache(
                Cache(
                    directory = File(System.getProperty("java.io.tmpdir"), "http_cache"),
                    maxSize = 50L * 1024L * 1024L
                )
            )
            .build()
    }
}
