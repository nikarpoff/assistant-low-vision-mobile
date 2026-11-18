plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

/** Gradle property [name] as a Java string literal for BuildConfig, empty when it is not set. */
fun buildConfigString(name: String): String {
    val value = providers.gradleProperty(name).orNull.orEmpty()
    return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
}

android {
    namespace = "io.github.nikarpoff.alv"
    compileSdk = 37
    buildToolsVersion = "37.0.0"

    defaultConfig {
        applicationId = "io.github.nikarpoff.alv"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"

        // Defaults of the settings (MOB-9), set at build time: -PalvServerUrl=... -PalvDeviceToken=...
        // or the same keys in ~/.gradle/gradle.properties, never in git
        buildConfigField("String", "DEFAULT_SERVER_URL", buildConfigString("alvServerUrl"))
        buildConfigField("String", "DEFAULT_DEVICE_TOKEN", buildConfigString("alvDeviceToken"))
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)

    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.android.vad.webrtc)

    testImplementation(libs.junit)
}
