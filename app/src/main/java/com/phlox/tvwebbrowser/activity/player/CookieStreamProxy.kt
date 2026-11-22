package com.phlox.tvwebbrowser.activity.player

import com.phlox.tvwebbrowser.webengine.BrowserStreamRequest
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import java.net.CookieHandler
import java.net.HttpCookie
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** A private, bounded streaming adapter: cookies never enter IJK's global header string. */
internal class CookieStreamProxy(private val request: BrowserStreamRequest) : Closeable {
    private class Target(val uri: URI, val playlist: Boolean)
    private class StoredCookie(val host: String, val cookie: BrowserStreamRequest.Cookie)
    private val initial = BrowserStreamRequest.httpUri(request.url)
    private val token = UUID.randomUUID().toString().replace("-", "")
    private val server = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
    private val clients = ConcurrentHashMap.newKeySet<Socket>()
    private val connections = ConcurrentHashMap.newKeySet<HttpURLConnection>()
    private val targets = java.util.LinkedHashMap<String, Target>(32, 0.75f, true)
    private val routes = linkedMapOf<String, String>()
    private var targetChars = 0
    private val cookies = request.cookies.map { StoredCookie(initial.host, it) }.toMutableList()
    private var nextId = 0L
    @Volatile private var closed = false
    private val workers = ThreadPoolExecutor(3, 3, 30, TimeUnit.SECONDS,
        ArrayBlockingQueue(8), { runnable -> Thread(runnable, "MomStreamHTTP").apply { isDaemon = true } })
    val url: String = route(initial, true)

    init {
        Thread({
            while (!closed) {
                val socket = try { server.accept() } catch (_: IOException) { break }
                if (closed) { socket.close(); break }
                clients.add(socket)
                try { workers.execute { serve(socket) } }
                catch (_: RuntimeException) { clients.remove(socket); socket.close() }
            }
        }, "MomStreamAccept").apply { isDaemon = true; start() }
    }

    @Synchronized private fun route(uri: URI, playlist: Boolean): String {
        if (closed) throw IOException("Stream closed")
        BrowserStreamRequest.httpUri(uri.toASCIIString())
        if (initial.scheme == "https" && uri.scheme != "https") throw IOException("Insecure stream redirect")
        val key = uri.toASCIIString()
        val existing = routes[key]
        if (existing != null) {
            targets[existing] // Keep recently referenced routes in the bounded cache.
            return "http://127.0.0.1:${server.localPort}$existing"
        }
        val suffix = if (playlist || uri.path.endsWith(".m3u8", true)) "m3u8" else
            uri.path.substringAfterLast('.', "ts").lowercase(Locale.ROOT)
                .takeIf { it in setOf("ts", "m4s", "mp4", "aac", "mp3", "key", "webm") } ?: "ts"
        val path = "/$token/${++nextId}.$suffix"
        routes[key] = path
        targets[path] = Target(uri, suffix == "m3u8")
        targetChars += key.length
        while (targets.size > 512 || targetChars > 512 * 1024) {
            val oldest = targets.entries.first { it.value.uri != initial }.key
            targets.remove(oldest)?.let {
                routes.remove(it.uri.toASCIIString())
                targetChars -= it.uri.toASCIIString().length
            }
        }
        return "http://127.0.0.1:${server.localPort}$path"
    }

    private fun readLine(input: BufferedInputStream): String {
        val result = ByteArrayOutputStream()
        while (result.size() <= 8192) {
            val byte = input.read()
            if (byte < 0) throw IOException("Incomplete local request")
            if (byte == 10) return result.toString("ISO-8859-1").removeSuffix("\r")
            result.write(byte)
        }
        throw IOException("Local request too long")
    }

    private fun serve(socket: Socket) {
        try {
            socket.soTimeout = 30_000
            val input = BufferedInputStream(socket.getInputStream())
            val line = readLine(input).split(' ')
            if (line.size != 3 || line[0] != "GET") return
            val target = synchronized(this) { targets[line[1]] } ?: return
            var range: String? = null
            var bytes = 0
            while (true) {
                val header = readLine(input)
                if (header.isEmpty()) break
                bytes += header.length
                if (bytes > 16_384) throw IOException("Local headers too long")
                if (header.startsWith("Range:", true)) {
                    val value = header.substringAfter(':').trim()
                    if (value.length <= 128 && value.matches(Regex("bytes=[0-9]*-[0-9]*"))) range = value
                }
            }
            fetch(socket, target, range)
        } catch (_: Exception) {
            // Close the connection so IJK falls back. Never append an error to media bytes,
            // and never log credentials, upstream URLs or raw exception messages.
        } finally {
            clients.remove(socket)
            runCatching { socket.close() }
        }
    }

    private fun requestHeaders(uri: URI): Map<String, String> {
        val result = request.headers.toMutableMap()
        if (!BrowserStreamRequest.sameOrigin(initial, uri)) {
            result.remove("Authorization")
            result["Referer"]?.let { referer ->
                result["Referer"] = runCatching {
                    val parsed = BrowserStreamRequest.httpUri(referer)
                    "${parsed.scheme}://${parsed.rawAuthority}/"
                }.getOrDefault("")
            }
        }
        result["Accept-Encoding"] = "identity"
        val now = System.currentTimeMillis()
        synchronized(cookies) {
            cookies.removeAll { it.cookie.expiresAt <= now }
            val applicable = cookies.filter {
                it.host.equals(uri.host, true) && (!it.cookie.secure || uri.scheme == "https") &&
                    BrowserStreamRequest.pathMatches(uri.rawPath.ifEmpty { "/" }, it.cookie.path)
            }.sortedByDescending { it.cookie.path.length }
            if (applicable.isNotEmpty()) result["Cookie"] = applicable.joinToString("; ") { "${it.cookie.name}=${it.cookie.value}" }
        }
        return result
    }

    private fun rememberCookies(connection: HttpURLConnection, uri: URI) {
        for ((name, values) in connection.headerFields) {
            if (!name.equals("Set-Cookie", true)) continue
            for (value in values) {
                if (value.length > 16_384) continue
                val parsed = runCatching { HttpCookie.parse(value) }.getOrDefault(emptyList())
                for (cookie in parsed) {
                    val domain = cookie.domain?.removePrefix(".")
                    if (domain != null && !uri.host.equals(domain, true) &&
                        !uri.host.endsWith(".$domain", true)) continue
                    if (!BrowserStreamRequest.safeHeader(cookie.value) || cookie.value.any { it == ';' || it.code > 126 || it < ' ' }) continue
                    val path = cookie.path?.takeIf { it.startsWith('/') } ?: uri.rawPath
                        .substringBeforeLast('/', "").let { if (it.isEmpty()) "/" else it }
                    if (path.length > 4096) continue
                    val expiresAt = when {
                        cookie.maxAge < 0 -> Long.MAX_VALUE
                        cookie.maxAge > (Long.MAX_VALUE - System.currentTimeMillis()) / 1000 -> Long.MAX_VALUE
                        else -> System.currentTimeMillis() + cookie.maxAge * 1000
                    }
                    val item = BrowserStreamRequest.Cookie(cookie.name, cookie.value, path, cookie.secure, expiresAt)
                    synchronized(cookies) {
                        cookies.removeAll { it.host.equals(uri.host, true) && it.cookie.name == item.name && it.cookie.path == item.path }
                        val size = cookies.sumOf { it.cookie.name.length + it.cookie.value.length + 2 }
                        if (expiresAt > System.currentTimeMillis() && cookies.size < 128 &&
                            size + item.name.length + item.value.length + 2 <= 16_384) cookies.add(StoredCookie(uri.host, item))
                    }
                }
            }
        }
    }

    private fun fetch(socket: Socket, target: Target, range: String?) {
        var uri = target.uri
        for (redirect in 0..5) {
            if (closed) throw IOException("Stream closed")
            // Gecko has its own store. Do not mix it with a process-wide Java cookie handler.
            if (CookieHandler.getDefault() != null) throw IOException("Unexpected Java cookie handler")
            BrowserStreamRequest.httpUri(uri.toASCIIString())
            if (initial.scheme == "https" && uri.scheme != "https") throw IOException("Insecure stream redirect")
            val connection = uri.toURL().openConnection() as HttpURLConnection
            connections.add(connection)
            try {
                connection.instanceFollowRedirects = false
                connection.connectTimeout = 15_000
                connection.readTimeout = 30_000
                connection.useCaches = false
                for ((name, value) in requestHeaders(uri)) connection.setRequestProperty(name, value)
                if (!target.playlist && range != null) connection.setRequestProperty("Range", range)
                val code = connection.responseCode
                rememberCookies(connection, uri)
                if (code in listOf(301, 302, 303, 307, 308)) {
                    val location = connection.getHeaderField("Location") ?: throw IOException("Missing redirect")
                    uri = resolve(uri, location)
                    continue
                }
                val output = socket.getOutputStream()
                if (code !in 200..299) {
                    output.write("HTTP/1.1 $code Upstream response\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                    return
                }
                val encoding = connection.getHeaderField("Content-Encoding")
                if (!encoding.isNullOrEmpty() && !encoding.equals("identity", true)) throw IOException("Unexpected stream compression")
                connection.inputStream.buffered().use { body ->
                    body.mark(16)
                    val prefix = ByteArray(10)
                    val count = body.read(prefix)
                    body.reset()
                    val playlist = target.playlist || String(prefix, 0, count.coerceAtLeast(0), StandardCharsets.UTF_8)
                        .removePrefix("\uFEFF").startsWith("#EXTM3U")
                    if (playlist) {
                        val bytes = ByteArrayOutputStream()
                        val buffer = ByteArray(8192)
                        while (true) {
                            val read = body.read(buffer)
                            if (read < 0) break
                            if (bytes.size() + read > 1024 * 1024) throw IOException("Playlist too large")
                            bytes.write(buffer, 0, read)
                        }
                        val text = bytes.toString("UTF-8").removePrefix("\uFEFF")
                        if (!text.startsWith("#EXTM3U")) throw IOException("Invalid playlist")
                        val rewritten = rewrite(text, uri).toByteArray(StandardCharsets.UTF_8)
                        output.write("HTTP/1.1 200 OK\r\nContent-Type: application/vnd.apple.mpegurl\r\nContent-Length: ${rewritten.size}\r\nConnection: close\r\n\r\n".toByteArray())
                        output.write(rewritten)
                    } else {
                        val header = StringBuilder("HTTP/1.1 $code OK\r\nConnection: close\r\n")
                        for (name in listOf("Content-Type", "Content-Length", "Content-Range", "Accept-Ranges")) {
                            connection.getHeaderField(name)?.takeIf { BrowserStreamRequest.safeHeader(it) }
                                ?.let { header.append("$name: $it\r\n") }
                        }
                        output.write(header.append("\r\n").toString().toByteArray(StandardCharsets.ISO_8859_1))
                        body.copyTo(output, 16 * 1024)
                    }
                    output.flush()
                }
                return
            } finally {
                connections.remove(connection)
                connection.disconnect()
            }
        }
        throw IOException("Too many stream redirects")
    }

    private fun rewrite(text: String, base: URI): String {
        var nextPlaylist = false
        return text.lineSequence().joinToString("\n") { line ->
            val trimmed = line.trim()
            when {
                trimmed.isEmpty() -> line
                trimmed.startsWith("#") -> {
                    if (trimmed.startsWith("#EXT-X-STREAM-INF:")) nextPlaylist = true
                    val playlist = trimmed.startsWith("#EXT-X-MEDIA:") || trimmed.startsWith("#EXT-X-I-FRAME-STREAM-INF:")
                    Regex("URI=\"([^\"]+)\"").replace(line) { match -> "URI=\"${route(resolve(base, match.groupValues[1]), playlist)}\"" }
                }
                else -> {
                    val value = route(resolve(base, trimmed), nextPlaylist)
                    nextPlaylist = false
                    value
                }
            }
        }
    }

    private fun resolve(base: URI, reference: String): URI {
        // URI.resolve drops the filename for query-only references on Android/JDK.
        val resolved = if (reference.startsWith('?')) {
            URI(base.toASCIIString().substringBefore('?') + reference)
        } else base.resolve(reference)
        return BrowserStreamRequest.httpUri(resolved.toASCIIString().substringBefore('#'))
    }

    override fun close() {
        closed = true
        runCatching { server.close() }
        for (socket in clients) runCatching { socket.close() }
        for (connection in connections) runCatching { connection.disconnect() }
        workers.shutdownNow()
        synchronized(this) { targets.clear(); routes.clear(); targetChars = 0 }
        synchronized(cookies) { cookies.clear() }
    }
}
