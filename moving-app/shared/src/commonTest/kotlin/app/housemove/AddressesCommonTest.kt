package app.housemove

import app.housemove.sync.InterfaceAddress
import app.housemove.sync.syncAddresses
import kotlin.test.Test
import kotlin.test.assertEquals

/** Which of an iPhone's addresses go in an invite (sync/Addresses.kt), on a made-up list of interfaces. */
class AddressesCommonTest {
    private fun v4(name: String, a: String, up: Boolean = true, running: Boolean = true, loopback: Boolean = false) =
        InterfaceAddress(name, up, running, loopback, a.split(".").map { it.toInt().toByte() }.toByteArray())

    private fun other(name: String) = InterfaceAddress(name, up = true, running = true, loopback = false, ipv4 = null)

    @Test
    fun wiFiAndTheHotspotAreTakenAndNothingElse() {
        val list = listOf(
            other("lo0"), v4("lo0", "127.0.0.1", loopback = true),
            other("pdp_ip0"), v4("pdp_ip0", "10.120.4.7"),
            other("en0"), v4("en0", "192.168.1.23"),
            other("utun3"), v4("utun3", "10.8.0.2"),
            v4("ipsec0", "172.16.5.5"),
            v4("awdl0", "192.168.7.7"), v4("llw0", "192.168.8.8"),
            v4("bridge100", "172.20.10.1"),
            v4("en2", "10.0.0.7"),
            v4("ap1", "192.168.9.9"),
            v4("anpi0", "192.168.10.10"),
        )
        assertEquals(listOf("192.168.1.23", "172.20.10.1", "10.0.0.7"), syncAddresses(list))
    }

    @Test
    fun onlySiteLocalIpv4OnInterfacesThatAreUpAndRunning() {
        val list = listOf(
            v4("en0", "192.168.1.23", up = false),
            v4("en1", "192.168.1.24", running = false),
            v4("en2", "8.8.8.8"),
            v4("en3", "169.254.3.4"),
            v4("en4", "172.15.0.1"), v4("en4", "172.16.0.1"), v4("en4", "172.31.255.1"), v4("en4", "172.32.0.1"),
            v4("en5", "10.255.255.255"),
            v4("en6", "192.169.0.1"), v4("en6", "192.168.0.1"),
            other("en7"),
        )
        assertEquals(listOf("172.16.0.1", "172.31.255.1", "10.255.255.255", "192.168.0.1"), syncAddresses(list))
    }

    @Test
    fun eachInterfaceComesWhereItFirstAppearsWithItsAddressesInOrder() {
        val list = listOf(
            v4("en1", "10.0.0.2"), v4("en0", "192.168.0.5"), v4("bridge100", "172.20.10.1"), v4("en1", "10.0.0.3"), v4("en0", "192.168.0.6"),
        )
        assertEquals(listOf("10.0.0.2", "10.0.0.3", "192.168.0.5", "192.168.0.6", "172.20.10.1"), syncAddresses(list))
        assertEquals(emptyList(), syncAddresses(emptyList()))
    }
}
