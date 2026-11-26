package app.housemove.pdf

import app.housemove.data.*

/**
 * The half-page card for Helpers (design 2.1, section 2): five steps that also work without the app,
 * the door-sign colours of the new home, and what to write on a box with no label ("write KIT on two
 * sides"). A page holds two cards, one above the other, to cut apart. A4 or Letter.
 *
 * Its words are here, shared by the screens; Android draws it as a PDF (app pdf/HelperCard.kt).
 */
object HelperCard {

    /** Page sizes in PDF points. */
    enum class Paper(val w: Int, val h: Int) { A4(595, 842), LETTER(612, 792) }

    /** One step on the card: a few words in bold, then one line on how. */
    class Step(val title: String, val how: String)

    /** The code to write on a box with no label: the new kitchen's, or the first new room's, else "KIT". */
    fun exampleCode(data: AppData): String {
        val rooms = data.roomsOf(Home.DEST)
        val r = rooms.firstOrNull { it.name.contains("kitchen", ignoreCase = true) } ?: rooms.firstOrNull()
        return r?.let { it.code.ifBlank { suggestRoomCode(it.name) } } ?: "KIT"
    }

    /** The five steps, in the words of the move ("van" or "truck", the new home's name). */
    fun steps(data: AppData): List<Step> {
        val van = data.vehicle()
        val code = exampleCode(data)
        val dest = data.move.destName.ifBlank { "the new home" }
        return listOf(
            Step("Pack one room at a time", "Heavy things in small boxes, light things in big ones. Fill each box to the top and tape it shut."),
            Step("Mark every box", "Stick a label on the top and one side. No label? Write the room's code on two sides: $code."),
            Step("Load the $van", "Heavy and big things first, against the front. First-night boxes last. With the app, scan each label as it goes on."),
            Step("Carry it to its colour", "Every room in $dest has a sign on its door in its own colour. Match the code on the box to the sign."),
            Step("Say when something is wrong", "Damaged? Photograph it before it is moved again. Not sure where it goes? Ask an Organiser."),
        )
    }

    /** The new home's rooms as their door signs: colour, code and name. */
    fun doorSigns(data: AppData): List<Triple<RoomColor, String, String>> =
        data.roomsOf(Home.DEST).map { r -> Triple(roomColor(r.color), r.code.ifBlank { suggestRoomCode(r.name) }, r.name) }
}
