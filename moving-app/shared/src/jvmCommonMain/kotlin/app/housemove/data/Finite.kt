package app.housemove.data

/*
 * NaN and the infinities cannot be written as JSON: AppJson keeps kotlinx.serialization's default, which
 * refuses them, and the sync format and the iPhone port depend on that JSON. A dataset holding one could not
 * be saved, backed up or synced. So the Store passes all data it takes through [finite]: a number that is not
 * a real one is treated as not given (null), or as the default where there must be a number.
 * A Double added to the saved data must be added here too (FiniteTest lists them all and checks).
 */

/** This number when it is a real one; null for NaN and the infinities. */
internal fun Double?.finiteOrNull(): Double? = this?.takeIf { it.isFinite() }

private fun Double?.bad(): Boolean = this != null && !isFinite()

internal fun MoveInfo.finite(): MoveInfo = if (vanCapacityM3.isFinite()) this else copy(vanCapacityM3 = MoveInfo().vanCapacityM3)

internal fun Room.finite(): Room = if (doorWidthCm.bad()) copy(doorWidthCm = null) else this

internal fun Item.finite(): Item =
    if (price.bad() || value.bad() || soldFor.bad() || widthCm.bad() || depthCm.bad() || heightCm.bad()) {
        copy(
            price = price.finiteOrNull(), value = value.finiteOrNull(), soldFor = soldFor.finiteOrNull(),
            widthCm = widthCm.finiteOrNull(), depthCm = depthCm.finiteOrNull(), heightCm = heightCm.finiteOrNull(),
        )
    } else this

internal fun Expense.finite(): Expense = if (amount.isFinite()) this else copy(amount = 0.0)

internal fun Proposal.finite(): Proposal {
    val e = expense?.finite()
    return if (price.bad() || e !== expense) copy(price = price.finiteOrNull(), expense = e) else this
}

internal fun Hidden.finite(): Hidden =
    if (value.bad() || price.bad() || soldFor.bad()) copy(value = value.finiteOrNull(), price = price.finiteOrNull(), soldFor = soldFor.finiteOrNull()) else this

internal fun Collect.finite(): Collect = if (amount.bad()) copy(amount = null) else this

/** This dataset with every number in it a real one (see the top of this file). The same instance when it already is. */
internal fun AppData.finite(): AppData {
    val move = move.finite()
    val rooms = rooms.mapChanged { it.finite() }
    val items = items.mapChanged { it.finite() }
    val expenses = expenses.mapChanged { it.finite() }
    val proposals = proposals.mapChanged { it.finite() }
    val hidden = hidden.mapChanged { it.finite() }
    val collect = collect.mapChanged { it.finite() }
    val same = move === this.move && rooms === this.rooms && items === this.items && expenses === this.expenses &&
        proposals === this.proposals && hidden === this.hidden && collect === this.collect
    return if (same) this else copy(move = move, rooms = rooms, items = items, expenses = expenses, proposals = proposals, hidden = hidden, collect = collect)
}
