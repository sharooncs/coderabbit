package app.housemove

import app.housemove.data.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Rules worked out from the data alone, with no phone, file or network: on the JVM and on the iPhone simulator.
 * (From DataTest, NoTypingTest, RoomNamesTest and BarcodeTest.)
 */
class LogicCommonTest {
    @Test
    fun fitCheck() {
        val wardrobe = Item(id = "w", widthCm = 120.0, depthCm = 60.0, heightCm = 210.0)
        assertEquals(Fit.OK, fitThrough(wardrobe, 76.0))          // 60 through a 76 door, 120 under the lintel
        assertEquals(Fit.TIGHT, fitThrough(wardrobe, 63.0))
        assertEquals(Fit.NO, fitThrough(wardrobe, 55.0))
        val sofa = Item(id = "s", widthCm = 220.0, depthCm = 95.0, heightCm = 90.0)
        assertEquals(Fit.NO, fitThrough(sofa, 76.0))
        assertEquals(Fit.UNKNOWN, fitThrough(Item(id = "x", widthCm = 10.0), 76.0))
    }

    @Test
    fun duplicateLabelsAreReported() {
        val d = AppData(
            boxes = listOf(Box(id = "1", label = "A1"), Box(id = "2", label = "a1"), Box(id = "3", label = "A2")),
        )
        assertEquals(listOf("A1"), d.duplicateLabels())
    }

    @Test
    fun taskDueDatesFollowMoveDay() {
        val d = AppData(move = MoveInfo(moveDay = 20_000), tasks = seedTasks())
        val first = d.liveTasks.minOf { d.dueDay(it)!! }
        assertEquals(20_000L - 42, first)
        assertNull(AppData(tasks = seedTasks()).let { it.dueDay(it.tasks.first()) })
    }

    @Test
    fun aKindAndSizeOfContainerIsSuggested() {
        assertEquals(ContainerSuggestion(ContainerKind.CARTON, BoxSize.M), suggestContainer("Kitchen"))
        assertEquals(ContainerSuggestion(ContainerKind.CARTON, BoxSize.S), suggestContainer("Office", "books")) // heavy: small box
        assertEquals(ContainerSuggestion(ContainerKind.CARTON, BoxSize.S), suggestContainer("Study"))
        assertEquals(ContainerSuggestion(ContainerKind.CARTON, BoxSize.L), suggestContainer("Bedroom 2")) // light and bulky: large box
        assertEquals(ContainerSuggestion(ContainerKind.WARDROBE, BoxSize.L), suggestContainer("Bedroom", "winter coats, dresses"))
        assertEquals(ContainerSuggestion(ContainerKind.BAG, BoxSize.L), suggestContainer("Bedroom", "duvet and pillows"))
        assertEquals(ContainerSuggestion(ContainerKind.CRATE, BoxSize.M), suggestContainer("Garage"))
        assertEquals(ContainerSuggestion(ContainerKind.CARTON, BoxSize.M), suggestContainer(null))
        assertEquals(ContainerSuggestion(ContainerKind.CARTON, BoxSize.S), suggestContainer("Lounge", category = "Books"))
    }

    @Test
    fun spokenOrScannedTextBecomesContentsLines() {
        assertEquals(listOf("Plates", "bowls", "the big pans"), contentsLines("Plates, bowls and the big pans"))
        assertEquals(
            listOf("Plates", "bowls", "Mugs", "cups", "tea towels"),
            contentsLines("Plates, bowls and plates\n  Mugs & cups;BOWLS,, and tea towels. "),
        )
        assertEquals(listOf("candles", "handbags"), contentsLines("candles and handbags")) // "and" inside a word is left alone
        assertEquals(listOf("books", "games", "toys"), contentsLines("books plus games And toys"))
        assertTrue(contentsLines("").isEmpty())
        assertTrue(contentsLines(" , ;\n and ").isEmpty())
        assertEquals("Plates, bowls, Mugs", addContents("Plates, bowls", "Mugs", "plates"))
        assertEquals("Mugs", addContents("", "Mugs"))
        assertEquals("Plates", removeContents("Plates, Mugs", " mugs "))
    }

    @Test
    fun usualOtherNamesAreOfferedForTheRoom() {
        assertTrue("Guest room" in roomRenames("Bedroom 2"))
        assertTrue("En suite" in roomRenames("Bathroom"))
        assertTrue(roomRenames("Garden / shed").isNotEmpty())
        assertTrue(roomRenames("Something odd").isNotEmpty())
    }

    @Test
    fun isbnAndBarcodeNumberRecognition() {
        assertTrue(isIsbn("9780140328721"))
        assertTrue(isIsbn("978-0-14-032872-1"))
        assertTrue(isIsbn("0140328726"))
        assertTrue(isIsbn("080442957X"))
        assertFalse(isIsbn("9780140328722")) // wrong check digit
        assertFalse(isIsbn("5012345678900")) // a product, not a book
        assertFalse(isIsbn("Dune"))
        assertTrue(looksLikeBarcodeNumber("5012345678900"))
        assertTrue(looksLikeBarcodeNumber("96385074"))
        assertFalse(looksLikeBarcodeNumber("Garden tools"))
        assertFalse(looksLikeBarcodeNumber("L042"))
        assertFalse(looksLikeBarcodeNumber("1984")) // a title that happens to be digits
    }

    @Test
    fun catalogueReplyIsUnderstood() {
        val reply = """{"ISBN:9780140328721": {"url": "https://openlibrary.org/books/OL7353617M/Fantastic_Mr._Fox",
            "key": "/books/OL7353617M", "title": "Fantastic Mr. Fox", "authors": [{"url": "https://openlibrary.org/authors/OL34184A/Roald_Dahl",
            "name": "Roald Dahl"}, {"name": "Quentin Blake"}], "number_of_pages": 96, "publish_date": "October 1, 1988"}}"""
        assertEquals(BookInfo("Fantastic Mr. Fox", "Roald Dahl, Quentin Blake"), BookLookup.parse(reply))
        assertEquals(BookInfo("Dune: A novel", ""), BookLookup.parse("""{"ISBN:1": {"title": "Dune", "subtitle": "A novel"}}"""))
        assertNull(BookLookup.parse("{}")) // not in the catalogue
        assertNull(BookLookup.parse("<html>Service unavailable</html>"))
        assertNull(BookLookup.parse("""{"ISBN:1": {"authors": []}}"""))
        assertTrue(BookLookup.url("9780140328721").startsWith("https://openlibrary.org/api/books?bibkeys=ISBN:9780140328721"))
    }
}
