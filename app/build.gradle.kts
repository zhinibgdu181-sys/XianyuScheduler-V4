plugins {
    id("com.android.application")
}

android {
    namespace = "com.zhinibgdu.xianyu"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.zhinibgdu.xianyu"
        minSdk = 26
        targetSdk = 35

        versionCode = 29
        versionName = "4.29.0"
    }

    signingConfigs {
        create("persistent") {
            val keystorePath = System.getenv("ANDROID_KEYSTORE_PATH")
            if (!keystorePath.isNullOrBlank()) {
                storeFile = file(keystorePath)
            }
            storePassword = System.getenv("ANDROID_KEYSTORE_PASSWORD")
            keyAlias = System.getenv("ANDROID_KEY_ALIAS")
            keyPassword = System.getenv("ANDROID_KEY_PASSWORD")
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            isShrinkResources = false
            // CI 使用固定 keystore；以后每次 app-debug.apk 都保持同一签名。
            if (!System.getenv("ANDROID_KEYSTORE_PATH").isNullOrBlank()) {
                signingConfig = signingConfigs.getByName("persistent")
            }
        }

        release {
            isMinifyEnabled = false
            isShrinkResources = false
            if (!System.getenv("ANDROID_KEYSTORE_PATH").isNullOrBlank()) {
                signingConfig = signingConfigs.getByName("persistent")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation("com.google.mlkit:text-recognition-chinese:16.0.1")
}
