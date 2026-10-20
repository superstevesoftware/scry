package app.scry

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * A multipart/form-data body whose exact length is known before any byte is sent, so a large
 * upload can be streamed with a fixed Content-Length instead of being buffered in memory.
 */
class MultipartBody(val boundary: String, private val parts: List<Part>) {
    /** One file field. [size] must equal the number of bytes [open] will yield. */
    class Part(val field: String, val fileName: String, val mime: String, val size: Long, val open: () -> InputStream)

    val contentType: String get() = "multipart/form-data; boundary=$boundary"

    private val closing = "--$boundary--\r\n".toByteArray(Charsets.UTF_8)

    private fun head(p: Part): ByteArray =
        ("--$boundary\r\n" +
            "Content-Disposition: form-data; name=\"${p.field}\"; filename=\"${escape(p.fileName)}\"\r\n" +
            "Content-Type: ${p.mime}\r\n\r\n").toByteArray(Charsets.UTF_8)

    /** Exact size of the whole body in bytes. */
    val contentLength: Long
        get() = parts.sumOf { head(it).size + it.size + 2 /* trailing CRLF */ } + closing.size

    /**
     * Streams the body. Throws if a file does not yield exactly the size it declared: with a
     * fixed length a short or long write would corrupt the request, so it must fail loudly.
     */
    fun writeTo(out: OutputStream, onBytes: (Long) -> Unit = {}) {
        val buf = ByteArray(64 * 1024)
        for (p in parts) {
            out.write(head(p))
            var sent = 0L
            p.open().use { input ->
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    sent += n
                    if (sent > p.size) throw IOException("${p.fileName} is larger than the ${p.size} bytes expected")
                    out.write(buf, 0, n)
                    onBytes(n.toLong())
                }
            }
            if (sent != p.size) throw IOException("${p.fileName}: read $sent of ${p.size} bytes")
            out.write(CRLF)
        }
        out.write(closing)
    }

    companion object {
        private val CRLF = "\r\n".toByteArray(Charsets.UTF_8)

        /** Keeps a file name from breaking out of the header line. */
        fun escape(name: String): String =
            name.replace("\"", "%22").replace("\r", "").replace("\n", "")
    }
}
