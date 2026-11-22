package app.housemove.data

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable

/**
 * Version of the saved data layout. Two phones must be on the same one to sync,
 * so fields added by a newer version are never dropped by an older one.
 */
const val SCHEMA = 4

/**
 * Every record carries an id, a last-modified time and a tombstone flag.
 * That is all two phones need to merge their copies without a server:
 * for each id the newer record wins, and deletions travel as tombstones.
 */
interface Rec {
    val id: String
    val updatedAt: Long
    val deleted: Boolean
}

/**
 * A record whose deletion is protected (items, containers, rooms and photos; see [mayDelete]).
 * [createdBy] is who added it, [hands] everyone who has changed it since (including them), and
 * [deletedBy] who deleted it and when, while [deleted] is set. Phones keep every hand either
 * of them knows of, so a change somebody made is never forgotten by a merge.
 */
interface Owned : Rec {
    val createdBy: String
    val hands: List<String>
    val deletedBy: Stamp
}

enum class Home { ORIGIN, DEST }

/** What the person is using the app for. Sets the wording, the checklist and which parts show. */
enum class MoveKind(val label: String) {
    SELF("Moving it myself"), MOVERS("Movers are doing it"), STORAGE("Putting things in storage"), INVENTORY("Just listing what I own")
}

/** Parts of the app that can be switched off when someone does not need them. */
enum class Part(val label: String) {
    TRIAGE("Deciding what to keep"), DEVICES("Devices and serial numbers"), PLAN("Room plan and fit check"),
    TIMELINE("Task timeline"), BUDGET("Supplies and costs"), BOXES("Packing boxes"), LABELS("Printed labels and door signs"),
    MOVE_DAY("Loading and unloading"), RECORDS("Photos and meters of both homes"), UNPACK("Unpacking"),
}

enum class Disposition(val label: String) {
    UNDECIDED("Undecided"), MOVE("Move"), SELL("Sell"), DONATE("Donate"), BIN("Bin"), STORE("Store")
}

/**
 * Where a box or item is in the journey. DONE = box unpacked / item placed. STORED = unloaded at the
 * storage unit: in storage, not delivered, until a trip brings it back to the new home.
 */
enum class Stage(val label: String) {
    AT_ORIGIN("At old home"), LOADED("On the van"), STORED("In storage"), DELIVERED("Delivered"), DONE("Done")
}

/**
 * The places a thing can be: the old home, the van (or someone's hands), the storage unit, the new home.
 * The storage unit is one place with no rooms; a trip goes from one place to another ([Trip.from], [Trip.to]).
 * Where a thing is follows from its [Stage] (see [AppData.placeOf]).
 */
enum class Place(val label: String) {
    OLD_HOME("Old home"), VAN("On the van"), STORAGE("Storage unit"), NEW_HOME("New home")
}

enum class Priority(val label: String) {
    FIRST_NIGHT("First night"), DAY_1("Day 1"), WEEK_1("Week 1"), WHENEVER("Whenever")
}

enum class BoxSize(val label: String, val m3: Double) {
    S("Small", 0.035), M("Medium", 0.06), L("Large", 0.10), XL("Extra large", 0.15)
}

/** What someone may do in a move. See [can] for the table. */
enum class Role(val label: String) { ORGANISER("Organiser"), DECIDER("Decider"), HELPER("Helper") }

/**
 * Anything things are packed into. A [Box] record is used for all of them.
 * [m3] is a typical volume (a carton uses its [BoxSize] instead); [noun] is the
 * word used in titles ("Suitcase B2").
 */
enum class ContainerKind(val label: String, val noun: String, val m3: Double, val travelsWithMe: Boolean = false) {
    CARTON("Carton", "Box", 0.06),
    SUITCASE("Suitcase", "Suitcase", 0.09, travelsWithMe = true),
    BAG("Bag", "Bag", 0.05),
    CRATE("Crate or tub", "Crate", 0.07),
    WARDROBE("Wardrobe box", "Wardrobe box", 0.30),
    OTHER("Other", "Container", 0.06),
}

/** What a suggestion is about. */
enum class ProposalKind(val label: String) {
    ITEM_DECISION("Keep, sell, donate, bin or store"), ITEM_ROOM("Which room it goes to"),
    COST("A cost"), TASK("A task"), QUESTION("A question")
}

enum class ProposalState(val label: String) {
    WAITING("Waiting"), APPROVED("Approved"), DECLINED("Declined"),

    /** An Organiser changed the same thing directly while the suggestion waited. */
    OVERTAKEN("Changed meanwhile"),

    /** Another suggestion for the same thing was approved instead. */
    NOT_CHOSEN("Another one was chosen")
}

/**
 * Who last set a protected part of a record, and when. [by] is a person id, or
 * blank when it was set while the move had no Organiser (which includes all
 * data from before version 2.0). [sum] is a check value over what was set, so
 * a change made without a new stamp is noticed when phones merge.
 */
@Serializable
data class Stamp(val by: String = "", val at: Long = 0, val sum: String = "")

enum class PhotoKind(val label: String) {
    GENERAL("Photo"), CONTENTS("Contents"), CONDITION("Condition before"), DISASSEMBLY("Disassembly"),
    CABLES("Cables"), DAMAGE("Damage"), ROOM("Room"), METER("Meter"), RECEIPT("Receipt"),
    SERIAL("Serial label")
}

@Serializable
data class MoveInfo(
    val name: String = "",
    /** Move day as an epoch day, or null when not decided yet. */
    val moveDay: Long? = null,
    val originName: String = "Old home",
    val destName: String = "New home",
    val vanCapacityM3: Double = 15.0,
    val useInches: Boolean = false,
    val currency: String = "",
    /** Highest number printed on a QR label sheet so far, so the next sheet continues from it. */
    val labelsPrinted: Int = 0,
    val kind: MoveKind = MoveKind.SELF,
    /** Names of the [Part]s switched off. Everything is on unless listed, so new parts appear by default. */
    val off: List<String> = emptyList(),
    val setupDone: Boolean = false,
    val updatedAt: Long = 0,
    /** Who last changed these settings (everything here except [labelsPrinted]). */
    val changedBy: Stamp = Stamp(),
)

@Serializable
data class Room(
    override val id: String,
    val home: Home,
    val name: String,
    /** Short code printed on labels and door signs, e.g. "KIT". */
    val code: String = "",
    /** Index into the room colour palette. */
    val color: Int = 0,
    val floor: String = "",
    /** For old-home rooms: the new-home room its contents go to by default. */
    val mapsTo: String? = null,
    /** For new-home rooms: narrowest opening on the way in, in cm. */
    val doorWidthCm: Double? = null,
    /** Old-home room: packing finished. */
    val packed: Boolean = false,
    /** Old-home room: final sweep done. */
    val swept: Boolean = false,
    val sort: Int = 0,
    /** Who added it. Blank for rooms from before version 2.0, or added while the move had no Organiser. */
    override val createdBy: String = "",
    override val hands: List<String> = emptyList(),
    override val deletedBy: Stamp = Stamp(),
    override val updatedAt: Long = 0,
    override val deleted: Boolean = false,
) : Owned

/** A thing catalogued on its own: furniture, appliances, electronics, valuables. */
@Serializable
data class Item(
    override val id: String,
    val name: String = "",
    /** Code of the QR label stuck on it, if any. */
    val label: String? = null,
    val originRoomId: String? = null,
    val destRoomId: String? = null,
    val disposition: Disposition = Disposition.UNDECIDED,
    /** Sell: listed for sale. */
    val listed: Boolean = false,
    /** Sell / donate / bin: it has actually gone. */
    val gone: Boolean = false,
    /** Moved to [Hidden.price] in 2.1. Only in data saved by 2.0 (moved out as it loads) and in [AppData.filledIn]. */
    val price: Double? = null,
    val deadlineDay: Long? = null,
    val widthCm: Double? = null,
    val depthCm: Double? = null,
    val heightCm: Double? = null,
    /** Moved to [Hidden.value] in 2.1, like [price]. */
    val value: Double? = null,
    val fragile: Boolean = false,
    val twoPerson: Boolean = false,
    val needsDisassembly: Boolean = false,
    val prepEmptied: Boolean = false,
    val prepPhotos: Boolean = false,
    val prepDisassembled: Boolean = false,
    val prepHardware: Boolean = false,
    val prepCables: Boolean = false,
    val prepWrapped: Boolean = false,
    val travelsWithMe: Boolean = false,
    val firstNight: Boolean = false,
    val hazardous: Boolean = false,
    val stage: Stage = Stage.AT_ORIGIN,
    val tripId: String? = null,
    val reassembled: Boolean = false,
    /**
     * Worked out from its [Damage] reports: true while any is open, with their notes. Saving it through the
     * Store reports damage (true), or clears the open reports (false, Organisers only).
     */
    val damaged: Boolean = false,
    val damageNote: String = "",
    val notes: String = "",
    /** Has a make, model and serial number worth recording. Null means "judge by its name". */
    val electronic: Boolean? = null,
    /** [make], [model], [serial], [boughtDay] and [warrantyDay] moved to [Hidden] in 2.1, like [price]. */
    val make: String = "",
    val model: String = "",
    val serial: String = "",
    val boughtDay: Long? = null,
    val warrantyDay: Long? = null,
    /** What travels with it: remote, power lead, stand, screws, cables. */
    val accessories: String = "",
    val accessoriesPacked: Boolean = false,
    /** Label of the box the accessories are in, when they are not taped to the item. */
    val accessoryBox: String = "",
    /** Must travel standing up (TVs, fridges). Shown when loading and unloading. */
    val keepUpright: Boolean = false,
    val prepCablesLabelled: Boolean = false,
    val prepSignedOut: Boolean = false,
    /** The container it is packed in, if any. While inside it travels with that container. */
    val boxId: String? = null,
    /** Moved to [Hidden.soldFor] in 2.1, like [price]. */
    val soldFor: Double? = null,
    /** Who decided [disposition] and [deadlineDay]. (The asking price has its own stamp, [Hidden.priceBy].) */
    val decisionBy: Stamp = Stamp(),
    /** Who chose [destRoomId]. */
    val roomBy: Stamp = Stamp(),
    /** Who carried the decision out: [listed], [gone]. (What it sold for has its own stamp, [Hidden.saleBy].) */
    val outcomeBy: Stamp = Stamp(),
    /** Who last moved it on, or back: [stage] and [tripId] together. */
    val whereBy: Stamp = Stamp(),
    /** Sell or donate: the person who is to hand it over to the buyer or the charity. */
    val handoverTo: String? = null,
    /** Sell: the buyer has paid already, so nothing is to be collected. Every Helper sees "Paid" or "Not paid". */
    val paid: Boolean = false,
    /** Who arranged the hand-over: [handoverTo] and [paid]. */
    val handoverBy: Stamp = Stamp(),
    /** Sell or donate: it has been handed over (collected by the buyer or the charity). Helpers may set it. */
    val handedOver: Boolean = false,
    val handedOverBy: Stamp = Stamp(),
    val createdAt: Long = 0,
    /** Who added it. Blank for items from before version 2.0, or added while the move had no Organiser. */
    override val createdBy: String = "",
    override val hands: List<String> = emptyList(),
    override val deletedBy: Stamp = Stamp(),
    override val updatedAt: Long = 0,
    override val deleted: Boolean = false,
) : Owned

/** One thing put in a box and recorded by its own barcode: a book, a game, a boxed product. */
@Serializable
data class BoxEntry(
    override val id: String,
    val boxId: String,
    /** The barcode number as printed, e.g. an ISBN. Empty when added by typing a title. */
    val code: String = "",
    val title: String = "",
    val author: String = "",
    val createdAt: Long = 0,
    /** Everyone who has put it in a container or changed it (see [Owned.hands]). */
    val hands: List<String> = emptyList(),
    override val updatedAt: Long = 0,
    override val deleted: Boolean = false,
) : Rec

@Serializable
data class Box(
    override val id: String,
    /** Code on the label, e.g. "L042" (printed) or "A7" (handwritten). */
    val label: String,
    val originRoomId: String? = null,
    val destRoomId: String? = null,
    /**
     * Worked out from its [ContentsLine]s, joined with commas. Saving a different text through the Store
     * adds and removes lines, so two phones changing it apart never lose or double a line.
     */
    val contents: String = "",
    val size: BoxSize = BoxSize.M,
    val fragile: Boolean = false,
    val heavy: Boolean = false,
    val thisWayUp: Boolean = false,
    val priority: Priority = Priority.WHENEVER,
    val sealed: Boolean = false,
    val stage: Stage = Stage.AT_ORIGIN,
    val travelsWithMe: Boolean = false,
    val hazardous: Boolean = false,
    val tripId: String? = null,
    /** Worked out from its [Damage] reports, as for [Item.damaged]. */
    val damaged: Boolean = false,
    val damageNote: String = "",
    val packedBy: String = "",
    val notes: String = "",
    /** What kind of container this is. A carton unless said otherwise. */
    val kind: ContainerKind = ContainerKind.CARTON,
    /** One of [BOX_CATEGORIES], or blank. */
    val category: String = "",
    /** Optional name shown instead of "Box L001", e.g. "Kitchen box 4". */
    val name: String = "",
    /** The container this one is inside, if any. One level only: see [parentOf]. */
    val parentId: String? = null,
    /** Who last moved it on, or back: [stage] and [tripId] together. */
    val whereBy: Stamp = Stamp(),
    val createdAt: Long = 0,
    /** Who added it. Blank for containers from before version 2.0, or added while the move had no Organiser. */
    override val createdBy: String = "",
    override val hands: List<String> = emptyList(),
    override val deletedBy: Stamp = Stamp(),
    override val updatedAt: Long = 0,
    override val deleted: Boolean = false,
) : Owned

@Serializable
data class Photo(
    override val id: String,
    /** Id of the box, item, room, expense or reading it belongs to, or "home:ORIGIN" / "home:DEST". */
    val ownerId: String,
    val kind: PhotoKind = PhotoKind.GENERAL,
    /** File name inside the photo directory. */
    val file: String,
    val note: String = "",
    /** Person who took or attached it. Blank for photos from before version 2.0. */
    val by: String = "",
    val createdAt: Long = 0,
    override val hands: List<String> = emptyList(),
    override val deletedBy: Stamp = Stamp(),
    override val updatedAt: Long = 0,
    override val deleted: Boolean = false,
) : Owned {
    override val createdBy: String get() = by
}

@Serializable
data class Task(
    override val id: String,
    val title: String,
    /** Days relative to move day (negative = before). Used when [dueDay] is null. */
    val offsetDays: Int? = null,
    /** Explicit due date as an epoch day. */
    val dueDay: Long? = null,
    val category: String = "",
    val done: Boolean = false,
    val notes: String = "",
    /** Person who is to do it, if anyone in particular. They may tick it off whatever their role. */
    val assignedTo: String? = null,
    /** Who created or last changed the task itself (everything except [done]). */
    val planBy: Stamp = Stamp(),
    /** Who ticked or unticked it. */
    val doneBy: Stamp = Stamp(),
    override val updatedAt: Long = 0,
    override val deleted: Boolean = false,
) : Rec

@Serializable
data class Expense(
    override val id: String,
    val title: String = "",
    val amount: Double = 0.0,
    val category: String = "",
    val day: Long = 0,
    /** True for a quote or planned cost, false once actually paid. */
    val planned: Boolean = false,
    /** Who created or last changed the cost itself (everything except [planned]). */
    val costBy: Stamp = Stamp(),
    /** Who marked it paid or not paid. */
    val paidBy: Stamp = Stamp(),
    override val updatedAt: Long = 0,
    override val deleted: Boolean = false,
) : Rec

@Serializable
data class Supply(
    override val id: String,
    val name: String,
    val need: Int = 0,
    val have: Int = 0,
    override val updatedAt: Long = 0,
    override val deleted: Boolean = false,
) : Rec

/** One vehicle run: from the old home to the new one, to the storage unit, or back from it. */
@Serializable
data class Trip(
    override val id: String,
    val name: String,
    val sort: Int = 0,
    val from: Place = Place.OLD_HOME,
    val to: Place = Place.NEW_HOME,
    override val updatedAt: Long = 0,
    override val deleted: Boolean = false,
) : Rec

/** A meter reading at either home. */
@Serializable
data class Reading(
    override val id: String,
    val home: Home,
    val kind: String = "Electricity",
    val value: String = "",
    val day: Long = 0,
    override val updatedAt: Long = 0,
    override val deleted: Boolean = false,
) : Rec

/** One change of someone's role, or their removal from the move. [by] is blank when there was no Organiser to ask. */
@Serializable
data class Grant(val role: Role = Role.HELPER, val removed: Boolean = false, val at: Long = 0, val by: String = "")

/**
 * Someone taking part in the move. [role] and [removed] are worked out from
 * [grants] (see [Roles]), so they cannot be changed by rewriting the two fields.
 */
@Serializable
data class Person(
    override val id: String,
    val name: String = "",
    val role: Role = Role.HELPER,
    /** Prefix for this person's handwritten box codes. No two people share one. */
    val letter: String = "",
    /** When their phone joined. 0 while they have been added but have not joined yet. */
    val joinedAt: Long = 0,
    val removed: Boolean = false,
    /** Every role change and removal, oldest first. */
    val grants: List<Grant> = emptyList(),
    override val updatedAt: Long = 0,
    /** Not used: people are removed, never deleted. */
    override val deleted: Boolean = false,
) : Rec

/**
 * A suggestion waiting for an Organiser, or a question about an item.
 * What is proposed never changes after it is made; only [state], [reason] and
 * [decided] do, and only an Organiser may set those.
 */
@Serializable
data class Proposal(
    override val id: String,
    val kind: ProposalKind = ProposalKind.QUESTION,
    /** The item, cost or task it is about. For a new cost or task: the id it gets when approved. */
    val targetId: String = "",
    /** Person who suggested or asked. */
    val by: String = "",
    val note: String = "",
    /** ITEM_DECISION: the decision proposed. */
    val disposition: Disposition? = null,
    /** ITEM_DECISION: asking price proposed, if any. */
    val price: Double? = null,
    /** ITEM_ROOM: the new-home room proposed. */
    val roomId: String? = null,
    /** COST: the cost as proposed. */
    val expense: Expense? = null,
    /** TASK: the task as proposed. */
    val task: Task? = null,
    /** A suggestion made as the answer to a QUESTION: that question's id. */
    val answers: String? = null,
    val state: ProposalState = ProposalState.WAITING,
    /** Why it was declined, or what the Organiser said when answering a question. */
    val reason: String = "",
    /** Who approved, declined or overtook it, and when. */
    val decided: Stamp = Stamp(),
    val createdAt: Long = 0,
    /**
     * A request to delete what [targetId] names (an item, container, room, photo, task or cost),
     * made by someone who may not delete it themselves. Its kind is [ProposalKind.QUESTION], so
     * anyone may make one; an Organiser approves (the thing is deleted) or declines it.
     */
    val deletes: Boolean = false,
    /**
     * Set when the person who made it withdrew it while it was still waiting: by them, when, and a
     * check value. A withdrawn suggestion is deleted (so it is in no list), and stays so on every
     * phone unless an Organiser approved or declined it first, which then stands.
     */
    val withdrawn: Stamp = Stamp(),
    override val updatedAt: Long = 0,
    override val deleted: Boolean = false,
) : Rec {
    val decidedBy: String get() = decided.by
    val decidedAt: Long get() = decided.at

    /** Withdrawn by the person who made it ([Store.withdraw]). */
    val isWithdrawn: Boolean get() = deleted && withdrawn.by.isNotEmpty() && withdrawn.by == by
}

/** One line of the shared log of who did what, e.g. "sealed L012". */
@Serializable
data class Activity(
    override val id: String,
    /** Person who did it. */
    val by: String = "",
    val at: Long = 0,
    /** Short and in the past tense, to read after the person's name. */
    val text: String = "",
    /** Id of the box, item, person or suggestion it was about, if any. */
    val refId: String? = null,
    override val updatedAt: Long = 0,
    override val deleted: Boolean = false,
) : Rec

/**
 * What Helpers are not sent about an item: what it is worth, its make, model, serial number and dates,
 * its asking price and what it sold for. One per item, with the item's id. Helpers never write it (a serial
 * number from a Helper comes in as a photo or a question). Each part has its own stamp (see Rules.kt).
 */
@Serializable
data class Hidden(
    override val id: String,
    val value: Double? = null,
    val make: String = "",
    val model: String = "",
    val serial: String = "",
    val boughtDay: Long? = null,
    val warrantyDay: Long? = null,
    /** Who last set [value], [make], [model], [serial], [boughtDay] and [warrantyDay]. */
    val detailsBy: Stamp = Stamp(),
    /** Sell: the asking price. Set with the decision. */
    val price: Double? = null,
    val priceBy: Stamp = Stamp(),
    /** Sell: what it actually went for. Set when the sale is carried out. */
    val soldFor: Double? = null,
    val saleBy: Stamp = Stamp(),
    override val updatedAt: Long = 0,
    override val deleted: Boolean = false,
) : Rec

/**
 * The amount to collect when a sold item is handed over, with the item's id. Sent to Organisers,
 * Deciders and [to], the person handing it over; to no other Helper.
 */
@Serializable
data class Collect(
    override val id: String,
    val to: String = "",
    val amount: Double? = null,
    /** Who arranged it: [to] and [amount]. */
    val by: Stamp = Stamp(),
    override val updatedAt: Long = 0,
    override val deleted: Boolean = false,
) : Rec

/**
 * One report of damage to a box or item. Each is cleared on its own, by an Organiser, so clearing one never
 * erases a report made meanwhile on another phone.
 */
@Serializable
data class Damage(
    override val id: String,
    /** The box or item it is about. */
    val thingId: String = "",
    val note: String = "",
    /** Who reported it, and when. Blank and 0 for damage recorded before 2.1. */
    val by: String = "",
    val at: Long = 0,
    val cleared: Boolean = false,
    /** Who cleared it, or, until then, who reported it. */
    val clearedBy: Stamp = Stamp(),
    override val updatedAt: Long = 0,
    override val deleted: Boolean = false,
) : Rec

/**
 * One line of what is in a box, e.g. "Plates". The id comes from the box and the words, so the same line
 * added on two phones is one line; a line removed stays removed (its tombstone is newer than any copy of it).
 */
@Serializable
data class ContentsLine(
    override val id: String,
    val boxId: String = "",
    val text: String = "",
    /** Order in the box: when it was added. */
    val sort: Long = 0,
    val by: String = "",
    /** For a line made from a box's 2.0 contents text: when that text was saved. 0 for lines added since. */
    val batch: Long = 0,
    override val updatedAt: Long = 0,
    override val deleted: Boolean = false,
) : Rec

/**
 * A change that arrived from a phone and was not accepted, kept for the Organisers: "Sam's phone tried to
 * mark Sofa sold; Sam is a Helper." It says nothing of amounts. Its id comes from what was tried, by whom and
 * when, so every phone that turns the change away makes the same one. An Organiser dismisses it (deleted).
 */
@Serializable
data class RefusedChange(
    override val id: String,
    /** Who made the change, and when they made it. */
    val by: String = "",
    val at: Long = 0,
    /** Which part: "decision", "room", "outcome", "price", "sale", "details", "cost", "paid", "task", "done", and so on. */
    val what: String = "",
    val targetId: String = "",
    /** What they tried, for after "tried to", with {} where the thing's name goes: "mark {} sold". */
    val tried: String = "",
    /** Their role when they made it, or null when they were not in the move then. */
    val role: Role? = null,
    override val updatedAt: Long = 0,
    override val deleted: Boolean = false,
) : Rec

/** The whole dataset. Lists include tombstones; use the `live*` views for display. */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class AppData(
    @EncodeDefault val version: Int = SCHEMA,
    val move: MoveInfo = MoveInfo(),
    val rooms: List<Room> = emptyList(),
    val items: List<Item> = emptyList(),
    val boxes: List<Box> = emptyList(),
    val photos: List<Photo> = emptyList(),
    val tasks: List<Task> = emptyList(),
    val expenses: List<Expense> = emptyList(),
    val supplies: List<Supply> = emptyList(),
    val trips: List<Trip> = emptyList(),
    val readings: List<Reading> = emptyList(),
    val entries: List<BoxEntry> = emptyList(),
    val people: List<Person> = emptyList(),
    val proposals: List<Proposal> = emptyList(),
    val activity: List<Activity> = emptyList(),
    val hidden: List<Hidden> = emptyList(),
    val collect: List<Collect> = emptyList(),
    val damage: List<Damage> = emptyList(),
    val lines: List<ContentsLine> = emptyList(),
    val refused: List<RefusedChange> = emptyList(),
) {
    /** Look-ups over the records that belong to items and boxes, made once per dataset. Not saved or compared. */
    internal val index: RecordIndex by lazy { RecordIndex(this) }

    /** This dataset as screens show it, with hidden details on the items ([filledIn]), made once per dataset. Not saved or compared. */
    internal val shown: AppData by lazy { fillHidden() }

    val liveRooms get() = rooms.filter { !it.deleted }.sortedWith(compareBy({ it.sort }, { it.name }))
    val liveItems get() = items.filter { !it.deleted }
    val liveBoxes get() = boxes.filter { !it.deleted }
    val livePhotos get() = photos.filter { !it.deleted }
    val liveTasks get() = tasks.filter { !it.deleted }
    val liveExpenses get() = expenses.filter { !it.deleted }
    val liveSupplies get() = supplies.filter { !it.deleted }
    val liveTrips get() = trips.filter { !it.deleted }.sortedWith(compareBy({ it.sort }, { it.name }))
    val liveReadings get() = readings.filter { !it.deleted }
    val liveEntries get() = entries.filter { !it.deleted }

    /** Everyone in the move who has not been removed, Organisers first. */
    val livePeople get() = people.filter { !it.removed }.sortedWith(compareBy({ it.role.ordinal }, { it.name.lowercase() }, { it.id }))
    val liveProposals get() = proposals.filter { !it.deleted }

    /** Things scanned into a box, newest first. */
    fun entriesOf(boxId: String) = entries.filter { !it.deleted && it.boxId == boxId }.sortedByDescending { it.createdAt }

    fun roomsOf(home: Home) = liveRooms.filter { it.home == home }
    fun room(id: String?) = if (id == null) null else rooms.firstOrNull { it.id == id && !it.deleted }
    fun item(id: String?) = if (id == null) null else items.firstOrNull { it.id == id && !it.deleted }
    fun box(id: String?) = if (id == null) null else boxes.firstOrNull { it.id == id && !it.deleted }
    fun trip(id: String?) = if (id == null) null else trips.firstOrNull { it.id == id && !it.deleted }
    fun task(id: String?) = if (id == null) null else tasks.firstOrNull { it.id == id && !it.deleted }
    fun expense(id: String?) = if (id == null) null else expenses.firstOrNull { it.id == id && !it.deleted }

    /** A person by id, including people who have been removed. */
    fun person(id: String?) = if (id.isNullOrEmpty()) null else people.firstOrNull { it.id == id }
    fun proposal(id: String?) = if (id == null) null else proposals.firstOrNull { it.id == id && !it.deleted }
    fun photosOf(ownerId: String, kind: PhotoKind? = null) =
        photos.filter { !it.deleted && it.ownerId == ownerId && (kind == null || it.kind == kind) }
            .sortedBy { it.createdAt }
}
