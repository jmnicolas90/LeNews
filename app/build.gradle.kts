import java.io.FileInputStream
import java.util.Properties

plugins {
    id("com.android.application")
    kotlin("android")
    alias(libs.plugins.compose.compiler)
    id("com.mikepenz.aboutlibraries.plugin")
}

val props = Properties().apply {
    runCatching {
        load(FileInputStream(rootProject.file("local.properties")))
    }
}


android {
    namespace = "com.readrops.app"

    defaultConfig {
        applicationId = "com.readrops.app"

        versionCode = 22
        versionName = "2.1.1"

        testInstrumentationRunner = "com.readrops.app.ReadropsTestRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true

            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }

        debug {
            isMinifyEnabled = false
            isShrinkResources = false

            applicationIdSuffix = ".debug"
            enableUnitTestCoverage = true
            enableAndroidTestCoverage = true

            proguardFiles(getDefaultProguardFile("proguard-android.txt"), "proguard-rules.pro")
        }

        create("beta") {
            initWith(getByName("release"))

            applicationIdSuffix = ".beta"
            signingConfig = signingConfigs.getByName("debug")
        }

        configureEach {
            val shouldSource = name == "debug" || name == "beta"
            val values = mapOf("url" to "https://", "login" to "", "password" to "")

            values.forEach { (param, default) ->
                val key = "debug.freshrss.$param"
                val value = if (shouldSource) props.getProperty(key, default) else default
                resValue("string", key, value)
            }
        }
    }

    buildFeatures {
        buildConfig = true
        compose = true
    }

    lint {
        // abortOnError is set once, in the root build.gradle.kts, for all three
        // modules. What is module-specific is the baseline, and this is the only
        // module with lint errors: 362 of them, every one translation debt. The
        // 67 warnings are in there too, so the report reads "no new issues"
        // rather than scrolling past known noise.
        //
        //   203 MissingTranslation — the 14 locales inherited from upstream's
        //       Weblate, most of them behind the English strings.
        //   146 ExtraTranslation, 50 UnusedResources and 10 MissingDefaultResource
        //       — the other side of the same debt. Deleting the local RSS,
        //       Nextcloud News and Fever services took their strings out of the
        //       English strings.xml and left the 14 translations holding strings
        //       that no longer exist anywhere else.
        //     3 ImpliedQuantity — the French and Brazilian Portuguese plurals of
        //       two error strings, whose "one" form also covers zero.
        //
        // All of it waits on one product call nobody has made yet: which of the
        // 14 locales LeNews keeps (see "Translations" under Not yet specified in
        // the map). Adding strings would mean inventing translations, deleting
        // locales would pre-empt the call, and clearing the orphans out of the
        // locales is the same edit as making the call.
        // Everything the baseline holds is a finding to clear, not a rule to
        // forget: new errors of the same kinds still fail the gate.
        baseline = file("lint-baseline.xml")
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation(project(":api"))
    implementation(project(":db"))

    implementation(libs.corektx)
    implementation(libs.appcompat)
    implementation(libs.material)
    implementation(libs.palette)
    implementation(libs.workmanager)
    implementation(libs.encrypted.preferences)
    implementation(libs.datastore)
    implementation(libs.browser)
    implementation(libs.splashscreen)
    implementation(libs.preferences)


    implementation(platform(libs.compose.bom))
    implementation(libs.bundles.compose)

    implementation(libs.bundles.voyager)
    implementation(libs.bundles.lifecycle)
    implementation(libs.bundles.coil)

    implementation(libs.bundles.coroutines)

    implementation(libs.bundles.room)
    implementation(libs.bundles.paging)

    implementation(platform(libs.koin.bom))
    implementation(libs.bundles.koin)

    implementation(libs.aboutlibraries.composem3)
    implementation(libs.jsoup)
    implementation(libs.colorpicker)

    implementation(libs.autofill)
    implementation(libs.template)
    implementation(libs.slf4j.android)

    testImplementation(libs.coroutines.test)
    testImplementation(libs.junit4)

    androidTestImplementation(libs.coroutines.test)
    androidTestImplementation(libs.bundles.test)
    androidTestImplementation(libs.bundles.kointest)
    androidTestImplementation(libs.okhttp.mockserver)
    androidTestImplementation(libs.coil.test)
    androidTestImplementation(libs.workmanager.test)
}
