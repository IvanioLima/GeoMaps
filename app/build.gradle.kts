import java.util.Base64

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Keystore fixa (texto base64 versionado) para que todas as compilações tenham a mesma
// assinatura: assim o APK novo instala por cima do antigo sem perder os dados.
val keystoreB64 = file("geomaps.keystore.b64")
val keystoreFile = layout.buildDirectory.file("keystore/geomaps.keystore").get().asFile
if (keystoreB64.exists() && !keystoreFile.exists()) {
    keystoreFile.parentFile.mkdirs()
    keystoreFile.writeBytes(Base64.getMimeDecoder().decode(keystoreB64.readText()))
}

// Recursos binários (ícone) guardados como texto base64 em app/binres → gerados em build/generated/binres
val binresDir = file("binres")
val binresOut = layout.buildDirectory.dir("generated/binres").get().asFile
binresDir.walkTopDown().filter { it.isFile && it.name.endsWith(".b64") }.forEach { src ->
    val out = File(binresOut, src.relativeTo(binresDir).path.removeSuffix(".b64"))
    if (!out.exists() || out.lastModified() < src.lastModified()) {
        out.parentFile.mkdirs()
        out.writeBytes(Base64.getMimeDecoder().decode(src.readText()))
    }
}

android {
    sourceSets["main"].res.srcDir(File(binresOut, "res"))
    namespace = "br.geomaps"
    compileSdk = 35

    defaultConfig {
        applicationId = "br.geomaps.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }

    signingConfigs {
        create("geomaps") {
            storeFile = keystoreFile
            storePassword = "geomaps123"
            keyAlias = "geomaps"
            keyPassword = "geomaps123"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("geomaps")
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("geomaps")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        resources {
            excludes += setOf("META-INF/AL2.0", "META-INF/LGPL2.1", "META-INF/DEPENDENCIES")
        }
    }
    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.exifinterface:exifinterface:1.3.7")
    implementation("androidx.work:work-runtime-ktx:2.10.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    implementation("org.maplibre.gl:android-sdk:11.8.8")
    implementation("org.locationtech.jts:jts-core:1.20.0")

    testImplementation("junit:junit:4.13.2")
}
