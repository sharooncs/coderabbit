package app.housemove

import app.housemove.data.*
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/** The stages on the way from the old home to the new one, in order. */
private val HOME_WAY = listOf(Stage.AT_ORIGIN, Stage.LOADED, Stage.DELIVERED, Stage.DONE)

/**
 * What one phone's owner does: one random, realistic action at a time, through
 * the same Store calls the screens use. Every phone tries everything, whatever
 * its role; the Store is what says no. Each action returns a line for the
 * trace that ends in what came of it.
 */
internal class Hands(private val p: Simulation.Phone) {
    private val sim get() = p.sim
    /** The move in use on the phone: Maple Road, or another of its moves. */
    private val s get() = p.current

    /** This phone's person in that move. */
    private val myId get() = s.device.value.personId
    private val r get() = p.rnd
    private val d: AppData get() = s.data.value

    private fun <T> pick(list: List<T>): T? = if (list.isEmpty()) null else list[r.nextInt(list.size)]
    private fun chance(percent: Int) = r.nextInt(100) < percent
    private fun id(x: String?) = x?.take(8) ?: "nothing"
    private fun no(why: String?) = Simulation.REFUSED + (why ?: "no reason given")
    private fun <T> said(o: Outcome<T>) = if (o.ok) "→ ok" else no(o.reason)
    private fun took(yes: Boolean, what: String) = if (yes) "→ ok" else no("A plain save left $what as it was.")

    private val things = listOf("Sofa", "TV", "Fridge", "Laptop", "Wardrobe", "Desk", "Lamp", "Mirror", "Washing machine", "Printer", "Rug", "Bike", "Guitar", "Table", "Armchair")
    private val stuff = listOf("plates", "books", "cables", "towels", "toys", "pans", "shoes", "tools", "glasses", "bedding", "paperwork")
    private val words = listOf("fragile", "heavy", "ask first", "from the loft", "scratched", "nearly new", "for the hall")
    private val isbns = listOf("9780140328721", "9780306406157", "9781861972712", "9780131103627")

    private val menu: List<Pair<Int, () -> String?>> = listOf(
        9 to ::addItem, 5 to ::editItem, 2 to ::deleteItem, 6 to ::photograph, 1 to ::deletePhoto,
        6 to ::decide, 3 to ::decideBySaving, 3 to ::planRoom,
        5 to ::suggestDecision, 2 to ::suggestRoom, 2 to ::suggestCost, 2 to ::suggestTask,
        8 to ::approve, 3 to ::decline, 3 to ::ask, 4 to ::answer, 5 to ::carryOut,
        9 to ::newContainer, 4 to ::editContainer, 8 to ::putIn, 3 to ::takeOut, 6 to ::nest, 2 to ::unNest,
        5 to ::seal, 8 to ::moveOn, 1 to ::bringBack, 3 to ::damage, 2 to ::deleteContainer,
        // 2.1: damage as reports of their own, the storage unit, hand-overs of what is sold or given away.
        3 to ::damageReport, 4 to ::storage, 3 to ::handover,
        3 to ::scan, 1 to ::deleteEntry,
        2 to ::newRoom, 2 to ::editRoom, 1 to ::deleteRoom,
        4 to ::addCost, 2 to ::editCost, 1 to ::deleteCost, 3 to ::pay, 2 to ::receipt,
        4 to ::addTask, 2 to ::editTask, 1 to ::deleteTask, 2 to ::assign, 5 to ::tick,
        1 to ::supplies, 1 to ::trip, 1 to ::reading,
        1 to ::addPerson, 1 to ::removePerson, 2 to ::restorePerson, 2 to ::changeRole, 2 to ::rename, 1 to ::changeLetter,
        2 to ::settings, 1 to ::printLabels,
        1 to ::backup, 1 to ::restore, 1 to ::tryErase,
        3 to ::openForLater,
        // Deleting by the rule (an own thing nobody else touched, or an Organiser's), asking for it, and taking a suggestion back.
        4 to ::deleteOwn, 3 to ::askToDelete, 3 to ::withdraw, 3 to ::saveOrSuggest,
    )
    private val total = menu.sumOf { it.first }

    fun one(): String {
        // An edit left open on a screen of another move went with that screen when this move was opened.
        if (p.pendingIn !== s) p.pending = null
        p.pending?.let { late ->
            if (chance(40)) {
                p.pending = null
                return late()
            }
        }
        repeat(30) {
            var n = r.nextInt(total)
            for ((weight, action) in menu) {
                if (n < weight) {
                    action()?.let { return it }
                    break
                }
                n -= weight
            }
        }
        return addItem()
    }

    // ---- items ---------------------------------------------------------------------------

    private fun addItem(): String {
        val room = pick(d.roomsOf(Home.ORIGIN))
        val item = s.createItem(pick(things)!! + " " + (1 + r.nextInt(99)), room?.id)
        if (chance(40)) {
            val label = if (chance(50)) handCode() else null
            val worth = r.nextInt(900).toDouble()
            s.put(d.item(item.id)!!.copy(fragile = chance(30), widthCm = 40.0 + r.nextInt(160), depthCm = 30.0 + r.nextInt(70), heightCm = 40.0 + r.nextInt(150), label = label))
            s.setDetails(item.id) { it.copy(value = worth) } // what it is worth is not a Helper's to set: refused for them
        }
        return "added item ${id(item.id)} ${item.name} → ok"
    }

    private fun editItem(): String? {
        val item = pick(d.liveItems) ?: return null
        val note = pick(words)!!
        when (r.nextInt(3)) {
            0 -> s.put(item.copy(notes = note))
            1 -> s.put(item.copy(name = pick(things)!! + " " + r.nextInt(99), fragile = !item.fragile))
            else -> return "set the serial number of item ${id(item.id)} " + said(s.setDetails(item.id) { it.copy(serial = "SN-" + r.nextInt(9999), make = "Make" + r.nextInt(9)) })
        }
        return "edited item ${id(item.id)} → ok"
    }

    private fun deleteItem(): String? {
        val item = pick(d.liveItems) ?: return null
        if (chance(50)) return "deleted item ${id(item.id)} " + said(s.tryDelete(item, withPhotos = chance(50)))
        s.delete(item)
        return "deleted item ${id(item.id)} " + took(d.item(item.id) == null, "the item")
    }

    /** Something this phone's person added: they may delete it while nobody else has had a hand in it. */
    private fun deleteOwn(): String? {
        val me = myId
        val things = d.liveItems.filter { it.createdBy == me } + d.liveBoxes.filter { it.createdBy == me } +
            d.liveRooms.filter { it.createdBy == me } + d.livePhotos.filter { it.by == me }
        val x = pick(things) ?: return null
        val o = when (x) {
            is Item -> s.tryDelete(x)
            is Box -> s.tryDelete(x)
            is Room -> s.tryDelete(x)
            is Photo -> s.tryDelete(x)
            else -> return null
        }
        return "deleted its own ${x.javaClass.simpleName.lowercase()} ${id(x.id)} " + said(o)
    }

    private fun askToDelete(): String? {
        val x: Rec = when (r.nextInt(6)) {
            0 -> pick(d.liveItems)
            1 -> pick(d.liveBoxes)
            2 -> pick(d.liveRooms)
            3 -> pick(d.livePhotos)
            4 -> pick(d.liveTasks)
            else -> pick(d.liveExpenses)
        } ?: return null
        val o = s.askToDelete(x.id, if (chance(50)) "Not needed" else "")
        return "asked to delete ${x.javaClass.simpleName.lowercase()} ${id(x.id)}${o.valueOrNull()?.let { " as " + id(it.id) } ?: ""} " + said(o)
    }

    private fun withdraw(): String? {
        val mine = d.waitingProposals().filter { it.by == myId }
        val prop = (if (chance(85)) pick(mine) else pick(d.waitingProposals())) ?: return null
        return "withdrew suggestion ${id(prop.id)} about ${id(prop.targetId)} " + said(s.withdraw(prop.id))
    }

    /** A task or cost changed through the calls that say what happened: saved for an Organiser, a suggestion for a Decider. */
    private fun saveOrSuggest(): String? {
        if (chance(50)) {
            val t = (if (chance(50)) pick(d.liveTasks)?.let { it.copy(title = "Sort the " + pick(stuff) + " " + r.nextInt(99), deleted = chance(10)) } else null) ?: task()
            val o = if (chance(50)) s.saveTask(t) else s.saveOrSuggest(t, pick(words)!!)
            return "saved or suggested task ${id(t.id)}${if (t.deleted) " deleted" else ""}${(o.valueOrNull() as? Proposal)?.let { " as suggestion " + id(it.id) } ?: ""} " + said(o)
        }
        val e = (if (chance(50)) pick(d.liveExpenses)?.let { it.copy(amount = (1 + r.nextInt(500)).toDouble(), planned = if (chance(20)) !it.planned else it.planned) } else null) ?: cost()
        val o = if (chance(50)) s.saveCost(e) else s.saveOrSuggest(e, "")
        return "saved or suggested cost ${id(e.id)}${(o.valueOrNull() as? Proposal)?.let { " as suggestion " + id(it.id) } ?: ""} " + said(o)
    }

    private fun photograph(): String? {
        val (owner, kinds) = when (r.nextInt(5)) {
            0, 1 -> (pick(d.liveItems)?.id ?: return null) to listOf(PhotoKind.GENERAL, PhotoKind.CONDITION, PhotoKind.DISASSEMBLY, PhotoKind.CABLES, PhotoKind.DAMAGE, PhotoKind.SERIAL, PhotoKind.RECEIPT)
            2, 3 -> (pick(d.liveBoxes)?.id ?: return null) to listOf(PhotoKind.CONTENTS, PhotoKind.CONTENTS, PhotoKind.DAMAGE)
            else -> (pick(d.liveRooms)?.id ?: "home:ORIGIN") to listOf(PhotoKind.ROOM, PhotoKind.METER)
        }
        val kind = pick(kinds)!!
        val size = if (chance(10)) 20_000 + r.nextInt(40_000) else 150 + r.nextInt(3000)
        val f = s.newPhotoFile().apply { writeBytes(ByteArray(size) { (it * 31 + size).toByte() }) }
        val photo = s.addPhoto(owner, kind, f)
        synchronized(sim.born) { sim.born[f.name] = size.toLong() }
        return "took a ${kind.name} photo ${id(photo.id)} of ${id(owner)} → ok"
    }

    private fun deletePhoto(): String? {
        val photo = pick(d.livePhotos) ?: return null
        if (chance(50)) return "deleted photo ${id(photo.id)} " + said(s.tryDelete(photo))
        s.delete(photo)
        return "deleted photo ${id(photo.id)} " + took(d.livePhotos.none { it.id == photo.id }, "the photo")
    }

    private fun disposition() = Disposition.entries[r.nextInt(Disposition.entries.size)]
    private fun price(x: Disposition) = if (x == Disposition.SELL && chance(70)) (5 + r.nextInt(300)).toDouble() else null

    private fun decide(): String? {
        val item = pick(d.liveItems) ?: return null
        val what = disposition()
        return "decided ${what.name} for item ${id(item.id)} " + said(s.decide(item.id, what, price(what)))
    }

    private fun decideBySaving(): String? {
        val items = d.liveItems.shuffled(r).take(1 + r.nextInt(3))
        if (items.isEmpty()) return null
        val what = disposition()
        if (items.size == 1) s.put(items[0].copy(disposition = what)) else s.updateItems(items.map { it.id }) { it.copy(disposition = what) }
        val after = items.mapNotNull { d.item(it.id) }
        return "saved ${what.name} on ${items.joinToString { id(it.id) }} " + took(after.all { it.disposition == what }, "the decision")
    }

    private fun planRoom(): String? {
        val item = pick(d.liveItems) ?: return null
        val room = if (chance(15)) null else pick(d.roomsOf(Home.DEST))
        if (chance(70)) return "planned item ${id(item.id)} for room ${id(room?.id)} " + said(s.planRoom(item.id, room?.id))
        s.put(item.copy(destRoomId = room?.id))
        return "saved room ${id(room?.id)} on item ${id(item.id)} " + took(d.item(item.id)?.destRoomId == room?.id, "the room")
    }

    private fun suggestDecision(): String? {
        val item = pick(d.liveItems) ?: return null
        val what = disposition()
        val o = s.suggestDecision(item.id, what, price(what), note = pick(words)!!)
        return "suggested ${what.name} for item ${id(item.id)}${o.valueOrNull()?.let { " as " + id(it.id) } ?: ""} " + said(o)
    }

    private fun suggestRoom(): String? {
        val item = pick(d.liveItems) ?: return null
        val room = pick(d.roomsOf(Home.DEST))
        val o = s.suggestRoom(item.id, room?.id)
        return "suggested room ${id(room?.id)} for item ${id(item.id)}${o.valueOrNull()?.let { " as " + id(it.id) } ?: ""} " + said(o)
    }

    private fun approve(): String? {
        val waiting = d.waitingProposals().filter { it.kind != ProposalKind.QUESTION || it.deletes }
        val prop = (if (chance(90)) pick(waiting) else pick(d.liveProposals)) ?: return null
        return "approved suggestion ${id(prop.id)} about ${id(prop.targetId)} " + said(s.approve(prop.id))
    }

    private fun decline(): String? {
        val prop = pick(d.waitingProposals()) ?: return null
        return "declined suggestion ${id(prop.id)} about ${id(prop.targetId)} " + said(s.decline(prop.id, pick(words)!!))
    }

    private fun ask(): String? {
        val item = pick(d.liveItems) ?: return null
        val o = s.ask(item.id, "What about this?")
        return "asked about item ${id(item.id)}${o.valueOrNull()?.let { " as " + id(it.id) } ?: ""} " + said(o)
    }

    private fun answer(): String? {
        val q = pick(d.waitingProposals().filter { it.kind == ProposalKind.QUESTION && (!it.deletes || chance(10)) }) ?: return null
        val what = disposition()
        val o = s.answer(q.id, what, price(what))
        return "answered question ${id(q.id)} about ${id(q.targetId)} with ${what.name}${o.valueOrNull()?.takeIf { it.id != q.id }?.let { " as suggestion " + id(it.id) } ?: ""} " + said(o)
    }

    private fun carryOut(): String? {
        val going = d.liveItems.filter { it.disposition in listOf(Disposition.SELL, Disposition.DONATE, Disposition.BIN) }
        val item = (if (chance(85)) pick(going) else pick(d.liveItems)) ?: return null
        if (chance(15)) {
            s.put(item.copy(gone = true))
            return "saved item ${id(item.id)} as gone " + took(d.item(item.id)?.gone == true, "whether it has gone")
        }
        val o = when (r.nextInt(3)) {
            0 -> s.carryOut(item.id, listed = true)
            1 -> s.carryOut(item.id, gone = true, soldFor = if (item.disposition == Disposition.SELL) (1 + r.nextInt(200)).toDouble() else null)
            else -> s.carryOut(item.id, gone = !item.gone)
        }
        return "carried out the decision on item ${id(item.id)} " + said(o)
    }

    // ---- containers ------------------------------------------------------------------------

    private fun handCode(): String = s.nextHandCode().also { synchronized(p.handCodes) { p.handCodes += "${s.moveId}/$it" } }

    private fun newContainer(): String {
        val kind = ContainerKind.entries[r.nextInt(ContainerKind.entries.size)]
        val room = pick(d.roomsOf(Home.ORIGIN))
        val label = when (r.nextInt(10)) {
            in 0..6 -> handCode()
            7, 8 -> "L%03d".format(1 + r.nextInt(maxOf(30, d.move.labelsPrinted)))
            else -> listOf("MISC", "TOP", "1")[r.nextInt(3)] // written by hand without asking the app: nothing stops two the same
        }
        val box = if (chance(70)) s.createContainer(label, room?.id, kind) else s.createBox(label, room?.id, kind)
        return "made a ${kind.name} ${id(box.id)} labelled $label → ok"
    }

    private fun editContainer(): String? {
        val box = pick(d.liveBoxes) ?: return null
        when (r.nextInt(4)) {
            0 -> s.put(box.copy(contents = addContents(box.contents, pick(stuff)!!)))
            1 -> s.put(box.copy(fragile = !box.fragile, heavy = chance(30), priority = Priority.entries[r.nextInt(4)]))
            2 -> s.put(box.copy(destRoomId = pick(d.roomsOf(Home.DEST))?.id, name = "Box of " + pick(stuff)))
            else -> s.updateBoxes(listOf(box.id)) { it.copy(notes = pick(words)!!, category = BOX_CATEGORIES[r.nextInt(BOX_CATEGORIES.size)]) }
        }
        return "edited container ${id(box.id)} → ok"
    }

    private fun putIn(): String? {
        val item = pick(d.liveItems) ?: return null
        val box = pick(d.liveBoxes) ?: return null
        return "put item ${id(item.id)} into container ${id(box.id)} " + said(s.putInto(item.id, box.id))
    }

    private fun takeOut(): String? {
        val item = pick(d.liveItems.filter { it.boxId != null }) ?: return null
        return "took item ${id(item.id)} out of container ${id(item.boxId)} " + said(s.takeOut(item.id))
    }

    private fun nest(): String? {
        val boxes = d.liveBoxes
        if (boxes.size < 2) return null
        val child = pick(boxes)!!
        val parent = if (chance(5)) child else pick(boxes.filter { it.id != child.id })!!
        if (chance(80)) return "put container ${id(child.id)} inside ${id(parent.id)} " + said(s.nest(child.id, parent.id))
        s.put(child.copy(parentId = parent.id))
        return "saved container ${id(child.id)} as inside ${id(parent.id)} " + took(d.box(child.id)?.parentId == parent.id, "what it is inside")
    }

    private fun unNest(): String? {
        val box = pick(d.liveBoxes.filter { it.parentId != null }) ?: return null
        return "took container ${id(box.id)} out of ${id(box.parentId)} " + said(s.nest(box.id, null))
    }

    private fun seal(): String? {
        val boxes = d.liveBoxes.shuffled(r).take(1 + r.nextInt(3))
        if (boxes.isEmpty()) return null
        val sealed = chance(80)
        if (boxes.size == 1) s.put(boxes[0].copy(sealed = sealed)) else s.updateBoxes(boxes.map { it.id }) { it.copy(sealed = sealed) }
        return "${if (sealed) "sealed" else "opened"} ${boxes.joinToString { id(it.id) }} → ok"
    }

    /** Load, deliver or unpack: a few things at the same stage move on one stage, by their own label or that of something inside them. */
    private fun moveOn(): String? {
        val data = d
        // The way through the new home; the storage unit is a way of its own (storageTrip).
        val from = HOME_WAY[r.nextInt(3)]
        val to = HOME_WAY[HOME_WAY.indexOf(from) + 1]
        val trip = if (to == Stage.LOADED) pick(data.liveTrips)?.id else null
        val boxes = (if (chance(80)) data.topBoxes() else data.liveBoxes).filter { it.stage == from }.shuffled(r).take(r.nextInt(4))
        val items = (if (chance(80)) data.looseItems() else data.liveItems).filter { it.stage == from }.shuffled(r).take(r.nextInt(3))
        if (boxes.isEmpty() && items.isEmpty()) return null
        if (boxes.isNotEmpty()) s.updateBoxes(boxes.map { it.id }) { it.copy(stage = to, tripId = trip ?: it.tripId) }
        if (items.isNotEmpty()) s.updateItems(items.map { it.id }) { it.copy(stage = to, tripId = trip ?: it.tripId) }
        return "moved ${(boxes.map { id(it.id) } + items.map { id(it.id) }).joinToString()} on to ${to.name} → ok"
    }

    private fun bringBack(): String? {
        val box = pick(d.liveBoxes.filter { it.stage != Stage.AT_ORIGIN }) ?: return null
        if (chance(50)) return "put container ${id(box.id)} back a step " + said(s.putBack(listOf(box.id)))
        val back = when (box.stage) {
            Stage.STORED -> Stage.LOADED
            else -> HOME_WAY[maxOf(0, HOME_WAY.indexOf(box.stage) - 1)]
        }
        s.updateBoxes(listOf(box.id)) { it.copy(stage = back) }
        return "put container ${id(box.id)} back a stage → ok"
    }

    private fun damage(): String? {
        if (chance(50)) {
            val box = pick(d.liveBoxes) ?: return null
            s.updateBoxes(listOf(box.id)) { it.copy(damaged = true, damageNote = "Corner crushed") }
            return "reported damage to container ${id(box.id)} → ok"
        }
        val item = pick(d.liveItems) ?: return null
        s.updateItems(listOf(item.id)) { it.copy(damaged = true, damageNote = "Leg cracked") }
        return "reported damage to item ${id(item.id)} → ok"
    }


    // ---- 2.1: damage reports, the storage unit, hand-overs -------------------------------------

    /** Report damage as a report of its own, or clear one (an Organiser's to do). */
    private fun damageReport(): String? {
        if (chance(35)) {
            val x = pick(d.openDamage()) ?: return null
            return "cleared damage report ${id(x.id)} on ${id(x.thingId)} " + said(s.clearDamage(x.id))
        }
        val thing = (if (chance(50)) pick(d.liveBoxes)?.id else pick(d.liveItems)?.id) ?: return null
        return "reported damage to ${id(thing)} " + said(s.reportDamage(thing, pick(words)!!))
    }

    /** A trip to the storage unit or back from it; loading, unloading or undoing a step on one. */
    private fun storage(): String? {
        val data = d
        return when (r.nextInt(5)) {
            0 -> {
                val back = chance(40)
                val o = if (back) s.addTrip(from = Place.STORAGE) else s.addTrip(to = Place.STORAGE)
                "added a trip ${if (back) "back from" else "to"} the storage unit " + said(o)
            }
            1 -> {
                val trip = pick(data.tripsToStorage()) ?: return null
                val things = data.movables().filter { it.stage == Stage.AT_ORIGIN }.shuffled(r).take(1 + r.nextInt(3))
                if (things.isEmpty()) return null
                "loaded ${things.joinToString { id(it.id) }} for the storage unit " + said(s.loadOnto(trip.id, things.map { it.id }))
            }
            2 -> {
                val trip = pick(data.tripsFromStorage()) ?: return null
                val things = (if (chance(80)) data.inStorage() else data.movables()).shuffled(r).take(1 + r.nextInt(2))
                if (things.isEmpty()) return null
                "loaded ${things.joinToString { id(it.id) }} to bring back from storage " + said(s.loadOnto(trip.id, things.map { it.id }))
            }
            3 -> {
                val things = data.movables().filter { it.stage == Stage.LOADED }.shuffled(r).take(1 + r.nextInt(3))
                if (things.isEmpty()) return null
                "unloaded ${things.joinToString { id(it.id) }} " + said(s.unload(things.map { it.id }))
            }
            else -> {
                val m = pick(data.movables().filter { it.stage != Stage.AT_ORIGIN }) ?: return null
                "put ${id(m.id)} back a step " + said(s.putBack(listOf(m.id)))
            }
        }
    }

    /** Arrange a hand-over (an Organiser's, or a Decider's with an approved suggestion), mark it paid, or hand it over (anyone's). */
    private fun handover(): String? {
        val going = d.liveItems.filter { it.disposition == Disposition.SELL || it.disposition == Disposition.DONATE }
        val item = (if (chance(85)) pick(going) else pick(d.liveItems)) ?: return null
        return when (r.nextInt(3)) {
            0 -> {
                val to = pick(d.livePeople)?.id
                "arranged the hand-over of ${id(item.id)} to ${id(to)} " + said(s.arrangeHandover(item.id, to, paid = chance(40)))
            }
            1 -> "marked ${id(item.id)} paid " + said(s.setPaid(item.id, chance(80)))
            else -> "handed over ${id(item.id)} " + said(s.handOver(item.id, chance(90)))
        }
    }
    private fun deleteContainer(): String? {
        val box = pick(d.liveBoxes) ?: return null
        if (chance(50)) return "deleted container ${id(box.id)} " + said(s.tryDelete(box, withPhotos = chance(50)))
        s.delete(box)
        return "deleted container ${id(box.id)} " + took(d.box(box.id) == null, "the container")
    }

    private fun scan(): String? {
        val box = pick(d.liveBoxes) ?: return null
        val got = when (r.nextInt(3)) {
            0 -> s.scanInto(box.id, pick(isbns)!!, title = "A book")
            1 -> s.scanInto(box.id, "50123456789" + r.nextInt(10) + r.nextInt(10))
            else -> s.scanInto(box.id, "", title = pick(stuff)!!)
        }
        return "scanned ${id(got.entry.id)} into container ${id(box.id)} → ok"
    }

    private fun deleteEntry(): String? {
        val e = pick(d.liveEntries) ?: return null
        if (chance(50)) return "removed scanned thing ${id(e.id)} " + said(s.tryDelete(e))
        s.delete(e)
        return "removed scanned thing ${id(e.id)} → ok"
    }

    // ---- rooms -----------------------------------------------------------------------------

    private fun newRoom(): String {
        val home = if (chance(50)) Home.ORIGIN else Home.DEST
        val room = s.createRoom(home, listOf("Office", "Bedroom 2", "Hall", "Loft", "Bathroom", "Utility")[r.nextInt(6)] + " " + r.nextInt(9))
        return "added room ${id(room.id)} at ${home.name} → ok"
    }

    private fun editRoom(): String? {
        val room = pick(d.liveRooms) ?: return null
        when (r.nextInt(3)) {
            0 -> s.put(room.copy(packed = !room.packed, swept = chance(30)))
            1 -> s.put(room.copy(mapsTo = pick(d.roomsOf(Home.DEST))?.id))
            else -> s.put(room.copy(name = room.name.take(12) + " " + r.nextInt(9), doorWidthCm = 60.0 + r.nextInt(40)))
        }
        return "edited room ${id(room.id)} → ok"
    }

    private fun deleteRoom(): String? {
        val room = pick(d.liveRooms) ?: return null
        if (d.liveRooms.size < 5) return null
        if (chance(50)) return "deleted room ${id(room.id)} " + said(s.tryDelete(room))
        s.delete(room)
        return "deleted room ${id(room.id)} " + took(d.room(room.id) == null, "the room")
    }

    // ---- costs and tasks -------------------------------------------------------------------

    private fun cost() = Expense(id = newId(), title = "Cost of " + pick(stuff), amount = (3 + r.nextInt(400)).toDouble(), category = "Fees", day = 20_590L + r.nextInt(20), planned = chance(60))

    private fun addCost(): String {
        val e = cost()
        s.put(e)
        return "added cost ${id(e.id)} " + took(d.expense(e.id) != null, "the costs")
    }

    private fun editCost(): String? {
        val e = pick(d.liveExpenses) ?: return null
        val amount = (1 + r.nextInt(500)).toDouble()
        s.put(e.copy(amount = amount))
        return "changed cost ${id(e.id)} " + took(d.expense(e.id)?.amount == amount, "the cost")
    }

    private fun deleteCost(): String? {
        val e = pick(d.liveExpenses) ?: return null
        if (chance(50)) return "deleted cost ${id(e.id)} " + said(s.tryDelete(e))
        s.delete(e)
        return "deleted cost ${id(e.id)} " + took(d.expense(e.id) == null, "the cost")
    }

    private fun pay(): String? {
        val e = pick(d.liveExpenses) ?: return null
        return "marked cost ${id(e.id)} paid " + said(s.markPaid(e.id, paid = chance(85)))
    }

    private fun receipt(): String? {
        val e = pick(d.liveExpenses) ?: return null
        val f = s.newPhotoFile().apply { writeBytes(ByteArray(400 + r.nextInt(2000)) { 5 }) }
        val length = f.length()
        val o = s.attachReceipt(e.id, f)
        if (o.ok) synchronized(sim.born) { sim.born[f.name] = length }
        return "attached receipt ${id(o.valueOrNull()?.id)} to cost ${id(e.id)} " + said(o)
    }

    private fun suggestCost(): String {
        val e = (if (chance(50)) pick(d.liveExpenses)?.let { it.copy(amount = it.amount + 10) } else null) ?: cost()
        val o = s.suggestCost(e, note = pick(words)!!)
        return "suggested cost ${id(e.id)}${o.valueOrNull()?.let { " as " + id(it.id) } ?: ""} " + said(o)
    }

    private fun task() = Task(id = newId(), title = "Sort the " + pick(stuff), offsetDays = -r.nextInt(30), category = "Pack", assignedTo = if (chance(60)) pick(d.livePeople)?.id else null)

    private fun addTask(): String {
        val t = task()
        s.put(t)
        return "added task ${id(t.id)} for ${id(t.assignedTo)} " + took(d.task(t.id) != null, "the tasks")
    }

    private fun editTask(): String? {
        val t = pick(d.liveTasks) ?: return null
        val title = "Sort the " + pick(stuff) + " " + r.nextInt(99)
        s.put(t.copy(title = title))
        return "changed task ${id(t.id)} " + took(d.task(t.id)?.title == title, "the task")
    }

    private fun deleteTask(): String? {
        val t = pick(d.liveTasks) ?: return null
        if (chance(50)) return "deleted task ${id(t.id)} " + said(s.tryDelete(t))
        s.delete(t)
        return "deleted task ${id(t.id)} " + took(d.task(t.id) == null, "the task")
    }

    private fun suggestTask(): String {
        val t = (if (chance(40)) pick(d.liveTasks)?.let { it.copy(title = it.title.take(30) + "!") } else null) ?: task()
        val o = s.suggestTask(t)
        return "suggested task ${id(t.id)}${o.valueOrNull()?.let { " as " + id(it.id) } ?: ""} " + said(o)
    }

    private fun assign(): String? {
        val t = pick(d.liveTasks) ?: return null
        val who = if (chance(15)) null else pick(d.people)?.id
        return "gave task ${id(t.id)} to ${id(who)} " + said(s.assign(t.id, who))
    }

    private fun tick(): String? {
        val mine = d.liveTasks.filter { it.assignedTo == myId }
        val t = (if (chance(60)) pick(mine) else null) ?: pick(d.liveTasks) ?: return null
        val done = chance(85)
        if (chance(70)) return "ticked task ${id(t.id)} " + said(s.tick(t.id, done))
        s.put(t.copy(done = done))
        return "saved task ${id(t.id)} as ${if (done) "done" else "not done"} " + took(d.task(t.id)?.done == done, "the tick")
    }

    private fun supplies(): String {
        val x = pick(d.liveSupplies)
        if (x != null && chance(60)) s.put(x.copy(have = x.have + 1)) else s.put(Supply(id = newId(), name = "Tape " + r.nextInt(9), need = 1 + r.nextInt(5)))
        return "counted supplies → ok"
    }

    private fun trip(): String {
        if (d.liveTrips.size < 3 && chance(50)) s.put(Trip(id = newId(), name = "Trip " + (d.liveTrips.size + 1), sort = d.liveTrips.size + 1))
        else pick(d.liveTrips)?.let { t -> s.setDevice { it.copy(currentTripId = t.id) } }
        return "looked after the trips → ok"
    }

    private fun reading(): String {
        s.put(Reading(id = newId(), home = if (chance(50)) Home.ORIGIN else Home.DEST, kind = "Gas", value = r.nextInt(99_999).toString(), day = 20_600))
        return "read a meter → ok"
    }

    // ---- people and settings ---------------------------------------------------------------

    private fun addPerson(): String {
        val role = Role.entries[r.nextInt(3)]
        val o = s.addPerson(if (chance(30)) "" else "Friend " + r.nextInt(999), role)
        return "!added ${id(o.valueOrNull()?.id)} as ${role.label} " + said(o)
    }

    private fun removePerson(): String? {
        val who = pick(d.livePeople) ?: return null
        return "!removed ${id(who.id)} (${who.name}) " + said(s.removePerson(who.id))
    }

    private fun restorePerson(): String? {
        val who = pick(d.people.filter { it.removed }) ?: return null
        return "!let ${id(who.id)} (${who.name}) back in " + said(s.restorePerson(who.id))
    }

    private fun changeRole(): String? {
        val who = pick(d.livePeople) ?: return null
        val role = Role.entries[r.nextInt(3)]
        return "!made ${id(who.id)} (${who.name}) ${role.label} " + said(s.setRole(who.id, role))
    }

    private fun rename(): String? {
        val who = (if (chance(60)) d.person(myId) else pick(d.people)) ?: return null
        if (who.id == myId && chance(30)) {
            val name = p.name + " " + r.nextInt(99)
            s.setDevice { it.copy(name = name) }
            return "renamed this phone $name → ok"
        }
        return "renamed ${id(who.id)} " + said(s.renamePerson(who.id, who.name.take(10) + " " + r.nextInt(99)))
    }

    /**
     * A new letter: usually one nobody has ever had (each phone hands out its own), sometimes one that is taken, and for
     * the phone's own person now and then the letter it writes with in another of its moves.
     */
    private fun changeLetter(): String? {
        val who = (if (chance(60)) d.person(myId) else pick(d.livePeople)) ?: return null
        val elsewhere = if (who.id == myId) p.stores().filter { it !== s }.map { it.device.value.letter }.filter { it.isNotBlank() } else emptyList()
        val letter = when {
            elsewhere.isNotEmpty() && chance(30) -> pick(elsewhere)!!
            chance(25) -> pick(d.people)!!.letter
            else -> "${"QRSTUVW"[p.index]}${'A' + (lettersGiven++ % 26)}"
        }
        return "gave ${id(who.id)} the letter $letter " + said(s.setLetter(who.id, letter))
    }

    private var lettersGiven = 0

    private fun settings(): String {
        val name = "Move " + r.nextInt(99)
        if (chance(40)) return "changed the move settings " + said(s.changeMove { it.copy(name = name, vanCapacityM3 = 10.0 + r.nextInt(10)) })
        when (r.nextInt(3)) {
            0 -> s.setMove { it.copy(name = name) }
            1 -> s.setMove { it.copy(name = name, moveDay = 20_590L + r.nextInt(30), currency = listOf("£", "$", "€")[r.nextInt(3)]) }
            else -> s.setMove { it.copy(name = name, off = if (chance(50)) listOf(Part.BUDGET.name) else emptyList()) }
        }
        return "saved the move settings " + took(d.move.name == name, "the settings")
    }

    private fun printLabels(): String {
        s.setMove { it.copy(labelsPrinted = it.labelsPrinted + 30) }
        return "printed a sheet of labels → ok"
    }

    private fun backup(): String {
        // Since 2.1 Helpers cannot make backups: the phone says so, and nothing is written.
        return try {
            p.backup = ByteArrayOutputStream().also { Backup.export(s, it) }.toByteArray()
            p.backupOf = s.moveId
            "made a backup → ok"
        } catch (e: java.io.IOException) {
            "made a backup " + no(e.message)
        }
    }

    /**
     * Restore the last backup this phone made, as the Sync screen does: into the move in use. That may be another move than
     * the one the backup is of; then nothing of it may go in (it belongs in a card of its own, which "Lost your phone?" makes).
     */
    private fun restore(): String? {
        val zip = p.backup ?: return null
        val into = s
        val other = !into.identity.value.matches(p.backupOf)
        return try {
            Backup.import(into, ByteArrayInputStream(zip))
            if (other) sim.broke(Simulation.CROSS, "${p.tag} restored a backup of ${sim.others[p.backupOf] ?: "Maple Road"} into ${into.data.value.move.name}, another move", listOf(p.backupOf, into.moveId))
            "restored its backup${if (other) " of another move" else ""} into the data → ok"
        } catch (e: IllegalArgumentException) {
            "restored its backup${if (other) " of another move" else ""} " + no(e.message)
        }
    }

    /** Only tried by someone who may not: an Organiser's erase would really empty the phone. */
    private fun tryErase(): String? {
        if (s.can(Action.ERASE)) return null
        return "tried to erase the phone " + said(s.erase())
    }

    /** Open something on a screen now and save it later, when a sync may have changed it underneath. */
    private fun openForLater(): String? {
        if (p.pending != null) return null
        val st = s
        p.pendingIn = st
        val note = "saved late " + r.nextInt(999)
        if (chance(50)) {
            val item = pick(d.liveItems) ?: return null
            p.pending = { st.put(item.copy(notes = note)); "saved the edit of item ${id(item.id)} it had open → ok" }
            return "opened item ${id(item.id)} to edit → ok"
        }
        val box = pick(d.liveBoxes) ?: return null
        p.pending = { st.put(box.copy(notes = note)); "saved the edit of container ${id(box.id)} it had open → ok" }
        return "opened container ${id(box.id)} to edit → ok"
    }
}
