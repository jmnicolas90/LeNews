// androidx.baselineprofile is published to Google's Maven repository and not to
// the Gradle plugin portal, which is the only place a `plugins { alias(...) }`
// block looks by default. The other Android plugins in this build never needed
// this: they come in through the root build file's buildscript classpath.
pluginManagement {
    repositories {
        gradlePluginPortal()
        google()
        mavenCentral()
    }
}

include(":api", ":db", ":app", ":baselineprofile")
