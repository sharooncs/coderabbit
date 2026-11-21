package app.housemove.data

/** Room names offered as one-tap chips during setup. */
val COMMON_ROOMS = listOf(
    "Kitchen", "Living room", "Dining room", "Main bedroom", "Bedroom 2", "Bedroom 3", "Bathroom",
    "Office", "Hall", "Utility", "Garage", "Loft", "Garden / shed",
)

data class TaskSeed(val offset: Int, val category: String, val title: String)

/** Self-move timeline, counted in days from move day. */
val SELF_MOVE_TASKS = listOf(
    TaskSeed(-42, "Plan", "Fix the move date and book time off"),
    TaskSeed(-42, "Plan", "Walk through every room and catalogue the big items"),
    TaskSeed(-42, "Declutter", "Triage every item: move, sell, donate, bin or store"),
    TaskSeed(-42, "Admin", "Give notice / confirm handover dates for both homes"),
    TaskSeed(-35, "Plan", "Measure the new home: doors, stairs, tight corners, key rooms"),
    TaskSeed(-35, "Plan", "Give every moving item a destination room and check it fits"),
    TaskSeed(-35, "Van", "Get van hire quotes for the estimated load"),
    TaskSeed(-35, "People", "Ask helpers and agree who does what on move day"),
    TaskSeed(-28, "Van", "Book the van: size, insurance, mileage, pickup and return times"),
    TaskSeed(-28, "Supplies", "Buy boxes, tape, wrap, blankets and straps"),
    TaskSeed(-28, "Supplies", "Print QR labels and door signs"),
    TaskSeed(-28, "Declutter", "List items for sale; book donation collection"),
    TaskSeed(-28, "Admin", "Arrange parking or lift access at both homes"),
    TaskSeed(-21, "Pack", "Pack loft, garage, shed and storage"),
    TaskSeed(-21, "Admin", "Arrange internet at the new home"),
    TaskSeed(-21, "Admin", "Arrange mail redirection"),
    TaskSeed(-21, "Admin", "Tell bank, employer, insurers, doctor, schools and subscriptions the new address"),
    TaskSeed(-14, "Pack", "Pack books, decor, off-season clothes and spare rooms"),
    TaskSeed(-14, "Admin", "Arrange electricity, gas and water for both homes"),
    TaskSeed(-14, "Declutter", "Finish selling, donating and tip runs"),
    TaskSeed(-14, "People", "Arrange care for children and pets on move day"),
    TaskSeed(-14, "Van", "Check equipment: dolly, straps, blankets, toolkit"),
    TaskSeed(-14, "Records", "Record make, model and serial number of TVs, computers and appliances; photograph receipts"),
    TaskSeed(-7, "Pack", "Pack most of the kitchen; keep only essentials out"),
    TaskSeed(-7, "Pack", "Take apart furniture you can live without"),
    TaskSeed(-7, "Van", "Confirm van, helpers and timings"),
    TaskSeed(-7, "Pack", "Run down the fridge and freezer"),
    TaskSeed(-7, "Admin", "Back up phones and computers"),
    TaskSeed(-3, "Pack", "Pack the \"Travels with me\" bag: documents, valuables, medication, chargers"),
    TaskSeed(-2, "Pack", "Defrost and dry the fridge-freezer"),
    TaskSeed(-2, "Pack", "Drain the washing machine and fit transit bolts"),
    TaskSeed(-2, "Pack", "Pack First night boxes: bedding, kettle, toiletries, tools, toilet roll"),
    TaskSeed(-1, "Pack", "Finish packing; seal and label every box"),
    TaskSeed(-1, "Pack", "Take apart beds (or first thing tomorrow)"),
    TaskSeed(-1, "Records", "Photograph the old home's condition room by room"),
    TaskSeed(-1, "People", "Sync all phones; charge phones and power banks"),
    TaskSeed(-1, "People", "Get water, snacks and cash for the day"),
    TaskSeed(0, "Move day", "Read the meters at the old home"),
    TaskSeed(0, "Move day", "Collect the van; check and photograph existing damage on it"),
    TaskSeed(0, "Move day", "Load out, scanning everything; first-night boxes go on last"),
    TaskSeed(0, "Move day", "Final sweep of every room, cupboard, loft and garden"),
    TaskSeed(0, "Move day", "Lock up and hand over the old keys"),
    TaskSeed(0, "Move day", "New home: photograph condition and read meters before unloading"),
    TaskSeed(0, "Move day", "Put up door signs"),
    TaskSeed(0, "Move day", "Unload, scanning everything to its room"),
    TaskSeed(0, "Move day", "Reconcile: nothing left on the van, damage noted"),
    TaskSeed(0, "Move day", "Refuel and return the van"),
    TaskSeed(1, "Settle", "Unpack First night and Day 1 boxes; rebuild beds"),
    TaskSeed(2, "Settle", "Connect appliances; let the fridge stand before switching on"),
    TaskSeed(7, "Settle", "Unpack Week 1 boxes"),
    TaskSeed(7, "Admin", "Update address on ID, licence, vehicle and voter records"),
    TaskSeed(7, "Admin", "Chase the deposit / final bills for the old home"),
    TaskSeed(14, "Settle", "Unpack the rest; flatten and pass on boxes"),
    TaskSeed(14, "Admin", "Export the home inventory for your insurer"),
)

/** Lines of the self-move list that are about hiring and driving a van yourself. */
private val VAN_ONLY = setOf("Van", "People")

/** With movers: the van, helper and equipment jobs go, and dealing with the firm comes in. */
val MOVERS_TASKS: List<TaskSeed> = SELF_MOVE_TASKS.filter { it.category !in VAN_ONLY && !Regex("\\bvan\\b", RegexOption.IGNORE_CASE).containsMatchIn(it.title) } + listOf(
    TaskSeed(-42, "Movers", "Ask three moving firms for quotes; send them the inventory from Reports"),
    TaskSeed(-35, "Movers", "Compare quotes: what is included, insurance cover, packing, storage"),
    TaskSeed(-28, "Movers", "Book the movers and confirm the date in writing"),
    TaskSeed(-21, "Movers", "Check what the movers will not carry (gas, paint, plants, valuables)"),
    TaskSeed(-7, "Movers", "Confirm arrival time, parking and access at both homes with the movers"),
    TaskSeed(-1, "Movers", "Sync all phones; get water and snacks for the crew"),
    TaskSeed(0, "Move day", "Walk the crew through both homes; point out fragile and keep-upright items"),
    TaskSeed(0, "Move day", "Scan everything onto the truck, or check it against the movers' list"),
    TaskSeed(0, "Move day", "Scan everything off; note any damage on the movers' paperwork before signing"),
    TaskSeed(3, "Admin", "Send the damage report to the movers within their claims deadline"),
)

val STORAGE_TASKS = listOf(
    TaskSeed(-21, "Plan", "Catalogue what is going into storage"),
    TaskSeed(-21, "Plan", "Choose a unit size from the load estimate; check access hours"),
    TaskSeed(-14, "Admin", "Book the unit and arrange insurance for what is stored"),
    TaskSeed(-14, "Supplies", "Buy boxes, tape, covers and a padlock"),
    TaskSeed(-7, "Pack", "Pack and label; note anything fragile or that must stay upright"),
    TaskSeed(-1, "Pack", "Photograph valuable items before they go in"),
    TaskSeed(0, "Move day", "Load, scanning everything; heaviest at the bottom, things you may need at the front"),
    TaskSeed(0, "Move day", "Photograph the packed unit and save a backup"),
    TaskSeed(1, "Admin", "Export the inventory and keep it with the storage contract"),
)

val INVENTORY_TASKS = listOf(
    TaskSeed(0, "List", "Photograph the big and valuable things in each room"),
    TaskSeed(0, "List", "Record make, model and serial number of electronics and appliances"),
    TaskSeed(0, "List", "Photograph receipts and warranty cards"),
    TaskSeed(7, "Admin", "Export the inventory and send a copy to yourself or your insurer"),
    TaskSeed(7, "Admin", "Save a backup somewhere off this phone"),
)

fun seedTasks(kind: MoveKind = MoveKind.SELF): List<Task> = when (kind) {
    MoveKind.SELF -> SELF_MOVE_TASKS
    MoveKind.MOVERS -> MOVERS_TASKS.sortedBy { it.offset }
    MoveKind.STORAGE -> STORAGE_TASKS
    MoveKind.INVENTORY -> INVENTORY_TASKS
}.map { Task(id = newId(), title = it.title, offsetDays = it.offset, category = it.category) }

/** Places people forget during the final sweep. */
val SWEEP_HINTS = listOf(
    "Inside every cupboard, drawer and wardrobe", "Top shelves and behind doors", "Loft, eaves and under stairs",
    "Washing machine, dishwasher, oven and fridge", "Curtains, blinds, rails, shelves, mirrors on walls",
    "Chargers and cables still plugged in", "Garden, shed, garage, bins and outside taps",
    "Mailbox, keys, remotes, manuals for the next occupant",
)

/** Packing order, least-used first. */
val PACK_ORDER = listOf(
    "Loft, garage, shed and storage" to "3 weeks before",
    "Books, decor, pictures, ornaments" to "2–3 weeks before",
    "Off-season clothes, spare bedding, guest rooms" to "2 weeks before",
    "Hobby gear, games, most toys" to "1–2 weeks before",
    "Most of the kitchen, dining and living room" to "1 week before",
    "Office and electronics (photograph the cables first)" to "3–4 days before",
    "Everyday clothes, bathroom, last kitchen items" to "1–2 days before",
    "Bedding, First night boxes, Travels-with-me bag" to "Last evening / move morning",
)

val EXPENSE_CATEGORIES = listOf("Van", "Fuel", "Supplies", "Helpers", "Storage", "Cleaning", "Fees", "Other")
val METER_KINDS = listOf("Electricity", "Gas", "Water", "Other")
