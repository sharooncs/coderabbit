package app.housemove

import app.housemove.data.*
import java.io.File

/**
 * The moves on the sample phone, for the screens that list them (Your moves, the dot by the
 * move's name, which move a label is in). The sample move shown everywhere else is one of them,
 * copied in under its own move ID, so its card is the one "open now"; with it are a clear-out
 * that needs its person (a job overdue) and an old move put away under "Earlier moves".
 */
internal object AuditMoves {
    fun shelf(root: File, here: Store): Moves {
        here.flush()
        val files = File(root, "files")
        val copy = File(files, "moves/mhere").apply { mkdirs() }
        val from = here.photoDir.parentFile!!
        for (name in listOf("data.json", "device.json", "move.json")) File(from, name).takeIf { it.isFile }?.copyTo(File(copy, name), overwrite = true)
        val moves = Moves(files, File(root, "no_backup"), { "Sam's phone" })
        moves.phone.updateSettings { it.copy(name = "Sam") }

        val gran = moves.create("Gran's flat clear-out", yourName = "Sam")
        gran.setMove { it.copy(kind = MoveKind.MOVERS, moveDay = today() + 23, setupDone = true) }
        val lounge = gran.createRoom(Home.ORIGIN, "Lounge")
        for (n in listOf("Sideboard", "Piano", "Armchair", "Clock")) gran.createItem(n, lounge.id)
        gran.put(Task(id = "gran-late", title = "Book the house clearance", dueDay = today() - 3))

        val old = moves.create("Old flat", yourName = "Sam")
        old.setMove { it.copy(moveDay = today() - 400, setupDone = true) }
        old.createBox("L001", null)
        moves.archive(old.moveId)

        moves.open(here.moveId)
        moves.refresh()
        return moves
    }
}
