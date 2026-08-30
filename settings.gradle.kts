/*
 * Google's Maven repository is served from dl.google.com, which is blocked from
 * the primary development machine (DECISIONS D10). Without a mirror an ordinary
 * Android build cannot resolve a single AndroidX artifact there.
 *
 * Opt in per machine with an environment variable, never in committed config:
 *
 *     EASYOTP_MIRRORS=true ./gradlew assembleDebug
 *
 * (./dev sets it inside the container automatically.) CI and contributors on an
 * unrestricted connection use the authoritative repositories untouched.
 *
 * An environment variable rather than a Gradle property because Gradle compiles
 * the pluginManagement block in a separate earlier stage, where helper functions
 * declared in this file are not yet in scope.
 *
 * Trusting a third-party mirror is a supply-chain decision, not a convenience.
 * See DECISIONS D11: dependency verification is the mitigation, and it is why
 * this stays opt-in and visible instead of becoming the silent default.
 */
val useMirrors = System.getenv("EASYOTP_MIRRORS")?.toBoolean() == true

pluginManagement {
    repositories {
        if (System.getenv("EASYOTP_MIRRORS")?.toBoolean() == true) {
            maven { setUrl("https://maven.aliyun.com/repository/google") }
            maven { setUrl("https://maven.aliyun.com/repository/central") }
        }
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        if (useMirrors) {
            maven { setUrl("https://maven.aliyun.com/repository/google") }
            maven { setUrl("https://maven.aliyun.com/repository/central") }
        }
        google()
        mavenCentral()
    }
}

rootProject.name = "EasyOTP"
include(":app")
