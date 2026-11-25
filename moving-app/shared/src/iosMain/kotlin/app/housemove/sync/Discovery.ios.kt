@file:OptIn(ExperimentalForeignApi::class)

package app.housemove.sync

import app.housemove.data.Lock
import app.housemove.data.synchronized
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.StableRef
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.asStableRef
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.pointed
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.staticCFunction
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import platform.darwin.DNSServiceBrowse
import platform.darwin.DNSServiceGetAddrInfo
import platform.darwin.DNSServiceRef
import platform.darwin.DNSServiceRefDeallocate
import platform.darwin.DNSServiceRefVar
import platform.darwin.DNSServiceRegister
import platform.darwin.DNSServiceResolve
import platform.darwin.DNSServiceSetDispatchQueue
import platform.darwin.dispatch_async
import platform.darwin.dispatch_queue_create
import platform.darwin.kDNSServiceErr_NoError
import platform.darwin.kDNSServiceErr_PolicyDenied
import platform.darwin.kDNSServiceFlagsAdd
import platform.darwin.kDNSServiceProtocol_IPv4
import platform.posix.AF_INET
import platform.posix.sockaddr
import platform.posix.sockaddr_in
import kotlin.concurrent.Volatile

/*
 * Bonjour on the iPhone, for syncing by itself (design/auto-sync.md, "Discovery"), through dns_sd (Kotlin/Native's
 * platform.darwin): DNSServiceRegister advertises HouseMove's own listening socket (a POSIX one, as code sync's), which
 * Network.framework's NWListener cannot, since it advertises only a listener it owns; DNSServiceBrowse, Resolve and
 * GetAddrInfo find the other phones and their IPv4 addresses. Every call and every answer runs on one serial dispatch
 * queue (DNSServiceSetDispatchQueue), so what they share needs no lock but [found], which the engine reads from its own
 * thread. The service type is declared in Info.plist (NSBonjourServices), as iOS requires; the first browse is what
 * makes iOS ask the local-network question, and "Don't Allow" comes back as kDNSServiceErr_PolicyDenied ([denied]).
 */

/** The TXT record: the protocol version and nothing else (a length byte, then "v=1"). */
private val TXT = byteArrayOf(3, 'v'.code.toByte(), '='.code.toByte(), '1'.code.toByte())

/** A dns_sd operation under way, and what it is about: the context its answers come back with. */
internal class BonjourOp(val discovery: DnsSdDiscovery, val name: String = "", val port: Int = 0) {
    var ref: DNSServiceRef? = null
    var stable: StableRef<BonjourOp>? = null
}

private fun opOf(context: COpaquePointer?): BonjourOp? = context?.asStableRef<BonjourOp>()?.get()

private val registered = staticCFunction { _: DNSServiceRef?, _: UInt, error: Int, name: CPointer<kotlinx.cinterop.ByteVar>?, _: CPointer<kotlinx.cinterop.ByteVar>?, _: CPointer<kotlinx.cinterop.ByteVar>?, context: COpaquePointer? ->
    val op = opOf(context) ?: return@staticCFunction
    if (error == kDNSServiceErr_PolicyDenied) op.discovery.deny()
    // The name it was registered under: Bonjour renames one that clashes with another on the network.
    else if (error == kDNSServiceErr_NoError && name != null) op.discovery.registeredAs(name.toKString())
}

private val browsed = staticCFunction { _: DNSServiceRef?, flags: UInt, iface: UInt, error: Int, name: CPointer<kotlinx.cinterop.ByteVar>?, type: CPointer<kotlinx.cinterop.ByteVar>?, domain: CPointer<kotlinx.cinterop.ByteVar>?, context: COpaquePointer? ->
    val op = opOf(context) ?: return@staticCFunction
    when {
        error == kDNSServiceErr_PolicyDenied -> op.discovery.deny()
        error != kDNSServiceErr_NoError || name == null -> Unit
        (flags and kDNSServiceFlagsAdd) != 0u -> op.discovery.resolve(name.toKString(), type?.toKString() ?: "", domain?.toKString() ?: "", iface)
        else -> op.discovery.gone(name.toKString())
    }
}

private val resolved = staticCFunction { _: DNSServiceRef?, _: UInt, iface: UInt, error: Int, _: CPointer<kotlinx.cinterop.ByteVar>?, host: CPointer<kotlinx.cinterop.ByteVar>?, port: UShort, _: UShort, _: CPointer<kotlinx.cinterop.UByteVar>?, context: COpaquePointer? ->
    val op = opOf(context) ?: return@staticCFunction
    op.discovery.finish(op)
    if (error == kDNSServiceErr_NoError && host != null) {
        // The port comes in network order, big end first.
        val p = port.toInt()
        op.discovery.lookUp(op.name, host.toKString(), ((p and 0xFF) shl 8) or ((p shr 8) and 0xFF), iface)
    }
}

private val addressed = staticCFunction { _: DNSServiceRef?, _: UInt, _: UInt, error: Int, _: CPointer<kotlinx.cinterop.ByteVar>?, address: CPointer<sockaddr>?, _: UInt, context: COpaquePointer? ->
    val op = opOf(context) ?: return@staticCFunction
    op.discovery.finish(op)
    if (error == kDNSServiceErr_NoError && address != null && address.pointed.sa_family.toInt() == AF_INET) {
        val b = address.reinterpret<sockaddr_in>().pointed.addressBytes()
        op.discovery.found(op.name, b.joinToString(".") { (it.toInt() and 0xFF).toString() }, op.port)
    }
}

/** Bonjour through dns_sd: the iPhone's [Discovery]. */
class DnsSdDiscovery : Discovery {
    private val queue = dispatch_queue_create("app.housemove.bonjour", null)

    // On [queue] only.
    private var advert: BonjourOp? = null
    private var browser: BonjourOp? = null
    private val looking = HashMap<String, BonjourOp>()

    private val lock = Lock()
    private val services = LinkedHashMap<String, Found>()

    @Volatile
    private var refused = false

    @Volatile
    private var ownName: String? = null

    override val denied: Boolean get() = refused

    override fun advertise(name: String, port: Int) {
        dispatch_async(queue) {
            advert?.let { end(it) }
            advert = null
            ownName = name
            val op = BonjourOp(this, name, port)
            val err = start(op) { ref, ctx ->
                TXT.usePinned { txt ->
                    DNSServiceRegister(
                        ref, 0u, 0u, name, Discovery.SERVICE_TYPE, "local.", null,
                        (((port and 0xFF) shl 8) or ((port shr 8) and 0xFF)).toUShort(), TXT.size.convert(), txt.addressOf(0), registered, ctx,
                    )
                }
            }
            if (err == kDNSServiceErr_NoError) advert = op else if (err == kDNSServiceErr_PolicyDenied) deny()
        }
    }

    override fun browse() {
        dispatch_async(queue) {
            if (browser != null) return@dispatch_async
            val op = BonjourOp(this)
            val err = start(op) { ref, ctx -> DNSServiceBrowse(ref, 0u, 0u, Discovery.SERVICE_TYPE, "local.", browsed, ctx) }
            if (err == kDNSServiceErr_NoError) {
                browser = op
                refused = false
            } else if (err == kDNSServiceErr_PolicyDenied) deny()
        }
    }

    override fun stop() {
        dispatch_async(queue) {
            advert?.let { end(it) }
            browser?.let { end(it) }
            looking.values.toList().forEach { end(it) }
            advert = null
            browser = null
            looking.clear()
            synchronized(lock) { services.clear() }
        }
    }

    override fun found(): List<Found> = synchronized(lock) { services.values.filter { it.name != ownName } }

    // ---- on the queue, from the answers ------------------------------------------------------------------------

    /** Starts [op] with [call] (one of DNSServiceRegister, Browse, Resolve, GetAddrInfo) and puts it on the queue. */
    private fun start(op: BonjourOp, call: (kotlinx.cinterop.CValuesRef<DNSServiceRefVar>, COpaquePointer?) -> Int): Int {
        val stable = StableRef.create(op)
        op.stable = stable
        return memScoped {
            val ref = alloc<DNSServiceRefVar>()
            val err = call(ref.ptr, stable.asCPointer())
            if (err == kDNSServiceErr_NoError && ref.value != null) {
                op.ref = ref.value
                DNSServiceSetDispatchQueue(ref.value, queue)
            } else {
                stable.dispose()
                op.stable = null
            }
            err
        }
    }

    /** Ends [op]: no more answers for it. */
    private fun end(op: BonjourOp) {
        op.ref?.let { DNSServiceRefDeallocate(it) }
        op.ref = null
        op.stable?.dispose()
        op.stable = null
    }

    /** One answer of a resolve or an address look-up was enough: that operation is over. */
    internal fun finish(op: BonjourOp) {
        if (looking[op.name] === op) looking.remove(op.name)
        end(op)
    }

    internal fun deny() {
        refused = true
    }

    internal fun registeredAs(name: String) {
        ownName = name
    }

    internal fun resolve(name: String, type: String, domain: String, iface: UInt) {
        if (!name.startsWith("HM-") || looking.containsKey(name)) return
        val op = BonjourOp(this, name)
        if (start(op) { ref, ctx -> DNSServiceResolve(ref, 0u, iface, name, type, domain, resolved, ctx) } == kDNSServiceErr_NoError) looking[name] = op
    }

    internal fun lookUp(name: String, host: String, port: Int, iface: UInt) {
        val op = BonjourOp(this, name, port)
        if (start(op) { ref, ctx -> DNSServiceGetAddrInfo(ref, 0u, iface, kDNSServiceProtocol_IPv4, host, addressed, ctx) } == kDNSServiceErr_NoError) looking[name] = op
    }

    internal fun found(name: String, host: String, port: Int) {
        synchronized(lock) { services[name] = Found(name, host, port) }
    }

    internal fun gone(name: String) {
        looking.remove(name)?.let { end(it) }
        synchronized(lock) { services.remove(name) }
    }
}
