package dev.jigen.providers.innertube

import java.net.Proxy
import java.util.concurrent.TimeUnit
import okhttp3.ConnectionPool
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
            .connectionPool(ConnectionPool(32, 5, TimeUnit.MINUTES))
            .protocols(listOf(Protocol.HTTP_2, Protocol.HTTP_1_1))
            .retryOnConnectionFailure(true)
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }
}
