plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "nl.wiebenieuwenhuis.tender"
    compileSdk = 37

    defaultConfig {
        applicationId = "nl.wiebenieuwenhuis.tender"
        minSdk = 28
        targetSdk = 37
        // Every commit on main counts up, so each upload has a higher version code.
        versionCode = providers.exec { commandLine("git", "rev-list", "--count", "HEAD"); isIgnoreExitValue = true }
            .standardOutput.asText.map { it.trim().toIntOrNull() ?: 1 }.getOrElse(1)
        versionName = "0.1"
        // The commit the app was built from, shown in Settings next to the version.
        val commit = providers.exec { commandLine("git", "rev-parse", "--short", "HEAD"); isIgnoreExitValue = true }
            .standardOutput.asText.map { it.trim() }.getOrElse("")
        buildConfigField("String", "GIT_COMMIT", "\"$commit\"")
    }

    // The Play upload key lives outside the repo (~/.android-keys); its password comes from the
    // macOS Keychain through scripts/bundle-release.sh (ORG_GRADLE_PROJECT_tenderUploadPassword).
    val uploadPassword = providers.gradleProperty("tenderUploadPassword").orNull
    signingConfigs {
        if (uploadPassword != null) {
            create("upload") {
                storeFile = file(System.getProperty("user.home") + "/.android-keys/tender-upload.jks")
                storePassword = uploadPassword
                keyAlias = "upload"
                keyPassword = uploadPassword
            }
        }
    }

    buildTypes {
        release {
            if (uploadPassword != null) signingConfig = signingConfigs.getByName("upload")
            isMinifyEnabled = true
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
    androidResources {
        localeFilters += listOf("en", "nl")
    }
}

kotlin {
    compilerOptions {
        optIn.addAll(
            "androidx.compose.material3.ExperimentalMaterial3Api",
            "androidx.compose.foundation.ExperimentalFoundationApi",
            "androidx.compose.foundation.layout.ExperimentalLayoutApi",
            "androidx.compose.material3.ExperimentalMaterial3ExpressiveApi",
        )
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.navigation.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material3.adaptive.nav)
    implementation(libs.compose.material.icons)
    debugImplementation(libs.compose.ui.tooling)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.exoplayer.hls)
    implementation(libs.media3.datasource.okhttp)
    implementation(libs.media3.ui)
    implementation(libs.media3.ui.compose)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)
    implementation(libs.androidx.webkit)
    implementation(libs.androidx.security.crypto)
}
