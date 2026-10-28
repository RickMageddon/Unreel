plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.unreel.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.unreel.app"
        minSdk = 26
        targetSdk = 35
        // CI passes the run number so every automatic build installs over the previous one.
        val buildNumber = (project.findProperty("buildNumber") as String?)?.toInt() ?: 1
        versionCode = buildNumber
        versionName = "1.0.$buildNumber"
    }

    // CI writes the keystore from repository secrets. Without it, fall back to the debug key.
    val releaseKeystore = file("release.keystore")
    signingConfigs {
        create("release") {
            storeFile = releaseKeystore
            storePassword = System.getenv("KEYSTORE_PASSWORD")
            keyAlias = System.getenv("KEY_ALIAS")
            keyPassword = System.getenv("KEYSTORE_PASSWORD")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName(
                if (releaseKeystore.exists()) "release" else "debug"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}
