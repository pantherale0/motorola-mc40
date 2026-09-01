package dev.pantherale0.mc40.net

import okhttp3.ConnectionSpec
import okhttp3.OkHttpClient
import okhttp3.TlsVersion
import java.security.KeyStore
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

object HttpClients {
    val okHttp: OkHttpClient = build()

    private fun build(): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
        val modern = ConnectionSpec.Builder(ConnectionSpec.MODERN_TLS)
            .tlsVersions(TlsVersion.TLS_1_2)
            .build()
        builder.connectionSpecs(listOf(modern, ConnectionSpec.CLEARTEXT))
        enableTls12(builder)
        return builder.build()
    }

    private fun enableTls12(builder: OkHttpClient.Builder) {
        try {
            val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
            tmf.init(null as KeyStore?)
            val trustManager = tmf.trustManagers.first { it is X509TrustManager } as X509TrustManager
            val context = SSLContext.getInstance("TLSv1.2")
            context.init(null, arrayOf(trustManager), null)
            builder.sslSocketFactory(Tls12SocketFactory(context.socketFactory), trustManager)
        } catch (_: Exception) {
            // Device default stack is used if TLSv1.2 setup fails.
        }
    }
}

private class Tls12SocketFactory(
    private val delegate: SSLSocketFactory
) : SSLSocketFactory() {
    override fun getDefaultCipherSuites(): Array<String> = delegate.defaultCipherSuites
    override fun getSupportedCipherSuites(): Array<String> = delegate.supportedCipherSuites

    override fun createSocket(s: java.net.Socket?, host: String?, port: Int, autoClose: Boolean) =
        patch(delegate.createSocket(s, host, port, autoClose))

    override fun createSocket(host: String?, port: Int) = patch(delegate.createSocket(host, port))

    override fun createSocket(
        host: String?,
        port: Int,
        localHost: java.net.InetAddress?,
        localPort: Int
    ) = patch(delegate.createSocket(host, port, localHost, localPort))

    override fun createSocket(host: java.net.InetAddress?, port: Int) =
        patch(delegate.createSocket(host, port))

    override fun createSocket(
        address: java.net.InetAddress?,
        port: Int,
        localAddress: java.net.InetAddress?,
        localPort: Int
    ) = patch(delegate.createSocket(address, port, localAddress, localPort))

    private fun patch(socket: java.net.Socket): java.net.Socket {
        if (socket is SSLSocket) {
            socket.enabledProtocols = arrayOf("TLSv1.2")
        }
        return socket
    }
}
