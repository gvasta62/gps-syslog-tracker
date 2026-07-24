import java.io.FileInputStream
import java.util.Properties

// File di build del modulo applicazione: qui si definiscono SDK, versione,
// opzioni di compilazione e le librerie (dipendenze) usate dall'app.
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Credenziali di firma release lette da keystore.properties (fuori dal repo).
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) FileInputStream(keystorePropsFile).use { load(it) }
}

android {
    // Namespace = nome del package base del codice.
    namespace = "it.gvasta.gpstracker"
    // Versione dell'SDK Android usata per COMPILARE (API 34 = Android 14).
    compileSdk = 34

    defaultConfig {
        // Identificativo univoco dell'app sul telefono.
        applicationId = "it.gvasta.gpstracker"
        // Versione minima di Android supportata (API 24 = Android 7.0).
        minSdk = 24
        // Versione su cui l'app dichiara di essere stata testata.
        targetSdk = 34
        versionCode = 2
        versionName = "1.1"
    }

    // Configurazione di firma per la release (attiva solo se c'e' il keystore).
    signingConfigs {
        create("release") {
            if (keystorePropsFile.exists()) {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        // Build di rilascio: niente offuscamento per mantenere i log leggibili.
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // Firma l'APK di release con la chiave dedicata, se disponibile.
            if (keystorePropsFile.exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    // L'app viene compilata con Java 17 (richiesto dagli strumenti recenti).
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    // Utility di base Android (estensioni Kotlin).
    implementation("androidx.core:core-ktx:1.13.1")
    // Compatibilita' UI (AppCompatActivity, temi).
    implementation("androidx.appcompat:appcompat:1.7.0")
    // Componenti grafici Material.
    implementation("com.google.android.material:material:1.12.0")
    // WorkManager: usato per il "watchdog" periodico che ricontrolla il servizio.
    implementation("androidx.work:work-runtime-ktx:2.9.1")
}
