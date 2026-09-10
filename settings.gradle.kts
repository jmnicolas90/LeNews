// androidx.baselineprofile is published to Google's Maven repository and not to
// the Gradle plugin portal, which is the only place a `plugins { alias(...) }`
// block looks by default. The other Android plugins in this build never needed
// this: they come in through the root build file's buildscript classpath.
pluginManagement {
    repositories {
        // The default this block replaces, and where ksp and the Compose
        // compiler plugin come from — so it has to be named again, not because
        // anything new needs it.
        gradlePluginPortal()
        google()
    }
}

include(":api", ":db", ":app", ":baselineprofile")
