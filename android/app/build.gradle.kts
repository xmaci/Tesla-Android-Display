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
        versionCode = 2
        versionName = "0.1.1-m1a"
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
    testOptions { unitTests.isReturnDefaultValues = true }
    sourceSets["main"].assets.srcDirs("src/main/assets", "../../web/car")
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}
