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

        versionCode = 36
        versionName = "4.36.0"
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            isShrinkResources = false
        }

        release {
            isMinifyEnabled = false
            isShrinkResources = false
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
