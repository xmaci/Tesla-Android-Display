plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.xmaci.tesladroid"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.xmaci.tesladroid"
        minSdk = 33
        targetSdk = 35
        versionCode = 26
        versionName = "0.5.4-m1"
        ndk { abiFilters += setOf("arm64-v8a") }   // Pixel 7 and other modern phones
    }

    // Shared personal dev key (in repo) so every build can update the installed app in place.
    signingConfigs {
        create("dev") {
            storeFile = rootProject.file("keystore/dev.keystore")
            storePassword = "tesladroid"
            keyAlias = "dev"
            keyPassword = "tesladroid"
        }
    }
    buildTypes {
        getByName("debug") { signingConfig = signingConfigs.getByName("dev") }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("dev")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    packaging { resources.excludes += setOf("META-INF/versions/**", "META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA") }
    testOptions { unitTests.isReturnDefaultValues = true }
    sourceSets["main"].assets.srcDirs("src/main/assets", "../../web/car")
}

dependencies {
    // Wireless-debugging pairing + ADB over TLS to the phone's own adbd (Apache-2.0 / GPL-3.0, we use Apache-2.0)
    implementation("com.github.MuntashirAkon:libadb-android:3.1.1")
    implementation("org.conscrypt:conscrypt-android:2.5.3")
    implementation("org.bouncycastle:bcpkix-jdk15to18:1.81")
    testImplementation("junit:junit:4.13.2")
}
