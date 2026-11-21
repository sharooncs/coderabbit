package app.housemove

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs

/*
 * The checks checked. A made-up screen has one of each kind of problem planted in
 * it, next to the same thing done right. The audit checks are run over it and must
 * find exactly the planted problems: nothing missed, nothing else reported.
 *
 * This is what makes a clean report believable ("no text is below the contrast it
 * needs" could also mean the contrast check sees nothing). It is also the one place
 * where the audit fails the build: when the checks themselves stop working.
 */

private val Dusk = Brush.horizontalGradient(listOf(Color(0xFF101030), Color(0xFF503070)))

@Composable
internal fun PlantedProblems() {
    MaterialTheme {
        Surface(color = Color.White) {
            Column(Modifier.fillMaxSize().padding(16.dp)) {
                // -- contrast: each "low" one is planted, each "fine" one must pass
                Text("Pale grey is low", color = Color(0xFF999999)) // 2.85:1
                Text("Dark grey is fine", color = Color(0xFF595959)) // 7:1
                Box(Modifier.background(Color(0xFFFFEB3B))) { Text("White on yellow is low", color = Color.White) } // 1.2:1
                Box(Modifier.background(Color(0xFFFFEB3B))) { Text("Black on yellow is fine", color = Color.Black) }
                Box(Modifier.alpha(0.4f)) { Text("Faded by a layer is low", color = Color.Black) } // black at 40%: 2.85:1, visible only in the pixels
                Text("Faint see-through colour is low", color = Color.Black.copy(alpha = 0.3f)) // 2.1:1
                Text("Strong see-through colour is fine", color = Color.Black.copy(alpha = 0.8f))
                Text("Centred in its own width is fine", color = Color(0xFF595959), textAlign = TextAlign.Center)
                Text("Large grey title is fine", color = Color(0xFF8A8A8A), fontSize = 28.sp) // 3.45:1, enough for large text
                Text("Same grey small is low", color = Color(0xFF8A8A8A)) // 3.45:1, not enough for small text
                Box(Modifier.background(Dusk)) { Text("Light on a gradient is fine", color = Color.White) }
                Box(Modifier.background(Dusk)) { Text("Dim on a gradient is low", color = Color(0xFF5A5A7A)) } // 1.6 to 2.8:1
                Box(
                    Modifier.drawBehind {
                        // Stripes of many colours: no single background to measure against.
                        val colours = listOf(0xFF204060, 0xFFE0C080, 0xFF80A040, 0xFFC04040, 0xFF6040A0, 0xFFF0F0F0, 0xFF303030)
                        val w = 5.dp.toPx()
                        var x = 0f
                        var i = 0
                        while (x < size.width) {
                            drawRect(Color(colours[i++ % colours.size]), Offset(x, 0f), Size(w, size.height))
                            x += w
                        }
                    },
                ) { Text("Over a pattern cannot be measured", color = Color.White) }
                Box {
                    Text("Under a see-through bar cannot be measured", color = Color.Black)
                    Box(Modifier.matchParentSize().background(Color.White.copy(alpha = 0.7f)).semantics { contentDescription = "A bar drawn over the text" })
                }
                Spacer(Modifier.height(24.dp))

                // -- touch targets
                Row {
                    Box(Modifier.size(24.dp).background(Color.Red).clickable {}.semantics { contentDescription = "Tiny and crowded one" })
                    Box(Modifier.size(24.dp).background(Color.Blue).clickable {}.semantics { contentDescription = "Tiny and crowded two" })
                }
                Spacer(Modifier.height(40.dp))
                Box(Modifier.size(24.dp).background(Color.Green).clickable {}.semantics { contentDescription = "Tiny but alone" })
                Spacer(Modifier.height(40.dp))
                Box(Modifier.size(120.dp, 64.dp)) {
                    // Two neighbours, one drawn over the other: touches around the small one go to the big one.
                    Box(Modifier.matchParentSize().background(Color.LightGray).clickable {}.semantics { contentDescription = "Big picture" })
                    Box(Modifier.align(Alignment.Center).size(30.dp).background(Color.DarkGray).clickable {}.semantics { contentDescription = "Button on the picture" })
                }
                Spacer(Modifier.height(24.dp))

                // -- names
                Box(Modifier.size(56.dp).background(Color.Cyan).clickable {})
                IconButton(onClick = {}) { Icon(Icons.Default.Delete, contentDescription = null) }
                IconButton(onClick = {}) { Icon(Icons.Default.Delete, contentDescription = "Named bin") }
                Row(Modifier.fillMaxWidth().clickable {}, verticalAlignment = Alignment.CenterVertically) {
                    Text("Named row", Modifier.weight(1f))
                    Switch(checked = true, onCheckedChange = {})
                }
                Spacer(Modifier.height(24.dp))

                // -- text that does not fit
                Text("An ellipsis ends this long line of words that cannot possibly fit on one line", maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("Cut clean off is this long line of words that cannot possibly fit on one line", maxLines = 1, overflow = TextOverflow.Clip)
                Text("Short and centred", Modifier.fillMaxWidth(), maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
                Text("These are quite a few words that wrap onto a second line, which is fine as long as nothing gets lost")
                Text("Pushed off the side", Modifier.offset(x = 330.dp), softWrap = false)
                Text("Below the bottom of a screen that does not scroll", Modifier.offset(y = 2000.dp))
                Spacer(Modifier.height(24.dp))
                Box(Modifier.height(10.dp).clipToBounds()) { Text("Sliced by the box it sits in", Modifier.wrapContentHeight(unbounded = true)) }
                Spacer(Modifier.height(40.dp))
                Box(Modifier.size(60.dp, 30.dp).background(Color.LightGray).clickable {}) { Text("Too long for its button", Modifier.wrapContentWidth(Alignment.Start, unbounded = true), softWrap = false) }
                Spacer(Modifier.height(40.dp))
                Spacer(Modifier.height(24.dp))

                // -- typing
                OutlinedTextField(value = "", onValueChange = {}, label = { Text("Field with a label") }, modifier = Modifier.width(300.dp))
                OutlinedTextField(value = "typed", onValueChange = {}, modifier = Modifier.width(300.dp))
            }
        }
    }
}

/** Run the checks over the planted screen. The answer lists everything that is not as planted; empty means all is well. */
internal fun selfCheck(tree: AuditTree, px: Pixels): List<String> {
    val wrong = ArrayList<String>()
    fun same(what: String, found: Collection<String>, planted: Collection<String>) {
        val missed = planted.filter { p -> found.none { it.startsWith(p) } }
        val extra = found.filter { f -> planted.none { f.startsWith(it) } }
        if (missed.isNotEmpty()) wrong += "$what: missed $missed"
        if (extra.isNotEmpty()) wrong += "$what: wrongly reported $extra"
    }

    val found = ContrastResult()
    contrast(tree, px, "self-check", 1, HashSet(), found)
    found.settle()
    same(
        "contrast", found.low.map { it.text },
        listOf("Pale grey is low", "White on yellow is low", "Faded by a layer is low", "Faint see-through colour is low", "Same grey small is low", "Dim on a gradient is low"),
    )
    same("contrast, not measurable", found.notMeasured.map { it.text }, listOf("Over a pattern cannot be measured", "Under a see-through bar cannot be measured"))
    fun ratio(text: String, wanted: Double, slack: Double) {
        val got = found.low.firstOrNull { it.text.startsWith(text) }?.ratio ?: return
        if (abs(got - wanted) > slack) wrong += "contrast: \"$text\" measured $got:1, should be about $wanted:1"
    }
    ratio("Pale grey is low", 2.85, 0.05)
    ratio("White on yellow is low", 1.23, 0.05)
    ratio("Faded by a layer is low", 2.85, 0.3) // from pixels alone
    ratio("Faint see-through colour is low", 2.09, 0.1)
    ratio("Same grey small is low", 3.45, 0.05)
    if (found.measured < 12) wrong += "contrast: only ${found.measured} texts measured"

    val touch = touchTargets(tree, "self-check", 1)
    same("touch targets", touch.filter { it.tooSmall }.map { it.label }, listOf("Tiny and crowded one", "Tiny and crowded two", "Button on the picture"))
    if (touch.none { it.label.startsWith("Tiny but alone") && !it.tooSmall && it.touchWidthDp >= MIN_TOUCH_DP - 0.5f }) wrong += "touch targets: the small control that stands alone should get its full 48dp"
    touch.firstOrNull { it.label.startsWith("Tiny and crowded one") }?.let {
        if (abs(it.touchWidthDp - 36f) > 1.5f || abs(it.touchHeightDp - 48f) > 1.5f) wrong += "touch targets: the crowded control should have 36×48dp to touch, not ${it.touchWidthDp}×${it.touchHeightDp}"
    }
    touch.firstOrNull { it.label.startsWith("Button on the picture") }?.let {
        if (abs(it.touchWidthDp - 30f) > 1.5f || !it.onTopOf) wrong += "touch targets: the button on the picture should keep only its own 30dp, not ${it.touchWidthDp}"
    }

    val noName = unnamed(tree, "self-check", 1)
    if (noName.count { it.insideNamed.isEmpty() && !it.textField } != 2) wrong += "names: should find the 2 unnamed controls that stand alone, found ${noName.count { it.insideNamed.isEmpty() && !it.textField }}"
    if (noName.count { it.textField } != 1) wrong += "names: should find the 1 text field without a label, found ${noName.count { it.textField }}"
    if (noName.count { it.insideNamed.startsWith("Named row") } != 1) wrong += "names: should find the 1 unnamed switch inside the named row"
    if (noName.size != 4) wrong += "names: 4 unnamed controls are planted, ${noName.size} reported"

    val cuts = cutOffs(tree, "self-check")
    same("shortened text", cuts.filter { it.kind == CUT_ELLIPSIS }.map { it.text }, listOf("An ellipsis ends"))
    same("cut-off text", cuts.filter { it.kind == CUT_CLIPPED }.map { it.text }, listOf("Cut clean off"))
    same("beyond the screen", cuts.filter { it.kind == CUT_BEYOND_WIDTH }.map { it.text }, listOf("Pushed off the side"))
    same("off the screen for good", cuts.filter { it.kind == CUT_UNREACHABLE }.map { it.text }, listOf("Below the bottom of a screen"))
    same("cut by its area", cuts.filter { it.kind == CUT_BY_PARENT }.map { it.text }, listOf("Sliced by the box"))
    same("sticking out of its control", cuts.filter { it.kind == CUT_SPILLS }.map { it.text }, listOf("Too long for its button"))

    val fields = textFields(tree, "self-check")
    if (fields.size != 2) wrong += "typing: 2 text fields are planted, ${fields.size} counted"
    if (fields.none { it.label.startsWith("Field with a label") }) wrong += "typing: the label of the first field was not read"
    if (fields.count { it.filled } != 1) wrong += "typing: exactly one field has something typed in it"
    return wrong
}
