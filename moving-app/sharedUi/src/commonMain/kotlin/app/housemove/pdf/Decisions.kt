package app.housemove.pdf

import app.housemove.data.*

/**
 * "Ask for decisions" (design 2.1, section 4): a picture list of the things nobody has decided on yet, for family who
 * never visit. Each has its number, its photo, its name and the room it is in, and the five answers to tick; they
 * reply in any messaging app ("3 sell"), and the Organiser taps the answers into Triage. Nothing else is printed:
 * no prices, values or serial numbers.
 *
 * What is on it is here, shared by the screens; Android draws it as a PDF (app pdf/Decisions.kt).
 */
object Decisions {
    /** Things on one page. */
    const val PER_PAGE = 6

    /** The answers, in the app's words. */
    val ANSWERS = listOf(Disposition.MOVE, Disposition.SELL, Disposition.DONATE, Disposition.BIN, Disposition.STORE).map { it.label }

    /** What there is to ask about: the things with no decision yet, room by room, as Triage lists them. */
    fun toAsk(data: AppData): List<Item> = data.liveItems.filter { it.disposition == Disposition.UNDECIDED }.sortedWith(
        compareBy({ data.room(it.originRoomId)?.sort ?: Int.MAX_VALUE }, { data.room(it.originRoomId)?.name ?: "~" }, { it.title().lowercase() }, { it.id }),
    )

    /** How many pages [n] things take. */
    fun pages(n: Int): Int = maxOf(1, (n + PER_PAGE - 1) / PER_PAGE)

    /** The photo printed for an item: its first ordinary photo (never a receipt or a serial label). */
    fun photoOf(data: AppData, item: Item): Photo? =
        data.photosOf(item.id).firstOrNull { it.kind == PhotoKind.GENERAL } ?: data.photosOf(item.id).firstOrNull { it.kind !in HIDDEN_FROM_HELPERS }
}
