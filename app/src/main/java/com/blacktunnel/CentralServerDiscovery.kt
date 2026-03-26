package com.blacktunnel

import java.io.ByteArrayOutputStream
import java.net.Inet6Address
import java.net.InetSocketAddress
import java.net.Socket

data class CentralServer(
    val id: String,
    val host: String,
    val region: String,
    val status: String
)

object CentralServerDiscovery {
    private const val CENTRAL_HOST = "central.brawlpass.com.ar"
    private const val PROXY_V6 = "2606:4700::6812:16b7"
    private const val EDGE_HOST = "emailmarketing.personal.com.ar"

    fun fetchServers(logger: (String) -> Unit): List<CentralServer> {
        val aggregate = StringBuilder()

        runCatching {
            val socket = Socket()
            socket.soTimeout = 5000
            socket.connect(InetSocketAddress(CENTRAL_HOST, 80), 5000)
            val req = "BT-SERVERS / HTTP/1.1\r\nHost: $CENTRAL_HOST\r\nUpgrade: websocket\r\n\r\n"
            socket.getOutputStream().write(req.toByteArray())
            Thread.sleep(200)
            aggregate.append(String(socket.getInputStream().readBytes(4096)))
            socket.close()
        }.onFailure { logger("Central IPv4 directo sin respuesta útil: ${it.message}") }

        runCatching {
            val socket = Socket()
            socket.soTimeout = 5000
            socket.connect(InetSocketAddress(Inet6Address.getByName(PROXY_V6), 80), 5000)
            val p1 = "GET / HTTP/1.1\r\nHost: $EDGE_HOST\r\n\r\n"
            val p2 = "BT-SERVERS / HTTP/1.1\r\nHost: $CENTRAL_HOST\r\nUpgrade: websocket\r\n\r\n"
            socket.getOutputStream().write(p1.toByteArray())
            socket.getOutputStream().write(p2.toByteArray())

            val buf = ByteArrayOutputStream()
            val deadline = System.currentTimeMillis() + 5000
            while (System.currentTimeMillis() < deadline) {
                try {
                    val tmp = ByteArray(4096)
                    val n = socket.getInputStream().read(tmp)
                    if (n <= 0) break
                    buf.write(tmp, 0, n)
                    if (buf.toString().contains("X-Servers:", ignoreCase = true)) break
                } catch (_: Exception) {
                    break
                }
            }
            aggregate.append(buf.toString())
            socket.close()
        }.onFailure { logger("Central vía Cloudflare IPv6 falló: ${it.message}") }

        val servers = parseServers(aggregate.toString())
        logger("Central servers obtenidos=${servers.joinToString { "#${it.id}:${it.region}" }}")
        return servers
    }

    private fun parseServers(raw: String): List<CentralServer> {
        val headerValue = raw.lines()
            .firstOrNull { it.trim().startsWith("X-Servers:", ignoreCase = true) }
            ?.substringAfter(":")
            ?.trim()
            ?: return emptyList()

        return headerValue.split(",")
            .map { it.trim() }
            .mapNotNull { entry ->
                val host = entry.substringBefore("(").trim()
                val id = host.substringBefore(".").trim()
                val inside = entry.substringAfter("(", "").substringBefore(")")
                val parts = inside.split("|").map { it.trim() }
                val region = parts.getOrNull(0).orEmpty()
                val status = parts.getOrNull(1).orEmpty()
                val cloudfrontRef = parts.getOrNull(2).orEmpty()
                if (host.isBlank() || id.toIntOrNull() == null) return@mapNotNull null
                if (cloudfrontRef.contains("cloudfront.net", ignoreCase = true)) return@mapNotNull null
                CentralServer(id = id, host = host, region = region.ifBlank { "N/A" }, status = status.ifBlank { "unknown" })
            }
            .distinctBy { it.host }
    }

    private fun java.io.InputStream.readBytes(max: Int): ByteArray {
        val buf = ByteArray(max)
        val n = read(buf)
        return if (n > 0) buf.copyOf(n) else ByteArray(0)
    }
}
