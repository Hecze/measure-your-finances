package app.misgastos.core

import app.misgastos.core.Fixtures.BCP
import app.misgastos.core.Fixtures.YAPE
import app.misgastos.core.Fixtures.email
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ParsersTest {
    @Test fun consumoNoSeConfundeConElPieDeRechazo() {
        // Todos los correos de consumo del BCP dicen al pie "Si tu compra fue rechazada...".
        val r = Parsers.parse(email(BCP, "Realizaste un consumo con tu Tarjeta de Crédito BCP - Servicio de Notificaciones BCP", "consumo_credito.html"))
        assertEquals(Kind.EXPENSE, r.kind)
        assertEquals(25.40, r.amount)
        assertEquals("BODEGA DON PEPE", r.counterparty)
        assertEquals("Tarjeta de Crédito ****1234", r.accountDetail)
        assertEquals("0000111111", r.externalRef)
        assertEquals(LocalDateTime.of(2026, 11, 12, 20, 17), r.occurredAt)
    }

    @Test fun consumoEnDolares() {
        val r = Parsers.parse(email(BCP, "Realizaste un consumo con tu Tarjeta de Débito BCP", "consumo_usd_debito.html"))
        assertEquals(7.99, r.amount); assertEquals("USD", r.currency)
    }

    @Test fun pagoAPlinConDebitoEsPersona() {
        val r = Parsers.parse(email(BCP, "Realizaste un consumo con tu Tarjeta de Débito BCP", "consumo_plin.html"))
        assertEquals("PLIN - JUAN PEREZ GARC", r.counterparty); assertEquals("person", r.counterpartyType)
    }

    @Test fun rechazoPorAsunto() {
        assertEquals(Kind.IGNORE, Parsers.parse(email(BCP, "Se rechazó tu compra por e-commerce no permitido", "consumo_credito.html")).kind)
    }

    @Test fun transferenciaTercerosConMensaje() {
        val r = Parsers.parse(email(BCP, "Constancia de Transferencia a Terceros BCP", "transfer_terceros.html"))
        assertEquals(600.0, r.amount); assertEquals("Alquiler", r.description)
        assertTrue(r.counterparty.startsWith("Inmobiliaria Los Pinos"))
    }

    @Test fun transferenciaOtrosBancosMensajeVacio() {
        val r = Parsers.parse(email(BCP, "Constancia de Transferencia a Otros Bancos", "transfer_otros.html"))
        assertEquals(650.0, r.amount); assertEquals("", r.description)
        assertEquals("Gomez Gomez Luis A (Ripley)", r.counterparty)
    }

    @Test fun transferenciaPropiaNoEsGasto() {
        assertEquals(Kind.NOT_EXPENSE, Parsers.parse(email(BCP, "Constancia de Transferencia Entre mis Cuentas", "transfer_propia.html")).kind)
    }

    @Test fun pagoDeServicioUniversidad() {
        val r = Parsers.parse(email(BCP, "ENVIO AUTOMATICO - CONSTANCIA DE PAGO DE SERVICIO - BANCA MOVIL BCP", "pago_servicio.html"))
        assertEquals(399.0, r.amount); assertEquals("UNIVERSIDAD EJEMPLO SAC", r.counterparty); assertEquals("CARRERAS", r.description)
        assertEquals(LocalDateTime.of(2026, 9, 28, 10, 28), r.occurredAt)
        assertTrue(r.fixedHint)
    }

    @Test fun favoritoNoEsPago() {
        assertEquals(Kind.IGNORE, Parsers.parse(email(BCP, "Constancia de Favorito: Agregaste un Pago de Servicios", "pago_servicio.html")).kind)
    }

    @Test fun pagoDeTarjetaPropia() {
        val r = Parsers.parse(email(BCP, "Constancia de Pago de Tarjeta de Crédito Propia", "pago_tarjeta.html"))
        assertEquals(Kind.NOT_EXPENSE, r.kind); assertEquals(293.06, r.amount)
    }

    @Test fun yapeo() {
        val r = Parsers.parse(email(YAPE, "Por tu seguridad, te notificaremos por cada yapeo que realices", "yape_yapeo.html"))
        assertEquals(3.0, r.amount); assertEquals("PIZZERIA EJEMPLO", r.counterparty)
        assertEquals(LocalDateTime.of(2026, 10, 15, 23, 34), r.occurredAt)
    }

    @Test fun pagarConYapeDesdeOtraApp() {
        val r = Parsers.parse(email(YAPE, "Pago exitoso", "yape_pago_exitoso.html"))
        assertEquals(17.2, r.amount); assertEquals("PEDIDOS YA", r.counterparty)
    }

    @Test fun correoDesconocidoNoSeAdivina() {
        assertEquals(Kind.UNKNOWN, Parsers.parse(Email(BCP, "Algo nuevo", "<p>Hola, algo con S/ 50.00</p>")).kind)
        val r = Parsers.parse(Email(BCP, "Realizaste un consumo con tu Tarjeta de Crédito BCP", "<p>Realizaste un consumo con tu tarjeta.</p>"))
        assertEquals(Kind.UNKNOWN, r.kind)
    }

    @Test fun fechas() {
        assertEquals(LocalDateTime.of(2026, 11, 2, 8, 25), Text.parseEsDateTime("02 Nov, 2026 - 08:25 am"))
        assertEquals(LocalDateTime.of(2026, 8, 22, 11, 27), Text.parseEsDateTime("22 Ago. 2026 - 11:27 am"))
        assertEquals(LocalDateTime.of(2026, 10, 15, 23, 34), Text.parseEsDateTime("15 octubre 2026 - 11:34 p. m."))
        assertEquals(LocalDateTime.of(2026, 11, 7, 13, 10), Text.parseEsDateTime("Sábado, 07 Noviembre 2026 - 01:10 PM"))
        assertEquals(LocalDateTime.of(2026, 10, 30, 21, 14, 12), Text.parseEsDateTime("30/10/2026 21:14:12"))
    }

    @Test fun nombresEnmascarados() {
        assertTrue(Text.namesMatch("Rodolfo Gom*", "Gomez Gomez Rodolfo A."))
        assertTrue(Text.namesMatch("Ana Per*", "ANA MARIA PEREZ LOPEZ"))
        assertTrue(!Text.namesMatch("Ana Per*", "Luis Gomez"))
    }
}
