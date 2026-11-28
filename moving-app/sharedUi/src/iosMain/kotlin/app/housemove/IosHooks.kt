@file:OptIn(ExperimentalForeignApi::class)

package app.housemove

import app.housemove.camera.AppleCamera
import app.housemove.camera.DemoCamera
import app.housemove.data.File
import app.housemove.sync.Discovery
import app.housemove.sync.DnsSdDiscovery
import app.housemove.sync.LocalDiscovery
import app.housemove.sync.LocalLan
import app.housemove.ui.iphone.LocalNetwork
import app.housemove.ui.iphone.PhoneCamera
import app.housemove.ui.iphone.PhoneFiles
import app.housemove.ui.iphone.PhoneHooks
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import platform.Foundation.NSFileManager
import platform.Foundation.NSLocale
import platform.Foundation.NSUserDefaults
import platform.Foundation.countryCode
import platform.Foundation.currentLocale
import platform.Foundation.NSURL
import platform.Network.nw_advertise_descriptor_create_bonjour_service
import platform.Network.nw_browse_descriptor_create_bonjour_service
import platform.Network.nw_browse_result_copy_endpoint
import platform.Network.nw_browser_cancel
import platform.Network.nw_browser_create
import platform.Network.nw_browser_set_browse_results_changed_handler
import platform.Network.nw_browser_set_queue
import platform.Network.nw_browser_set_state_changed_handler
import platform.Network.nw_browser_start
import platform.Network.nw_browser_state_failed
import platform.Network.nw_connection_cancel
import platform.Network.nw_endpoint_get_bonjour_service_name
import platform.Network.nw_error_domain_dns
import platform.Network.nw_error_get_error_code
import platform.Network.nw_error_get_error_domain
import platform.Network.nw_listener_cancel
import platform.Network.nw_listener_create
import platform.Network.nw_listener_set_advertise_descriptor
import platform.Network.nw_listener_set_new_connection_handler
import platform.Network.nw_listener_set_queue
import platform.Network.nw_listener_set_state_changed_handler
import platform.Network.nw_listener_start
import platform.Network.nw_parameters_create
import platform.Network.nw_parameters_create_secure_tcp
import platform.Network._nw_parameters_configure_protocol_default_configuration
import platform.Network._nw_parameters_configure_protocol_disable
import platform.UIKit.UIApplication
import platform.UIKit.UIApplicationOpenSettingsURLString
import platform.UIKit.UIDevice
import platform.UIKit.UIPasteboard
import platform.darwin.DISPATCH_TIME_NOW
import platform.darwin.NSEC_PER_SEC
import platform.darwin.dispatch_after
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue
import platform.darwin.dispatch_queue_create
import platform.darwin.dispatch_time
import kotlin.random.Random

/**
 * What the shared screens ask of the iPhone ([PhoneHooks]): its name, Settings, the local-network question, the
 * screen staying awake, the clipboard, and deleting a move's folders. [dataDir] and [noBackupDir] are where the moves
 * are kept (AppFolders, or demo mode's own folders).
 */
class IosHooks(override val dataDir: File, override val noBackupDir: File, private val demo: Boolean = false) : PhoneHooks {
    /** The internet mailbox: on the real app; demo mode's sample move has none. */
    override val mailbox: Boolean get() = !demo

    /**
     * "iPhone": since iOS 16 an app is told the model's name, not the one the owner gave the phone. Read once, here on
     * the main thread, as UIKit asks: the sync threads ask for it later.
     */
    private val name: String = UIDevice.currentDevice.name

    override fun deviceName(): String = name

    override fun openSettings() {
        val url = NSURL.URLWithString(UIApplicationOpenSettingsURLString) ?: return
        UIApplication.sharedApplication.openURL(url, options = emptyMap<Any?, Any>(), completionHandler = null)
    }

    override fun checkLocalNetwork(done: (LocalNetwork) -> Unit) = LocalNetworkCheck.run(done)

    override fun keepAwake(on: Boolean) {
        dispatch_async(dispatch_get_main_queue()) { Awake.hold(on) }
    }

    /** The camera, the scanner and choosing photos (step 3a): AVFoundation and PhotosUI, or demo mode's fakes (camera/). */
    override val camera: PhoneCamera = AppleCamera { openSettings() }.let { if (demo) DemoCamera(it) else it }

    override fun paste(): String? = UIPasteboard.generalPasteboard.string

    override fun deleteFolder(folder: File) {
        NSFileManager.defaultManager.removeItemAtPath(folder.path, error = null)
    }

    /** The share sheet, the document picker, "Open in HouseMove" and the printouts (IosFiles.kt). */
    override val files: PhoneFiles get() = IosFiles

    /** Bonjour (dns_sd); in demo mode, the phones inside this app only: it and the sample move it hosts. */
    override fun discovery(): Discovery = if (demo) LocalDiscovery(LocalLan.shared) else DnsSdDiscovery()

    override val loopbackOnly: Boolean get() = demo

    /**
     * The shared screens' small things kept on this iPhone (which answers its person has read), in the app's own
     * defaults. Demo mode keeps them for as long as the app runs only, so a UI test never sees the one before it.
     */
    private val kept = HashMap<String, Set<String>>()

    override fun readSet(key: String): Set<String> {
        if (demo) return kept[key] ?: emptySet()
        val list = NSUserDefaults.standardUserDefaults.stringArrayForKey("shared.$key") ?: return emptySet()
        return list.mapNotNull { it as? String }.toSet()
    }

    override fun writeSet(key: String, values: Set<String>) {
        if (demo) {
            kept[key] = values
            return
        }
        NSUserDefaults.standardUserDefaults.setObject(values.toList(), forKey = "shared.$key")
    }

    override fun country(): String = NSLocale.currentLocale.countryCode ?: ""
}

/**
 * Asks iOS whether HouseMove may use the local network. There is no call that just says so: this advertises a
 * Bonjour service of HouseMove's own (`_housemove._tcp`, declared in Info.plist's NSBonjourServices) and browses for
 * it. Finding it means allowed; iOS refusing the browse with "policy denied" means switched off. The first time,
 * this is also what makes iOS ask the person (NSLocalNetworkUsageDescription), and the answer comes once they have
 * tapped Allow or Don't Allow. Anything else, or nothing within 35 seconds, is UNKNOWN. Nothing is sent to any
 * other device, and both are stopped as soon as there is an answer.
 */
internal object LocalNetworkCheck {
    private const val SERVICE = "_housemove._tcp"

    /** kDNSServiceErr_PolicyDenied (dns_sd.h): the local network is switched off for this app. */
    private const val POLICY_DENIED = -65570

    fun run(done: (LocalNetwork) -> Unit) {
        // Every handler runs on this one serial queue, so what they share needs no lock.
        val queue = dispatch_queue_create("app.housemove.local-network", null)
        val name = "HouseMove check " + Random.nextInt(100_000, 999_999)
        var answered = false
        val listener = nw_listener_create(
            nw_parameters_create_secure_tcp(_nw_parameters_configure_protocol_disable, _nw_parameters_configure_protocol_default_configuration),
        )
        val browser = nw_browser_create(nw_browse_descriptor_create_bonjour_service(SERVICE, "local."), nw_parameters_create())

        fun finish(answer: LocalNetwork) {
            if (answered) return
            answered = true
            if (browser != null) nw_browser_cancel(browser)
            if (listener != null) nw_listener_cancel(listener)
            done(answer)
        }
        if (listener == null || browser == null) {
            done(LocalNetwork.UNKNOWN)
            return
        }

        nw_listener_set_advertise_descriptor(listener, nw_advertise_descriptor_create_bonjour_service(name, SERVICE, "local."))
        // Nobody connects to it; any connection is closed at once.
        nw_listener_set_new_connection_handler(listener) { connection -> if (connection != null) nw_connection_cancel(connection) }
        nw_listener_set_state_changed_handler(listener) { _, error ->
            if (error != null && nw_error_get_error_domain(error) == nw_error_domain_dns && nw_error_get_error_code(error) == POLICY_DENIED) {
                finish(LocalNetwork.DENIED)
            }
        }
        nw_listener_set_queue(listener, queue)

        nw_browser_set_browse_results_changed_handler(browser) { _, found, _ ->
            val endpoint = if (found != null) nw_browse_result_copy_endpoint(found) else null
            if (endpoint != null && nw_endpoint_get_bonjour_service_name(endpoint)?.toKString() == name) finish(LocalNetwork.ALLOWED)
        }
        nw_browser_set_state_changed_handler(browser) { state, error ->
            when {
                error != null && nw_error_get_error_domain(error) == nw_error_domain_dns && nw_error_get_error_code(error) == POLICY_DENIED ->
                    finish(LocalNetwork.DENIED)
                state == nw_browser_state_failed -> finish(LocalNetwork.UNKNOWN)
            }
        }
        nw_browser_set_queue(browser, queue)

        nw_listener_start(listener)
        nw_browser_start(browser)
        dispatch_after(dispatch_time(DISPATCH_TIME_NOW, 35L * NSEC_PER_SEC.toLong()), queue) { finish(LocalNetwork.UNKNOWN) }
    }
}
