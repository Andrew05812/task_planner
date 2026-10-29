import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("kotlin-kapt")
    id("com.google.dagger.hilt.android")
    id("androidx.navigation.safeargs.kotlin")
    id("org.jetbrains.kotlin.plugin.parcelize")
}

/**
 * Where a cloud configuration value may come from, in the order the developer is told to use.
 *
 * `providers.gradleProperty(…)` reads `gradle.properties` — the project's committed one and the user's own in
 * `~/.gradle` — and `-P` flags. It does **not** read `local.properties`, which is the file Android Studio creates
 * for `sdk.dir`, the one `.gitignore` keeps out of git, and the one `firebase.example.properties` and
 * `docs/FIREBASE_SETUP.md` tell a developer to copy these four values into. Written this way the documented path
 * would have been silently ignored: the build would print `NOT_CONFIGURED` forever and the reader would conclude
 * the instructions were wrong rather than the build script. A `-P` flag still wins, so CI secrets keep working.
 *
 * (`Properties` is imported rather than written as `java.util.Properties`: in a Gradle Kotlin DSL script the name
 * `java` resolves to the `java` plugin extension, so the qualified spelling is not the `java.util` package, and
 * the failure it produces is a script compilation error three lines away from the reason.)
 */
val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

fun cloudProperty(name: String): String =
    providers.gradleProperty(name).orNull?.takeIf { it.isNotBlank() }
        ?: localProperties.getProperty(name)?.takeIf { it.isNotBlank() }
        ?: "NOT_CONFIGURED"

android {
    namespace = "com.taskplanner"
    compileSdk = 34

    // Add experimental settings
    buildFeatures {
        viewBinding = true
        dataBinding = true
        buildConfig = true
    }

    defaultConfig {
        applicationId = "com.taskplanner"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        testInstrumentationRunnerArguments["clearPackageData"] = "true"

        // Firebase / Google OAuth configuration. These are placeholders on purpose:
        // real values must be provided by the developer (see docs/FIREBASE_SETUP.md).
        // The app runs fully in local mode while they are not configured.
        val firebaseProjectId = cloudProperty("firebase.projectId")
        val firebaseAppId = cloudProperty("firebase.appId")
        val firebaseApiKey = cloudProperty("firebase.apiKey")
        val googleWebClientId = cloudProperty("google.webClientId")
        buildConfigField("String", "FIREBASE_PROJECT_ID", "\"$firebaseProjectId\"")
        buildConfigField("String", "FIREBASE_APP_ID", "\"$firebaseAppId\"")
        buildConfigField("String", "FIREBASE_API_KEY", "\"$firebaseApiKey\"")
        buildConfigField("String", "GOOGLE_WEB_CLIENT_ID", "\"$googleWebClientId\"")

        // Say which build this is, at build time. A reader who opens Settings on a local-only build already sees
        // the disabled sign-in row and the reason beside it; a developer looking at a console should not have to
        // infer the same thing from silence. No value is ever printed — only which keys are still placeholders.
        val unconfigured = listOfNotNull(
            "firebase.projectId".takeIf { firebaseProjectId == "NOT_CONFIGURED" },
            "firebase.appId".takeIf { firebaseAppId == "NOT_CONFIGURED" },
            "firebase.apiKey".takeIf { firebaseApiKey == "NOT_CONFIGURED" },
            "google.webClientId".takeIf { googleWebClientId == "NOT_CONFIGURED" }
        )
        if (unconfigured.isNotEmpty()) {
            logger.lifecycle(
                "task-planner: local-only build — " + unconfigured.joinToString(", ") +
                    " still NOT_CONFIGURED (set them in local.properties or with -P; see docs/FIREBASE_SETUP.md" +
                    " and firebase.example.properties). Every local feature works; Google sign-in and Firestore" +
                    " sync stay disabled."
            )
        }

        // Room schema export
        kapt {
            arguments {
                arg("room.schemaLocation", "$projectDir/schemas")
                arg("room.incremental", "true")
                arg("room.expandProjection", "true")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }

    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs += listOf("-Xjvm-default=all")
    }

    testOptions {
        unitTests {
            // Robolectric needs the real Android resources to run Room migrations on the JVM.
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
        }
    }

    lint {
        // A Russian reader who gets an English string is not looking at a fallback — Android hides that case
        // completely — they are looking at a defect. The three gates now agree on that: the JVM contracts in
        // `com.taskplanner.res` fail the test task, `scripts/check-localization.py` fails the CI step with a
        // readable list, and these rules fail the build itself, so a developer who runs neither still cannot ship
        // an untranslated string. `ExtraTranslation` is here for the mirror case: a Russian key with no English
        // original is a string no code path can ask for, and it rots in silence.
        error += listOf(
            "MissingTranslation",
            "ExtraTranslation",
            "MissingQuantity",
            "ImpliedQuantity",
            "StringFormatInvalid",
            "StringFormatMatches",
            "StringFormatCount"
        )
    }

    kapt {
        correctErrorTypes = true
        useBuildCache = true
        arguments {
            arg("dagger.fastInit", "enabled")
            arg("kapt.verbose", "true")
            arg("kapt.kotlin.generated", "true")
        }
    }
}

dependencies {
    coreLibraryDesugaring(libs.android.desugar)

    // Core Android
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.lifecycle.runtime)
    implementation(libs.androidx.lifecycle.viewmodel)
    implementation(libs.androidx.lifecycle.livedata)
    implementation(libs.androidx.navigation.fragment)
    implementation(libs.androidx.navigation.ui)
    implementation(libs.androidx.swiperefresh)

    // Room
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    kapt(libs.androidx.room.compiler)

    // Coroutines
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.core)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.7.3")

    // Dagger Hilt
    implementation(libs.hilt.android)
    kapt(libs.hilt.compiler)
    implementation("androidx.hilt:hilt-navigation-fragment:1.1.0")

    // Gson
    implementation("com.google.code.gson:gson:2.10.1") {
        isTransitive = true
    }

    // Color Picker
    implementation("com.github.skydoves:colorpickerview:2.3.0")

    // Firebase + Google Credential Manager
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.auth)
    implementation(libs.firebase.firestore)
    implementation(libs.androidx.credentials)
    implementation(libs.androidx.credentials.play)
    implementation(libs.google.identity)

    // Background sync
    implementation(libs.androidx.work.runtime)

    // Testing
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.core)
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")
    testImplementation("org.robolectric:robolectric:4.11.1")
    testImplementation("androidx.arch.core:core-testing:2.2.0")
    testImplementation("androidx.test:core:1.5.0")
    testImplementation(libs.androidx.room.testing)
    // Migration tests run the real AppMigrations SQL against a genuine SQLite engine
    // (same SQLite version family Android ships), on the JVM, without Robolectric.
    testImplementation("org.xerial:sqlite-jdbc:3.45.3.0")
    androidTestImplementation("androidx.room:room-testing:2.6.1")
    androidTestImplementation("androidx.test:runner:1.5.2")
    androidTestImplementation("androidx.test:rules:1.5.0")
    androidTestImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")
    androidTestImplementation(libs.androidx.test.ext)
    androidTestImplementation(libs.androidx.test.espresso)
}

tasks.withType<Test>().configureEach {
    // Migration tests validate against the schema JSON Room exported, so they need to know
    // where the module lives regardless of the working directory Gradle picks.
    systemProperty("taskplanner.project.dir", projectDir.absolutePath)
    testLogging {
        events("failed", "skipped")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        showStackTraces = true
    }
}