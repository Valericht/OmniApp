plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.omni.monitor"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.omni.monitor"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }

    buildFeatures { viewBinding = true }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    // Teks UI berbahasa Indonesia ditulis langsung di layout (satu bahasa, tanpa lokalisasi).
    lint { disable += setOf("HardcodedText", "SetTextI18n") }
}

dependencies {
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.github.mik3y:usb-serial-for-android:3.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
}
