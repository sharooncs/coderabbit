package app.housemove

import kotlinx.serialization.Serializable

/*
 * What the audit checks find, as plain data. AuditEngine fills these in, each test of
 * AuditChecksTest saves its share as one "part", and AuditReport writes them up as
 * report.md and puts them all together in report.json.
 */

// ---- findings ----------------------------------------------------------------------

/** One screen the checks tried to open. */
@Serializable
internal data class ScreenRun(
    val name: String,
    val route: String,
    /** The route as the navigation graph spells it, e.g. "item/{id}". */
    val pattern: String = "",
    val checked: Boolean = true,
    /** Why it was not checked, or what went wrong. */
    val note: String = "",
    /** How many views (the screen as it opens, then scrolled further down) were looked at. */
    val views: Int = 1,
    /** Where the route came from: the list in AuditChecksTest, AuditShotsTest, or the navigation graph. */
    val from: String = "",
)

@Serializable
internal data class TouchTarget(
    val screen: String,
    val label: String,
    val role: String,
    /** Size it is drawn at. */
    val widthDp: Float,
    val heightDp: Float,
    /** Size of the area that answers to a finger, across its middle. */
    val touchWidthDp: Float,
    val touchHeightDp: Float,
    val tooSmall: Boolean,
    /** Neighbours that take part of the 48dp it should have. */
    val crowdedBy: List<String> = emptyList(),
    /** It is drawn over a bigger control (a button on a tappable picture), which gets every touch around it. */
    val onTopOf: Boolean = false,
    val atEdge: Boolean = false,
    val disabled: Boolean = false,
    val xDp: Float = 0f,
    val yDp: Float = 0f,
    val view: Int = 1,
    val key: String = "",
)

@Serializable
internal data class Unnamed(
    val screen: String,
    val role: String,
    val state: String = "",
    val widthDp: Float,
    val heightDp: Float,
    val xDp: Float,
    val yDp: Float,
    /** Name of the row or card around it, when that one is named and tappable itself. */
    val insideNamed: String = "",
    /** The nearest text on screen, to find it by. */
    val near: String = "",
    val textField: Boolean = false,
    val view: Int = 1,
    val key: String = "",
)

@Serializable
internal data class LowContrast(
    val screen: String,
    val text: String,
    val ratio: Double,
    val required: Double,
    val textColor: String,
    val background: String,
    val sizeSp: Float = 0f,
    val bold: Boolean = false,
    val method: String = "",
    /** Within a tenth of the limit and taken from pixels alone: could be either side. */
    val borderline: Boolean = false,
    val view: Int = 1,
    val xDp: Float = 0f,
    val yDp: Float = 0f,
    val widthDp: Float = 0f,
    val heightDp: Float = 0f,
)

@Serializable
internal data class NotMeasured(val screen: String, val text: String, val why: String)

@Serializable
internal data class TypedField(val screen: String, val label: String, val hint: String = "", val filled: Boolean = false, val key: String = "")

@Serializable
internal data class CutOff(
    val screen: String,
    val kind: String,
    val text: String,
    val detail: String = "",
    /** For text shortened with …: how many of its characters still show. */
    val shownChars: Int = -1,
    val totalChars: Int = 0,
)

@Serializable
internal data class ContrastCount(val screen: String, val measured: Int, val low: Int, val notMeasured: Int, val disabledSkipped: Int, val symbolsSkipped: Int)

/** Everything found at the normal phone size. */
@Serializable
internal data class PhonePart(
    val size: String,
    val dataSource: String,
    val screens: List<ScreenRun>,
    val touch: List<TouchTarget>,
    val unnamed: List<Unnamed>,
    val lowContrast: List<LowContrast>,
    /** Texts that pass by less than half as much again as they need: the ones to watch. */
    val closeContrast: List<LowContrast>,
    val notMeasured: List<NotMeasured>,
    val contrastCounts: List<ContrastCount>,
    val fields: List<TypedField>,
    val cutOffs: List<CutOff>,
    val pictures: String,
    /** Copies of the pictures that have findings, with a box drawn round each one. */
    val marked: List<String> = emptyList(),
    val seconds: Double,
)

/** What does not fit at one other screen size or text size. */
@Serializable
internal data class SizePart(
    val key: String,
    val title: String,
    val widthDp: Int,
    val heightDp: Int,
    val fontScale: Float,
    val screens: List<ScreenRun>,
    val cutOffs: List<CutOff>,
    val pictures: String,
    val seconds: Double,
)

@Serializable
internal data class Timing(
    val name: String,
    /** "data" for a function of the data layer, "screen" for opening a screen. */
    val kind: String,
    val medianMs: Double,
    val runsMs: List<Double>,
    val limitMs: Double,
    val over: Boolean,
    /** The same thing with the small sample move, for comparison (screens only). */
    val smallMoveMs: Double? = null,
    /** How many semantics nodes the screen held once open: a measure of how much it builds. */
    val nodes: Int = 0,
    val note: String = "",
)

@Serializable
internal data class SpeedPart(val records: Map<String, Int>, val word: String, val timings: List<Timing>, val notes: List<String> = emptyList(), val seconds: Double)

/** What the checks made of the made-up screen with planted problems. Empty [wrong] means they found exactly those. */
@Serializable
internal data class SelfCheckPart(val wrong: List<String>, val seconds: Double)

@Serializable
internal data class Worst(val score: Int, val check: String, val text: String)

@Serializable
internal data class SummaryLine(val check: String, val findings: Int, val text: String)

@Serializable
internal data class FullReport(
    val written: String,
    val summary: List<SummaryLine>,
    val worst: List<Worst>,
    val phone: PhonePart? = null,
    val sizes: List<SizePart> = emptyList(),
    val speed: SpeedPart? = null,
    val selfCheck: SelfCheckPart? = null,
)
