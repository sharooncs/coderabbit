package app.housemove.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.housemove.data.*
import app.housemove.ui.*

/** Every device and appliance with what has and hasn't been recorded about it. */
@Composable
fun ElectronicsScreen() {
    val nav = LocalNav.current
    val data = appData()
    val devices = data.liveItems.filter { it.isElectronic() }.sortedBy { it.title().lowercase() }
    val withSerial = devices.count { it.serial.isNotBlank() || data.photosOf(it.id, PhotoKind.SERIAL).isNotEmpty() }
    Screen("Electronics and serial numbers") {
        LazyColumn(Modifier.fillMaxSize()) {
            item(key = "head") {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ProgressRow("Serial number recorded", Ratio(withSerial, devices.size))
                    Hint(
                        "For each device: make, model and serial number (type it, scan its barcode, or photograph the label), the receipt, " +
                            "and what travels with it. Insurers and the police ask for serial numbers; warranties need the receipt."
                    )
                }
            }
            if (devices.isEmpty()) item(key = "empty") {
                EmptyHint("No devices yet. Add a TV, computer or appliance in the walk-through; anything with a name like that shows up here. You can also switch \"It has a make, model and serial number\" on for any item.")
            }
            items(devices, key = { it.id }) { d ->
                Row(
                    Modifier.fillMaxWidth().clickable { nav.open("item/${d.id}") }.padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OwnerThumb(d.id, Modifier.size(56.dp))
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(d.title(), style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        val mm = d.makeModel()
                        if (mm.isNotBlank()) Hint(mm)
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                            when {
                                d.serial.isNotBlank() -> Hint("S/N ${d.serial}")
                                data.photosOf(d.id, PhotoKind.SERIAL).isNotEmpty() -> Hint("Serial label photographed")
                                else -> Flag("No serial number", error = true)
                            }
                            if (data.photosOf(d.id, PhotoKind.RECEIPT).isEmpty()) Flag("No receipt")
                            if (d.accessories.isNotBlank() && !d.accessoriesPacked) Flag("Accessories loose")
                        }
                        d.warrantyDay?.let { Hint("Warranty until ${fmtDay(it)}" + if (it < today()) " (ended)" else "") }
                    }
                }
                HorizontalDivider()
            }
        }
    }
}
