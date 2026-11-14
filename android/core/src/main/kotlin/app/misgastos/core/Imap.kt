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
import javax.mail.internet.ContentType
import javax.mail.internet.InternetAddress
import javax.mail.internet.MimeMessage
import javax.mail.internet.MimeMultipart
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

    /** Correos que no se pudieron leer en la última descarga (se reintentan la próxima vez). */
    @Volatile var failed = 0

    /**
     * Descarga los correos nuevos de los remitentes de la lista. No toca los datos:
     * el procesamiento se hace después (así la app no se congela mientras descarga).
     */
    fun fetch(host: String, user: String, password: String, senders: List<String>, daysBack: Int, known: Set<String>,
              onProgress: (Int, Int) -> Unit = { _, _ -> },
              onBatch: (List<IncomingEmail>) -> Unit = {}): List<IncomingEmail> {
        val props = Properties().apply {
            put("mail.store.protocol", "imaps")
            put("mail.imaps.host", host)
            put("mail.imaps.port", "993")
            put("mail.imaps.ssl.enable", "true")
            put("mail.imaps.connectiontimeout", "20000")
            put("mail.imaps.timeout", "60000")
            put("mail.imaps.partialfetch", "false")
            put("mail.imaps.fetchsize", "1048576")
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
            // 1) Una sola petición para saber qué correos ya teníamos (solo encabezados).
            folder.fetch(msgs, FetchProfile().apply { add(FetchProfile.Item.ENVELOPE); add("Message-ID") })
            val pending = msgs.mapNotNull { m ->
                val id = m.getHeader("Message-ID")?.firstOrNull() ?: "noid-${m.receivedDate?.time}-${m.subject}"
                if (id in known) null else m to id
            }
            // 2) Los nuevos se bajan completos por tandas (una petición por tanda, no una por correo),
            //    y cada tanda se guarda al momento: si el celular corta la conexión, no se pierde lo ya leído.
            val out = mutableListOf<IncomingEmail>()
            failed = 0
            var done = 0
            onProgress(0, pending.size)
            for (chunk in pending.chunked(BATCH)) {
                val arr = chunk.map { it.first }.toTypedArray()
                runCatching { folder.fetch(arr, FetchProfile().apply { add(com.sun.mail.imap.IMAPFolder.FetchProfileItem.MESSAGE) }) }
                val batch = mutableListOf<IncomingEmail>()
                for ((m, id) in chunk) {
                    // Un correo raro no debe frenar la lectura de los demás.
                    try { batch += toIncoming(m, id) } catch (e: Exception) { failed++ }
                }
                done += chunk.size
                onBatch(batch)
                out += batch
                onProgress(done, pending.size)
            }
            folder.close(false)
            return out
        } finally {
            store.close()
        }
    }

    private const val BATCH = 25

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

    /**
     * Separa un correo en HTML, texto y adjuntos .xlsx.
     * No depende de los "mailcap" de JavaMail (en Android pueden faltar y entonces getContent()
     * devuelve un stream en vez de un Multipart): si hace falta, arma las partes a mano.
     */
    private fun walk(p: Part, out: Parts) {
        val name = p.fileName
        when {
            name != null && name.lowercase().endsWith(".xlsx") -> out.files += name to p.inputStream.readBytes()
            name != null && Part.ATTACHMENT.equals(p.disposition, true) -> {} // otros adjuntos: no interesan
            p.isMimeType("multipart/*") -> {
                val mp = (p.content as? Multipart) ?: MimeMultipart(p.dataHandler.dataSource)
                for (i in 0 until mp.count) walk(mp.getBodyPart(i), out)
            }
            p.isMimeType("message/rfc822") -> {
                val inner = (p.content as? Message) ?: MimeMessage(null as Session?, p.inputStream)
                walk(inner, out)
            }
            p.isMimeType("text/html") && out.html.isEmpty() -> out.html = textOf(p)
            p.isMimeType("text/plain") && out.text.isEmpty() -> out.text = textOf(p)
        }
    }

    private fun textOf(p: Part): String {
        val c = p.content
        if (c is String) return c
        val charset = runCatching { ContentType(p.contentType).getParameter("charset") }.getOrNull() ?: "UTF-8"
        val bytes = p.inputStream.readBytes() // ya decodificado (base64 / quoted-printable)
        return runCatching { String(bytes, java.nio.charset.Charset.forName(charset)) }.getOrElse { String(bytes, Charsets.UTF_8) }
    }

    /** Convierte un mensaje IMAP/MIME en lo que procesa el motor. Público para poder probarlo. */
    fun toIncoming(m: Message, id: String): IncomingEmail {
        val parts = Parts()
        walk(m, parts)
        val received = (m.receivedDate ?: m.sentDate)?.toInstant()?.atZone(ZoneId.of("America/Lima"))?.toLocalDateTime()
        return IncomingEmail(id, addr(m.from), m.subject ?: "", parts.html, parts.text, received, parts.files)
    }
}
