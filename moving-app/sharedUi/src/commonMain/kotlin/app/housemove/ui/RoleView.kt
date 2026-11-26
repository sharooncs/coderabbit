package app.housemove.ui

import kotlin.concurrent.Volatile
import app.housemove.data.*

/*
 * What a phone shows of the data, by its person's role (design 2.1, section 2).
 *
 * Helpers are not sent costs, receipts, prices and sale amounts, item values, serial numbers, make and model,
 * warranty or serial-label photos. A Helper's phone can still hold some for a while (2.0 sent them, or the
 * person was a Decider until lately) until a full sync with an Organiser or a Decider lets them go
 * ([Store.fullSyncDone]). Until then the screens show it nothing of them either: every screen reads the
 * data through [appData], which is this.
 */

/** May this phone's person see values, prices, serial numbers and costs? Everyone may, except a Helper on a move that has an Organiser. */
fun AppData.seesHidden(device: DeviceInfo): Boolean = !hasOrganiser() || roleOf(device) != Role.HELPER

/** The last Helper's view made, with the dataset and person it was made for: every screen element asks for the same one. */
private class HelperView(val of: AppData, val personId: String, val view: AppData)

@Volatile
private var lastHelperView: HelperView? = null

/**
 * The data as this phone's person may see it: with each item's hidden details filled in for an Organiser or a
 * Decider ([filledIn], made once per dataset); without anything a Helper is not sent for a Helper
 * ([withoutHiddenFor]), whatever the phone still holds, also made once per dataset. Never saved: the Store keeps
 * the data itself.
 */
fun AppData.shownTo(device: DeviceInfo): AppData {
    if (seesHidden(device)) return filledIn()
    lastHelperView?.let { if (it.of === this && it.personId == device.personId) return it.view }
    val view = if (holdsHidden(device.personId)) withoutHiddenFor(device.personId) else this
    lastHelperView = HelperView(this, device.personId, view)
    return view
}
