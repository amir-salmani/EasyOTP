plugins {
    alias(libs.plugins.android.application)
    // No kotlin-android plugin: AGP 9.0+ provides Kotlin support built in and
    // rejects the standalone plugin. See kotl.in/gradle/agp-built-in-kotlin.
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "ir.rhinocloud.easyotp"
    // 36 is the newest platform installable from the stable SDK channel; 37 is
    // catalogued but not published there yet. Dependency versions are pinned to
    // match in gradle/libs.versions.toml.
    compileSdk = 36

    defaultConfig {
        applicationId = "ir.rhinocloud.easyotp"
        // API 26 is the floor for TelephonyManager.sendUssdRequest, which M2 needs
        // for balance and package checks -- the feature that keeps a remote SIM alive.
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures { compose = true }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)

    testImplementation(libs.junit)
}
