import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.plugin.KotlinPlatformType

// The move's data and its phone-to-phone sync (packages app.housemove.data and app.housemove.sync), shared by the
// Android app and the iPhone app (steps 0c and 1 of design/ios-plan.md; design/ios-step1.md is the checklist).
// Nearly all of it is in commonMain. What it asks of the platform is behind small expect/actual seams (Files.kt,
// Threads.kt, Platform.kt, sync/Net.kt): on Android and the JVM, in jvmCommonMain, each is the java.* call the code
// made before (Android runs what it ran); on the iPhone, in iosMain, the small ones are Apple's, and the files,
// sockets, zips and QR codes come in step 1b. The update-file crypto (Keys.kt, Sealed.kt, Update.kt) and zxing stay
// in jvmCommonMain only. The JVM target runs the data and sync tests without Android or Robolectric.
plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("com.android.kotlin.multiplatform.library")
    id("org.jetbrains.kotlin.plugin.serialization")
}

kotlin {
    android {
        namespace = "app.housemove.shared"
        // The same as the app, which Compose 1.12 needs to compile against.
        compileSdk = 37
        minSdk = 26
        // The same bytecode level as the app (its compileOptions).
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
    }
    jvm {
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
    }
    // The iPhone (device and Apple-silicon simulator). Nothing in jvmCommonMain (java.*, javax.crypto, Keys.kt,
    // Sealed.kt, Update.kt, zxing) reaches it.
    iosArm64()
    iosSimulatorArm64()

    // jvmCommonMain holds what the Android and JVM targets share, and iosMain what the two iPhone targets share.
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

    // expect/actual classes (File, Lock and the other seams in commonMain) are Beta in Kotlin 2.4; this only silences
    // the warning that says so.
    compilerOptions { freeCompilerArgs.add("-Xexpect-actual-classes") }

    sourceSets {
        commonMain.dependencies {
            // api: their types are part of what this module offers (StateFlow, the serializers).
            api("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.1")
            api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.1")
        }
        named("jvmCommonMain") {
            dependencies {
                // QR codes and barcodes on Android and the JVM (Qr.kt); api: Qr.matrix gives zxing's BitMatrix.
                api("com.google.zxing:core:3.5.3")
            }
        }
        jvmTest.dependencies {
            implementation("junit:junit:4.13.2")
        }
    }
}

tasks.named<Test>("jvmTest") {
    useJUnit()
    // As much memory as the app's unit tests had when these tests ran there (the seven-phone simulation needs it).
    maxHeapSize = "1536m"
}
