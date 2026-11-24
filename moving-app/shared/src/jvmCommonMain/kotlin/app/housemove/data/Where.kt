package app.housemove.data

/*
 * Where things are: at the old home, on the van (or with someone), at the storage unit, or at the new home.
 *
 * A thing's stage and trip are one protected part with its own stamp ([Item.whereBy], [Box.whereBy]): whoever
 * moved it last, on or back, decides where it is. So a phone that has not heard of the loading does not put a
 * box back at the old home by changing its note, and an explicit undo or a trip back from storage is not held
 * up by any "furthest along" rule.
 *
 * The storage unit is one place with no rooms. A trip goes there ([Trip.to] = [Place.STORAGE]); what is
 * unloaded from it is in storage ([Stage.STORED]), not delivered. A later trip from storage ([Trip.from] =
 * [Place.STORAGE]) takes it to the new home.
 */

const val STORAGE_TRIP_NAME = "To storage"
const val BRING_BACK_TRIP_NAME = "Bring back from storage"

/** Where a thing is now. Something inside a container is wherever the container is. */
fun AppData.placeOf(m: Movable): Place = carrierOf(m).stage.place()

fun Stage.place(): Place = when (this) {
    Stage.AT_ORIGIN -> Place.OLD_HOME
    Stage.LOADED -> Place.VAN
    Stage.STORED -> Place.STORAGE
    Stage.DELIVERED, Stage.DONE -> Place.NEW_HOME
}

/** Does this move use the storage unit: a trip to or from it, or anything in it? */
fun AppData.usesStorage(): Boolean =
    trips.any { !it.deleted && (it.to == Place.STORAGE || it.from == Place.STORAGE) } ||
        boxes.any { !it.deleted && it.stage == Stage.STORED } || items.any { !it.deleted && it.stage == Stage.STORED }

/** Trips to the storage unit. */
fun AppData.tripsToStorage(): List<Trip> = liveTrips.filter { it.to == Place.STORAGE }

/** Trips bringing things back from the storage unit. */
fun AppData.tripsFromStorage(): List<Trip> = liveTrips.filter { it.from == Place.STORAGE }

/** Things at the storage unit: containers not inside another, and things on their own. */
fun AppData.inStorage(): List<Movable> = movables().filter { it.stage == Stage.STORED }

/**
 * The stage something has once it is unloaded from [tripId]: in storage from a trip to the storage unit,
 * delivered otherwise (carried by hand included).
 */
fun AppData.arrivalStage(tripId: String?): Stage = if (trip(tripId)?.to == Place.STORAGE) Stage.STORED else Stage.DELIVERED

/** The stage something goes back to when its loading onto [tripId] is undone: where that trip starts. */
fun AppData.departureStage(tripId: String?): Stage = if (trip(tripId)?.from == Place.STORAGE) Stage.STORED else Stage.AT_ORIGIN

/**
 * Can [m] go onto [trip] now? Only from where the trip starts: the old home for an ordinary trip or one to the
 * storage unit, the storage unit for a trip back from it. Null when it can, else why not, in words.
 */
fun AppData.whyNotLoad(m: Movable, trip: Trip?): String? {
    val from = trip?.from ?: Place.OLD_HOME
    val c = carrierOf(m)
    return when {
        trip != null && trip.deleted -> "That trip is no longer here."
        from == Place.STORAGE && c.stage != Stage.STORED -> "${c.title} is not in storage."
        from == Place.OLD_HOME && c.stage != Stage.AT_ORIGIN -> if (c.stage == Stage.STORED) "${c.title} is in storage: bring it back on a trip from storage." else "${c.title} has already left ${move.originName.lowercase().ifBlank { "the old home" }}."
        else -> null
    }
}

/** What is waiting to be unpacked at the new home: delivered and not yet unpacked. Things in storage are not. */
fun AppData.toUnpack(): List<Movable> = movables().filter { it.stage == Stage.DELIVERED }

/** Where everything stands at the end of a trip, for the Reconcile screen. Each thing once: a container stands for what is in it. */
data class Reconcile(
    /** On the van (not carried by hand), on any trip. */
    val onVan: List<Movable>,
    /** Never loaded: still at the old home. */
    val atOldHome: List<Movable>,
    /** Taken along by hand and not yet marked arrived. */
    val withSomeone: List<Movable>,
    /** At the storage unit: neither missing nor delivered. */
    val inStorage: List<Movable>,
    /** Delivered or unpacked at the new home. */
    val arrived: List<Movable>,
    val damaged: List<Movable>,
) {
    /** Nothing left on the van or at the old home, and something has arrived (at the new home or in storage). */
    val allIn: Boolean get() = onVan.isEmpty() && atOldHome.isEmpty() && (arrived.isNotEmpty() || inStorage.isNotEmpty())
}

fun AppData.reconcile(): Reconcile {
    val all = movables().sortedBy { it.title }
    return Reconcile(
        onVan = all.filter { it.onTheVan() },
        atOldHome = all.filter { it.stage == Stage.AT_ORIGIN && !it.carriedByHand() },
        withSomeone = all.filter { it.carriedByHand() && it.stage == Stage.LOADED },
        inStorage = all.filter { it.stage == Stage.STORED },
        arrived = all.filter { it.stage == Stage.DELIVERED || it.stage == Stage.DONE },
        damaged = all.filter { it.damaged },
    )
}

/** The words the log uses when something moves from [from] to [to], or null for a move that is not logged. */
internal fun stageChangeVerb(from: Stage, to: Stage, byHand: Boolean): String? = when {
    to == from -> null
    to == Stage.LOADED && from == Stage.STORED -> "took out of storage"
    to == Stage.STORED && from == Stage.LOADED -> "put in storage"
    to.ordinal <= from.ordinal -> null
    to == Stage.LOADED -> if (byHand) "took along" else "loaded"
    to == Stage.STORED -> "put in storage"
    to == Stage.DELIVERED -> "delivered"
    to == Stage.DONE -> "unpacked"
    else -> null
}
