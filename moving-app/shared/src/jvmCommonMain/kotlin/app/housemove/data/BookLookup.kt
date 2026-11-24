package app.housemove.data

import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

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

    /** Blocking; call off the main thread. Null when not listed; throws [IOException] when there is no connection. */
    fun fetch(isbn: String, timeoutMs: Int = 7000): BookInfo? {
        val clean = isbn.filter { it.isDigit() || it == 'X' || it == 'x' }
        val conn = URL(url(clean)).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = timeoutMs
            conn.readTimeout = timeoutMs
            conn.setRequestProperty("User-Agent", "HouseMove (Android app)")
            conn.setRequestProperty("Accept", "application/json")
            if (conn.responseCode != 200) throw IOException("The book catalogue is not answering just now. The title can be looked up later.")
            return parse(conn.inputStream.use { it.readBytes().decodeToString() })
        } finally {
            conn.disconnect()
        }
    }
}
