plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.onda.player"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.onda.player"
        minSdk = 26
        targetSdk = 34
        versionCode = 8
        versionName = "2.4"
    }
    // Llave fija: todas las versiones se firman igual y se pueden instalar encima de la anterior.
    signingConfigs {
        create("onda") {
            storeFile = file("onda.keystore")
            storePassword = "onda1234"
            keyAlias = "onda"
            keyPassword = "onda1234"
        }
    }
    buildTypes {
        debug { signingConfig = signingConfigs.getByName("onda") }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("onda")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("androidx.webkit:webkit:1.11.0")
    implementation("androidx.media3:media3-exoplayer:1.4.1")
}
