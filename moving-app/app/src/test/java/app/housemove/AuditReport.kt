package app.housemove

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Writes build/audit/report.md and report.json.
 *
 * Each test of AuditChecksTest saves its findings as one "part" (a JSON file under
 * build/audit/checks/parts) and the report is then written again from all the parts
 * that exist. So it is complete once the last test has run, whatever order they run
 * in, and one test can be run again on its own.
 */
internal object AuditReport {
    /** Relative to the app module, which is where the tests run. */
    val dir = File("build/audit")
    private val parts = File(dir, "checks/parts")
    private val json = Json { prettyPrint = true; encodeDefaults = true; ignoreUnknownKeys = true }

    /** Slower than this gets marked: a function of the data layer, and opening a screen. */
    const val DATA_LIMIT_MS = 300.0
    const val SCREEN_LIMIT_MS = 1500.0

    fun save(part: PhonePart) = put("phone.json", json.encodeToString(part))
    fun save(part: SizePart) = put("size-${part.key}.json", json.encodeToString(part))
    fun save(part: SpeedPart) = put("speed.json", json.encodeToString(part))
    fun save(part: SelfCheckPart) = put("selfcheck.json", json.encodeToString(part))

    private fun put(name: String, text: String) {
        parts.mkdirs()
        File(parts, name).writeText(text)
        write()
    }

    private inline fun <reified T> read(name: String): T? = File(parts, name).takeIf { it.exists() }?.let {
        try {
            json.decodeFromString<T>(it.readText())
        } catch (_: Exception) {
            null
        }
    }

    /** The order the other sizes are written up in; sizes added later follow. */
    private val ORDER = listOf("small", "flip", "largetext")

    /** Write report.md and report.json from the parts that exist. */
    fun write() {
        val phone = read<PhonePart>("phone.json")
        val sizes = parts.listFiles().orEmpty().filter { it.name.startsWith("size-") }.sortedBy { it.name }
            .mapNotNull { read<SizePart>(it.name) }.sortedBy { ORDER.indexOf(it.key).let { i -> if (i < 0) 99 else i } }
        val speed = read<SpeedPart>("speed.json")
        val self = read<SelfCheckPart>("selfcheck.json")
        val written = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
        val worst = ArrayList<Worst>()
        val summary = ArrayList<SummaryLine>()
        val body = StringBuilder()
        touchSection(phone, body, summary, worst)
        namesSection(phone, body, summary, worst)
        contrastSection(phone, body, summary, worst)
        sizesSection(phone, sizes, body, summary, worst)
        typingSection(phone, body, summary)
        speedSection(speed, body, summary, worst)
        limitsSection(phone, sizes, body)
        val ten = worst.sortedByDescending { it.score }.take(10)

        val md = StringBuilder()
        md.appendLine("# HouseMove audit checks")
        md.appendLine()
        val seconds = (phone?.seconds ?: 0.0) + sizes.sumOf { it.seconds } + (speed?.seconds ?: 0.0) + (self?.seconds ?: 0.0)
        md.appendLine(
            "Written $written by `tools/checks.sh`." +
                (phone?.let { " ${it.screens.count { s -> s.checked }} screens were opened with ${it.dataSource}." } ?: "") +
                " The checks themselves ran for ${seconds.roundToInt()} s. They are an audit, not a gate: findings never fail the build.",
        )
        md.appendLine()
        md.appendLine(
            when {
                self == null -> "_The self-check of the checks has not been run._"
                self.wrong.isEmpty() -> "Self-check passed: run over a made-up screen with one of each kind of problem planted in it (`checks/selfcheck.png`), the checks found exactly those and nothing else."
                else -> "**The self-check FAILED, so do not trust this report until it is fixed.** On the made-up screen with planted problems: " + self.wrong.joinToString("; ") + "."
            },
        )
        md.appendLine()
        md.appendLine("## Summary")
        md.appendLine()
        md.appendLine("| Check | Findings | In short |")
        md.appendLine("|---|---:|---|")
        for (s in summary) md.appendLine("| ${s.check} | ${s.findings} | ${cell(s.text)} |")
        md.appendLine()
        md.appendLine("### The ten worst findings")
        md.appendLine()
        if (ten.isEmpty()) md.appendLine("Nothing was found.")
        ten.forEachIndexed { i, w -> md.appendLine("${i + 1}. **${w.check}.** ${w.text}") }
        md.appendLine()
        md.appendLine(
            "The order is a rough judgement of how much each one gets in a person's way: what cannot be reached or has no name first, " +
                "then slowness, then what is hard to read or hard to hit, then text that is shortened.",
        )
        md.appendLine()
        md.append(body)
        dir.mkdirs()
        File(dir, "report.md").writeText(md.toString())
        File(dir, "report.json").writeText(json.encodeToString(FullReport(written, summary, ten, phone, sizes, speed, self)))
    }

    // ---- small helpers ----

    private fun cell(s: String) = s.replace("|", "\\|").replace("\n", " ")
    private fun q(s: String) = "“$s”"
    private fun num(v: Float): String = if (v == v.roundToInt().toFloat()) v.roundToInt().toString() else v.toString()
    private fun size(w: Float, h: Float) = "${num(w)}×${num(h)}"
    private fun ms(v: Double): String = "%,d".format(v.roundToInt())
    private fun plural(n: Int, one: String, many: String = one + "s") = "$n ${if (n == 1) one else many}"

    private fun screensOf(names: Collection<String>, show: Int = 6): String {
        val d = names.distinct()
        return d.take(show).joinToString(", ") + if (d.size > show) " and ${d.size - show} more" else ""
    }

    private fun examples(texts: Collection<String>, show: Int = 3): String {
        val d = texts.filter { it.isNotBlank() }.distinct()
        return d.take(show).joinToString(", ") { q(it) } + if (d.size > show) " and ${d.size - show} more" else ""
    }

    /** From 40 and 46 make "40–46"; from 40 and 40 make "40". */
    private fun range(values: List<Float>): String = if (values.min() == values.max()) num(values.min()) else "${num(values.min())}–${num(values.max())}"

    private fun missing(body: StringBuilder, what: String) {
        body.appendLine("_This check has not been run ($what is missing). Run `tools/checks.sh`._")
        body.appendLine()
    }

    /** The marked copy of the picture a finding is on, as a link, when there is one. */
    private fun marked(phone: PhonePart, screen: String, view: Int): String {
        val file = "checks/marked/" + screen.replace(Regex("[^A-Za-z0-9_.-]+"), "_") + (if (view == 1) "" else "-$view") + ".png"
        return if (file in phone.marked) "[picture]($file)" else ""
    }

    // ---- 1 ----

    private fun touchSection(phone: PhonePart?, body: StringBuilder, summary: MutableList<SummaryLine>, worst: MutableList<Worst>) {
        body.appendLine("## 1. Touch targets")
        body.appendLine()
        if (phone == null) {
            summary += SummaryLine("1. Touch targets", 0, "not run")
            return missing(body, "the phone-size part")
        }
        val small = phone.touch.filter { it.tooSmall }
        val fine = phone.touch.filter { !it.tooSmall }
        body.appendLine(
            "Every control that can be tapped or switched, on every screen at ${phone.size}, including what is further down a scrolling screen. " +
                "Android asks for 48×48dp to touch. Compose stretches the touch area of a smaller control to 48dp by itself, but only into space that no other control claims: " +
                "a touch that lands on a neighbour, or nearer to another small control, goes there instead. " +
                "So the size a control is drawn at says little, and this table lists the controls whose touch area stays under 48dp once neighbours and the screen edge have taken their share. " +
                "The area is measured across the middle of the control, in both directions.",
        )
        body.appendLine()
        if (small.isEmpty()) {
            body.appendLine("**No control has a touch area under 48×48dp.**")
        } else {
            body.appendLine("| Screen | Control | Drawn (dp) | Touch area (dp) | What takes the space | |")
            body.appendLine("|---|---|---:|---:|---|---|")
            for ((k, list) in small.groupBy { listOf(it.screen, it.role, size(it.widthDp, it.heightDp), it.onTopOf.toString(), it.crowdedBy.isEmpty().toString()) }) {
                val t = list.first()
                val what = if (list.size == 1) "${t.role} ${q(t.label)}" else "${list.size} × ${t.role}: ${examples(list.map { it.label })}"
                val why = when {
                    list.any { it.onTopOf } -> "sits on top of " + examples(list.flatMap { it.crowdedBy }, 2) + ", which can be tapped too and gets every touch around it"
                    list.any { it.crowdedBy.isNotEmpty() } -> "next to " + examples(list.flatMap { it.crowdedBy }, 2)
                    t.atEdge -> "the edge of the screen"
                    else -> ""
                }
                body.appendLine(
                    "| ${k[0]} | ${cell(what)}${if (list.all { it.disabled }) " (greyed out)" else ""} | ${k[2]} | " +
                        "${range(list.map { it.touchWidthDp })}×${range(list.map { it.touchHeightDp })} | ${cell(why)} | ${marked(phone, t.screen, t.view)} |",
                )
            }
        }
        body.appendLine()
        if (fine.isNotEmpty()) {
            val kinds = fine.groupBy { "${it.role} ${size(it.widthDp, it.heightDp)}" }.entries.sortedByDescending { it.value.size }
            body.appendLine(
                "${plural(fine.size, "more control")} ${if (fine.size == 1) "is" else "are"} drawn smaller than 48dp but keep a full 48dp touch area, because nothing else is close: " +
                    kinds.take(8).joinToString(", ") { "${it.key} (${it.value.size})" } + (if (kinds.size > 8) " and ${kinds.size - 8} more kinds" else "") + ". These are not findings.",
            )
            body.appendLine()
        }
        for ((k, list) in small.groupBy { listOf(it.role, size(it.widthDp, it.heightDp), it.onTopOf.toString()) }) {
            val least = list.minOf { min(it.touchWidthDp, it.touchHeightDp) }
            val score = (40 + 45 * (1 - least / MIN_TOUCH_DP) + min(8, list.size / 2)).roundToInt()
            val why = if (list.any { it.onTopOf }) ", because ${if (list.size == 1) "it sits" else "they sit"} on top of something that can be tapped too" else ""
            worst += Worst(
                score, "Touch target",
                "${plural(list.size, "${k[0]} control")} drawn ${k[1]}dp ${if (list.size == 1) "has" else "have"} only " +
                    "${range(list.map { it.touchWidthDp })}×${range(list.map { it.touchHeightDp })}dp to touch$why. On ${screensOf(list.map { it.screen }, 4)}: ${examples(list.map { it.label })}.",
            )
        }
        summary += SummaryLine(
            "1. Touch targets", small.size,
            if (small.isEmpty()) "No control has less than 48×48dp to touch. ${fine.size} are drawn smaller but get the full area."
            else "${plural(small.size, "control")} on ${plural(small.map { it.screen }.distinct().size, "screen")} ${if (small.size == 1) "has" else "have"} less than 48×48dp to touch " +
                "(smallest ${small.minByOrNull { min(it.touchWidthDp, it.touchHeightDp) }!!.let { size(it.touchWidthDp, it.touchHeightDp) }}dp). ${fine.size} more are drawn small but get the full area.",
        )
    }

    // ---- 2 ----

    private fun namesSection(phone: PhonePart?, body: StringBuilder, summary: MutableList<SummaryLine>, worst: MutableList<Worst>) {
        body.appendLine("## 2. Names for screen readers")
        body.appendLine()
        if (phone == null) {
            summary += SummaryLine("2. Names for screen readers", 0, "not run")
            return missing(body, "the phone-size part")
        }
        val alone = phone.unnamed.filter { it.insideNamed.isEmpty() }
        val inRow = phone.unnamed.filter { it.insideNamed.isNotEmpty() }
        body.appendLine(
            "Controls that TalkBack stops on but cannot name: no text and no description, on the control or on anything merged into it. " +
                "A text field counts as named by its label or placeholder, not by what is typed in it. Pictures that cannot be tapped are not looked at.",
        )
        body.appendLine()
        if (alone.isEmpty()) body.appendLine("**Every control that stands alone has a name.**") else {
            body.appendLine("**Controls with no name at all**")
            body.appendLine()
            body.appendLine("| Screen | Control | Size (dp) | Find it near | |")
            body.appendLine("|---|---|---:|---|---|")
            for ((k, list) in alone.groupBy { listOf(it.screen, it.role, size(it.widthDp, it.heightDp)) }) {
                val t = list.first()
                body.appendLine(
                    "| ${k[0]} | ${if (list.size > 1) "${list.size} × " else ""}${k[1]}${t.state.let { if (it.isEmpty() || list.size > 1) "" else " ($it)" }} | ${k[2]} | " +
                        "${cell(examples(list.map { it.near }, 4))} | ${marked(phone, t.screen, t.view)} |",
                )
            }
        }
        body.appendLine()
        if (inRow.isNotEmpty()) {
            body.appendLine(
                "**Unnamed controls inside a named row.** The row around the control has a name and can be tapped, so a listener usually hears that name just before. " +
                    "But the switch or tick box is a stop of its own, and there it says only “switch, on” or “tick box, not ticked”. " +
                    "It matters most where tapping the row does something else than the control does; the check cannot tell that apart.",
            )
            body.appendLine()
            body.appendLine("| Screen | Control | Inside the rows | |")
            body.appendLine("|---|---|---|---|")
            for ((k, list) in inRow.groupBy { listOf(it.screen, it.role) }) {
                body.appendLine("| ${k[0]} | ${if (list.size > 1) "${list.size} × " else ""}${k[1]} | ${cell(examples(list.map { it.insideNamed }, 3))} | ${marked(phone, list.first().screen, list.first().view)} |")
            }
            body.appendLine()
        }
        for ((role, list) in alone.groupBy { it.role }) {
            worst += Worst(80 + min(10, list.size), "Screen reader", "${plural(list.size, "$role control")} with no name at all on ${screensOf(list.map { it.screen }, 4)}, near ${examples(list.map { it.near })}.")
        }
        for ((role, list) in inRow.groupBy { it.role }) {
            worst += Worst(
                48 + min(10, list.size / 3), "Screen reader",
                "${plural(list.size, "$role control")} without a name of ${if (list.size == 1) "its" else "their"} own, inside rows that are named, on ${screensOf(list.map { it.screen }, 4)}: " +
                    "on that stop a screen reader says only whether it is on or ticked, not what it is for. For example in ${examples(list.map { it.insideNamed }, 1)}.",
            )
        }
        summary += SummaryLine(
            "2. Names for screen readers", phone.unnamed.size,
            (if (alone.isEmpty()) "Every control that stands alone has a name." else "${plural(alone.size, "control")} on ${plural(alone.map { it.screen }.distinct().size, "screen")} ${if (alone.size == 1) "has" else "have"} no name at all.") +
                (if (inRow.isEmpty()) "" else " ${inRow.size} ${if (alone.isEmpty()) "" else "more "}${if (inRow.size == 1) "is an unnamed switch or tick box" else "are unnamed switches or tick boxes"} inside a row that is named."),
        )
    }

    // ---- 3 ----

    private fun contrastSection(phone: PhonePart?, body: StringBuilder, summary: MutableList<SummaryLine>, worst: MutableList<Worst>) {
        body.appendLine("## 3. Contrast")
        body.appendLine()
        if (phone == null) {
            summary += SummaryLine("3. Contrast (estimate from pixels)", 0, "not run")
            return missing(body, "the phone-size part")
        }
        val low = phone.lowContrast
        val measured = phone.contrastCounts.sumOf { it.measured }
        body.appendLine(
            "**This is an estimate from the pixels of the rendered screens, not a reading of the theme.** For each text the background is the commonest colour " +
                "in the patch of picture behind it, judged piece by piece along the text so that a gradient is followed. The text colour is the colour the text was given, " +
                "when that colour can be seen in the patch; otherwise it is taken from the pixels that differ most from the background. " +
                "Text needs ${CONTRAST_NORMAL}:1, or ${CONTRAST_LARGE}:1 from ${LARGE_TEXT_SP.roundToInt()}sp (${LARGE_BOLD_TEXT_SP.roundToInt()}sp when bold). " +
                "Each screen is also scrolled down, so text below the fold is measured where it shows. " +
                "Greyed-out controls are exempt and left out; so are symbols and emoji. Icons and pictures are not measured.",
        )
        body.appendLine()
        fun table(rows: List<LowContrast>) {
            body.appendLine("| Text colour | Background | Contrast | Needs | Texts | Screens | For example |")
            body.appendLine("|---|---|---:|---:|---:|---|---|")
            for ((k, list) in rows.groupBy { listOf(it.textColor, it.background, it.required.toString()) }.entries.sortedBy { it.value.minOf { f -> f.ratio / f.required } }) {
                val ratios = list.map { it.ratio }
                val r = if (ratios.max() - ratios.min() < 0.05) "%.2f".format(ratios.min()) else "%.2f–%.2f".format(ratios.min(), ratios.max())
                val mark = if (list.all { it.borderline }) " (borderline)" else ""
                body.appendLine("| `${k[0]}` | `${k[1]}` | $r$mark | ${k[2]} | ${list.size} | ${cell(screensOf(list.map { it.screen }, 5))} | ${cell(examples(list.map { it.text }))} |")
            }
            body.appendLine()
        }
        if (low.isEmpty()) {
            body.appendLine("**No text is below the contrast it needs** ($measured texts measured).")
            body.appendLine()
        } else {
            table(low)
            val byPixels = low.count { it.method == METHOD_PIXELS }
            if (byPixels > 0) {
                body.appendLine(
                    "For $byPixels of these the text colour comes from the pixels alone, because the colour on screen is not the one the text was given " +
                        "(a see-through layer over it, for instance). Those are a little less exact.",
                )
                body.appendLine()
            }
            if (phone.marked.isNotEmpty()) {
                body.appendLine("Pictures with these texts boxed in orange: " + low.map { marked(phone, it.screen, it.view) }.filter { it.isNotEmpty() }.distinct().take(12).joinToString(", ") { it.replace("[picture]", "[" + it.substringAfterLast('/').substringBefore(".png") + "]") } + ".")
                body.appendLine()
            }
        }
        if (phone.closeContrast.isNotEmpty()) {
            val pairs = phone.closeContrast.groupBy { listOf(it.textColor, it.background, it.required.toString()) }.entries.sortedBy { it.value.minOf { f -> f.ratio / f.required } }.take(6)
            body.appendLine("The colour pairs that pass by the least (worth watching when colours change):")
            body.appendLine()
            table(pairs.flatMap { it.value })
        }
        body.appendLine(
            "$measured texts were measured on ${phone.contrastCounts.count { it.measured > 0 }} screens; ${phone.contrastCounts.sumOf { it.disabledSkipped }} greyed-out and " +
                "${phone.contrastCounts.sumOf { it.symbolsSkipped }} symbol-only texts were left out. Every measurement is listed in `checks/contrast.csv`.",
        )
        if (phone.notMeasured.isNotEmpty()) {
            body.appendLine()
            body.appendLine("${plural(phone.notMeasured.size, "text")} could not be measured and should be looked at by eye:")
            body.appendLine()
            for ((why, list) in phone.notMeasured.groupBy { it.why }) {
                body.appendLine(
                    "- ${list.size} because $why: " + list.groupBy { it.screen }.entries.take(8).joinToString("; ") { "${it.key}: ${examples(it.value.map { v -> v.text }, 2)}" } +
                        (if (list.map { it.screen }.distinct().size > 8) "; and more" else ""),
                )
            }
        }
        body.appendLine()
        for ((k, list) in low.groupBy { listOf(it.textColor, it.background, it.required.toString()) }) {
            val least = list.minOf { it.ratio }
            val needs = list.first().required
            val score = (45 + 45 * (1 - least / needs) + min(8, list.size / 3)).roundToInt()
            worst += Worst(
                score, "Contrast",
                "Text `${k[0]}` on `${k[1]}` is about ${"%.1f".format(least)}:1 where ${needs}:1 is needed: ${plural(list.size, "text")} on ${screensOf(list.map { it.screen }, 4)}, such as ${examples(list.map { it.text }, 2)}.",
            )
        }
        val closest = phone.closeContrast.minByOrNull { it.ratio / it.required }
        summary += SummaryLine(
            "3. Contrast (estimate from pixels)", low.size,
            if (low.isEmpty()) "No text is below the contrast it needs. $measured measured, ${phone.notMeasured.size} could not be." +
                (closest?.let { " Closest to the limit: `${it.textColor}` on `${it.background}`, ${"%.2f".format(it.ratio)}:1 where ${it.required}:1 is needed." } ?: "")
            else "${plural(low.size, "text")} in ${plural(low.map { it.textColor + it.background }.distinct().size, "colour pair")} on ${plural(low.map { it.screen }.distinct().size, "screen")} " +
                "${if (low.size == 1) "is" else "are"} below the contrast needed (lowest about ${"%.1f".format(low.minOf { it.ratio })}:1). $measured measured, ${phone.notMeasured.size} could not be.",
        )
    }

    // ---- 4 ----

    private val KIND_WORDS = linkedMapOf(
        CUT_UNREACHABLE to "off the screen, and the screen does not scroll there",
        CUT_BEYOND_WIDTH to "reaches beyond the side of the screen",
        CUT_BY_PARENT to "cut away by the area it sits in",
        CUT_CLIPPED to "text cut off without …",
        CUT_SPILLS to "text sticks out of its control",
        CUT_ELLIPSIS to "text shortened with …",
    )
    private val HARD = setOf(CUT_UNREACHABLE, CUT_BEYOND_WIDTH, CUT_BY_PARENT)
    private val CUT = setOf(CUT_CLIPPED, CUT_SPILLS)

    private fun sizesSection(phone: PhonePart?, sizes: List<SizePart>, body: StringBuilder, summary: MutableList<SummaryLine>, worst: MutableList<Worst>) {
        body.appendLine("## 4. Screen sizes and large text")
        body.appendLine()
        if (sizes.isEmpty()) {
            summary += SummaryLine("4. Screen sizes and large text", 0, "not run")
            return missing(body, "every size part")
        }
        body.appendLine(
            "Every screen is opened again on other screens and with larger system text. Listed are texts that do not fit (the text layout itself says so), " +
                "and texts and controls that leave the screen or are cut by the area they sit in. Things that are only scrolled out of view are not listed. " +
                "Shortening a long name with … in a list is often intended; those are listed so that the ones that matter can be picked out, with how much of the text is left. " +
                "Only each screen as it opens is checked, and dialogs are not opened. Crowding and awkward wrapping do not show in numbers: look at the pictures for those.",
        )
        body.appendLine()
        val normal = phone?.cutOffs.orEmpty().map { Triple(it.screen, it.kind, it.text) }.toSet()
        body.appendLine("| Size | Screens | Off screen or cut by its area | Text cut off without … | Text shortened with … | Of all these, fine at the normal size | Pictures |")
        body.appendLine("|---|---:|---:|---:|---:|---:|---|")
        fun row(title: String, screens: Int, cuts: List<CutOff>, fresh: String, pictures: String) {
            body.appendLine("| $title | $screens | ${cuts.count { it.kind in HARD }} | ${cuts.count { it.kind in CUT }} | ${cuts.count { it.kind == CUT_ELLIPSIS }} | $fresh | `$pictures/` |")
        }
        phone?.let { row("Normal phone, ${it.size} (for comparison)", it.screens.count { s -> s.checked }, it.cutOffs, "–", it.pictures) }
        for (s in sizes) row(s.title, s.screens.count { it.checked }, s.cutOffs, s.cutOffs.count { Triple(it.screen, it.kind, it.text) !in normal }.toString(), s.pictures)
        body.appendLine()
        for (s in sizes) {
            body.appendLine("### ${s.title}")
            body.appendLine()
            if (s.cutOffs.isEmpty()) {
                body.appendLine("Nothing is cut off.")
                body.appendLine()
                continue
            }
            body.appendLine("| What happens | Text or control | Screens | Detail | Also at the normal size |")
            body.appendLine("|---|---|---|---|---|")
            val groups = s.cutOffs.groupBy { it.kind to it.text }.entries
                .sortedWith(compareBy({ KIND_WORDS.keys.indexOf(it.key.first) }, { e -> e.value.minOf { if (it.totalChars > 0) it.shownChars.toFloat() / it.totalChars else 1f } }))
            for ((k, list) in groups) {
                val also = list.count { Triple(it.screen, it.kind, it.text) in normal }
                body.appendLine(
                    "| ${KIND_WORDS[k.first] ?: k.first} | ${cell(q(k.second))} | ${cell(screensOf(list.map { it.screen }, 4))} | ${cell(list.first().detail)} | " +
                        "${if (phone == null) "?" else if (also == list.size) "yes" else if (also == 0) "no" else "on some"} |",
                )
            }
            body.appendLine()
        }
        // For the list of worst findings: one entry per screen, kind and how much of the text is gone.
        val everywhere = sizes.flatMap { s -> s.cutOffs.map { s to it } }
        fun lost(c: CutOff) = if (c.totalChars > 0) 1f - c.shownChars.coerceAtLeast(0).toFloat() / c.totalChars else 0f
        fun grade(c: CutOff) = when {
            c.kind != CUT_ELLIPSIS -> ""
            lost(c) > 0.99f -> "nothing"
            lost(c) >= 0.5f -> "most"
            else -> "some"
        }
        for ((k, list) in everywhere.groupBy { Triple(it.second.kind, it.second.screen, grade(it.second)) }) {
            val (kind, screen, grade) = k
            val texts = list.map { it.second.text }.distinct()
            val where = list.map { it.first.title.substringBefore(',').substringBefore(" (").lowercase() }.distinct().joinToString(", ")
            val n = texts.size
            val base = when (kind) {
                CUT_UNREACHABLE -> 88
                CUT_BEYOND_WIDTH -> 78
                CUT_BY_PARENT -> 70
                CUT_CLIPPED -> 60
                CUT_SPILLS -> 55
                else -> when (grade) {
                    "nothing" -> 62
                    "most" -> 44
                    else -> 24
                }
            }
            val what = when {
                grade == "nothing" -> "${plural(n, "text")} on $screen ${if (n == 1) "is" else "are"} shortened until nothing is left but …"
                grade == "most" -> "${plural(n, "text")} on $screen ${if (n == 1) "loses" else "lose"} more than half to …"
                grade == "some" -> "${plural(n, "text")} on $screen ${if (n == 1) "is" else "are"} shortened with …"
                kind == CUT_CLIPPED -> "${plural(n, "text")} on $screen ${if (n == 1) "is" else "are"} cut off without …"
                kind == CUT_SPILLS -> "${plural(n, "text")} on $screen ${if (n == 1) "sticks" else "stick"} out of ${if (n == 1) "its control" else "their controls"}"
                else -> "${plural(n, "text or control", "texts or controls")} on $screen: ${KIND_WORDS[kind]}"
            }
            worst += Worst(base + min(5, n), "Screen size", "$what ($where): ${examples(texts)}." + if (grade.isEmpty()) " ${list.first().second.detail.replaceFirstChar { it.uppercase() }}." else "")
        }
        summary += SummaryLine(
            "4. Screen sizes and large text", everywhere.size,
            sizes.joinToString("; ") { s ->
                val gone = s.cutOffs.count { it.kind == CUT_ELLIPSIS && it.shownChars <= 0 }
                "${s.title.substringBefore(',').substringBefore(" (").lowercase()}: ${s.cutOffs.count { it.kind in HARD }} off screen, ${s.cutOffs.count { it.kind in CUT }} cut off, " +
                    "${s.cutOffs.count { it.kind == CUT_ELLIPSIS }} shortened with …" + (if (gone > 0) " ($gone down to nothing)" else "")
            } + (phone?.let { ". At the normal size: ${it.cutOffs.count { c -> c.kind in HARD }}, ${it.cutOffs.count { c -> c.kind in CUT }} and ${it.cutOffs.count { c -> c.kind == CUT_ELLIPSIS }}." } ?: "."),
        )
    }

    // ---- 5 ----

    private fun typingSection(phone: PhonePart?, body: StringBuilder, summary: MutableList<SummaryLine>) {
        body.appendLine("## 5. Typing needed")
        body.appendLine()
        if (phone == null) {
            summary += SummaryLine("5. Typing needed", 0, "not run")
            return missing(body, "the phone-size part")
        }
        body.appendLine(
            "Fields that take typed text on each screen as it opens (including further down the screen). Fields in dialogs, in folded-away sections and on later steps " +
                "of a wizard appear only after a tap and are not counted. Several examples of one screen (three items, three boxes) count once in the total, at their highest.",
        )
        body.appendLine()
        val checked = phone.screens.filter { it.checked }
        val by = phone.fields.groupBy { it.screen }
        body.appendLine("| Screen | Fields | Already filled in the sample | Labels |")
        body.appendLine("|---|---:|---:|---|")
        for (s in checked) {
            val f = by[s.name].orEmpty()
            if (f.isEmpty()) continue
            body.appendLine("| ${s.name} | ${f.size} | ${f.count { it.filled }} | ${cell(f.joinToString(", ") { it.label })} |")
        }
        val perScreen = checked.groupBy { it.pattern.ifEmpty { it.name } }.mapValues { (_, runs) -> runs.maxOf { by[it.name].orEmpty().size } }
        val total = perScreen.values.sum()
        val none = checked.filter { by[it.name].isNullOrEmpty() }.map { it.name }
        body.appendLine("| **Total over ${perScreen.size} different screens** | **$total** | | |")
        body.appendLine()
        body.appendLine("No typing on: ${none.joinToString(", ").ifEmpty { "–" }}.")
        body.appendLine()
        val most = perScreen.entries.sortedByDescending { it.value }.take(3).filter { it.value > 0 }
        summary += SummaryLine(
            "5. Typing needed", total,
            "${plural(total, "text field")} on ${perScreen.count { it.value > 0 }} of ${perScreen.size} screens as they open" +
                (if (most.isEmpty()) "." else "; most on " + most.joinToString(", ") { "${it.key} (${it.value})" } + ".") + " Fields in dialogs and folded sections are not counted.",
        )
    }

    // ---- 6 ----

    private fun speedSection(speed: SpeedPart?, body: StringBuilder, summary: MutableList<SummaryLine>, worst: MutableList<Worst>) {
        body.appendLine("## 6. Speed with a large move")
        body.appendLine()
        if (speed == null) {
            summary += SummaryLine("6. Speed with a large move", 0, "not run")
            return missing(body, "the speed part")
        }
        body.appendLine(
            "A move with " + speed.records.entries.filter { it.value > 0 }.joinToString(", ") { "%,d %s".format(it.value, it.key) } +
                " (counted in the saved file, which is ${"%,d".format(speed.savedBytes / 1024)} KB; the photos are records without picture files). " +
                "Each number is the middle one of a few runs after a warm-up, in milliseconds, on the computer that ran the checks. " +
                "**A phone is several times slower than this, and drawing is not part of it, so read the numbers as a rough guide and compare them between runs rather than trusting any one.** " +
                "Marked when over ${ms(DATA_LIMIT_MS)} ms for a data function or ${ms(SCREEN_LIMIT_MS)} ms for a screen. " +
                "A screen is timed from asking for it until Compose has nothing left to do: building and laying out the screen, and stepping through the 0.7 s change-over animation, which is simulated and costs little real time. " +
                "The first row is a fixed piece of work that has nothing to do with the app: when it takes twice as long as in another report, the computer was twice as slow, and so is everything below it. " +
                "“Nodes built” says how much of the data the screen turned into things on screen: a list that builds only what shows stays small however long it is.",
        )
        body.appendLine()
        fun table(rows: List<Timing>) {
            body.appendLine("| What | Median (ms) | Limit (ms) | | Runs (ms) | With the small sample move (ms) | Nodes built |")
            body.appendLine("|---|---:|---:|---|---|---:|---:|")
            for (t in rows) {
                body.appendLine(
                    "| ${cell(t.name)}${if (t.note.isEmpty()) "" else " — " + cell(t.note)} | ${ms(t.medianMs)} | ${if (t.limitMs > 0) ms(t.limitMs) else "–"} | " +
                        "${if (t.limitMs <= 0) "" else if (t.over) "**over**" else "ok"} | " +
                        "${t.runsMs.joinToString(", ") { ms(it) }} | ${t.smallMoveMs?.let { ms(it) } ?: ""} | ${if (t.nodes > 0) "%,d".format(t.nodes) else ""} |",
                )
            }
            body.appendLine()
        }
        val real = speed.timings.filter { it.kind != "reference" }
        table(speed.timings.filter { it.kind != "other screen" })
        val others = speed.timings.filter { it.kind == "other screen" }
        if (others.isNotEmpty()) {
            body.appendLine("Every other screen was opened with the large move as well, because any of them may be the one that does not keep up. Slowest first:")
            body.appendLine()
            table(others.sortedByDescending { it.medianMs })
        }
        for (n in speed.notes) body.appendLine("- $n")
        if (speed.notes.isNotEmpty()) body.appendLine()
        val over = real.filter { it.over }
        for (t in over) {
            val times = t.medianMs / t.limitMs
            worst += Worst((72 + min(26.0, 13 * (times - 1))).roundToInt(), "Speed", "${t.name} takes ${ms(t.medianMs)} ms with the large move (limit ${ms(t.limitMs)} ms).")
        }
        val slowestData = real.filter { it.kind == "data" }.maxByOrNull { it.medianMs }
        val slowestScreen = real.filter { it.kind != "data" }.maxByOrNull { it.medianMs }
        val reference = speed.timings.firstOrNull { it.kind == "reference" }
        summary += SummaryLine(
            "6. Speed with a large move", over.size,
            (if (over.isEmpty()) "All ${real.size} timings are within the limits."
            else "${over.size} of ${real.size} timings are over the limit: " + over.sortedByDescending { it.medianMs / it.limitMs }.take(3).joinToString("; ") { "${it.name} ${ms(it.medianMs)} ms" } + ".") +
                " Slowest data function: " + (slowestData?.let { "${it.name}, ${ms(it.medianMs)} ms" } ?: "–") +
                "; slowest screen: " + (slowestScreen?.let { "${it.name.removePrefix("Open the ").removeSuffix(" screen")}, ${ms(it.medianMs)} ms" } ?: "–") +
                ". Computer time, not phone time" + (reference?.let { "; the reference work took ${ms(it.medianMs)} ms" } ?: "") + ".",
        )
    }

    // ---- what was and was not checked ----

    private fun limitsSection(phone: PhonePart?, sizes: List<SizePart>, body: StringBuilder) {
        body.appendLine("## What was and was not checked")
        body.appendLine()
        if (phone != null) {
            val ok = phone.screens.filter { it.checked }
            body.appendLine("Screens checked (${ok.size}): " + ok.joinToString(", ") { it.name + if (it.views > 1) " (${it.views} views)" else "" } + ". A view is one screenful; long screens are scrolled down and looked at again.")
            body.appendLine()
            val added = ok.filter { it.from.isNotEmpty() }
            if (added.isNotEmpty()) {
                body.appendLine("Picked up without being listed in the checks: " + added.joinToString(", ") { "${it.name} (${it.from})" } + ".")
                body.appendLine()
            }
        }
        val skipped = (phone?.screens.orEmpty() + sizes.flatMap { it.screens }).filter { !it.checked }.distinctBy { it.name + it.note }
        if (skipped.isNotEmpty()) {
            body.appendLine("Not checked:")
            body.appendLine()
            for (s in skipped) body.appendLine("- `${s.route}`: ${s.note}")
            body.appendLine()
        }
        body.appendLine(
            "Not covered by any of these checks: dialogs, menus and sheets that open after a tap; folded-away sections; wizard steps after the first; the variants of Home (helper, movers, inventory); " +
                "the camera views; dark mode; the keyboard being open; real photos; and how fast a real phone is.",
        )
        body.appendLine()
        body.appendLine(
            "The same data is in `report.json` next to this file. Pictures of every screen as it was checked are under `${phone?.pictures ?: "checks/phone"}/` and `sizes/`. " +
                "Screens with findings also have a copy under `checks/marked/` with a box round each one: red for a touch area under 48dp, purple for a control without a name, orange for low contrast.",
        )
    }
}
