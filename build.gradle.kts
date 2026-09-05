import com.android.build.api.variant.ApplicationAndroidComponentsExtension
import com.android.build.api.variant.LibraryAndroidComponentsExtension
import com.android.build.gradle.AppPlugin
import com.android.build.gradle.BaseExtension
import com.android.build.gradle.LibraryExtension
import com.android.build.gradle.LibraryPlugin
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.result.ResolvedComponentResult
import org.gradle.api.artifacts.result.ResolvedDependencyResult
import org.gradle.api.artifacts.result.UnresolvedDependencyResult
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinAndroidProjectExtension

buildscript {
    repositories {
        gradlePluginPortal()
        google()
        mavenCentral()
    }

    dependencies {
        classpath(libs.android.agp)
        classpath(libs.kotlin.kgp)
        classpath(libs.jacoco)
        classpath(libs.aboutlibraries)
    }
}

plugins {
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.compose.compiler) apply false
    jacoco
}

allprojects {
    repositories {
        google()
        mavenCentral()
        maven(url = "https://jitpack.io")
    }
}

subprojects {
    // Lint is a gate stage (G2), so it has to be able to fail the build. It was
    // turned off here and turned off again in each module's own build file,
    // which made every lint error in this repo advisory. It is on now, in this
    // one place, for all three modules. Errors only: warnings still print and
    // still stop nothing, and the errors that were already here on the day the
    // gate was built are held in app/lint-baseline.xml rather than fixed blind
    // — see app/build.gradle.kts for what is in that baseline and why.
    //
    // Set through the typed extension rather than through BaseExtension's
    // lintOptions, which AGP has deprecated.
    afterEvaluate {
        with(extensions.getByType<KotlinAndroidProjectExtension>()) {
            compilerOptions {
                jvmTarget = JvmTarget.JVM_17
            }
        }

        plugins.withType<AppPlugin> {
            configure<BaseExtension> {
                configure(this)
            }
            the<com.android.build.api.dsl.ApplicationExtension>().lint {
                abortOnError = true
            }
        }
        plugins.withType<LibraryPlugin> {
            configure<LibraryExtension> {
                configure(this)
            }
            the<com.android.build.api.dsl.LibraryExtension>().lint {
                abortOnError = true
            }
        }
    }

}

fun configure(extension: BaseExtension) = with(extension) {
    compileSdkVersion(35)

    defaultConfig {
        minSdk = 31
        targetSdk = 35
        buildToolsVersion = "35.0.1"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

}

tasks.register<Delete>("clean") {
    delete(rootProject.layout.buildDirectory)
}

jacoco {
    toolVersion = "0.8.7"
}


tasks.register<JacocoReport>("jacocoFullReport") {
    group = "Reporting"
    description = "Generate Jacoco coverage reports for the debug build"

    reports {
        xml.required.set(true)
        html.required.set(true)
    }

    dependsOn(":app:testDebugUnitTest")
    dependsOn(":app:connectedAndroidTest")
    dependsOn(":api:testDebugUnitTest")
    dependsOn(":db:testDebugUnitTest")
    dependsOn(":db:connectedAndroidTest")

    val excludeFilter = listOf(
        "**/R.class",
        "**/R\$*.class",
        "**/BuildConfig.*",
        "**/Manifest*.*",
        "android/**/*.*"
    )

    classDirectories.setFrom(
        files(
            fileTree("${project.rootDir}/app/build/intermediates/javac/debug") { exclude(excludeFilter) },
            fileTree("${project.rootDir}/app/build/tmp/kotlin-classes/debug") { exclude(excludeFilter) }
        ),
        fileTree("${project.rootDir}/api/build/tmp/kotlin-classes/debug")  { exclude(excludeFilter) },
        fileTree("${project.rootDir}/db/build/tmp/kotlin-classes/debug")  { exclude(excludeFilter) },
    )

    val coverageSourceDirs = listOf(
        "${project.rootDir}/app/src/main/java",
        "${project.rootDir}/api/src/main/java",
        "${project.rootDir}/db/src/main/java",
    )

    additionalSourceDirs.setFrom(files(coverageSourceDirs))
    sourceDirectories.setFrom(files(coverageSourceDirs))

    executionData.setFrom(
        fileTree(project.rootDir) {
            include(
                listOf(
                    "api/build/outputs/unit_test_code_coverage/**/*.exec",
                    "db/build/outputs/unit_test_code_coverage/**/*.exec",
                    "app/build/outputs/code_coverage/debugAndroidTest/connected/**/*.ec",
                    "db/build/outputs/code_coverage/debugAndroidTest/connected/**/*.ec"
                )
            )
        }
    )
}

// ---------------------------------------------------------------------------
// Google dependency guard (gate stage G4)
// ---------------------------------------------------------------------------
// The app has to keep working on a de-Googled OS — GrapheneOS with no Play
// Services, sandboxed or otherwise — so nothing on a runtime classpath may pull
// in Play Services or Firebase, including transitively, which is how it would
// actually happen.
//
// Match on coordinates, never on the string "google". `com.google.android.material`
// is AndroidX Material Components, `com.google.accompanist` is Compose helpers
// and `com.google.devtools.ksp` is the annotation processor: all three are in
// this graph today and none of them has anything to do with Play Services.
val bannedDependencyGroups = setOf("com.google.android.gms", "com.google.firebase")
val bannedDependencyModuleFragment = "play-services"

// One task per module rather than one task at the root walking all three. A task
// may only resolve its own project's configurations; a root task reaching into
// :app's would be cross-project resolution, which Gradle is in the middle of
// taking away. Running `./gradlew checkNoGoogleDependencies` unqualified still
// runs all three, and scripts/check.sh names them one by one so the stage says
// which module failed.
subprojects {
    // Collected from the variant API rather than hard-coded to debug and
    // release, so that a build type or product flavour added later is guarded
    // the day it appears. This project already has a third build type, `beta`.
    // "May never enter the graph" is not a constraint that should depend on
    // someone remembering to extend a list.
    val guardedConfigurations = mutableListOf<String>()
    val modulePath = path

    plugins.withType<AppPlugin> {
        extensions.getByType<ApplicationAndroidComponentsExtension>().onVariants { variant ->
            guardedConfigurations.add(variant.runtimeConfiguration.name)
        }
    }
    plugins.withType<LibraryPlugin> {
        extensions.getByType<LibraryAndroidComponentsExtension>().onVariants { variant ->
            guardedConfigurations.add(variant.runtimeConfiguration.name)
        }
    }

    val guard = tasks.register("checkNoGoogleDependencies") {
        group = "verification"
        description = "Fails if a variant runtime classpath pulls in Play Services or Firebase."

        // Resolve the whole dependency graph, not just the first level: the
        // point of the guard is to catch what an innocent-looking dependency
        // drags in behind it. This block runs when the task is realised, which
        // is after the variant callbacks above have filled the list.
        val dependencyGraphs = guardedConfigurations.associateWith { name ->
            configurations.named(name).flatMap { it.incoming.resolutionResult.rootComponent }
        }
        doLast {
            // A guard that inspects nothing passes everything. If the variant
            // API ever hands back an empty list, that has to be loud rather
            // than green.
            if (dependencyGraphs.isEmpty()) {
                throw GradleException(
                    "checkNoGoogleDependencies found no variant runtime classpath to inspect in" +
                            " $modulePath, so it checked nothing. Fix the guard."
                )
            }

            val offenders = sortedSetOf<String>()

            // An edge Gradle could not resolve is an edge nobody can see the
            // group and module of, so the check above cannot be run on it. A
            // banned dependency with a typo in its version would walk straight
            // through a guard that skipped these. Collected and reported, so
            // "the guard did not look at this one" is loud instead of silent.
            val unresolved = sortedSetOf<String>()

            dependencyGraphs.forEach { (configurationName, rootComponent) ->
                val visited = mutableSetOf<String>()
                val pending = ArrayDeque<ResolvedComponentResult>()
                pending.add(rootComponent.get())

                while (pending.isNotEmpty()) {
                    val component = pending.removeFirst()
                    if (!visited.add(component.id.displayName)) {
                        continue
                    }

                    val id = component.id
                    if (id is ModuleComponentIdentifier &&
                        (id.group in bannedDependencyGroups ||
                                id.module.contains(bannedDependencyModuleFragment))
                    ) {
                        offenders.add(
                            "  ${id.group}:${id.module}:${id.version}" +
                                    "  (in $modulePath $configurationName)"
                        )
                    }

                    component.dependencies.forEach { dependency ->
                        when (dependency) {
                            is ResolvedDependencyResult -> pending.add(dependency.selected)
                            is UnresolvedDependencyResult -> unresolved.add(
                                "  ${dependency.attempted.displayName}" +
                                        "  (in $modulePath $configurationName)" +
                                        "\n      ${dependency.failure.message}"
                            )

                            else -> unresolved.add(
                                "  ${dependency.requested.displayName}" +
                                        "  (in $modulePath $configurationName)" +
                                        "\n      unknown kind of dependency result:" +
                                        " ${dependency.javaClass.name}"
                            )
                        }
                    }
                }
            }

            val problems = mutableListOf<String>()
            if (offenders.isNotEmpty()) {
                problems.add(
                    "Google Play Services / Firebase dependencies are not allowed:\n" +
                            offenders.joinToString("\n") +
                            "\n\nThis app has to run on a de-Googled OS. Find a replacement, or" +
                            "\nchange this rule deliberately in build.gradle.kts."
                )
            }
            if (unresolved.isNotEmpty()) {
                problems.add(
                    "checkNoGoogleDependencies could not resolve these dependencies, so it" +
                            " could not tell\nwhether they are allowed:\n" +
                            unresolved.joinToString("\n") +
                            "\n\nFix the dependency, then run the guard again."
                )
            }
            if (problems.isNotEmpty()) {
                throw GradleException(problems.joinToString("\n\n"))
            }
        }
    }

    // Wire the guard into the standard lifecycle so it is not only enforced by
    // the scripts that happen to remember to call it. `check` is created by the
    // base plugin that the Android plugins bring, so match it lazily rather
    // than asking for it before it exists.
    tasks.matching { it.name == "check" }.configureEach {
        dependsOn(guard)
    }
}
