package app.housemove.data

import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

data class BookInfo(val title: String, val author: String)

/**
 * Looks a book up by its barcode in Open Library, a free public catalogue.
 * This is the only thing in the app that uses the internet, and only when the
 * person has switched it on. Nothing but the barcode number is sent.
 */
object BookLookup {
    fun url(isbn: String) = "https://openlibrary.org/api/books?bibkeys=ISBN:$isbn&format=json&jscmd=data"

    /** Pick the title and authors out of the catalogue's reply. Null when the book is not listed. */
    fun parse(json: String): BookInfo? = try {
        val book = AppJson.parseToJsonElement(json).jsonObject.values.firstOrNull()?.jsonObject
        val title = book?.get("title")?.jsonPrimitive?.content?.trim().orEmpty()
        if (book == null || title.isEmpty()) null else {
            val subtitle = book["subtitle"]?.jsonPrimitive?.content?.trim().orEmpty()
            val authors = book["authors"]?.jsonArray
                ?.mapNotNull { it.jsonObject["name"]?.jsonPrimitive?.content?.trim() }
                ?.filter { it.isNotEmpty() }.orEmpty()
            BookInfo(if (subtitle.isEmpty()) title else "$title: $subtitle", authors.joinToString(", "))
        }
    } catch (_: Exception) {
        null
    }

    /** What [fetch] says when the catalogue answers with anything but the book. */
    const val NOT_ANSWERING = "The book catalogue is not answering just now. The title can be looked up later."

    /** Blocking; call off the main thread. Null when not listed; throws [IOException] when there is no connection. */
    fun fetch(isbn: String, timeoutMs: Int = 7000): BookInfo? {
        val clean = isbn.filter { it.isDigit() || it == 'X' || it == 'x' }
        return parse(fetchCatalogue(url(clean), timeoutMs))
    }
}

/**
 * The catalogue's answer at [url] as text, waiting up to [timeoutMs] milliseconds to connect and to read. Throws
 * IOException ([BookLookup.NOT_ANSWERING] when it answers with anything but 200). Android and the JVM ask with
 * HttpURLConnection; book titles are not looked up on the iPhone v1.
 */
internal expect fun fetchCatalogue(url: String, timeoutMs: Int): String
