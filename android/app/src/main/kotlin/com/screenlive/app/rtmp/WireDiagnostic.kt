package com.screenlive.app.rtmp

import kotlinx.coroutines.*
import java.net.InetSocketAddress
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

object WireDiagnostic {
    fun run(streamKey: String) {
        CoroutineScope(Dispatchers.IO).launch {
            var client: MinimalRtmpsClient? = null
            try {
                PtlLog.i("WIRE: Anchor reached (post-FGS)")

                val host = "a.rtmps.youtube.com"
                val port = 443

                // 1) TLS probe (SNI is mandatory for RTMPS)
                PtlLog.i("WIRE: TLS probe → $host:$port")
                val ssl = SSLSocketFactory.getDefault().createSocket() as SSLSocket
                ssl.connect(InetSocketAddress(host, port), 6000)
                ssl.enabledProtocols = arrayOf("TLSv1.3", "TLSv1.2")
                ssl.enabledCipherSuites = ssl.supportedCipherSuites
                ssl.sslParameters = ssl.sslParameters.apply { serverNames = listOf(SNIHostName(host)) }
                ssl.startHandshake()
                ssl.close()
                PtlLog.i("WIRE: TLS OK with SNI")

                if (streamKey.isBlank()) {
                    PtlLog.e("WIRE: streamKey is BLANK → skip RTMPS")
                    return@launch
                }

                // 2) Call into RTMPS client
                PtlLog.i("WIRE: Creating MinimalRtmpsClient for key ***${streamKey.takeLast(4)}")
                client = MinimalRtmpsClient(
                    host = host,
                    port = port,
                    app = "live2",
                    streamKey = streamKey
                )
                
                PtlLog.i("WIRE: Calling connectBlocking(15000)...")
                client.connectBlocking(15000)

                if (client.published) {
                    PtlLog.i("WIRE: ✅ RTMPS publish started → ready to push AV frames")
                } else {
                    PtlLog.e("WIRE: ❌ publish NOT started (timeout without exception)")
                }
            } catch (t: Throwable) {
                PtlLog.e("WIRE: RTMPS failed", t)
            } finally {
                client?.let {
                    PtlLog.i("WIRE: Closing diagnostic RTMPS client")
                    it.closeQuiet()
                }
            }
        }
    }
}
