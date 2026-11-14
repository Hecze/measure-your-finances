plugins { id("org.jetbrains.kotlin.jvm") }

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }

dependencies {
    implementation("org.jsoup:jsoup:1.17.2")
    // SDK oficial de Anthropic para el proveedor Claude (los demás usan HTTP estándar).
    implementation("com.anthropic:anthropic-java:3.8.0")
    // IMAP: en Android se usa com.sun.mail:android-mail (misma API javax.mail).
    compileOnly("com.sun.mail:javax.mail:1.6.2")
    testImplementation("com.sun.mail:javax.mail:1.6.2")
    // Android ya trae org.json; en la JVM (tests y servidor de desarrollo) se agrega aparte.
    compileOnly("org.json:json:20240303")
    testImplementation("org.json:json:20240303")
    testImplementation(kotlin("test"))
}

tasks.test { useJUnitPlatform() }

tasks.test {
    // Pasa variables para la verificación opcional con correos reales.
    environment("REAL_EMAILS_DIR", System.getenv("REAL_EMAILS_DIR") ?: "")
    environment("REAL_YAPE_XLSX", System.getenv("REAL_YAPE_XLSX") ?: "")
    testLogging { showStandardStreams = true; events("failed"); exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL }
}
