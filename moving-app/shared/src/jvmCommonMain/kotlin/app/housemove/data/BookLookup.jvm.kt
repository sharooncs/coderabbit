package app.housemove.data

import java.net.HttpURLConnection
import java.net.URL

// Android and the JVM: the request BookLookup.fetch made before it was shared.

internal actual fun fetchCatalogue(url: String, timeoutMs: Int): String {
    val conn = URL(url).openConnection() as HttpURLConnection
    try {
        conn.connectTimeout = timeoutMs
        conn.readTimeout = timeoutMs
        conn.setRequestProperty("User-Agent", "HouseMove (Android app)")
        conn.setRequestProperty("Accept", "application/json")
        if (conn.responseCode != 200) throw IOException(BookLookup.NOT_ANSWERING)
        return conn.inputStream.use { it.readBytes().decodeToString() }
    } finally {
        conn.disconnect()
    }
}
