package app.housemove.sync

/*
 * Which of an iPhone's addresses go in an invite (step 1b of design/ios-plan.md). Android and the JVM keep
 * NetworkInterface (Net.jvm.kt); the iPhone lists its interfaces with getifaddrs (Net.ios.kt) and picks from them
 * here, in common code, so the rules can be tested on every platform with a made-up list.
 */

/** One address of one network interface, as the system lists it. [ipv4] is the address's four bytes, or null for an address of another kind. */
internal class InterfaceAddress(
    val name: String,
    val up: Boolean,
    val running: Boolean,
    val loopback: Boolean,
    val ipv4: ByteArray?,
)

/** Interfaces another phone can reach an iPhone on: Wi-Fi (en0, en1 and on) and the iPhone's own Personal Hotspot (bridge100, 172.20.10.x). */
internal val SYNC_INTERFACES = listOf("en", "bridge")

/**
 * Interfaces it cannot, whatever addresses they have: cellular (pdp_ip), VPNs (utun, ipsec), AirDrop's peer-to-peer
 * Wi-Fi and its low-latency link (awdl, llw), and loopback (lo). Any other interface is left out too: only
 * [SYNC_INTERFACES] are taken.
 */
internal val NOT_SYNC_INTERFACES = listOf("pdp_ip", "utun", "ipsec", "awdl", "llw", "lo")

/** Is [b] a site-local IPv4 address (10/8, 172.16/12, 192.168/16), as Inet4Address.isSiteLocalAddress says? */
internal fun isSiteLocal(b: ByteArray): Boolean {
    val a0 = b[0].toInt() and 0xFF
    val a1 = b[1].toInt() and 0xFF
    return a0 == 10 || (a0 == 172 && (a1 and 0xF0) == 16) || (a0 == 192 && a1 == 168)
}

/**
 * The addresses to put in an invite, from [list] as the system gives it: the site-local IPv4 addresses (an invite
 * cannot hold IPv6, whose colons would split it) of Wi-Fi and hotspot interfaces that are up and running and not
 * loopback ([SYNC_INTERFACES], never [NOT_SYNC_INTERFACES]), as dotted text.
 *
 * The order is the JVM code's rule: none of its own. NetworkInterface gives interfaces one by one, each with its
 * addresses, and the JVM keeps that order; here each interface comes where it first appears in [list], with its
 * addresses in the order they come.
 */
internal fun syncAddresses(list: List<InterfaceAddress>): List<String> {
    val byInterface = LinkedHashMap<String, MutableList<InterfaceAddress>>()
    for (a in list) byInterface.getOrPut(a.name) { ArrayList() } += a
    val out = ArrayList<String>()
    for ((name, addresses) in byInterface) {
        if (NOT_SYNC_INTERFACES.any { name.startsWith(it) } || SYNC_INTERFACES.none { name.startsWith(it) }) continue
        for (a in addresses) {
            val b = a.ipv4 ?: continue
            if (!a.up || !a.running || a.loopback || b.size != 4 || !isSiteLocal(b)) continue
            out += b.joinToString(".") { (it.toInt() and 0xFF).toString() }
        }
    }
    return out
}
