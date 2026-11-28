import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.plugin.KotlinPlatformType

// The screens shared by the Android app and the iPhone app (Compose Multiplatform), design/ios-plan.md steps 0e and 2.
// It holds the shared look (theme, Outfit fonts, the app's Material icons as source), every screen of the app (ui/,
// ui/screens; what they need of the phone is ui/Platform.kt, which the app implements for Android) and the iPhone's own
// screens (ui/iphone: join a move from an Android phone, get fresh data, leave it, and IphonePlatform).
//   android    the Android library the app uses; the same androidx Compose as the app's BOM
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
val navigationVersion = "2.10.0"
val lifecycleVersion = "2.11.0"
val coilVersion = "3.6.3"

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

    // jvmCommonMain holds what Android and the desktop JVM share (java.time and java.io: the calls the screens made on
    // Android before they were shared), and iosMain what the two iPhone targets share; as in the shared module.
    @OptIn(ExperimentalKotlinGradlePluginApi::class)
    applyDefaultHierarchyTemplate {
        common {
            group("jvmCommon") {
                withJvm()
                withCompilations { it.target.platformType == KotlinPlatformType.androidJvm }
            }
            group("ios") {
                withIos()
            }
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
            // BackHandler: on the iPhone, the swipe from the left edge goes back, as the Back button does.
            implementation("org.jetbrains.compose.ui:ui-backhandler:$composeVersion")
            // The screens' navigation and lifecycle: JetBrains' builds of androidx Navigation 2.10.0 and Lifecycle 2.11.0,
            // the versions Compose Multiplatform 1.12.2 is released with (its notes name Navigation 2.10.0-rc01, based on
            // Jetpack Navigation 2.10.0; this is that release, built against Compose 1.11). On Android they are androidx's
            // own artifacts, so the app's NavHostController is the one it always had.
            api("org.jetbrains.androidx.navigation:navigation-compose:$navigationVersion")
            api("org.jetbrains.androidx.lifecycle:lifecycle-runtime-compose:$lifecycleVersion")
            // Photos from files on both phones (Coil 3, built against Compose Multiplatform 1.12.0). No network part: it
            // only reads the move's own photo files.
            api("io.coil-kt.coil3:coil-compose:$coilVersion")
        }
        named("desktopTest").dependencies {
            implementation(kotlin("test"))
            // Skia for this machine, which renders the screens in the tests.
            implementation(compose.desktop.currentOs)
            // A main dispatcher for the screens' coroutines that run on it (Dispatchers.Main), as the phones have.
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
        }
    }
}

compose.resources {
    packageOfResClass = "app.housemove.ui.res"
    generateResClass = always
}

// The same as the app had for its screens: the Material and layout APIs they use, and how Compose sees the shared
// module's data classes (see the file).
kotlin {
    compilerOptions {
        optIn.addAll(
            "androidx.compose.material3.ExperimentalMaterial3Api",
            "androidx.compose.foundation.layout.ExperimentalLayoutApi",
            "androidx.compose.foundation.ExperimentalFoundationApi",
        )
    }
}

composeCompiler {
    stabilityConfigurationFiles.add(rootProject.layout.projectDirectory.file("compose-stability.conf"))
}

tasks.named<Test>("desktopTest") {
    // A failure in CI says which test and why in the job log itself.
    testLogging {
        events(org.gradle.api.tasks.testing.logging.TestLogEvent.FAILED)
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
    // The rendered screens go to build/screens (see ScreensRenderTest).
    systemProperty("screens.out", layout.buildDirectory.dir("screens").get().asFile.path)
    maxHeapSize = "1536m"
}
