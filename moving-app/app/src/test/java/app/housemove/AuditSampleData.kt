package app.housemove

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import app.housemove.data.*
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The data the audit checks, the picture sheets and the role walk run on. It is the only
 * file of theirs that knows the data model, so it is the one to touch when the model changes.
 *
 *  - [phones] is the mid-move sample: the same move on an Organiser's, a Decider's and a Helper's phone.
 *  - [alone] is that move with nobody else on it.
 *  - [largeMoveJson] is a big move written as a saved file, for the speed check.
 */
internal object AuditSampleData {

    /** Says which sample the screens were opened with; printed in the report. */
    const val source: String = "AuditSampleData.phones(), the same data as the picture sheets"

    /**
     * Every screen the picture sheets show, by route. The checks open these too, so a
     * screen added here is checked as well.
     */
    val ROUTES = listOf(
        "home", "overview", "items", "item/wardrobe", "item/fridge", "item/sofa", "walk", "triage", "plan", "disposal", "electronics",
        "boxes", "box/box1", "box/box2", "box/box5", "pack", "labels", "lists", "packorder", "tasks",
        "moveday", "load", "unload", "reconcile", "sweep", "records/ORIGIN", "unpack", "budget", "reports",
        "rooms", "more", "sync", "settings", "search", "scan",
        "people", "people/add", "approvals", "activity",
    )

    /** The same move on three phones: the Organiser's (Sam), a Decider's (Priya) and a Helper's (Tom). */
    class Phones(val organiser: Store, val decider: Store, val helper: Store)

    /** A stand-in photograph: a coloured room with an object in it, different for every seed. */
    private fun picture(file: File, seed: Int) {
        val w = 480
        val h = 360
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val hues = listOf(0xFFB9C7D6, 0xFFD9C6A5, 0xFFC7D8C0, 0xFFE2C4C0, 0xFFCFC7E0, 0xFFE6DDB8)
        val objs = listOf(0xFF6B4F3A, 0xFF2F4858, 0xFF8A3B2E, 0xFF3F6B4E, 0xFF55407A, 0xFF7A6A2E)
        c.drawColor(hues[seed % hues.size].toInt())
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.color = 0x33000000
        c.drawRect(0f, h * 0.72f, w.toFloat(), h.toFloat(), p)
        p.color = objs[(seed / 2) % objs.size].toInt()
        val ow = w * (0.35f + (seed % 3) * 0.1f)
        val oh = h * (0.4f + (seed % 4) * 0.08f)
        c.drawRoundRect(RectF((w - ow) / 2, h * 0.8f - oh, (w + ow) / 2, h * 0.8f), 14f, 14f, p)
        p.color = 0x22FFFFFF
        c.drawRoundRect(RectF((w - ow) / 2 + 12, h * 0.8f - oh + 12, (w + ow) / 2 - 12, h * 0.8f - oh * 0.55f), 8f, 8f, p)
        file.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 85, it) }
    }

    /** One phone with a move that is mid-way through: rooms, furniture, boxes, tasks, costs, photos. Nobody else is on it. */
    fun alone(dir: File): Store {
        val s = Store(dir)
        var n = 0
        fun photo(owner: String, kind: PhotoKind) {
            val f = s.newPhotoFile()
            picture(f, n++)
            s.addPhoto(owner, kind, f)
        }
        s.setMove { it.copy(name = "Maple Road move", moveDay = today() + 9, setupDone = true, currency = "£") }
        s.setDevice { it.copy(name = "Sam", letter = "S") }
        val dKitchen = s.createRoom(Home.DEST, "Kitchen").copy(doorWidthCm = 76.0, floor = "Ground floor").also { s.put(it) }
        val dBed = s.createRoom(Home.DEST, "Main bedroom").copy(doorWidthCm = 70.0, floor = "Upstairs, first left").also { s.put(it) }
        val dLiving = s.createRoom(Home.DEST, "Living room").copy(doorWidthCm = 80.0).also { s.put(it) }
        val dOffice = s.createRoom(Home.DEST, "Office")
        val kitchen = s.createRoom(Home.ORIGIN, "Kitchen").copy(mapsTo = dKitchen.id).also { s.put(it) }
        val bed = s.createRoom(Home.ORIGIN, "Main bedroom").copy(mapsTo = dBed.id, packed = true).also { s.put(it) }
        val living = s.createRoom(Home.ORIGIN, "Living room").copy(mapsTo = dLiving.id).also { s.put(it) }
        val loft = s.createRoom(Home.ORIGIN, "Loft")
        s.setDevice { it.copy(currentRoomId = kitchen.id) }
        val trip = Trip(id = newId(), name = "Trip 1", sort = 1).also { s.put(it) }
        s.setDevice { it.copy(currentTripId = trip.id) }

        s.put(Item(id = "wardrobe", name = "Oak wardrobe", originRoomId = bed.id, destRoomId = dBed.id, disposition = Disposition.MOVE,
            widthCm = 120.0, depthCm = 74.0, heightCm = 205.0, value = 450.0, twoPerson = true, label = "L010", prepEmptied = true, prepPhotos = true))
        s.put(Item(id = "fridge", name = "Fridge-freezer", originRoomId = kitchen.id, destRoomId = dKitchen.id, disposition = Disposition.MOVE,
            widthCm = 60.0, depthCm = 65.0, heightCm = 180.0, stage = Stage.LOADED, tripId = trip.id, make = "Bosch", model = "KGN36",
            serial = "FD9907-000123", keepUpright = true, accessories = "Shelves, ice tray"))
        s.put(Item(id = "tv", name = "Living room TV", originRoomId = living.id, destRoomId = dLiving.id, disposition = Disposition.MOVE, keepUpright = true, fragile = true))
        s.put(Item(id = "bed", name = "King bed", originRoomId = bed.id, destRoomId = dBed.id, disposition = Disposition.MOVE,
            needsDisassembly = true, firstNight = true, stage = Stage.DELIVERED, damaged = true, damageNote = "Scratch on headboard"))
        s.put(Item(id = "sofa", name = "Old sofa", originRoomId = living.id, disposition = Disposition.SELL, price = 80.0, listed = true, deadlineDay = today() + 3))
        s.put(Item(id = "books", name = "Bookcase", originRoomId = living.id, disposition = Disposition.DONATE))
        s.put(Item(id = "lamp", name = "", originRoomId = loft.id))
        s.put(Item(id = "desk", name = "Desk", originRoomId = bed.id, disposition = Disposition.MOVE))
        s.put(Item(id = "table", name = "Dining table", originRoomId = kitchen.id, destRoomId = dKitchen.id, disposition = Disposition.MOVE, needsDisassembly = true))
        s.put(Item(id = "bike", name = "Exercise bike", originRoomId = loft.id, disposition = Disposition.BIN))
        for (id in listOf("wardrobe", "fridge", "tv", "bed", "sofa", "books", "lamp", "table")) photo(id, PhotoKind.GENERAL)
        photo("wardrobe", PhotoKind.CONDITION); photo("bed", PhotoKind.DAMAGE)

        s.put(Box(id = "box1", label = "L001", originRoomId = kitchen.id, destRoomId = dKitchen.id, contents = "Kettle, mugs, tea, cutlery",
            priority = Priority.FIRST_NIGHT, sealed = true, fragile = true))
        s.put(Box(id = "box2", label = "L002", originRoomId = kitchen.id, destRoomId = dKitchen.id, contents = "Pans and baking trays", heavy = true))
        s.put(Box(id = "box3", label = "S1", originRoomId = bed.id, destRoomId = dBed.id, contents = "Bedding", sealed = true,
            stage = Stage.LOADED, tripId = trip.id, priority = Priority.FIRST_NIGHT))
        s.put(Box(id = "box4", label = "L004", originRoomId = bed.id, destRoomId = dBed.id, contents = "Winter clothes", sealed = true, stage = Stage.DELIVERED))
        s.put(Box(id = "box5", label = "L005", originRoomId = living.id, destRoomId = dLiving.id, contents = "Books", sealed = true, stage = Stage.DONE))
        s.put(Box(id = "box6", label = "L006", originRoomId = loft.id, contents = "Passports, documents", travelsWithMe = true, sealed = true))
        s.put(Box(id = "box7", label = "L007", originRoomId = living.id, destRoomId = dOffice.id, contents = "Cables, router, chargers", thisWayUp = true))
        for (id in listOf("box1", "box2", "box4", "box5", "box7")) photo(id, PhotoKind.CONTENTS)
        s.scanInto("box5", "9780140328721", title = "Fantastic Mr. Fox", author = "Roald Dahl")
        s.scanInto("box5", "", title = "Photo albums")

        seedTasks().forEachIndexed { i, t -> s.put(if (i < 14) t.copy(done = true) else t) }
        s.put(Expense(id = "e1", title = "Van hire", amount = 180.0, category = "Van", day = today(), planned = true))
        s.put(Expense(id = "e2", title = "Boxes and tape", amount = 62.5, category = "Supplies", day = today() - 4))
        s.put(Supply(id = "s1", name = "Medium boxes", need = 30, have = 12))
        s.put(Supply(id = "s2", name = "Packing tape rolls", need = 4, have = 4))
        s.put(Reading(id = "r1", home = Home.ORIGIN, kind = "Electricity", value = "48213", day = today()))
        s.put(Item(id = "mirror", name = "Hall mirror", originRoomId = living.id))
        photo("mirror", PhotoKind.GENERAL)
        s.flush()
        return s
    }

    /** Someone added to [host]'s move whose phone has joined. Returns that phone. */
    private fun joined(host: Store, dir: File, name: String, role: Role): Store {
        val p = host.addPerson(name, role).valueOrNull()!!
        val phone = Store(dir)
        phone.joinAs(p.id, host.data.value.visibleTo(role))
        host.mergeIn(phone.data.value)
        return phone
    }

    /**
     * The sample move with people on it: Sam organises, Priya may suggest, Tom helps, Gran
     * has not joined yet and Alex was removed. Two of their suggestions are already decided
     * (one approved, one declined with a reason) and three still wait. [newFolder] gives an
     * empty directory for each phone.
     */
    fun phones(newFolder: () -> File): Phones {
        val s = alone(newFolder())
        val office = s.data.value.roomsOf(Home.DEST).first { it.name == "Office" }
        val priya = joined(s, newFolder(), "Priya", Role.DECIDER)
        val tom = joined(s, newFolder(), "Tom", Role.HELPER)
        s.addPerson("Gran", Role.HELPER)
        s.addPerson("Alex", Role.HELPER).valueOrNull()?.let { s.removePerson(it.id) }
        val toOffice = priya.suggestRoom("tv", office.id).valueOrNull()!!
        val donateBike = priya.suggestDecision("bike", Disposition.DONATE).valueOrNull()!!
        priya.suggestDecision("table", Disposition.SELL, 40.0, note = "Too big for the new kitchen")
        priya.suggestCost(Expense(id = "e3", title = "20 more boxes", amount = 35.0, category = "Supplies", day = today()))
        tom.ask("mirror", note = "Is this coming with us?")
        for (phone in listOf(priya, tom)) s.mergeIn(phone.data.value)
        s.approve(toOffice.id)
        s.decline(donateBike.id, "Too worn to give away")
        for (phone in listOf(priya, tom)) {
            phone.mergeIn(s.data.value.visibleTo(phone.myRole))
            s.photoDir.listFiles()?.forEach { f -> f.copyTo(File(phone.photoDir, f.name), overwrite = true) }
            phone.flush()
        }
        s.flush()
        return Phones(s, priya, tom)
    }

    /** The Organiser's phone of the sample move, for checks that look at one phone only. */
    fun seeded(tmp: TemporaryFolder): Store = phones { tmp.newFolder() }.organiser

    // ---- a large move ------------------------------------------------------------

    /** A word that many records of the large move contain; the search timing looks for it. */
    const val COMMON_WORD = "lamp"

    /** Screens of single records of the large move, for the speed check. */
    val LARGE_EXAMPLES = listOf("item/item0", "box/box0")

    /** How many records of each kind the large move holds. */
    val LARGE = mapOf("rooms" to 40, "items" to 2000, "boxes" to 300, "entries" to 3000, "photos" to 1500, "tasks" to 200)

    private val THINGS = listOf(
        "lamp", "chair", "table", "bookcase", "mirror", "rug", "desk", "sofa", "wardrobe", "chest of drawers",
        "monitor", "speaker", "printer", "heater", "fan", "toolbox", "ladder", "bike", "suitcase", "picture frame",
    )
    private val SORTS = listOf("Oak", "White", "Black", "Old", "Small", "Large", "Folding", "Glass", "Metal", "Blue")
    private val ROOMS = listOf(
        "Kitchen", "Living room", "Main bedroom", "Bedroom 2", "Bedroom 3", "Bathroom", "Hall", "Office", "Loft", "Garage",
        "Shed", "Dining room", "Utility room", "Landing", "Cellar", "Porch", "Playroom", "Guest room", "Study", "Conservatory",
    )
    private val STAGES = listOf("AT_ORIGIN", "AT_ORIGIN", "LOADED", "DELIVERED", "DONE")
    private val DISPOSITIONS = listOf("MOVE", "MOVE", "MOVE", "MOVE", "MOVE", "MOVE", "UNDECIDED", "SELL", "DONATE", "BIN")

    /**
     * The large move as the text of a saved data file (the layout version 1.3 writes).
     * It is written as text rather than built from the model classes so that it keeps
     * working when records gain fields: it is then simply an older saved file being opened.
     */
    fun largeMoveJson(): String {
        val day = today()
        val root = buildJsonObject {
            put("version", 3)
            putJsonObject("move") {
                put("name", "Big house move"); put("moveDay", day + 12); put("setupDone", true); put("currency", "£")
            }
            putJsonArray("rooms") {
                for (i in 0 until LARGE.getValue("rooms")) addJsonObject {
                    val dest = i % 2 == 1
                    put("id", "room$i"); put("home", if (dest) "DEST" else "ORIGIN"); put("name", ROOMS[i / 2 % ROOMS.size])
                    put("code", "R$i"); put("color", i / 2); put("sort", i)
                    if (dest) put("doorWidthCm", 70.0 + i % 4 * 5) else put("mapsTo", "room${i + 1}")
                    if (!dest && i % 6 == 0) put("packed", true)
                }
            }
            putJsonArray("trips") {
                for (i in 1..3) addJsonObject { put("id", "trip$i"); put("name", "Trip $i"); put("sort", i) }
            }
            putJsonArray("items") {
                for (i in 0 until LARGE.getValue("items")) addJsonObject {
                    val room = i % 20 * 2
                    val stage = STAGES[i % STAGES.size]
                    val disposition = DISPOSITIONS[i % DISPOSITIONS.size]
                    put("id", "item$i"); put("name", "${SORTS[i / 20 % SORTS.size]} ${THINGS[i % THINGS.size]} ${i + 1}")
                    put("originRoomId", "room$room"); put("disposition", disposition)
                    if (disposition == "MOVE") {
                        if (i % 9 != 0) put("destRoomId", "room${room + 1}")
                        put("stage", stage)
                        if (stage != "AT_ORIGIN") put("tripId", "trip${i % 3 + 1}")
                    }
                    if (i % 3 == 0) { put("widthCm", 40.0 + i % 90); put("depthCm", 30.0 + i % 50); put("heightCm", 50.0 + i % 160) }
                    if (i % 11 == 0) put("fragile", true)
                    if (i % 13 == 0) put("twoPerson", true)
                    if (i % 17 == 0) { put("make", "Acme"); put("model", "M${i % 300}"); put("serial", "SN-${100000 + i}") }
                    if (i % 25 == 0) put("label", "L${1000 + i}")
                    if (i % 40 == 0) put("notes", "Wrap in a blanket and keep the screws in a bag")
                    put("createdAt", 1_700_000_000_000 + i); put("updatedAt", 1_700_000_000_000 + i)
                }
            }
            putJsonArray("boxes") {
                for (i in 0 until LARGE.getValue("boxes")) addJsonObject {
                    val room = i % 20 * 2
                    val stage = STAGES[i % STAGES.size]
                    put("id", "box$i"); put("label", "L${(i + 1).toString().padStart(3, '0')}")
                    put("originRoomId", "room$room")
                    if (i % 12 != 0) put("destRoomId", "room${room + 1}")
                    if (i % 15 != 0) put("contents", "${THINGS[i % THINGS.size]}s, ${THINGS[(i + 7) % THINGS.size]} parts, cables")
                    put("stage", stage)
                    if (stage != "AT_ORIGIN") put("tripId", "trip${i % 3 + 1}")
                    if (i % 4 != 0) put("sealed", true)
                    if (i % 7 == 0) put("fragile", true)
                    if (i % 9 == 0) put("heavy", true)
                    if (i % 30 == 0) put("priority", "FIRST_NIGHT")
                    put("createdAt", 1_700_000_100_000 + i); put("updatedAt", 1_700_000_100_000 + i)
                }
            }
            putJsonArray("entries") {
                for (i in 0 until LARGE.getValue("entries")) addJsonObject {
                    put("id", "entry$i"); put("boxId", "box${i % LARGE.getValue("boxes")}")
                    if (i % 3 != 0) put("code", (9_780_000_000_000 + i).toString())
                    put("title", if (i % 10 == 0) "The ${THINGS[i % THINGS.size]} book ${i + 1}" else "Paperback number ${i + 1}")
                    if (i % 2 == 0) put("author", "A. Writer")
                    put("createdAt", 1_700_000_200_000 + i); put("updatedAt", 1_700_000_200_000 + i)
                }
            }
            putJsonArray("photos") {
                for (i in 0 until LARGE.getValue("photos")) addJsonObject {
                    put("id", "photo$i")
                    put("ownerId", if (i % 3 == 0) "box${i % LARGE.getValue("boxes")}" else "item${i % LARGE.getValue("items")}")
                    put("kind", if (i % 3 == 0) "CONTENTS" else "GENERAL")
                    put("file", "photo$i.jpg") // only the record: there is no picture file behind it
                    put("createdAt", 1_700_000_300_000 + i); put("updatedAt", 1_700_000_300_000 + i)
                }
            }
            putJsonArray("tasks") {
                for (i in 0 until LARGE.getValue("tasks")) addJsonObject {
                    put("id", "task$i"); put("title", "Task ${i + 1}: sort out the ${THINGS[i % THINGS.size]}")
                    put("offsetDays", i % 60 - 45); put("category", listOf("Paperwork", "Packing", "Van", "Utilities")[i % 4])
                    if (i % 3 == 0) put("done", true)
                    put("updatedAt", 1_700_000_400_000 + i)
                }
            }
        }
        return root.toString()
    }

    /**
     * How many records each list of a saved data file holds, read from the file itself.
     * The speed check prints this so the report says what was really loaded.
     */
    fun countRecords(savedFile: File): Map<String, Int> = try {
        val root = AppJson.parseToJsonElement(savedFile.readText()) as JsonObject
        root.entries.filter { it.value is JsonArray }.associate { it.key to (it.value as JsonArray).size }
    } catch (_: Exception) {
        emptyMap()
    }
}
