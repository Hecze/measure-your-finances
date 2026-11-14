package app.misgastos.core

/**
 * Vocabulario para entender descripciones cortas de Yape ("1/4 + 1l de chicha", "pasaje mall").
 * Construido a partir de descripciones reales y del habla peruana. Se complementa con lo que
 * el usuario enseña (cada palabra que categoriza) y, si quiere, con IA.
 *
 * Las claves son los nombres por defecto de las categorías (en español); la app las relaciona
 * por id con las categorías del usuario.
 */
object Vocabulary {
    const val FOOD = "Comida"
    const val TRANSPORT = "Transporte"
    const val HOME = "Casa y servicios"
    const val HEALTH = "Salud y cuidado"
    const val EDUCATION = "Educación"
    const val FUN = "Salidas y ocio"
    const val SHOPPING = "Compras"

    /** Nombres por defecto (es, en). */
    val DEFAULTS = listOf(
        FOOD to "Food", TRANSPORT to "Transport", HOME to "Home & bills", HEALTH to "Health & care",
        EDUCATION to "Education", FUN to "Going out", SHOPPING to "Shopping",
    )

    val WORDS: Map<String, List<String>> = mapOf(
        FOOD to listOf(
            "almuerzo", "almuerzos", "menu", "menú", "desayuno", "cena", "lonche", "comida", "comidas", "pollo", "pollito", "broster",
            "broaster", "brasa", "1/4", "chaufa", "aeropuerto", "causa", "ceviche", "cebiche", "lomo", "saltado", "tallarin",
            "tallarines", "arroz", "papa", "papas", "salchipapa", "anticucho", "anticuchos", "pancita", "rachi", "picarones", "tamal",
            "tamales", "humita", "empanada", "empanadas", "pan", "panes", "pancito", "pasteles", "pastel", "torta", "keke", "queque",
            "galleta", "galletas", "fruguitos", "chocolate", "golosinas", "dulces", "caramelos", "helado", "helados", "postre", "gelatina",
            "mazamorra", "arroz con leche", "chicha", "maracuya", "maracuyá", "limonada", "jugo", "jugos", "emoliente", "cafe", "café",
            "gaseosa", "inca kola", "coca", "agua", "pizza", "hamburguesa", "burger", "sanguche", "sandwich", "pan con",
            "milanesa", "filete", "chuleta", "pescado", "chicharron", "chicharrón", "fruta", "frutas", "durazno", "duraznos", "platano",
            "plátano", "manzana", "mandarina", "naranja", "uva", "uvas", "palta", "verdura", "verduras", "mercado", "huevo", "huevos",
            "leche", "yogurt", "queso", "carne", "abarrotes", "bodega", "snack", "snacks", "piqueo", "chifa", "polleria", "pollería",
            "cevicheria", "restaurante", "delivery", "pedidos ya", "pedidosya", "rappi", "kfc", "bembos", "mcdonalds", "popeyes", "pardos", "norky", "rokys", "roky",
        ),
        TRANSPORT to listOf(
            "pasaje", "pasajes", "taxi", "uber", "cabify", "didi", "indrive", "indriver", "combi", "micro", "bus", "colectivo", "moto",
            "mototaxi", "metropolitano", "corredor", "tren", "linea 1", "peaje", "gasolina", "combustible", "grifo", "estacionamiento",
            "cochera", "viaje", "movilidad", "corredores", "tarjeta preferencial",
        ),
        HOME to listOf(
            "alquiler", "renta", "hab", "habitacion", "habitación", "depa", "departamento", "luz", "gas", "balon", "balón",
            "cable", "limpieza", "bitel", "claro", "movistar", "entel", "postpago", "prepago", "recarga", "recargas", "mibitel",
            "enel", "luz del sur", "sedapal", "calidda", "win", "pila", "lavanderia", "lavandería", "pilas", "foco", "focos", "detergente", "lejia", "lejía",
            "papel", "escoba", "ferreteria", "ferretería", "gasfitero", "electricista", "mantenimiento", "condominio",
        ),
        HEALTH to listOf(
            "farmacia", "botica", "pastilla", "pastillas", "medicina", "medicinas", "remedio", "doctor", "medico", "médico", "consulta",
            "cita", "dentista", "analisis", "análisis", "clinica", "clínica", "inyeccion", "vacuna", "lentes", "gillette", "gillet",
            "afeitadora", "shampoo", "champu", "jabon", "jabón", "pasta dental", "cepillo", "desodorante", "corte", "peluqueria",
            "peluquería", "barberia", "barbería", "uñas", "manicure", "gym", "gimnasio",
        ),
        EDUCATION to listOf(
            "curso", "cursos", "clase", "clases", "taller", "academia", "universidad", "uni", "instituto", "colegio", "matricula",
            "matrícula", "pension", "pensión", "mensualidad", "libro", "libros", "copias", "impresion", "impresión", "utiles", "útiles",
            "cuaderno", "carreras", "ciclo", "cibertec", "upc", "pucp", "usil", "utp", "ulima", "upn", "senati", "idat", "certus",
            "toulouse", "unmsm", "karate", "natacion", "natación", "futbol", "fútbol", "ballet", "danza", "musica", "música", "ingles", "inglés",
        ),
        FUN to listOf(
            "cine", "pelicula", "película", "entrada", "entradas", "concierto", "fiesta", "tono", "discoteca", "bar", "chela", "chelas",
            "cerveza", "cervezas", "trago", "tragos", "ron", "pisco", "vino", "salida",
            // Bebidas alcohólicas conocidas (marcas sin otro significado común)
            "mike", "mikes", "mike's", "four loko", "fourloko", "four lokos", "loko", "pilsen", "cusquena", "cusqueña", "heineken",
            "stella", "budweiser", "michelob", "smirnoff", "vodka", "whisky", "whiskey", "tequila", "gin", "jager", "jagermeister",
            "chilcano", "chilcanos", "pisco sour", "cuba libre", "sangria", "sangría", "cartavio", "queirolo", "tabernero",
            "johnnie walker", "chivas", "baileys", "anis", "anís", "caneca", "six pack", "sixpack", "cerveceria", "juego", "juegos", "bowling", "karaoke",
            "cumpleaños", "cumple", "regalo", "regalos", "paseo", "cinemark", "cineplanet", "cinestar", "teleticket", "joinnus", "netflix", "spotify", "disney", "youtube", "steam", "playstation",
        ),
        SHOPPING to listOf(
            "ropa", "polo", "polos", "pantalon", "pantalón", "zapatillas", "zapatos", "medias", "casaca", "mochila", "celular",
            "audifonos", "audífonos", "cargador", "cable usb", "funda", "accesorios", "laptop", "mouse", "teclado", "falabella",
            "ripley", "oechsle", "sodimac", "promart", "aliexpress", "temu", "shein", "mercado libre", "amazon",
        ),
    )

    /** Palabras sin significado para categorizar. */
    val STOP = setOf(
        "de", "del", "la", "el", "los", "las", "y", "con", "para", "por", "en", "a", "al", "un", "una", "mas", "más", "mi", "tu",
        "su", "que", "lo", "le", "se", "x", "xd", "ok", "gracias", "pago", "yape", "plin", "s", "1", "2", "3", "4", "5", "nº", "n",
    )

    /** Palabras de un texto normalizadas, con algunos bigramas ("pan con", "inca kola"). */
    fun tokens(text: String): List<String> {
        val raw = Text.stripAccents(text.lowercase()).replace(Regex("[^a-z0-9ñ/ ]+"), " ").split(Regex("\\s+")).filter { it.isNotEmpty() }
        val words = raw.filter { it !in STOP }
        val bigrams = raw.zipWithNext { a, b -> "$a $b" }
        return words + bigrams
    }

    private val INDEX: Map<String, String> by lazy {
        val m = mutableMapOf<String, String>()
        for ((cat, words) in WORDS) for (w in words) m.putIfAbsent(Text.stripAccents(w.lowercase()), cat)
        m
    }

    /** Categoría por vocabulario (nombre por defecto) o null si no hay ninguna palabra conocida. */
    fun guess(text: String): String? {
        val score = mutableMapOf<String, Int>()
        for (t in tokens(text)) INDEX[t]?.let { score[it] = (score[it] ?: 0) + if (t.contains(' ')) 2 else 1 }
        return score.maxByOrNull { it.value }?.key
    }
}
