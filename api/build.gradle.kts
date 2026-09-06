plugins {
    id("com.android.library")
    kotlin("android")
}

android {
    namespace = "app.lenews.api"

    buildTypes {
        debug {
            enableUnitTestCoverage = true
        }
    }

    sourceSets {
        getByName("androidTest") {
            assets.srcDirs("$projectDir/androidTest/assets")
        }
    }

    kotlinOptions {
        freeCompilerArgs = listOf("-Xstring-concat=inline")
    }

    lint {
        // abortOnError lives in the root build.gradle.kts, one setting for all
        // three modules. This module has no lint errors and so needs no baseline.

        // disable lint rule which isn't supposed to be applied on a non compose module
        disable.add("CoroutineCreationDuringComposition")
    }
}

dependencies {
    implementation(project(":db"))

    implementation(libs.coroutines.core)

    implementation(platform(libs.koin.bom))
    implementation(libs.bundles.koin)

    implementation(libs.okhttp)

    implementation(libs.bundles.retrofit) {
        exclude("com.squareup.okhttp3", "okhttp3")
        exclude("com.squareup.moshi", "moshi")
    }

    implementation(libs.moshi)
    implementation(libs.jsoup)

    testImplementation(libs.junit4)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.bundles.kointest)
    testImplementation(libs.okhttp.mockserver)
    testImplementation(libs.okhttp.tls)
}
