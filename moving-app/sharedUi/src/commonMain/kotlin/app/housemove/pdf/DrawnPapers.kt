package app.housemove.pdf

import app.housemove.data.AppData
import app.housemove.data.File
import app.housemove.data.Store
import app.housemove.ui.Papers

/**
 * The printouts ([Papers]) drawn with the shared layout through [writer], each made in its own file from [outFile] (the
 * folder files are shared from), with the names Android gives them. The iPhone's papers (UIKit's PDF renderer), and the
 * desktop tests'. Android keeps its own, with the same layout (app ui/AndroidPlatform.kt, pdf/).
 */
class DrawnPapers(private val writer: PaperWriter, private val outFile: (String) -> File) : Papers {
    override fun labels(codes: List<String>, sheet: LabelSheet, copies: Int, moveTag: String): File =
        writer.pdf(outFile(Printouts.LABELS)) { Printouts.labels(it, codes, sheet, copies, moveTag) }

    override fun hangTags(codes: List<String>, sheet: TagSheet, moveTag: String): File =
        writer.pdf(outFile(Printouts.HANG_TAGS)) { Printouts.hangTags(it, codes, sheet, moveTag = moveTag) }

    override fun doorSigns(data: AppData, letter: Boolean): File =
        writer.pdf(outFile(Printouts.DOOR_SIGNS)) { Printouts.doorSigns(it, data, letter) }

    override fun helperCard(data: AppData, paper: HelperCard.Paper): File =
        writer.pdf(outFile(HelperCard.fileName(paper))) { HelperCard.document(it, data, paper) }

    override fun inventory(data: AppData): File = writer.pdf(outFile(Printouts.INVENTORY)) { Printouts.inventory(it, data) }

    override fun inventoryCsv(data: AppData): File = writer.text(outFile(Printouts.INVENTORY_CSV), Printouts.inventoryCsv(data))

    override fun damageReport(store: Store): File = writer.pdf(outFile(Printouts.DAMAGE_REPORT)) { Printouts.damageReport(it, store) }

    override fun decisions(store: Store): File = writer.pdf(outFile(Decisions.fileName(store.data.value))) { Decisions.document(it, store) }
}
