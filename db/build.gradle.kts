plugins {
    id("com.android.library")
    kotlin("android")
    id("com.google.devtools.ksp")
}

android {
    namespace = "app.lenews.db"

    buildTypes {
        debug {
            enableUnitTestCoverage = true
            enableAndroidTestCoverage = true
        }
    }

    defaultConfig {
        consumerProguardFiles("consumer-rules.pro")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    sourceSets {
        getByName("androidTest") {
            assets.srcDirs("$projectDir/schemas")
        }
    }

    // No lint block: abortOnError lives in the root build.gradle.kts, one
    // setting for all three modules, and this module has no lint errors and so
    // needs no baseline.
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
    arg("room.incremental", "true")
    arg("room.generateKotlin", "true")
}

dependencies {
    implementation(libs.corektx)
    implementation(libs.appcompat)

    implementation(libs.bundles.room)
    ksp(libs.room.compiler)

    implementation(libs.bundles.coroutines)
    implementation(libs.bundles.paging)

    implementation(platform(libs.koin.bom))
    implementation(libs.bundles.koin)

    testImplementation(libs.junit4)

    androidTestImplementation(libs.bundles.test)
    androidTestImplementation(libs.bundles.kointest)
    androidTestImplementation(libs.room.testing)
    androidTestImplementation(libs.coroutines.test)
}
