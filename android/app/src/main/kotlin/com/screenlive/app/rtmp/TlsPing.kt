package com.screenlive.app.rtmp

import javax.net.ssl.SSLSocketFactory
import java.net.InetSocketAddress
import javax.net.ssl.SSLSocket

object TlsPing {
    fun connect(host: String, port: Int = 443, timeout: Int = 5000): Boolean {
        val fac = SSLSocketFactory.getDefault() as SSLSocketFactory
        val s = (fac.createSocket() as SSLSocket)
        s.soTimeout = timeout
        s.connect(InetSocketAddress(host, port), timeout)
        s.startHandshake() // will throw if TLS blocked/DNS fail
        s.close()
        return true
    }
}