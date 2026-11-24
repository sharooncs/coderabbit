import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// The screens shared by the Android app and the iPhone app (Compose Multiplatform), step 0e of design/ios-plan.md.
// For now it holds the shared look (theme, Outfit fonts, the app's Material icons as source) and a trial screen that
// the iPhone app shows; the app's screens move here in step 2. The app does not depend on it yet.
//   android    the Android library the app will use (step 2); the same androidx Compose as the app's BOM
//   desktop    a plain JVM, to render the screens to PNG on Linux (desktopTest)
//   iosArm64, iosSimulatorArm64   a static framework, HouseMoveKit, that Xcode links into the iPhone app
plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("com.android.kotlin.multiplatform.library")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Compose Multiplatform 1.12.2 and its Material 3 (1.9.0). On Android these are androidx Compose 1.12.1 and
// Material 3 1.4.0, the versions in the app's Compose BOM (2026.09.00).
val composeVersion = "1.12.2"
val material3Version = "1.9.0"

kotlin {
    android {
        namespace = "app.housemove.sharedui"
        compileSdk = 37
        minSdk = 26
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
        // Compose resources (the fonts) travel as Android assets.
        androidResources { enable = true }
    }
    jvm("desktop") {
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
    }
    listOf(iosArm64(), iosSimulatorArm64()).forEach { target ->
        target.binaries.framework {
            baseName = "HouseMoveKit"
            // Linked into the app's own binary: nothing to embed, and one binary for Apple to check.
            isStatic = true
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(project(":shared"))
            implementation("org.jetbrains.compose.runtime:runtime:$composeVersion")
            implementation("org.jetbrains.compose.foundation:foundation:$composeVersion")
            implementation("org.jetbrains.compose.ui:ui:$composeVersion")
            implementation("org.jetbrains.compose.material3:material3:$material3Version")
            // Material 3 1.9.0 asks for ripple 1.9.1; this keeps it at the Compose version (androidx 1.12.1 on Android).
            implementation("org.jetbrains.compose.material:material-ripple:$composeVersion")
            implementation("org.jetbrains.compose.components:components-resources:$composeVersion")
        }
        named("desktopTest").dependencies {
            implementation(kotlin("test"))
            // Skia for this machine, which renders the screens in the tests.
            implementation(compose.desktop.currentOs)
        }
    }
}

compose.resources {
    packageOfResClass = "app.housemove.ui.res"
    generateResClass = always
}

tasks.named<Test>("desktopTest") {
    // The rendered screens go to build/trial (see TrialScreenRenderTest).
    systemProperty("trial.out", layout.buildDirectory.dir("trial").get().asFile.path)
    maxHeapSize = "1536m"
}
