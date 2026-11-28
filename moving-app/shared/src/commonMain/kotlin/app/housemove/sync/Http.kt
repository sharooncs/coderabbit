package app.housemove.sync

import kotlin.concurrent.Volatile

/*
 * HTTPS as the phones use it to reach the move's mailbox (design/encryption.md, "Mailbox as built"): one request, one
 * answer, with time-outs, no cookies, no cache, no redirects followed, and only to https addresses. What travels is
 * sealed end to end anyway ([Envelope]); HTTPS keeps the metadata (which mailbox, the read secret) between the phone and
 * the laptop's Tailscale Funnel, whose certificate the platform checks as for any web site.
 *
 * Android and the JVM: java.net.HttpURLConnection (Http.jvm.kt). The iPhone: NSURLSession with an ephemeral
 * configuration (Http.ios.kt).
 */

/** A request to the mailbox: [method], [url], [headers] and the [body], if any. */
internal class HttpRequest(val method: String, val url: String, val headers: Map<String, String> = emptyMap(), val body: ByteArray? = null)

/** What came back: the HTTP [status], or 0 when nothing did (no network, a time-out, a refused address), with [problem] then. */
internal class HttpAnswer(val status: Int, val body: ByteArray = ByteArray(0), val problem: String = "")

/** How a phone talks to the mailbox: the platform's HTTPS ([Http.platform]), or in tests a stand-in in the same process. */
internal fun interface HttpTransport {
    fun call(r: HttpRequest): HttpAnswer
}

internal object Http {
    /** How long a request may take to connect, and then between bytes. */
    const val TIMEOUT_MS = 20_000

    /** The most of an answer that is read: a little more than the largest envelope. */
    const val MAX_ANSWER = 9 * 1024 * 1024

    /** Tests only: http://127.0.0.1 is let through as well as https. The app never sets it. */
    @Volatile
    internal var loopbackForTests = false

    /** May a request go to [url]? An https address; in tests also http://127.0.0.1. */
    fun allowed(url: String): Boolean = (url.startsWith("https://") && url.length > 8) || (loopbackForTests && url.startsWith("http://127.0.0.1:"))

    /** The platform's HTTPS. */
    val platform = HttpTransport { r ->
        if (!allowed(r.url)) HttpAnswer(0, problem = "only https addresses")
        else try {
            httpCall(r.method, r.url, r.headers, r.body, TIMEOUT_MS)
        } catch (e: Exception) {
            HttpAnswer(0, problem = e::class.simpleName ?: "error")
        }
    }
}

/**
 * One HTTP request on the platform: [method] to [url] with [headers] and [body], waiting at most [timeoutMs] to connect
 * and between bytes. Cookies are neither sent nor kept, nothing is cached, and redirects are not followed.
 */
internal expect fun httpCall(method: String, url: String, headers: Map<String, String>, body: ByteArray?, timeoutMs: Int): HttpAnswer
