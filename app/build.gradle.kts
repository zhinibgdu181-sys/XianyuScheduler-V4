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

        versionCode = 49
        versionName = "4.42.1"
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
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.mockito:mockito-core:5.14.2")
}
