package app.housemove.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.housemove.data.*
import app.housemove.ui.*
import java.time.LocalDate
import java.time.format.DateTimeFormatter

private val MONTH_YEAR = DateTimeFormatter.ofPattern("MMM yyyy")

/** Every device and appliance with what has and hasn't been recorded about it. */
@Composable
fun ElectronicsScreen() {
    val nav = LocalNav.current
    val data = appData()
    val devices = data.liveItems.filter { it.isElectronic() }.sortedBy { it.title().lowercase() }
    val withSerial = devices.count { it.serial.isNotBlank() || data.photosOf(it.id, PhotoKind.SERIAL).isNotEmpty() }
    Screen("Electronics and serial numbers") {
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
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item(key = "head") {
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surface)
                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(14.dp)).padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Ring(Ratio(withSerial, devices.size), size = 64.dp, color = MaterialTheme.colorScheme.tertiary)
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("$withSerial of ${devices.size} with a serial number", style = MaterialTheme.typography.titleMedium)
                        Hint("Insurers and the police ask for it; warranties need the receipt.")
                    }
                }
            }
            items(devices, key = { it.id }) { d -> DeviceRow(d, data) { nav.open("item/${d.id}") } }
        }
    }
}

@Composable
private fun DeviceRow(d: Item, data: AppData, onOpen: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val serialPhoto = data.photosOf(d.id, PhotoKind.SERIAL).isNotEmpty()
    val recorded = d.serial.isNotBlank() || serialPhoto
    Row(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(scheme.surface)
            .border(1.dp, if (recorded) scheme.outlineVariant else scheme.error, RoundedCornerShape(14.dp))
            .clickable(onClick = onOpen)
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OwnerThumb(d.id, Modifier.size(64.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(d.title(), style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val mm = d.makeModel()
            if (mm.isNotBlank()) {
                Text(mm, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (recorded) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.CheckCircle, contentDescription = "Recorded", tint = scheme.tertiary, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(
                        if (d.serial.isNotBlank()) "S/N ${d.serial}" else "Serial label photographed",
                        style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (!recorded) IconFlag(Icons.Default.Warning, "No serial number", error = true)
                if (data.photosOf(d.id, PhotoKind.RECEIPT).isEmpty()) IconFlag(Icons.Default.Receipt, "No receipt")
                if (d.accessories.isNotBlank() && !d.accessoriesPacked) IconFlag(Icons.Default.Cable, "Accessories loose")
                d.warrantyDay?.let {
                    IconFlag(Icons.Default.VerifiedUser, if (it < today()) "Warranty ended" else "Warranty to ${MONTH_YEAR.format(LocalDate.ofEpochDay(it))}")
                }
            }
        }
        Icon(Icons.Default.ChevronRight, contentDescription = null, tint = scheme.onSurfaceVariant)
    }
}
