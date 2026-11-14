package app.misgastos.core

import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Date
import java.util.Properties
import javax.mail.Address
import javax.mail.FetchProfile
import javax.mail.Folder
import javax.mail.Message
import javax.mail.Multipart
import javax.mail.Part
import javax.mail.Session
import javax.mail.internet.InternetAddress
import javax.mail.search.ComparisonTerm
import javax.mail.search.FromStringTerm
import javax.mail.search.OrTerm
import javax.mail.search.AndTerm
import javax.mail.search.ReceivedDateTerm
import javax.mail.search.SearchTerm

/**
 * Lee los correos de los bancos por IMAP (Gmail: imap.gmail.com + contraseña de aplicación).
 * - Solo busca correos de los remitentes de la lista (bancos y Yape).
 * - Abre la carpeta en solo lectura: nunca marca nada como leído.
 * - Lee mensaje por mensaje (Gmail agrupa en hilos y eso hace perder correos).
 */
object Imap {
    class ImapException(msg: String) : Exception(msg)

    /**
     * Descarga los correos nuevos de los remitentes de la lista. No toca los datos:
     * el procesamiento se hace después (así la app no se congela mientras descarga).
     */
    fun fetch(host: String, user: String, password: String, senders: List<String>, daysBack: Int, known: Set<String>,
              onProgress: (Int, Int) -> Unit = { _, _ -> }): List<IncomingEmail> {
        val props = Properties().apply {
            put("mail.store.protocol", "imaps")
            put("mail.imaps.host", host)
            put("mail.imaps.port", "993")
            put("mail.imaps.ssl.enable", "true")
            put("mail.imaps.connectiontimeout", "20000")
            put("mail.imaps.timeout", "60000")
            put("mail.imaps.partialfetch", "false")
        }
        val store = Session.getInstance(props).getStore("imaps")
        try {
            store.connect(host, user, password)
        } catch (e: javax.mail.AuthenticationFailedException) {
            // Se conserva el mensaje de Google (ej. "[AUTHENTICATIONFAILED] Invalid credentials") para mostrarlo.
            throw ImapException("auth|" + (e.message ?: "").take(200))
        } catch (e: javax.mail.MessagingException) {
            throw ImapException("connect|" + (e.message ?: e.cause?.message ?: "").take(200))
        }
        try {
            val folder = allMailFolder(store)
            folder.open(Folder.READ_ONLY)
            val since = Date.from(LocalDateTime.now().minusDays(daysBack.toLong()).atZone(ZoneId.systemDefault()).toInstant())
            val from: SearchTerm = OrTerm(senders.map { FromStringTerm(it) as SearchTerm }.toTypedArray())
            val msgs = folder.search(AndTerm(ReceivedDateTerm(ComparisonTerm.GE, since), from))
            folder.fetch(msgs, FetchProfile().apply { add(FetchProfile.Item.ENVELOPE); add("Message-ID") })
            val out = mutableListOf<IncomingEmail>()
            for ((i, m) in msgs.withIndex()) {
                onProgress(i + 1, msgs.size)
                val id = m.getHeader("Message-ID")?.firstOrNull() ?: "noid-${m.receivedDate?.time}-${m.subject}"
                if (id in known) continue
                val parts = Parts()
                walk(m, parts)
                val received = (m.receivedDate ?: m.sentDate)?.toInstant()?.atZone(ZoneId.of("America/Lima"))?.toLocalDateTime()
                out += IncomingEmail(id, addr(m.from), m.subject ?: "", parts.html, parts.text, received, parts.files)
            }
            folder.close(false)
            return out
        } finally {
            store.close()
        }
    }

    /** En Gmail, "Todos" (\All) incluye lo archivado. Si no existe, INBOX. */
    private fun allMailFolder(store: javax.mail.Store): Folder {
        try {
            for (f in store.defaultFolder.list("*")) {
                val attrs = (f as? com.sun.mail.imap.IMAPFolder)?.attributes ?: continue
                if (attrs.any { it.equals("\\All", ignoreCase = true) }) return f
            }
        } catch (_: Exception) { }
        return store.getFolder("INBOX")
    }

    private fun addr(a: Array<Address>?): String = a?.firstOrNull()?.let { (it as? InternetAddress)?.address ?: it.toString() } ?: ""

    private class Parts {
        var html = ""
        var text = ""
        val files = mutableListOf<Pair<String, ByteArray>>()
    }

    private fun walk(p: Part, out: Parts) {
        val name = p.fileName
        when {
            name != null && (Part.ATTACHMENT.equals(p.disposition, true) || name.lowercase().endsWith(".xlsx")) ->
                if (name.lowercase().endsWith(".xlsx")) out.files += name to p.inputStream.readBytes()
            p.isMimeType("multipart/*") -> {
                val mp = p.content as Multipart
                for (i in 0 until mp.count) walk(mp.getBodyPart(i), out)
            }
            p.isMimeType("text/html") && out.html.isEmpty() -> out.html = p.content.toString()
            p.isMimeType("text/plain") && out.text.isEmpty() -> out.text = p.content.toString()
            p.isMimeType("message/rfc822") -> (p.content as? Message)?.let { walk(it, out) }
        }
    }
}
