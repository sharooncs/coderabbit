package app.housemove

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import app.housemove.sync.Discovery
import app.housemove.sync.Found
import java.net.Inet4Address
import java.net.InetAddress

/**
 * Bonjour on Android, for syncing by itself (design/auto-sync.md, "Discovery"): NsdManager advertises this phone's
 * listening socket as `HM-<tag>-<random>` of type `_housemove._tcp`, browses for the others, and resolves each to its
 * IPv4 address, one at a time (before Android 14 a second resolve under way fails). NsdManager is a system service
 * that does the multicast itself: it needs no permission beyond INTERNET, and no multicast lock.
 */
class NsdDiscovery(context: Context) : Discovery {
    private val nsd: NsdManager? = context.applicationContext.getSystemService(NsdManager::class.java)
    private val lock = Any()
    private var registration: NsdManager.RegistrationListener? = null
    private var browsing: NsdManager.DiscoveryListener? = null

    /** The name the system registered this phone's service under (it renames one that clashes). */
    @Volatile
    private var ownName: String? = null
    private val services = LinkedHashMap<String, Found>()
    private val waiting = ArrayDeque<NsdServiceInfo>()
    private var resolving = false

    override fun advertise(name: String, port: Int) {
        val nsd = nsd ?: return
        synchronized(lock) {
            registration?.let { unregister(it) }
            ownName = name
            val info = NsdServiceInfo().apply {
                serviceName = name
                serviceType = Discovery.SERVICE_TYPE
                setPort(port)
                setAttribute("v", Discovery.VERSION)
            }
            val listener = object : NsdManager.RegistrationListener {
                override fun onServiceRegistered(info: NsdServiceInfo) {
                    ownName = info.serviceName
                }

                override fun onRegistrationFailed(info: NsdServiceInfo, error: Int) {
                    synchronized(lock) { if (registration === this) registration = null }
                }

                override fun onServiceUnregistered(info: NsdServiceInfo) {}
                override fun onUnregistrationFailed(info: NsdServiceInfo, error: Int) {}
            }
            registration = listener
            try {
                nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener)
            } catch (_: Exception) {
                registration = null
            }
        }
    }

    override fun browse() {
        val nsd = nsd ?: return
        synchronized(lock) {
            if (browsing != null) return
            val listener = object : NsdManager.DiscoveryListener {
                override fun onServiceFound(info: NsdServiceInfo) {
                    // Only HouseMove's own names; anything else of this type (the iPhone's local-network check) is let be.
                    if (info.serviceName.startsWith("HM-")) wantResolved(info)
                }

                override fun onServiceLost(info: NsdServiceInfo) {
                    synchronized(lock) { services.remove(info.serviceName) }
                }

                override fun onStartDiscoveryFailed(type: String, error: Int) {
                    synchronized(lock) { if (browsing === this) browsing = null }
                }

                override fun onDiscoveryStarted(type: String) {}
                override fun onDiscoveryStopped(type: String) {}
                override fun onStopDiscoveryFailed(type: String, error: Int) {}
            }
            browsing = listener
            try {
                nsd.discoverServices(Discovery.SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
            } catch (_: Exception) {
                browsing = null
            }
        }
    }

    override fun stop() {
        synchronized(lock) {
            registration?.let { unregister(it) }
            registration = null
            browsing?.let { l ->
                try {
                    nsd?.stopServiceDiscovery(l)
                } catch (_: Exception) {
                }
            }
            browsing = null
            services.clear()
            waiting.clear()
            ownName = null
        }
    }

    override fun found(): List<Found> = synchronized(lock) { services.values.filter { it.name != ownName } }

    private fun unregister(l: NsdManager.RegistrationListener) {
        try {
            nsd?.unregisterService(l)
        } catch (_: Exception) {
        }
    }

    private fun wantResolved(info: NsdServiceInfo) {
        synchronized(lock) {
            if (browsing == null || waiting.any { it.serviceName == info.serviceName }) return
            waiting.addLast(info)
        }
        resolveNext()
    }

    @Suppress("DEPRECATION")
    private fun resolveNext() {
        val nsd = nsd ?: return
        val info = synchronized(lock) {
            if (resolving) return
            val next = waiting.removeFirstOrNull() ?: return
            resolving = true
            next
        }
        val listener = object : NsdManager.ResolveListener {
            override fun onServiceResolved(info: NsdServiceInfo) {
                val address = ipv4Of(info)
                synchronized(lock) {
                    if (address != null && browsing != null) services[info.serviceName] = Found(info.serviceName, address, info.port)
                    resolving = false
                }
                resolveNext()
            }

            override fun onResolveFailed(info: NsdServiceInfo, error: Int) {
                synchronized(lock) { resolving = false }
                resolveNext()
            }
        }
        try {
            nsd.resolveService(info, listener)
        } catch (_: Exception) {
            synchronized(lock) { resolving = false }
        }
    }

    @Suppress("DEPRECATION")
    private fun ipv4Of(info: NsdServiceInfo): String? {
        val all: List<InetAddress> = if (Build.VERSION.SDK_INT >= 34) info.hostAddresses else listOfNotNull(info.host)
        return all.firstOrNull { it is Inet4Address }?.hostAddress
    }
}
