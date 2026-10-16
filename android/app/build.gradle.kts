plugins {
    id("com.android.application")
}

android {
    namespace = "app.sdot"
    compileSdk = 36

    defaultConfig {
        applicationId = "app.sdot"
        minSdk = 33
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    // Local JVM tests only; the app itself ships with platform APIs and the Kotlin stdlib.
    testImplementation("junit:junit:4.13.2")
}
