package app.housemove.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import app.housemove.ui.icons.Icons
import app.housemove.ui.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.housemove.data.*
import app.housemove.ui.*
import java.time.LocalDate
import java.time.format.DateTimeFormatter

private val MONTH_YEAR = DateTimeFormatter.ofPattern("MMM yyyy")

/**
 * Every device and appliance with what has and hasn't been recorded about it.
 * A device with no serial number yet has a scan button right on its row: point the
 * camera at the barcode on its back and it is done, without opening the item.
 */
@Composable
fun ElectronicsScreen() {
    val nav = LocalNav.current
    val store = LocalStore.current
    val context = LocalContext.current
    val data = appData()
    // Make, model and serial numbers are not sent to Helpers: the list is not theirs.
    if (!data.seesHidden(deviceInfo())) {
        Screen("Devices") {
            ScrollColumn {
                EmptyState(
                    Pic.Device, "Kept by the Organisers",
                    text = "Make, model and serial numbers are kept by the Organisers and Deciders. Photograph a serial label from the item's own page.",
                    action = "Items", onAction = { nav.goTab("items") },
                )
            }
        }
        return
    }
    // Those still to do come first.
    val devices = data.liveItems.filter { it.isElectronic() }
        .sortedWith(compareBy({ it.serial.isNotBlank() || data.photosOf(it.id, PhotoKind.SERIAL).isNotEmpty() }, { it.title().lowercase() }))
    val withSerial = devices.count { it.serial.isNotBlank() || data.photosOf(it.id, PhotoKind.SERIAL).isNotEmpty() }
    var scanning by remember { mutableStateOf<String?>(null) }

    Screen("Devices") {
        if (devices.isEmpty()) {
            ScrollColumn {
                EmptyState(
                    Pic.Device, "No devices yet",
                    text = "TVs, computers and appliances you list show up here.",
                    action = "Start the walk-through", onAction = { nav.open("walk") },
                )
            }
        } else LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item(key = "head") {
                ProgressCard(
                    Ratio(withSerial, devices.size),
                    if (withSerial == devices.size) "Every serial number recorded" else "$withSerial of ${devices.size} with a serial number",
                    sub = "Insurers and the police ask for it.",
                )
            }
            items(devices, key = { it.id }) { d -> DeviceRow(d, data, onOpen = { nav.open("item/${d.id}") }, onScan = { scanning = d.id }) }
        }
    }

    scanning?.let { id ->
        val name = data.item(id)?.title() ?: "the device"
        ScanDialog(
            title = "Serial number of $name",
            raw = true,
            formats = ScanFormats.SERIALS,
            typeLabel = "Or type the serial number",
            onCode = { text ->
                val serial = text.trim()
                if (serial.isNotEmpty()) {
                    store.setDetails(id) { it.copy(serial = serial) }
                    toast(context, "Serial number: $serial. Check it against the label.")
                    scanning = null
                }
            },
            onClose = { scanning = null },
        )
    }
}

@Composable
private fun DeviceRow(d: Item, data: AppData, onOpen: () -> Unit, onScan: () -> Unit) {
    val serialPhoto = data.photosOf(d.id, PhotoKind.SERIAL).isNotEmpty()
    val recorded = d.serial.isNotBlank() || serialPhoto
    GlassCard(Modifier.fillMaxWidth(), tone = if (recorded) GlassTone.Plain else GlassTone.Amber, shape = RoundedCornerShape(22.dp)) {
        Row(Modifier.padding(start = 8.dp, top = 8.dp, bottom = 8.dp, end = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f).clip(RoundedCornerShape(16.dp)).clickable(onClick = onOpen), verticalAlignment = Alignment.CenterVertically) {
                ItemPhoto(d.id, Modifier.size(68.dp))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(d.title(), style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    val mm = d.makeModel()
                    if (mm.isNotBlank()) {
                        Text(mm, style = MaterialTheme.typography.bodySmall, color = Night.TextDim, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    if (recorded) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.CheckCircle, contentDescription = "Recorded", tint = Night.MintLight, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(
                                if (d.serial.isNotBlank()) "S/N ${d.serial}" else "Serial label photographed",
                                style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (!recorded) IconFlag(Icons.Default.Warning, "No serial number")
                        if (data.photosOf(d.id, PhotoKind.RECEIPT).isEmpty()) IconFlag(Icons.Default.Receipt, "No receipt")
                        if (d.accessories.isNotBlank() && !d.accessoriesPacked) IconFlag(Icons.Default.Cable, "Accessories loose")
                        d.warrantyDay?.let {
                            IconFlag(Icons.Default.VerifiedUser, if (it < today()) "Warranty ended" else "Warranty to ${MONTH_YEAR.format(LocalDate.ofEpochDay(it))}")
                        }
                    }
                }
            }
            if (recorded) Icon(Icons.Default.ChevronRight, contentDescription = null, tint = Night.TextDim, modifier = Modifier.padding(end = 4.dp))
            else GlassOrb(Icons.Default.QrCodeScanner, "Scan the serial number of ${d.title()}", kind = OrbKind.Selected, size = 46.dp, onClick = onScan)
        }
    }
}
