plugins {
    id("com.android.application")
}

// Release inputs come from -P properties or the environment, never from committed files.
fun setting(property: String, env: String? = null): String? =
    (providers.gradleProperty(property).orNull ?: env?.let { System.getenv(it) })?.trim()?.takeIf { it.isNotEmpty() }

// GitHub repository (owner/repo) the in-app updater checks; the update button is hidden when unset.
val updateRepo = setting("sdot.updateRepo") ?: ""
require(updateRepo.isEmpty() || Regex("^[A-Za-z0-9-]{1,39}/[A-Za-z0-9._-]{1,100}$").matches(updateRepo)) {
    "sdot.updateRepo must look like owner/repo"
}

// versionCode = major * 10000 + minor * 100 + patch (minor and patch 0..99), so releases always increase.
val sdotVersion = setting("sdot.versionName") ?: "0.1.0"
val sdotVersionCode = Regex("^(\\d{1,4})\\.(\\d{1,2})\\.(\\d{1,2})$").matchEntire(sdotVersion)
    ?.destructured?.let { (major, minor, patch) -> major.toInt() * 10_000 + minor.toInt() * 100 + patch.toInt() }
    ?.takeIf { it > 0 }
    ?: throw GradleException("sdot.versionName must be MAJOR.MINOR.PATCH with minor and patch 0..99, got '$sdotVersion'")

val keystorePath = setting("sdot.keystore", "SDOT_KEYSTORE")
val keystorePassword = setting("sdot.keystorePassword", "SDOT_KEYSTORE_PASSWORD")
val keyAliasName = setting("sdot.keyAlias", "SDOT_KEY_ALIAS")
val keyPasswordValue = setting("sdot.keyPassword", "SDOT_KEY_PASSWORD")
val releaseSigningReady = listOf(keystorePath, keystorePassword, keyAliasName, keyPasswordValue).all { it != null }

android {
    namespace = "app.sdot"
    compileSdk = 36

    defaultConfig {
        applicationId = "app.sdot"
        minSdk = 33
        targetSdk = 36
        versionCode = sdotVersionCode
        versionName = sdotVersion
        buildConfigField("String", "UPDATE_REPO", "\"$updateRepo\"")
    }

    buildFeatures {
        buildConfig = true
    }

    signingConfigs {
        if (releaseSigningReady) {
            create("release") {
                storeFile = file(keystorePath!!)
                storePassword = keystorePassword
                keyAlias = keyAliasName
                keyPassword = keyPasswordValue
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (releaseSigningReady) signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

// An unsigned release APK cannot be installed or update the app, so refuse to package one.
// Only packaging is gated: compiling and unit-testing the release variant keep working without keys.
tasks.matching { it.name == "packageRelease" || it.name == "bundleRelease" }.configureEach {
    doFirst {
        if (!releaseSigningReady) {
            throw GradleException(
                "Release signing is not configured. Set SDOT_KEYSTORE, SDOT_KEYSTORE_PASSWORD, SDOT_KEY_ALIAS and " +
                    "SDOT_KEY_PASSWORD (or -Psdot.keystore, -Psdot.keystorePassword, -Psdot.keyAlias, -Psdot.keyPassword). " +
                    "Debug builds do not need them.",
            )
        }
    }
}

dependencies {
    // Local JVM tests only; the app itself ships with platform APIs and the Kotlin stdlib.
    testImplementation("junit:junit:4.13.2")
}
