package app.housemove.data

/*
 * What Helpers are not sent, and handing sold or donated things over.
 *
 * Helpers are not sent: costs and cost suggestions, receipts and serial-label photos (as in 2.0), and since
 * 2.1 an item's [Hidden] record (value, make, model, serial number, dates, asking price, what it sold for),
 * the refused changes kept for Organisers, and the amounts to collect at hand-overs other than their own.
 * Whole records are left out, never single fields, so a Helper's copy of an item can never blank any of these
 * on another phone, and leaving a record out is not a deletion. The activity log, which Helpers are sent,
 * names no amounts.
 *
 * A Decider's suggestion keeps the price it suggests, and Helpers are sent suggestions: a Helper's phone needs
 * the approved ones to accept what a Decider carries out, and a suggestion has to reach an Organiser by way of
 * any phone with what it asks for.
 *
 * A Helper's phone that holds such data from before (2.0 sent it, or the person was a Decider until lately)
 * keeps it until it has finished a full sync with an Organiser's or a Decider's phone, which then holds
 * everything it had; then it lets it go ([withoutHiddenFor], [Store.fullSyncDone]).
 */

/**
 * The part of the data that is sent to a phone whose person is [personId] with [role]: everything for an
 * Organiser or a Decider. A Helper (or anyone this phone does not know the role of) is sent what [visibleTo]
 * leaves them, without hidden records or refused changes, and with only the amounts to collect at hand-overs
 * given to them. The sync sends this.
 */
fun AppData.sentTo(role: Role?, personId: String? = null): AppData {
    val r = role ?: Role.HELPER
    if (r != Role.HELPER) return this
    return visibleTo(Role.HELPER).copy(
        hidden = emptyList(),
        refused = emptyList(),
        collect = if (personId.isNullOrEmpty()) emptyList() else collect.filter { c -> c.to == personId && item(c.id)?.handoverTo == personId },
    )
}

/**
 * What a Helper's phone keeps of a merge, beyond [withHelpersShareOf]: none of the hidden records or refused
 * changes that came in (it keeps what it had, [was]), and of the amounts to collect, only those given to [me].
 */
internal fun AppData.withHelpersHiddenOf(was: AppData, me: String): AppData {
    val collectWere = was.collect.filter { it.to != me }.associateBy { it.id }
    val mine = collect.filter { it.to == me && it.id !in collectWere }
    return copy(hidden = was.hidden, refused = was.refused, collect = mine + collectWere.values)
}

/**
 * A Helper's data without what Helpers are not sent: what such a phone keeps once an Organiser's or a
 * Decider's phone has everything it held. Of the amounts to collect it keeps those given to [me].
 */
fun AppData.withoutHiddenFor(me: String): AppData = copy(
    expenses = emptyList(),
    photos = photos.filter { it.kind !in HIDDEN_FROM_HELPERS },
    proposals = proposals.filter { it.kind != ProposalKind.COST },
    hidden = emptyList(),
    refused = emptyList(),
    collect = collect.filter { it.to == me && item(it.id)?.handoverTo == me },
)

/**
 * [withoutHiddenFor] after a full sync in which this phone sent [sent]: what it let go is only what the other phone
 * was sent as it is now. Anything made or changed here after that went across (in the middle of the sync), and the
 * receipt and serial-label photos this phone's person took themselves, stay: the next sync with an Organiser or a
 * Decider passes them on. [held] is the data before letting go.
 */
internal fun AppData.keepingUnsent(held: AppData, sent: AppData, me: String): AppData {
    fun <T : Rec> back(now: List<T>, was: List<T>, theirs: List<T>, hiddenKind: (T) -> Boolean, mine: (T) -> Boolean = { false }): List<T> {
        val sentAt = HashMap<String, Long>(theirs.size * 2)
        for (r in theirs) sentAt[r.id] = r.updatedAt
        val kept = now.mapTo(HashSet()) { it.id }
        val extra = was.filter { r -> hiddenKind(r) && r.id !in kept && (mine(r) || (sentAt[r.id] ?: Long.MIN_VALUE) < r.updatedAt) }
        return if (extra.isEmpty()) now else now + extra
    }
    return copy(
        expenses = back(expenses, held.expenses, sent.expenses, { true }),
        photos = back(photos, held.photos, sent.photos, { it.kind in HIDDEN_FROM_HELPERS }, { it.by == me && me.isNotEmpty() }),
        proposals = back(proposals, held.proposals, sent.proposals, { it.kind == ProposalKind.COST }),
        hidden = back(hidden, held.hidden, sent.hidden, { true }),
        refused = back(refused, held.refused, sent.refused, { true }),
        collect = back(collect, held.collect, sent.collect, { true }),
    )
}

/** Does this phone hold anything a Helper is not sent? */
fun AppData.holdsHidden(me: String): Boolean =
    expenses.isNotEmpty() || hidden.isNotEmpty() || refused.isNotEmpty() || photos.any { it.kind in HIDDEN_FROM_HELPERS } ||
        proposals.any { it.kind == ProposalKind.COST } || collect.any { it.to != me || item(it.id)?.handoverTo != me }

/** Why this phone may not make a backup, in words for the screen, or null when it may. Helpers cannot make backups. */
fun AppData.whyNoBackup(device: DeviceInfo): String? =
    if (hasOrganiser() && roleOf(device) == Role.HELPER) "Helpers cannot make backups. What you do here is kept on the Organisers' phones when you sync." else null

/**
 * The data with each item's hidden details filled into its old fields ([Item.price], [Item.serial] and the
 * rest), for screens, reports, the PDF and the CSV that still read those fields. On a Helper's phone there is
 * nothing to fill in. Never saved or sent: the Store ignores those fields when an item is saved.
 */
fun AppData.filledIn(): AppData = shown

internal fun AppData.fillHidden(): AppData {
    if (hidden.isEmpty()) return this
    val h = index.hidden
    return copy(
        items = items.mapChanged { i ->
            val x = h[i.id] ?: return@mapChanged i
            i.copy(price = x.price, soldFor = x.soldFor, value = x.value, make = x.make, model = x.model, serial = x.serial, boughtDay = x.boughtDay, warrantyDay = x.warrantyDay)
        },
    )
}

/** The item as screens show it: with its hidden details when this phone holds them ([filledIn]). */
fun AppData.filled(i: Item): Item {
    val x = hiddenOf(i.id) ?: return i
    return i.copy(price = x.price, soldFor = x.soldFor, value = x.value, make = x.make, model = x.model, serial = x.serial, boughtDay = x.boughtDay, warrantyDay = x.warrantyDay)
}

fun Hidden.makeModel(): String = listOf(make, model).filter { it.isNotBlank() }.joinToString(" ")

/** The serial number of an item, if this phone holds it. */
fun AppData.serialOf(itemId: String): String = hiddenOf(itemId)?.serial.orEmpty()

/** The asking price of an item, if this phone holds it. */
fun AppData.priceOf(itemId: String): Double? = hiddenOf(itemId)?.price

// ---- changes, as a person makes them ([Draft]) ---------------------------------------------

/**
 * Change an item's hidden record with [f], in this person's name: each part whose values changed gets their
 * stamp. The caller has checked they may change those parts. Nothing is saved when nothing changed.
 */
internal fun Draft.changeHidden(itemId: String, f: (Hidden) -> Hidden): Hidden {
    val old = d.hidden.firstOrNull { it.id == itemId }
    val was = (old ?: Hidden(itemId)).stamped()
    var r = f(was).copy(id = itemId, detailsBy = was.detailsBy, priceBy = was.priceBy, saleBy = was.saleBy, deleted = false, updatedAt = was.updatedAt)
    if (r.detailsSum() != was.detailsSum()) r = r.copy(detailsBy = actor.stamp(t, r.detailsSum()))
    if (r.priceSum() != was.priceSum()) r = r.copy(priceBy = actor.stamp(t, r.priceSum()))
    if (r.saleSum() != was.saleSum()) r = r.copy(saleBy = actor.stamp(t, r.saleSum()))
    if (r == was) return old ?: was
    r = r.copy(updatedAt = t)
    d = d.copy(hidden = if (old == null) d.hidden + r else d.hidden.map { if (it.id == itemId) r else it })
    return r
}

/** Set what [to] is to collect when handing [itemId] over, in this person's name. The caller has checked they may. */
internal fun Draft.setCollect(itemId: String, to: String?, amount: Double?): Collect? {
    val old = d.collect.firstOrNull { it.id == itemId }
    val next = Collect(itemId, to.orEmpty(), amount).let { it.copy(by = actor.stamp(t, it.collectSum()), updatedAt = t) }
    if (old != null && old.to == next.to && old.amount == next.amount && !old.deleted) return old
    if (old == null && to.isNullOrEmpty()) return null
    d = d.copy(collect = if (old == null) d.collect + next else d.collect.map { if (it.id == itemId) next else it })
    return next
}

// ---- handing things over -------------------------------------------------------------

/** Is [item] to be handed over to someone: decided sell or donate, and not yet gone? */
fun Item.forHandover(): Boolean = (disposition == Disposition.SELL || disposition == Disposition.DONATE) && !deleted

/**
 * May [person] mark [item] handed over now? Anyone in the move may, once it is decided it is to be sold or
 * donated: that is what makes it an approved sale or donation.
 */
fun AppData.mayHandOver(person: Person?, item: Item): Boolean {
    if (!hasOrganiser()) return true
    if (person == null || person.removed) return false
    return item.forHandover() || person.role == Role.ORGANISER
}

/** May [person] arrange [item]'s hand-over (who hands it over, whether it is paid, what to collect)? The same as [mayCarryOut]. */
fun AppData.mayArrangeHandover(person: Person?, item: Item): Boolean = mayCarryOut(person, item)

/** A hand-over as this phone may show it. [amount] is null unless this phone holds it: the Helper doing it, an Organiser or a Decider. */
data class Handover(
    val itemId: String,
    val to: String?,
    /** Sell: "Paid" (true) or "Not paid" (false). Every Helper sees this on an approved sale. */
    val paid: Boolean,
    val handedOver: Boolean,
    /** What to collect; null when nothing is to be collected or this phone may not see it. */
    val amount: Double?,
)

/** The hand-over of [itemId], or null when it is not to be sold or donated. */
fun AppData.handoverOf(itemId: String): Handover? {
    val i = item(itemId)?.takeIf { it.forHandover() } ?: return null
    val c = index.collect[i.id]?.takeIf { it.to == i.handoverTo }
    return Handover(i.id, i.handoverTo, i.paid, i.handedOver, if (i.paid || i.disposition != Disposition.SELL) null else c?.amount)
}

/** "Paid" or "Not paid", for an approved sale; null for anything else. */
fun AppData.paidLabel(itemId: String): String? {
    val i = item(itemId)?.takeIf { it.disposition == Disposition.SELL } ?: return null
    return if (i.paid) "Paid" else "Not paid"
}

/** Hand-overs given to [personId] that have not been done yet. */
fun AppData.handoversFor(personId: String?): List<Handover> =
    if (personId.isNullOrEmpty()) emptyList()
    else liveItems.filter { it.forHandover() && it.handoverTo == personId && !it.handedOver && !it.gone }.mapNotNull { handoverOf(it.id) }
