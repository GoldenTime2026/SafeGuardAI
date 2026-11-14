import java.util.Properties

// Only the sharing-server address and the release signing key location are read from local.properties;
// API keys there never go into the app, and the keystore stays outside the repository.
val localProps = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}
val sharingServer: String = localProps.getProperty("safeguard.server", "http://127.0.0.1:8787")
val releaseStore: String? = localProps.getProperty("release.storeFile")

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.safeguard.ai"
    // AAOS Car API (CarPropertyManager) for standard data the Pleos SDK leaves to AAOS: speed, outside temperature.
    useLibrary("android.car")
    compileSdk = 34

    defaultConfig {
        applicationId = "com.safeguard.ai"
        minSdk = 28
        targetSdk = 34
        versionCode = 1
        versionName = "0.2.0"
        // Empty string turns sharing off (the app then keeps reports on the car).
        buildConfigField("String", "SAFEGUARD_SERVER", "\"$sharingServer\"")
    }

    signingConfigs {
        if (releaseStore != null) create("release") {
            storeFile = file(releaseStore)
            storePassword = localProps.getProperty("release.storePassword")
            keyAlias = localProps.getProperty("release.keyAlias")
            keyPassword = localProps.getProperty("release.keyPassword")
        }
    }

    buildTypes {
        release {
            // Not minified: the Pleos SDK ships without consumer ProGuard rules for every class it reflects on.
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release")
        }
    }

    buildFeatures {
        buildConfig = true
    }

    testOptions {
        // Rules log through android.util.Log; return defaults instead of throwing in JVM tests.
        unitTests.isReturnDefaultValues = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("ai.pleos.playground:Vehicle:2.3.2")
    implementation("ai.pleos.playground:NaviHelper:3.0.5")
    implementation("ai.pleos.playground:FusedLocation:1.1.3")
    implementation("ai.pleos.playground:TextToSpeech:3.3.1")
    implementation("ai.pleos.playground:SpeechToText:3.3.1")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    testImplementation("junit:junit:4.13.2")
    // Real org.json for JVM tests (android.jar only has stubs).
    testImplementation("org.json:json:20231013")
}
