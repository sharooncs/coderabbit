@file:OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)

package app.housemove.sync

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import platform.Foundation.NSData
import platform.Foundation.NSError
import platform.Foundation.NSHTTPCookieAcceptPolicy
import platform.Foundation.NSHTTPURLResponse
import platform.Foundation.NSMutableURLRequest
import platform.Foundation.NSURL
import platform.Foundation.NSURLRequest
import platform.Foundation.NSURLRequestReloadIgnoringLocalCacheData
import platform.Foundation.NSURLResponse
import platform.Foundation.NSURLSession
import platform.Foundation.NSURLSessionConfiguration
import platform.Foundation.NSURLSessionTask
import platform.Foundation.NSURLSessionTaskDelegateProtocol
import platform.Foundation.create
import platform.Foundation.dataTaskWithRequest
import platform.Foundation.setHTTPBody
import platform.Foundation.setHTTPMethod
import platform.Foundation.setHTTPShouldHandleCookies
import platform.Foundation.setValue
import platform.darwin.DISPATCH_TIME_NOW
import platform.darwin.NSObject
import platform.darwin.dispatch_semaphore_create
import platform.darwin.dispatch_semaphore_signal
import platform.darwin.dispatch_semaphore_wait
import platform.darwin.dispatch_time
import platform.posix.memcpy
import kotlin.concurrent.Volatile

// The iPhone: NSURLSession with an ephemeral configuration (nothing kept on disk), no cookies accepted, set or sent, no
// cache, and a delegate that follows no redirect. HTTPS with iOS's own certificate checks (Tailscale Funnel's certificate
// is a Let's Encrypt one). The shared code calls this from its own threads and waits for the answer.

/** Follows no redirect: the mailbox never sends one, so one would be someone else's. */
private class NoRedirects : NSObject(), NSURLSessionTaskDelegateProtocol {
    override fun URLSession(
        session: NSURLSession,
        task: NSURLSessionTask,
        willPerformHTTPRedirection: NSHTTPURLResponse,
        newRequest: NSURLRequest,
        completionHandler: (NSURLRequest?) -> Unit,
    ) {
        completionHandler(null)
    }
}

private val session: NSURLSession by lazy {
    val config = NSURLSessionConfiguration.ephemeralSessionConfiguration
    config.HTTPCookieAcceptPolicy = NSHTTPCookieAcceptPolicy.NSHTTPCookieAcceptPolicyNever
    config.HTTPShouldSetCookies = false
    config.HTTPCookieStorage = null
    config.URLCache = null
    config.requestCachePolicy = NSURLRequestReloadIgnoringLocalCacheData
    NSURLSession.sessionWithConfiguration(config, NoRedirects(), null)
}

/** What the completion handler hands over to the waiting thread. */
private class Outcome {
    @Volatile var status = 0
    @Volatile var body: ByteArray = ByteArray(0)
    @Volatile var problem = ""
}

private fun ByteArray.toData(): NSData = if (isEmpty()) NSData() else usePinned { NSData.create(bytes = it.addressOf(0), length = size.convert()) }

private fun NSData.toBytes(): ByteArray {
    val n = length.toInt()
    if (n == 0) return ByteArray(0)
    val out = ByteArray(n)
    out.usePinned { memcpy(it.addressOf(0), bytes, length) }
    return out
}

internal actual fun httpCall(method: String, url: String, headers: Map<String, String>, body: ByteArray?, timeoutMs: Int): HttpAnswer {
    val address = NSURL.URLWithString(url) ?: return HttpAnswer(0, problem = "not an address")
    val request = NSMutableURLRequest.requestWithURL(address, NSURLRequestReloadIgnoringLocalCacheData, timeoutMs / 1000.0)
    request.setHTTPMethod(method)
    request.setHTTPShouldHandleCookies(false)
    request.setValue("application/json, application/octet-stream", forHTTPHeaderField = "Accept")
    for ((k, v) in headers) request.setValue(v, forHTTPHeaderField = k)
    if (body != null) request.setHTTPBody(body.toData())
    val done = dispatch_semaphore_create(0)
    val outcome = Outcome()
    val task = session.dataTaskWithRequest(request) { data: NSData?, response: NSURLResponse?, error: NSError? ->
        val http = response as? NSHTTPURLResponse
        when {
            error != null -> outcome.problem = "NSURLError ${error.code}"
            http == null -> outcome.problem = "no answer"
            else -> {
                val bytes = data?.toBytes() ?: ByteArray(0)
                if (bytes.size > Http.MAX_ANSWER) outcome.problem = "answer too large"
                else {
                    outcome.body = bytes
                    outcome.status = http.statusCode.toInt()
                }
            }
        }
        dispatch_semaphore_signal(done)
    }
    task.resume()
    // The request's own time-out is between bytes; the whole of it may take a little longer for a large envelope.
    val waitMs = 3L * timeoutMs + 5_000L
    if (dispatch_semaphore_wait(done, dispatch_time(DISPATCH_TIME_NOW, waitMs * 1_000_000L)) != 0L) {
        task.cancel()
        return HttpAnswer(0, problem = "timed out")
    }
    return HttpAnswer(outcome.status, outcome.body, outcome.problem)
}
