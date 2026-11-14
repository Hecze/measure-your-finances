package app.misgastos.core

import java.io.File
import java.security.MessageDigest
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/** Correos originales guardados comprimidos, uno por archivo (nombre = hash del Message-ID). */
class MailDir(private val dir: File) {
    private fun fileFor(id: String): File {
        val h = MessageDigest.getInstance("SHA-1").digest(id.toByteArray()).joinToString("") { "%02x".format(it) }
        return File(dir, "$h.html.gz")
    }

    fun save(id: String, html: String) {
        dir.mkdirs()
        val f = fileFor(id)
        val tmp = File(dir, f.name + ".tmp")
        GZIPOutputStream(tmp.outputStream()).use { it.write(html.toByteArray(Charsets.UTF_8)) }
        if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
    }

    fun has(id: String): Boolean = fileFor(id).exists()

    fun load(id: String): String? {
        val f = fileFor(id)
        if (!f.exists()) return null
        return GZIPInputStream(f.inputStream()).use { String(it.readBytes(), Charsets.UTF_8) }
    }
}
