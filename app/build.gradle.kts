plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("kotlin-kapt")
    id("com.google.dagger.hilt.android")
    id("androidx.navigation.safeargs.kotlin")
    id("org.jetbrains.kotlin.plugin.parcelize")
}

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
        val firebaseProjectId = providers.gradleProperty("firebase.projectId").getOrElse("NOT_CONFIGURED")
        val firebaseAppId = providers.gradleProperty("firebase.appId").getOrElse("NOT_CONFIGURED")
        val firebaseApiKey = providers.gradleProperty("firebase.apiKey").getOrElse("NOT_CONFIGURED")
        val googleWebClientId = providers.gradleProperty("google.webClientId").getOrElse("NOT_CONFIGURED")
        buildConfigField("String", "FIREBASE_PROJECT_ID", "\"$firebaseProjectId\"")
        buildConfigField("String", "FIREBASE_APP_ID", "\"$firebaseAppId\"")
        buildConfigField("String", "FIREBASE_API_KEY", "\"$firebaseApiKey\"")
        buildConfigField("String", "GOOGLE_WEB_CLIENT_ID", "\"$googleWebClientId\"")

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