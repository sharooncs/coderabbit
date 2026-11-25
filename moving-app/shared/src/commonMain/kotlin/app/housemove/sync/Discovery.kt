package app.housemove.sync

import app.housemove.data.Lock
import app.housemove.data.synchronized

/** A HouseMove phone advertising on the network: its service name, and where to connect to it. */
class Found(val name: String, val host: String, val port: Int)

/**
 * How phones find each other on the local network (design/auto-sync.md, "Discovery"): DNS-SD over mDNS, service type
 * [SERVICE_TYPE]. On Android it is NsdManager (the app's NsdDiscovery), on the iPhone dns_sd (DnsSdDiscovery), and in
 * tests and the iPhone's demo mode an in-process stand-in ([LocalDiscovery]). [AutoSync] calls it from one thread at a
 * time; what the platform reports comes in on threads of its own.
 */
interface Discovery {
    /** Advertise this phone as [name] (`HN-<phone tag>-<random>`) on [port], in place of anything it advertised before. */
    fun advertise(name: String, port: Int)

    /** Start looking for other phones' services; what is found comes into [found]. */
    fun browse()

    /** Stop advertising and looking. */
    fun stop()

    /** The services found and resolved so far (this phone's own may be among them). */
    fun found(): List<Found>

    /** Works without Wi-Fi: phones in one process (tests, demo mode). */
    val needsWifi: Boolean get() = true

    /** The platform has refused HouseMove the local network (the iPhone's Local Network switch is off). */
    val denied: Boolean get() = false

    companion object {
        /** HouseMove's Bonjour service type, as the iPhone's Info.plist declares it (NSBonjourServices). */
        const val SERVICE_TYPE = "_housemove._tcp"

        /**
         * The protocol version in the TXT record (`v=2`): 2.4's encrypted sync, advertised under `HN-…` names
         * (design/encryption.md, "Discovery"). 2.3 advertised `v=1` under `HM-…`.
         */
        const val VERSION = "2"
    }
}

/** Phones in one process, as if on one Wi-Fi: what [LocalDiscovery] advertises is found by every other one on the same lan. */
class LocalLan {
    private val lock = Lock()
    private val services = LinkedHashMap<String, Found>()

    internal fun put(f: Found) {
        synchronized(lock) { services[f.name] = f }
    }

    internal fun remove(name: String) {
        synchronized(lock) { services.remove(name) }
    }

    internal fun all(): List<Found> = synchronized(lock) { services.values.toList() }

    companion object {
        /** The one the iPhone's demo mode uses: the app and the sample move it hosts inside itself. */
        val shared = LocalLan()
    }
}

/** A stand-in for Bonjour, for phones in one process ([LocalLan]): each is found at 127.0.0.1 on the port it gave. */
class LocalDiscovery(private val lan: LocalLan = LocalLan.shared) : Discovery {
    private val lock = Lock()
    private var mine: String? = null

    override fun advertise(name: String, port: Int) {
        synchronized(lock) {
            mine?.let { lan.remove(it) }
            mine = name
            lan.put(Found(name, "127.0.0.1", port))
        }
    }

    override fun browse() {}

    override fun stop() {
        synchronized(lock) {
            mine?.let { lan.remove(it) }
            mine = null
        }
    }

    override fun found(): List<Found> = lan.all()

    override val needsWifi: Boolean get() = false
}
