import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

/**
 * Keys live in local.properties, which is git ignored. Nothing here is
 * required: with no token the app runs on the bundled fixtures, which is the
 * whole point of having them.
 */
val secrets = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

fun secret(name: String): String =
    (secrets.getProperty(name) ?: System.getenv(name) ?: "").trim()

android {
    namespace = "ae.clearlane.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "ae.clearlane.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1"

        buildConfigField("String", "MAPBOX_TOKEN", "\"${secret("CLEARLANE_MAPBOX_TOKEN")}\"")
        buildConfigField("String", "MAP_STYLE_URL", "\"${secret("CLEARLANE_MAP_STYLE_URL")}\"")

        // Off unless a developer asks for it. Camera positions for the UAE are
        // bundled; turning this on queries Overpass live for anywhere else,
        // which is fine for filling in another country and not fine to ship,
        // because Overpass is donated hardware and its usage policy says so.
        buildConfigField(
            "boolean",
            "LIVE_CAMERA_LOOKUP",
            secret("CLEARLANE_LIVE_CAMERA_LOOKUP").ifBlank { "false" },
        )
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
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

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":data"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.maplibre)

    // Android 15 devices can use 16 KB memory pages, and a native library that
    // is not aligned for them makes the system put the whole app into a
    // compatibility mode and say so in a dialog. Both offenders are transitive,
    // so they are pinned here rather than fixed upstream.
    implementation(libs.androidx.graphics.path)
}
