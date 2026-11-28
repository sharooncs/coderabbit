import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.plugin.KotlinPlatformType
import java.security.MessageDigest

// The move's data and its phone-to-phone sync (packages app.housemove.data and app.housemove.sync), shared by the
// Android app and the iPhone app (steps 0c and 1 of design/ios-plan.md; design/ios-step1.md is the checklist).
// Nearly all of it is in commonMain. What it asks of the platform is behind small expect/actual seams (Files.kt,
// Threads.kt, Platform.kt, sync/Net.kt): on Android and the JVM, in jvmCommonMain, each is the java.* call the code
// made before (Android runs what it ran); on the iPhone, in iosMain, they are Apple's and POSIX calls with the same
// behaviour (files, sockets, threads), and the zips and QR codes are common Kotlin (Zip.kt, QrEncoder.kt) that writes
// what java.util.zip and zxing write. The update files (Sealed.kt, Update.kt) are common too, on a small crypto interface
// (Crypto.kt): the JCA on Android and the JVM (Crypto.jvm.kt), CryptoKit in Swift on the iPhone, registered at start-up
// (Crypto.ios.kt; design/encryption.md). zxing stays in jvmCommonMain only. The JVM target runs the data and sync tests
// without Android or Robolectric.
plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("com.android.kotlin.multiplatform.library")
    id("org.jetbrains.kotlin.plugin.serialization")
}

/**
 * Fixture files as a Kotlin object ([objectName] in [packageName]): the golden fixtures (src/jvmTest/resources/golden, see
 * its README) as GoldenFiles for the common tests, since on the iPhone simulator there are no Java resources to read them
 * from; the sealing known-answer file as SealingFiles; and, for the iPhone app's crypto check, golden/update.json and that
 * file as CryptoCheckFiles. Each file's text goes in with every character that is not plain ASCII as a \u escape, beside
 * the SHA-256 of the file's bytes, which the tests check, so what they read is the file exactly. The files are only read:
 * nothing here records or changes them.
 */
abstract class GoldenSources : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val fixtures: ConfigurableFileCollection

    @get:Input
    abstract val objectName: Property<String>

    @get:Input
    abstract val packageName: Property<String>

    @get:Input
    abstract val about: Property<String>

    @get:OutputDirectory
    abstract val output: DirectoryProperty

    private fun literal(s: String): String = buildString {
        append('"')
        for (c in s) {
            if (c.code in 0x20..0x7e && c != '"' && c != '\\' && c != '$') append(c)
            else append("\\u").append(c.code.toString(16).padStart(4, '0'))
        }
        append('"')
    }

    @TaskAction
    fun write() {
        val files = fixtures.files.filter { it.name.endsWith(".json") }.sortedBy { it.name }
        val out = StringBuilder()
        out.append("// Made by the Gradle task :shared:${name} from ${about.get()}. Not to be edited.\n")
        out.append("package ${packageName.get()}\n\n")
        out.append("internal object ${objectName.get()} {\n")
        out.append("    /** The text of each fixture, by file name. */\n")
        out.append("    val text: Map<String, String> by lazy {\n        mapOf(\n")
        for ((i, f) in files.withIndex()) out.append("            ${literal(f.name)} to file$i(),\n")
        out.append("        )\n    }\n\n")
        out.append("    /** The SHA-256 of each fixture's bytes, as lower-case hex. */\n")
        out.append("    val sha256: Map<String, String> = mapOf(\n")
        for (f in files) {
            val sha = MessageDigest.getInstance("SHA-256").digest(f.readBytes())
                .joinToString("") { ((it.toInt() and 0xff) + 0x100).toString(16).substring(1) }
            out.append("        ${literal(f.name)} to ${literal(sha)},\n")
        }
        out.append("    )\n")
        for ((i, f) in files.withIndex()) {
            val text = f.readBytes().decodeToString(throwOnInvalidSequence = true)
            out.append("\n    private fun file$i(): String = listOf(\n")
            // Pieces of at most 4000 characters, so that no constant is too long for a JVM class file.
            for (piece in text.chunked(4000)) out.append("        ${literal(piece)},\n")
            out.append("    ).joinToString(\"\")\n")
        }
        out.append("}\n")
        val root = output.get().asFile
        root.deleteRecursively()
        val dir = root.resolve(packageName.get().replace('.', '/'))
        dir.mkdirs()
        dir.resolve("${objectName.get()}.kt").writeText(out.toString())
    }
}

val goldenSources = tasks.register<GoldenSources>("goldenSources") {
    fixtures.from(layout.projectDirectory.dir("src/jvmTest/resources/golden").asFileTree.matching { include("*.json") })
    objectName.set("GoldenFiles")
    packageName.set("app.housemove")
    about.set("src/jvmTest/resources/golden")
    output.set(layout.buildDirectory.dir("generated/golden/kotlin"))
}

// The update file sealed with fixed keys and random bytes (src/jvmTest/resources/sealing, SealingFixtureTest), for the
// common tests: the JVM, the reference crypto on the simulator and CryptoKit in the app must all write it byte for byte.
val sealingSources = tasks.register<GoldenSources>("sealingSources") {
    fixtures.from(layout.projectDirectory.dir("src/jvmTest/resources/sealing").asFileTree.matching { include("*.json") })
    objectName.set("SealingFiles")
    packageName.set("app.housemove")
    about.set("src/jvmTest/resources/sealing")
    output.set(layout.buildDirectory.dir("generated/sealing/kotlin"))
}

// The encrypted sync's handshakes and frames with fixed keys (src/jvmTest/resources/noise, NoiseFixtureTest), for the
// common tests: the JVM, the reference crypto on the simulator and CryptoKit in the app must all make them byte for byte.
val noiseSources = tasks.register<GoldenSources>("noiseSources") {
    fixtures.from(layout.projectDirectory.dir("src/jvmTest/resources/noise").asFileTree.matching { include("*.json") })
    objectName.set("NoiseFiles")
    packageName.set("app.housemove")
    about.set("src/jvmTest/resources/noise")
    output.set(layout.buildDirectory.dir("generated/noise/kotlin"))
}

// HPKE (RFC 9180) as the mailbox and update files of format 2 seal (src/jvmTest/resources/hpke): the CFRG's test vectors
// for HouseMove's suite, and HouseMove's own sealed messages with fixed keys (HpkeFixtureTest), for the common tests: the
// JVM, the reference crypto on the simulator and CryptoKit in the app must all make them byte for byte.
val hpkeSources = tasks.register<GoldenSources>("hpkeSources") {
    fixtures.from(layout.projectDirectory.dir("src/jvmTest/resources/hpke").asFileTree.matching { include("*.json") })
    objectName.set("HpkeFiles")
    packageName.set("app.housemove")
    about.set("src/jvmTest/resources/hpke")
    output.set(layout.buildDirectory.dir("generated/hpke/kotlin"))
}

// What the iPhone app's crypto check opens and seals (CryptoCheck.kt, iosMain): Android's update file from the golden
// fixtures, the sealing known-answer file, the encrypted sync's handshakes, and HPKE's test vectors and HouseMove's
// sealed messages. Only the app run with -HMCryptoCheck YES (the UI tests) reads them.
val cryptoCheckSources = tasks.register<GoldenSources>("cryptoCheckSources") {
    fixtures.from(
        layout.projectDirectory.file("src/jvmTest/resources/golden/update.json"),
        layout.projectDirectory.file("src/jvmTest/resources/sealing/update-sealed.json"),
        layout.projectDirectory.file("src/jvmTest/resources/noise/handshakes.json"),
        layout.projectDirectory.dir("src/jvmTest/resources/hpke").asFileTree.matching { include("*.json") },
    )
    objectName.set("CryptoCheckFiles")
    packageName.set("app.housemove.data")
    about.set("golden/update.json, sealing/update-sealed.json, noise/handshakes.json and hpke/*.json")
    output.set(layout.buildDirectory.dir("generated/cryptoCheck/kotlin"))
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
    // The iPhone (device and Apple-silicon simulator). Nothing in jvmCommonMain (java.*, javax.crypto, the JCA in
    // Crypto.jvm.kt, zxing) reaches it; its crypto is the app's CryptoKit.
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
        named("iosMain") {
            // What the app's crypto check opens and seals (cryptoCheckSources, above).
            kotlin.srcDir(cryptoCheckSources)
        }
        commonTest {
            // The golden fixtures as Kotlin (goldenSources, above), for the tests that also run on the iPhone simulator.
            kotlin.srcDir(goldenSources)
            kotlin.srcDir(sealingSources)
            kotlin.srcDir(noiseSources)
            kotlin.srcDir(hpkeSources)
            dependencies {
                implementation(kotlin("test"))
            }
        }
        jvmTest.dependencies {
            implementation("junit:junit:4.13.2")
        }
    }
}

// The simulator the common tests run on: HM_SIMULATOR names one (CI gives the one tools/ios.sh sim uses); without it
// the Kotlin plugin picks one of those Xcode has.
tasks.withType<org.jetbrains.kotlin.gradle.targets.native.tasks.KotlinNativeSimulatorTest>().configureEach {
    providers.environmentVariable("HM_SIMULATOR").orNull?.takeIf { it.isNotBlank() }?.let { device.set(it) }
}

tasks.named<Test>("jvmTest") {
    useJUnit()
    // As much memory as the app's unit tests had when these tests ran there (the seven-phone simulation needs it).
    maxHeapSize = "1536m"
    // A failure in CI says which test and why in the job log itself (reports there cannot be fetched).
    testLogging {
        events(org.gradle.api.tasks.testing.logging.TestLogEvent.FAILED)
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
