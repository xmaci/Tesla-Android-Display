import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}


val relProps = Properties().apply {
    val f = rootProject.file("keystore.properties"); if (f.exists()) f.inputStream().use { this.load(it) }
}

android {
    namespace = "com.xmaci.tesladroid"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.xmaci.tesladroid"
        minSdk = 33
        targetSdk = 35
        versionCode = 80
        versionName = "0.14.3"
        ndk { abiFilters += setOf("arm64-v8a") }   // Pixel 7 and other modern phones
    }

    // Official builds are signed with a private key kept OUT of the repo (keystore.properties +
    // keystore/release.keystore, git-ignored). Without it (a fork, CI) builds use the old public dev key,
    // which the licence check refuses (License.SIGNER_SHA256).
    signingConfigs {
        create("dev") {
            storeFile = rootProject.file("keystore/dev.keystore")
            storePassword = "tesladroid"
            keyAlias = "dev"
            keyPassword = "tesladroid"
        }
        if (relProps.getProperty("storeFile") != null) create("rel") {
            storeFile = rootProject.file(relProps.getProperty("storeFile"))
            storePassword = relProps.getProperty("storePassword")
            keyAlias = relProps.getProperty("keyAlias")
            keyPassword = relProps.getProperty("keyPassword")
        }
    }
    val official = signingConfigs.findByName("rel") ?: signingConfigs.getByName("dev")
    buildTypes {
        getByName("debug") { signingConfig = official }
        release {
            isMinifyEnabled = false
            signingConfig = official
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
