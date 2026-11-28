package app.housemove.sync

import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

// Android and the JVM: java.net.HttpURLConnection, HTTPS with the platform's certificate checks (Tailscale Funnel's
// certificate is a Let's Encrypt one, which both trust). No CookieHandler is ever set in the app, so no cookie is sent or
// kept; caches are off and redirects are not followed. On a JVM whose system properties name a proxy, it is used as any
// connection would; Android phones use none.

internal actual fun httpCall(method: String, url: String, headers: Map<String, String>, body: ByteArray?, timeoutMs: Int): HttpAnswer {
    val c = URL(url).openConnection() as HttpURLConnection
    try {
        c.requestMethod = method
        c.connectTimeout = timeoutMs
        c.readTimeout = timeoutMs
        c.useCaches = false
        c.instanceFollowRedirects = false
        c.setRequestProperty("Accept", "application/json, application/octet-stream")
        for ((k, v) in headers) c.setRequestProperty(k, v)
        if (body != null) {
            c.doOutput = true
            c.setFixedLengthStreamingMode(body.size)
            c.outputStream.use { it.write(body) }
        }
        val status = c.responseCode
        val stream: InputStream? = if (status >= 400) c.errorStream else c.inputStream
        val bytes = stream?.use { readUpTo(it, Http.MAX_ANSWER) } ?: ByteArray(0)
        return HttpAnswer(status, bytes)
    } catch (e: java.io.IOException) {
        return HttpAnswer(0, problem = e::class.java.simpleName)
    } finally {
        c.disconnect()
    }
}

/** At most [max] bytes of [input]; an IOException when there are more. */
private fun readUpTo(input: InputStream, max: Int): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    val buf = ByteArray(16 * 1024)
    var total = 0
    while (true) {
        val n = input.read(buf)
        if (n < 0) break
        total += n
        if (total > max) throw java.io.IOException("answer too large")
        out.write(buf, 0, n)
    }
    return out.toByteArray()
}
