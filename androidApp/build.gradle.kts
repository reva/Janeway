import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
}

// AcoustID API key: ACOUSTID_API_KEY environment variable (CI) or acoustid.apiKey in local.properties.
val localProperties = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use(::load)
}
val acoustIdApiKey: String = providers.environmentVariable("ACOUSTID_API_KEY").orNull
    ?: localProperties.getProperty("acoustid.apiKey")
    ?: ""

android {
    namespace = "io.github.reva.janeway"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "io.github.reva.janeway"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        versionCode = 1
        versionName = "0.1.0"
        buildConfigField("String", "ACOUSTID_API_KEY", "\"${acoustIdApiKey.trim()}\"")
    }

    // Optional shared debug key, so APKs from CI and from local builds can update each other.
    val sharedDebugKey = file("debug.keystore")
    if (sharedDebugKey.exists()) {
        signingConfigs {
            getByName("debug") {
                storeFile = sharedDebugKey
                storePassword = "android"
                keyAlias = "androiddebugkey"
                keyPassword = "android"
            }
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(project(":shared"))
    implementation(libs.androidx.activity.compose)
}
