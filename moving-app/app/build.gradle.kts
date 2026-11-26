plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

// The Android images Robolectric runs the screen tests on: Android 14 (the tests' @Config(sdk = [34])) and
// Android 15 (the default, from targetSdk). Keep these in step with the Robolectric version below.
// One configuration each: in one, Gradle would keep only the newer "version" of the same artifact.
val robolectricImage14: Configuration by configurations.creating { isTransitive = false }
val robolectricImage15: Configuration by configurations.creating { isTransitive = false }
val robolectricImagesDir = layout.buildDirectory.dir("robolectric-images")
val robolectricImages by tasks.registering(Sync::class) {
    from(robolectricImage14, robolectricImage15)
    into(robolectricImagesDir)
}

android {
    namespace = "app.housemove"
    // 37 for the Compose that Compose Multiplatform 1.12 uses on Android; the app still targets 35.
    compileSdk = 37

    defaultConfig {
        applicationId = "app.housemove"
        minSdk = 26
        targetSdk = 35
        versionCode = 13
        versionName = "2.5"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // A fixed key kept in the repo so every build (local or CI) can be installed
    // over the previous one without uninstalling and losing data.
    signingConfigs {
        create("sideload") {
            storeFile = rootProject.file("keystore/sideload.jks")
            storePassword = "housemove"
            keyAlias = "housemove"
            keyPassword = "housemove"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("sideload")
        }
        debug {
            signingConfig = signingConfigs.getByName("sideload")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
    }
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            all {
                it.systemProperty("roborazzi.test.record", "true")
                it.systemProperty("robolectric.graphicsMode", "NATIVE")
                // Robolectric's Android images come through Gradle (cached, retried) instead of Robolectric's own
                // download from Maven Central on every run, which CI has seen refused with HTTP 429.
                it.dependsOn(robolectricImages)
                it.systemProperty("robolectric.offline", "true")
                it.systemProperty("robolectric.dependency.dir", robolectricImagesDir.get().asFile.absolutePath)
                it.maxHeapSize = "1536m"
                // A failure in CI says which test and why in the job log itself.
                it.testLogging {
                    events(org.gradle.api.tasks.testing.logging.TestLogEvent.FAILED)
                    exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
                }
            }
        }
    }
}

// Kotlin is compiled by the Android plugin itself; its JVM target follows compileOptions above (17).
kotlin {
    compilerOptions {
        optIn.addAll(
            "androidx.compose.material3.ExperimentalMaterial3Api",
            "androidx.compose.foundation.layout.ExperimentalLayoutApi",
            "androidx.compose.foundation.ExperimentalFoundationApi",
        )
    }
}


// Keeps Compose's view of the shared module's data classes as it was before the split (see the file).
composeCompiler {
    stabilityConfigurationFiles.add(layout.projectDirectory.file("compose-stability.conf"))
}

dependencies {
    // Compose 1.12.1 and Material 3 1.4.0: what Compose Multiplatform 1.12.2 resolves to on Android.
    val composeBom = platform("androidx.compose:compose-bom:2026.09.00")
    implementation(composeBom)
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.material3:material3")

    val camerax = "1.4.2"
    implementation("androidx.camera:camera-core:$camerax")
    implementation("androidx.camera:camera-camera2:$camerax")
    implementation("androidx.camera:camera-lifecycle:$camerax")
    implementation("androidx.camera:camera-view:$camerax")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.1")
    // The move's data and sync (they bring kotlinx-serialization, kotlinx-coroutines-core and ZXing with them).
    implementation(project(":shared"))
    // The look and the screens shared with the iPhone (Compose Multiplatform): the theme, the Outfit fonts as Compose
    // resources and the Material icons the app uses, as source (design/ios-plan.md, step 2).
    implementation(project(":sharedUi"))

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
    robolectricImage14("org.robolectric:android-all-instrumented:14-robolectric-10818077-i7")
    robolectricImage15("org.robolectric:android-all-instrumented:15-robolectric-12650502-i7")
    testImplementation("androidx.test:core:1.6.1")
    testImplementation(composeBom)
    testImplementation("androidx.compose.ui:ui-test-junit4")
    testImplementation("io.github.takahirom.roborazzi:roborazzi:1.43.0")
    testImplementation("io.github.takahirom.roborazzi:roborazzi-compose:1.43.0")
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    // Tests that run on a real device or emulator (see DeviceTest).
    androidTestImplementation(composeBom)
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:core:1.6.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:rules:1.6.1")
}
