package app.misgastos.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MailDirTest {
    @Test fun guardaYLeeElCorreoOriginal() {
        val dir = kotlin.io.path.createTempDirectory("mail").toFile()
        val m = MailDir(dir)
        assertNull(m.load("<a@b>"))
        val html = "<html><body>Monto total: S/ 84.10 — ñandú</body></html>"
        m.save("<a@b>", html)
        assertEquals(html, m.load("<a@b>"))
        dir.deleteRecursively()
    }
}
