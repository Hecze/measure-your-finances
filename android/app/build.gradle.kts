plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "app.misgastos"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.misgastos"
        minSdk = 26
        targetSdk = 35
        versionCode = (System.getenv("VERSION_CODE") ?: "1").toInt()
        versionName = System.getenv("VERSION_NAME") ?: "0.1.0"
    }

    signingConfigs {
        create("release") {
            // En CI se puede usar una llave propia vía secretos; si no, la llave pública del proyecto
            // (permite instalar actualizaciones sin desinstalar, es decir, sin perder datos).
            storeFile = file(System.getenv("KEYSTORE_FILE") ?: "../keystore/mis-gastos.jks")
            storePassword = System.getenv("KEYSTORE_PASSWORD") ?: "mis-gastos"
            keyAlias = System.getenv("KEY_ALIAS") ?: "mis-gastos"
            keyPassword = System.getenv("KEY_PASSWORD") ?: "mis-gastos"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    packaging {
        resources {
            excludes += setOf(
                "META-INF/*.md", "META-INF/LICENSE*", "META-INF/NOTICE*", "META-INF/DEPENDENCIES", "META-INF/INDEX.LIST",
                "META-INF/*.kotlin_module", "META-INF/versions/**", "META-INF/javamail.*", "META-INF/mailcap*",
            )
            pickFirsts += setOf("META-INF/mimetypes.default", "META-INF/javamail.default.providers", "META-INF/javamail.default.address.map")
        }
    }
}

dependencies {
    implementation(project(":core"))
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.webkit:webkit:1.12.1")
    implementation("androidx.work:work-runtime-ktx:2.9.1")
    implementation("androidx.security:security-crypto:1.0.0")
    implementation("com.sun.mail:android-mail:1.6.7")
    implementation("com.sun.mail:android-activation:1.6.7")
}
