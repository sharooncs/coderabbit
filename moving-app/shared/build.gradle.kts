import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.plugin.KotlinPlatformType

// The move's data and its phone-to-phone sync (packages app.housemove.data and app.housemove.sync), shared by the
// Android app and, later, the iPhone app (step 0c of design/ios-plan.md). For now the code still uses java.io,
// java.net, javax.crypto and other JDK APIs, so it sits in jvmCommonMain, below commonMain, which the Android target
// and a plain JVM target both build on. The JVM target runs the data and sync tests without Android or Robolectric.
// The iPhone targets have only commonMain (empty) until step 1 moves code there.
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
    // The iPhone (device and Apple-silicon simulator), so that sharedUi can depend on this module there. They build
    // on commonMain alone: nothing in jvmCommonMain (java.*, javax.crypto, Keys.kt, Sealed.kt) reaches the iPhone.
    iosArm64()
    iosSimulatorArm64()

    // commonMain stays empty until code moves there (step 1); jvmCommonMain holds what the Android and JVM targets
    // share, and iosMain what the two iPhone targets will share.
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
        named("jvmCommonMain") {
            dependencies {
                // api: their types are part of what this module offers (StateFlow, the serializers, Qr's BitMatrix).
                api("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.1")
                api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.1")
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
