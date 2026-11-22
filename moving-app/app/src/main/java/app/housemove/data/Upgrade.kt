package app.housemove.data

/**
 * Bring the records of data saved by version 2.0 up to 2.1. Nothing is lost, and nothing is changed but its shape:
 *
 *  - An item's value, asking price, sale amount, make, model, serial number and dates move into its [Hidden]
 *    record, which Helpers are not sent. 2.0 sealed the price into the decision's stamp and the sale amount into
 *    the outcome's. Each old stamp is checked against the old values and, when it holds, re-sealed with the same
 *    author and time: the decision and the outcome without them, the price and the sale on their own parts of the
 *    hidden record. So no decision or sale resets on any phone, a Helper's included. A stamp that did not hold
 *    still does not.
 *  - Damage marked on an item or a box becomes a [Damage] report (one per thing, the same on every phone).
 *  - A box's contents text becomes [ContentsLine]s, made the same way on every phone.
 *
 * It goes by what the data holds, not by its version number, and doing it twice changes nothing, so it can run
 * on every load ([migrate] calls it) and on data arriving from another phone ([merge] does). Whoever raises
 * [SCHEMA] calls it from the version step as well.
 */
fun upgradeRecordsTo21(d: AppData): AppData {
    val hasHidden = d.hidden.mapTo(HashSet()) { it.id }
    val hasDamage = d.damage.mapTo(HashSet()) { it.thingId }
    val hasLines = d.lines.mapTo(HashSet()) { it.boxId }
    val hidden = ArrayList<Hidden>()
    val damage = ArrayList<Damage>()
    val lines = ArrayList<ContentsLine>()

    fun oldDamage(id: String, damaged: Boolean, note: String, updatedAt: Long) {
        if (damaged && id !in hasDamage) damage += Damage(oldDamageId(id), id, note.trim(), updatedAt = updatedAt).stamped()
    }

    val items = d.items.mapChanged { i ->
        oldDamage(i.id, i.damaged, i.damageNote, i.updatedAt)
        val legacy = i.price != null || i.soldFor != null || i.value != null || i.make.isNotEmpty() || i.model.isNotEmpty() ||
            i.serial.isNotEmpty() || i.boughtDay != null || i.warrantyDay != null
        val decision20 = i.decisionBy.sum.isNotEmpty() && i.decisionBy.sum == i.decisionSum20()
        val outcome20 = i.outcomeBy.sum.isNotEmpty() && i.outcomeBy.sum == i.outcomeSum20()
        if (!legacy && !decision20 && !outcome20) return@mapChanged i
        val bare = i.copy(price = null, soldFor = null, value = null, make = "", model = "", serial = "", boughtDay = null, warrantyDay = null)
        fun unset(s: Stamp) = s.sum.isEmpty() && s.by.isEmpty()
        /** The stamp of a part that moved out: the old one re-sealed when it held; as it stands when never set; else one that does not hold. */
        fun moved(old: Stamp, held: Boolean, sum: String, value: Any?): Stamp = when {
            held -> Stamp(old.by, old.at, sum)
            unset(old) -> Stamp()
            value == null -> Stamp()
            else -> old.copy(sum = "?")
        }
        val priceBy = moved(i.decisionBy, decision20, seal(i.price), i.price)
        val saleBy = moved(i.outcomeBy, outcome20, seal(i.soldFor), i.soldFor)
        val out = bare.copy(
            decisionBy = if (decision20) i.decisionBy.copy(sum = bare.decisionSum()) else i.decisionBy,
            outcomeBy = if (outcome20) i.outcomeBy.copy(sum = bare.outcomeSum()) else i.outcomeBy,
        )
        fun meant(s: Stamp) = s.by.isNotEmpty() || s.at != 0L
        if (i.id !in hasHidden && (legacy || meant(priceBy) || meant(saleBy))) {
            hidden += Hidden(
                id = i.id, value = i.value, make = i.make, model = i.model, serial = i.serial, boughtDay = i.boughtDay, warrantyDay = i.warrantyDay,
                price = i.price, priceBy = priceBy, soldFor = i.soldFor, saleBy = saleBy, updatedAt = i.updatedAt,
            ).stamped()
        }
        out
    }

    for (b in d.boxes) {
        oldDamage(b.id, b.damaged, b.damageNote, b.updatedAt)
        if (b.contents.isBlank() || b.id in hasLines) continue
        val batch = maxOf(1L, b.updatedAt)
        contentsLines(b.contents).forEachIndexed { n, text ->
            lines += ContentsLine(lineId(b.id, text), b.id, text, sort = n.toLong(), batch = batch, updatedAt = b.updatedAt)
        }
    }

    if (items === d.items && hidden.isEmpty() && damage.isEmpty() && lines.isEmpty()) return d
    return d.copy(
        items = items,
        hidden = if (hidden.isEmpty()) d.hidden else d.hidden + hidden,
        damage = if (damage.isEmpty()) d.damage else d.damage + damage,
        lines = if (lines.isEmpty()) d.lines else d.lines + lines.distinctBy { it.id },
    )
}
