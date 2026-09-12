import java.io.FileInputStream
import java.util.Properties

plugins {
    id("com.android.application")
    kotlin("android")
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.baselineprofile)
    id("com.mikepenz.aboutlibraries.plugin")
}

val props = Properties().apply {
    runCatching {
        load(FileInputStream(rootProject.file("local.properties")))
    }
}

// Release signing is presence-based, decided in ticket 23. The four properties
// live in ~/.gradle/gradle.properties on the one machine that holds the key —
// never in the tree, never in a GitHub Actions secret — so the repository holds
// no knowledge of the key at all, not even its path. When all four are there the
// release build is signed; when any is missing, which is every CI runner, no
// signingConfig is declared and :app:assembleRelease produces
// app-release-unsigned.apk exactly as it did before this existed.
// scripts/create-release-keystore.sh is what writes them.
val signingPropertyNames = listOf("storeFile", "storePassword", "keyAlias", "keyPassword")

val releaseSigning: Map<String, String>? = signingPropertyNames
    .mapNotNull { name ->
        providers.gradleProperty("lenews.release.$name").orNull
            ?.takeIf { it.isNotBlank() }
            ?.let { name to it }
    }
    .toMap()
    .takeIf { it.size == signingPropertyNames.size }

android {
    namespace = "app.lenews"

    defaultConfig {
        applicationId = "app.lenews"

        // LeNews starts at 1. Nothing upgrades from the Readrops package — the
        // applicationId changed, so every install is a fresh one — and upstream's
        // numbers carry no obligation here.
        versionCode = 1
        versionName = "1.0.0"

        testInstrumentationRunner = "app.lenews.LeNewsTestRunner"
    }

    signingConfigs {
        releaseSigning?.let { properties ->
            create("release") {
                val keystore = file(properties.getValue("storeFile"))

                // The properties being set is a claim that the key is there. If
                // it is not, that is a mistake to report, not a reason to fall
                // back to unsigned: a silent fall back hands over an APK that
                // cannot be installed and that everything downstream — the
                // release procedure included — believes is signed.
                //
                // It throws during configuration, so it fails every task in the
                // build and not only the release one. That is the price of
                // saying it early rather than at the end of a gate run, and the
                // message names both ways out.
                if (!keystore.isFile) {
                    throw GradleException(
                        "lenews.release.storeFile names a keystore that is not " +
                            "there: ${keystore.absolutePath}. Either restore it " +
                            "from its KeePassXC entry, or remove the four " +
                            "lenews.release.* properties from " +
                            "~/.gradle/gradle.properties to build unsigned."
                    )
                }

                storeFile = keystore
                storePassword = properties.getValue("storePassword")
                keyAlias = properties.getValue("keyAlias")
                keyPassword = properties.getValue("keyPassword")

                // minSdk 31, so every device this can reach verifies v2 and v1
                // jar signing is dead weight. v3 is what makes rotating this key
                // possible at all, should it ever need rotating.
                enableV1Signing = false
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true

            // Null when the properties are absent, which is the same release
            // build type this module had before signing existed.
            signingConfig = signingConfigs.findByName("release")

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

        // Two build types, no more. Upstream's debug-signed `beta` existed to
        // hand out pre-releases; LeNews publishes GitHub releases and has no
        // second audience. The `.debug` suffix stays: it is what lets the store
        // Readrops and a LeNews debug build sit on the phone at the same time.

        configureEach {
            val shouldSource = name == "debug"
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
        // module with lint errors: 347 of them, every one translation debt. The
        // 64 warnings are in there too, so the report reads "no new issues"
        // rather than scrolling past known noise.
        //
        //   199 MissingTranslation — the 14 locales inherited from upstream,
        //       most of them behind the English strings.
        //   135 ExtraTranslation, 47 UnusedResources and 10 MissingDefaultResource
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

// Ticket 24. The profile itself is a text file under
// src/main/generated/baselineProfiles/, committed, and this block is what keeps
// producing it a deliberate act rather than a step of every release build.
baselineProfile {
    // The generator needs a device: it drives the app through a cold start and
    // a timeline scroll and records what ART loaded. Left on, every
    // :app:assembleRelease — G6 of the gate, and every CI run — would want an
    // emulator it has no reason to need, and a runner without one would fail a
    // stage that has nothing to do with the release build working.
    //
    // So the profile is regenerated by hand, when the code it describes has
    // moved enough to be worth it:
    //
    //   ./gradlew :app:generateBaselineProfile
    //
    // against emulator-5554 running bench-pixel6-aosp with a seeded store —
    // scripts/seed-store.sh puts one there. The file it rewrites is committed
    // in the same commit as the code it was generated from.
    automaticGenerationDuringBuild = false

    // One profile for every build type rather than one per variant. It is the
    // release build that reads it, but the file describes the app's code and
    // not a build of it, and src/main is where a file that belongs to all of
    // them goes.
    mergeIntoMain = true

    // The release build is minified, so the class and method names in a profile
    // generated from the unminified build have to be put through R8's mapping
    // before they can match anything. Both of these default to off, and off is
    // the setting whose cost is invisible: the profile would still merge, still
    // ship, and match almost nothing.
    //
    // The first hands R8 the profile so it rewrites its rules to the obfuscated
    // names; the second hands R8 the startup half so it lays the dex files out
    // with the classes read before the first frame next to each other.
    baselineProfileRulesRewrite = true
    dexLayoutOptimization = true
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

    // Installs the baseline profile packaged in the APK into ART's reference
    // profile on first run. Without it the profile is only honoured by
    // installers that know to hand it to the platform, and LeNews is installed
    // by adb and by whatever a GitHub release download opens.
    implementation(libs.profileinstaller)

    testImplementation(libs.coroutines.test)
    testImplementation(libs.junit4)
    testImplementation(libs.bundles.kointest)
    testImplementation(libs.okhttp.mockserver)
    testImplementation(libs.okhttp.tls)

    androidTestImplementation(libs.coroutines.test)
    androidTestImplementation(libs.bundles.test)
    androidTestImplementation(libs.bundles.kointest)
    androidTestImplementation(libs.okhttp.mockserver)
    androidTestImplementation(libs.okhttp.tls)
    androidTestImplementation(libs.coil.test)
    androidTestImplementation(libs.workmanager.test)

    // Where the committed profile comes from. The dependency is what tells the
    // plugin which module generates it; with automaticGenerationDuringBuild off
    // it adds no task to any build that does not ask for one by name.
    baselineProfile(project(":baselineprofile"))
}
