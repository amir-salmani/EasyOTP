pluginManagement {
    repositories {
        mirrorsFirst()
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoryMode.set(RepositoryMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mirrorsFirst()
        google()
        mavenCentral()
    }
}

/**
 * Google's Maven repository is served from dl.google.com, which is blocked from
 * the primary development machine (DECISIONS D10) -- so an ordinary Android build
 * cannot resolve a single AndroidX artifact there. A mirror makes it buildable.
 *
 * Off by default: CI and unaffected contributors use the authoritative repos.
 * Enable per-machine, never in committed config:
 *
 *     echo "easyotp.mirrors=true" >> ~/.gradle/gradle.properties
 *
 * Trusting a third-party mirror is a supply-chain decision, not a convenience --
 * see DECISIONS D11. Gradle dependency verification is the mitigation, and it is
 * why this must stay opt-in and visible rather than becoming the silent default.
 */
fun RepositoryHandler.mirrorsFirst() {
    val enabled = providers.gradleProperty("easyotp.mirrors").orNull.toBoolean()
    if (!enabled) return
    maven {
        name = "aliyun-google-mirror"
        setUrl("https://maven.aliyun.com/repository/google")
    }
    maven {
        name = "aliyun-central-mirror"
        setUrl("https://maven.aliyun.com/repository/central")
    }
}

rootProject.name = "EasyOTP"
include(":app")
