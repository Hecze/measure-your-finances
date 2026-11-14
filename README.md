# Mis Gastos

App Android open source para saber **cuánto gastas y en qué**, leyendo los correos de tus bancos y tu historial de Yape. Todo vive en tu teléfono: sin servidores, sin cuentas, sin publicidad.

**[Descargar el APK](https://github.com/Hecze/measure-your-finances/releases/download/apk-latest/mis-gastos.apk)**

## Qué hace

- **Un número**: lo que gastaste este mes, separado en **Fijos** (alquiler, universidad, plan de celular) y **Variables**.
- **Por revisar, sin obligación**: solo lo que se puede recordar días después: pagos a empresas sin categoría o con dudas, y pagos de más de S/ 100. Los yapeos chicos entre personas nunca se preguntan. Solo este mes y el anterior.
- **Gastos hormiga**: pagos de menos de S/ 10 sin más información se agrupan solos como gastos hormiga (variables).
- **No adivina.** Cada tipo de correo tiene una plantilla exacta. Si llega uno que no reconoce, te pregunta.
- **Mide gastos, no ingresos.** Tú decides qué no cuenta: cambio de efectivo, encargos, pases de dinero, transferencias a tus propias cuentas.
- **Una sola moneda en pantalla**: todo se suma y se muestra en soles. Los gastos en dólares (tarjeta o cuenta en dólares) se convierten con el tipo de cambio del último pago de tu tarjeta en dólares que aparece en tus correos del BCP (si aún no hay, 3.40). El monto original se ve debajo.
- **Cuotas**: una compra con tarjeta de crédito en cuotas cuenta solo la cuota de cada mes.
- **Monto editable**: toca el monto de un gasto para cambiarlo (por ejemplo, si pagaste por otros).
- **Español e inglés** (Ajustes → Idioma).

## De dónde salen los datos

| Fuente | Qué trae | Cómo |
|---|---|---|
| **Gmail** | Consumos con tarjeta, transferencias, pagos de servicios, pagos con Yape en otras apps | Ajustes → Gmail. Se usa una *contraseña de aplicación* de Google. Solo se leen correos de los bancos y Yape, nunca se marcan como leídos. Se revisa al abrir la app y cada 6 horas. |
| **Historial de Yape** | Los pagos a personas (Yape **no** envía correo por esos) | Pídelo en la app de Yape. Llega a tu correo y la app lo importa sola; o ábrelo con "Abrir con → Mis Gastos". Importar dos veces no duplica nada. |
| **Manual** | Gastos en efectivo | Ver todos los gastos → Agregar gasto en efectivo. |

¿Por qué una contraseña de aplicación y no "Iniciar sesión con Google"? Google exige una auditoría de seguridad de pago a las apps que leen Gmail. Para una app open source que instalas tú, la contraseña de aplicación es el camino realista. Se guarda cifrada con el Keystore de Android.

## Cómo entiende tus gastos

1. **Fijo o variable**: lo que tú elijas en cada pago › el mismo pago repetido 3+ meses › el tipo de su categoría (fijas: Casa y servicios, Transporte, Educación, Deudas) › palabras como "alquiler".
2. **En qué fue** (para lo variable):
   - vocabulario peruano ("1/4 + 1l de chicha" → Comida, "pasaje mall" → Transporte, "mike" o "fourloko" → Salidas);
   - **Mis palabras** (Ajustes): tú dices qué significa una palabra ("enanita" = Comida) y solo se aplica cuando la descripción contiene exactamente esa palabra. Nada se deduce por su cuenta. Por defecto, "sencillo" y "monedas" son cambio de efectivo y no se cuentan;
   - lo usual con cada persona (si lo de Sonia siempre fue comida, se sugiere comida, marcado como sugerencia);
   - **IA opcional** con la API key de tu proveedor (Claude, OpenAI, Gemini, Groq, OpenRouter, DeepSeek o cualquier compatible). Si la IA tampoco entiende, te pregunta.
3. **¿Es realmente un gasto?** Señales, nunca decisiones automáticas:
   - "X te envió S/150 un minuto antes": ¿pagaste por él?
   - "Esta persona también te envía dinero": ¿encargo?
   - descripciones como "sencillo" o "monedas": ¿cambio de efectivo?

### Trampas encontradas con correos reales

| Trampa | Solución |
|---|---|
| Gmail agrupa correos con el mismo asunto en un hilo | Se lee mensaje por mensaje |
| Los correos de consumo del BCP dicen al pie *"Si tu compra fue rechazada…"* | Los rechazos se detectan solo por el asunto |
| *"Favorito: Agregaste un Pago de Servicios"* parece un pago | Se ignora antes que cualquier plantilla |
| El monto del pago de servicios no está en el resumen del correo | Se lee la etiqueta *Monto total* del cuerpo |
| Un pago de Yape a Plin/BIM también llega como consumo con débito BCP | Se reconoce como el mismo pago (monto y hora) |
| Pagar tu tarjeta de crédito duplicaría los consumos | El pago de tu propia tarjeta no es gasto |
| "Compra por internet", "agua", "luz" en descripciones | No vuelven fijo un gasto |

Plantillas verificadas (BCP y Yape): consumo con tarjeta de crédito y débito, transferencias (a terceros, otros bancos, entre tus cuentas), pago de servicios, pago de tarjeta propia, devoluciones, ahorro (wardadito), yapeo recibido, yapeo, "Pagar con Yape" en otras apps, yapeo de servicio, recarga, Yape Promos. Otros bancos usan una plantilla genérica que siempre pide confirmación.

## Privacidad

- Los datos se guardan solo en el almacenamiento privado de la app.
- La contraseña de Gmail y la API key de IA se guardan cifradas.
- Nada se envía a ningún servidor, salvo a Gmail (para leer tus correos) y, si la activas, al proveedor de IA (solo descripción, a quién y monto de los gastos que no se entienden).
- Copia de seguridad: Ajustes → Copia de seguridad.

## Desarrollo

```
android/
  core/       motor en Kotlin puro: parsers, Excel de Yape, clasificación, IA (probado en la JVM)
  app/        app Android: WebView con la interfaz (assets/web) + puente + lectura de Gmail
  devserver/  sirve la misma interfaz en el navegador, para desarrollar sin emulador
```

Tests del núcleo (solo necesita Java 17+ y Gradle):

```bash
cd android && gradle :core:test
```

Probar la interfaz en el navegador:

```bash
cd android && gradle :devserver:run
```

El APK se compila en GitHub Actions (`.github/workflows/android.yml`) y se publica en la release `apk-latest`. Para compilarlo localmente necesitas el Android SDK:

```bash
cd android && gradle :app:assembleRelease
```

**Firma:** la llave `android/keystore/mis-gastos.jks` es pública a propósito: así las actualizaciones se instalan sobre la versión anterior sin perder datos. Si distribuyes tu propia versión, usa tu llave con las variables `KEYSTORE_FILE`, `KEYSTORE_PASSWORD`, `KEY_ALIAS` y `KEY_PASSWORD`.

¿Tu banco no está? Abre un issue con la **estructura** del correo (sin datos personales) o agrega una plantilla en `core/.../Parsers.kt` con su test.

## Licencia

MIT
