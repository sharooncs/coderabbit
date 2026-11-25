package app.housemove.ui.iphone

import app.housemove.data.ContainerKind
import app.housemove.data.Disposition
import app.housemove.data.Home
import app.housemove.data.Role
import app.housemove.data.Store

/**
 * A small made-up move for demo mode (the UI tests join it) and the screens' pictures: two homes with a few rooms
 * each, things decided and packed, a box inside a suitcase, a box on the van. Made through the Store, as an Organiser
 * on an Android phone makes a move, so it is what a real move looks like on the wire.
 */
object DemoMove {
    const val NAME = "Sample move"

    /** The person the demo invite is for: the iPhone joins as them. */
    const val JOINER = "Alex"

    /**
     * Fills [store], a new move on its own, and returns the id of [JOINER] (a Decider), whom the iPhone joins as.
     * The store's own person is Sam, the Organiser.
     */
    fun fill(store: Store): String {
        store.setDevice { it.copy(name = "Sam") }
        store.setMove { it.copy(name = NAME, setupDone = true) }

        // The new home first, so each old-home room can say where its things go.
        val kitchenNew = store.createRoom(Home.DEST, "Kitchen")
        val lounge = store.createRoom(Home.DEST, "Lounge")
        val bedroomNew = store.createRoom(Home.DEST, "Main bedroom")
        store.createRoom(Home.DEST, "Office")
        val kitchen = store.createRoom(Home.ORIGIN, "Kitchen").also { store.put(it.copy(mapsTo = kitchenNew.id)) }
        val living = store.createRoom(Home.ORIGIN, "Living room").also { store.put(it.copy(mapsTo = lounge.id)) }
        val bedroom = store.createRoom(Home.ORIGIN, "Bedroom").also { store.put(it.copy(mapsTo = bedroomNew.id)) }
        store.createRoom(Home.ORIGIN, "Hall")

        fun item(name: String, roomId: String, decision: Disposition, fragile: Boolean = false): String {
            val i = store.createItem(name, roomId)
            if (fragile) store.put(i.copy(fragile = true))
            store.decide(i.id, decision)
            return i.id
        }

        val kettle = item("Kettle", kitchen.id, Disposition.MOVE)
        val toaster = item("Toaster", kitchen.id, Disposition.MOVE)
        item("Microwave", kitchen.id, Disposition.DONATE)
        item("Sofa", living.id, Disposition.MOVE)
        item("Television", living.id, Disposition.MOVE, fragile = true)
        item("Armchair", living.id, Disposition.SELL)
        val bed = item("Bed frame", bedroom.id, Disposition.MOVE)
        item("Mirror", bedroom.id, Disposition.MOVE, fragile = true)
        item("Desk lamp", bedroom.id, Disposition.UNDECIDED)

        val plates = store.createBox("A1", kitchen.id).let { b ->
            store.put(b.copy(contents = "Plates, mugs, glasses", fragile = true)); b.id
        }
        store.createBox("A2", kitchen.id).let { b -> store.put(b.copy(contents = "Pans and baking trays", heavy = true)) }
        store.putInto(kettle, plates)
        store.putInto(toaster, plates)
        val clothes = store.createBox("B1", bedroom.id).let { b -> store.put(b.copy(contents = "Winter clothes")); b.id }
        val suitcase = store.createBox("B2", bedroom.id, ContainerKind.SUITCASE).let { b -> store.put(b.copy(contents = "Passports and chargers")); b.id }
        val wash = store.createBox("B3", bedroom.id, ContainerKind.BAG).let { b -> store.put(b.copy(contents = "Wash bag")); b.id }
        store.nest(wash, suitcase)
        store.createBox("C1", living.id).let { b -> store.put(b.copy(contents = "Books, board games")) }

        // Move day has begun: the bed and the winter clothes are on the van.
        store.addTrip().valueOrNull()?.let { trip -> store.loadOnto(trip.id, listOf(bed, clothes)) }

        val alex = store.addPerson(JOINER, Role.DECIDER).valueOrNull() ?: error("could not add $JOINER")
        store.flush()
        return alex.id
    }
}
