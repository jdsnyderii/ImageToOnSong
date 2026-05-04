import org.gradle.internal.os.OperatingSystem

val buildArch: String = layout.buildDirectory.get().asFile.parentFile.name // e.g., 'chordcharter'

// Logic to determine which runtime suffix to apply.
fun getBytedecoClassifier(actual : Boolean): String {
    // We strictly use the architecture detected by Gradle
    if (OperatingSystem.current().isMacOsX) {
        // 'os.arch' typically returns 'aarch64' on Apple Silicon and 'x86_64' on Intel
        println(System.getProperty("os.arch"))
        if (System.getProperty("os.arch") == "aarch64") {
            return if (actual) "macosx-arm64" else "macosx-x86_64"
        } else if (System.getProperty("os.arch") == "x86_64") {
            return if (actual) "macosx-x86_64" else "macosx-arm64"
        }
    }
    // Fail-safe for CI or non-macOS builds
    return "macosx-" + System.getProperty("os.arch")
}

val currentNativeClassifier = getBytedecoClassifier(true)
val filteredNativeClassifier = getBytedecoClassifier(false)

println("Bytedeco Native Suffix detected: $currentNativeClassifier")

plugins {
    java
    id("application")
    id("com.gradleup.shadow") version "9.3.0"          // fat JAR for dev / standalone dist
    id("org.openjfx.javafxplugin") version "0.1.0"
    id("org.beryx.runtime") version "2.0.1"
}

group = "com.imagetoonsong"
version = "1.0.0"

javafx {
    version = "21"
    modules = listOf("javafx.base", "javafx.controls", "javafx.fxml", "javafx.graphics")
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
        vendor = JvmVendorSpec.matching("BellSoft")
    }
}

repositories {
    mavenCentral()
}

dependencies {
    val javafxVersion = "25"
    val javacppVersion = "1.5.13"
    val javacvVersion = "1.5.13"
    val tesseractVersion = "5.5.2"
    val opencvVersion = "4.13.0"
    val leptonicaVersion = "1.87.0"
    val jsoupVersion = "1.17.2"
    val junitVersion = "5.11.4"
    val metadataVersion = "2.19.0"
    val logbackVersion = "1.5.32"
    val julslf4jVersion = "2.0.17"
    val javadiffVersion = "4.12"
    val commonstextVersion = "1.15.0"

    // ── JavaFX ────────────────────────────────────────────────────────────────
    implementation("org.openjfx:javafx-controls:$javafxVersion")
    implementation("org.openjfx:javafx-fxml:$javafxVersion")

    // The core library for reading Exif, IPTC, XMP, etc.
    implementation("com.drewnoakes:metadata-extractor:$metadataVersion")

    // ── JavaCPP runtime ───────────────────────────────────────────────────────
    implementation("org.bytedeco:javacpp:$javacppVersion")
    runtimeOnly("org.bytedeco:javacpp:$javacppVersion:$currentNativeClassifier")

    // ── OpenBLAS (required by OpenCV on ARM — was the hang cause) ─────────────
    implementation("org.bytedeco:openblas:0.3.31-$javacppVersion")
    runtimeOnly("org.bytedeco:openblas:0.3.31-$javacppVersion:$currentNativeClassifier")

    // ── Tesseract OCR ─────────────────────────────────────────────────────────
    implementation("org.bytedeco:tesseract:$tesseractVersion-$javacppVersion")
    runtimeOnly("org.bytedeco:tesseract:$tesseractVersion-$javacppVersion:$currentNativeClassifier")

    // ── Leptonica ─────────────────────────────────────────────────────────────
    implementation("org.bytedeco:leptonica:$leptonicaVersion-$javacppVersion")
    runtimeOnly("org.bytedeco:leptonica:$leptonicaVersion-$javacppVersion:$currentNativeClassifier")

    implementation("org.apache.commons:commons-text:$commonstextVersion")

    // ── OpenCV via JavaCV (version driven by javacv:1.5.13 → 4.13.0) ─────────
    implementation("org.bytedeco:javacv:$javacvVersion") {
        // Exclude everything we don't use — camera SDKs, ffmpeg, AR toolkits
        exclude(group = "org.bytedeco", module = "ffmpeg")
        exclude(group = "org.bytedeco", module = "flycapture")
        exclude(group = "org.bytedeco", module = "libdc1394")
        exclude(group = "org.bytedeco", module = "libfreenect")
        exclude(group = "org.bytedeco", module = "libfreenect2")
        exclude(group = "org.bytedeco", module = "librealsense")
        exclude(group = "org.bytedeco", module = "librealsense2")
        exclude(group = "org.bytedeco", module = "videoinput")
        exclude(group = "org.bytedeco", module = "artoolkitplus")
    }
    implementation("org.bytedeco:opencv:$opencvVersion-$javacppVersion")
    runtimeOnly("org.bytedeco:opencv:$opencvVersion-$javacppVersion:$currentNativeClassifier")

    // Logback pulls in slf4j-api transitively — one dependency does it all
    implementation("ch.qos.logback:logback-classic:$logbackVersion")
    implementation("org.slf4j:jul-to-slf4j:$julslf4jVersion")

    // ── HTML parsing ──────────────────────────────────────────────────────────
    implementation("org.jsoup:jsoup:$jsoupVersion")
    // JUnit 5 — aggregator pulls in api, params, and engine
    testImplementation("org.junit.jupiter:junit-jupiter:$junitVersion")
    testImplementation("io.github.java-diff-utils:java-diff-utils:$javadiffVersion")
    // Required by Gradle to run JUnit Platform tests
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()  // required — not enabled by default
    jvmArgs(
        "--enable-native-access=javafx.graphics",
        "--enable-native-access=ALL-UNNAMED"
    )
}

application {
    applicationName = "ImageToOnSong"
    mainClass = "com.imagetoonsong.Main"
    applicationDefaultJvmArgs = listOf("-Xdock:name=ImageToOnSong")
}


tasks.jpackage {
    dependsOn(tasks.shadowJar)
}

// ── Shadow JAR ────────────────────────────────────────────────────────────────
// Used for `./gradlew run` (dev) and standalone fat-JAR distribution.
// NOT used by the runtime/jpackage tasks — badass-runtime handles its own
// dependency collection for the .app bundle.
tasks.shadowJar {
    manifest {
        attributes["Main-Class"] = "com.imagetoonsong.Main"
    }
    // Optional: Ensure the archive name is exactly what jpackage expects
    archiveFileName.set("ImageToOnSong-" + project.version.toString() + "-all.jar")
    archiveBaseName.set("ImageToOnSong-" + project.version.toString() + "-all")
    archiveClassifier.set("")
    archiveVersion.set("")
    isZip64 = true
    // Belt-and-suspenders: drop any non-arm64 natives that sneak in transitively
    exclude("org/bytedeco/*/linux*/**")
    exclude("org/bytedeco/*/windows*/**")
    exclude("org/bytedeco/*/$filteredNativeClassifier/**")
}

tasks.register<Exec>("signApp") {
    description = "Self Sign the Application"
    group = "distribution"

    // Set the working directory (optional)
    workingDir = layout.buildDirectory.get().asFile

    // Define the command and arguments
    val appPath = layout.buildDirectory.dir("jpackage/ImageToOnSong.app").get().asFile.absolutePath

    commandLine("codesign", "--force", "--deep", "--sign", "-", appPath)

    // Ensure it only runs after jpackage is done
    mustRunAfter("jpackage")
}

// 4. Trigger it automatically after packaging
tasks.named("jpackage") {
    finalizedBy("signApp")
}
// Ensure it runs after the package task
tasks.named("jpackage") {
    finalizedBy("signApp")
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
}

tasks.withType<JavaExec> {
    jvmArgs(
        "--enable-native-access=javafx.graphics",
        "--enable-native-access=ALL-UNNAMED"
    )
}



// ── Runtime image + DMG installer (Phase 1: macOS arm64) ─────────────────────
//
//   ./gradlew runtime      → builds the .app image in build/image/
//   ./gradlew jpackage     → builds the .dmg installer in build/jpackage/
//
//   Phase 2 (universal): swap runtimeOnly classifiers to include macosx-x86_64,
//   run `lipo -create` on each .dylib pair, and point jdkHome to a Zulu universal
//   JDK (available from azul.com → "macOS — Universal").
// ─────────────────────────────────────────────────────────────────────────────
runtime {
    options.set(listOf(
        "--strip-debug",
        "--compress", "zip-9",
        "--no-header-files",
        "--no-man-pages"
    ))

    // Minimum module set for JavaFX + bytedeco + jsoup.
    // After your first successful build, run `./gradlew suggestModules` to verify
    // nothing is missing from your actual dependency graph.
    modules.set(listOf(
        "java.desktop",     // AWT/Swing compatibility layer (JavaFX needs this)
        "java.logging",
        "java.management",  // JMX — bytedeco uses it internally
        "java.naming",      // JNDI — pulled in transitively
        "java.net.http",
        "java.sql",         // Several bytedeco helpers reference JDBC types
        "java.xml",
        "jdk.unsupported",  // sun.misc.Unsafe — required by bytedeco/JavaCPP
        "javafx.controls",
        "javafx.fxml",
        "javafx.graphics",
        "javafx.base"
    ))


    jpackage {
        imageName       = "ImageToOnSong"
        installerName   = "ImageToOnSong"
        installerType   = "dmg"
        appVersion      = project.version.toString()

        mainJar = "ImageToOnSong-$appVersion-all.jar"
        mainClass = "com.imagetoonsong.Main" // Ensure this matches your actual package path

        jvmArgs = listOf(
            "-Dfile.encoding=UTF-8",
            "--enable-native-access=javafx.graphics",
            "--enable-native-access=ALL-UNNAMED"
        )

        // Place your .icns icon + any Info.plist overrides here.
        // Minimum: src/main/packaging/ImageToOnSong.icns
        resourceDir = file("src/main/packaging")

        // macOS-specific options (uncomment when ready to sign/notarize):
        // installerOptions = listOf(
        //     "--mac-package-identifier", "com.imagetoonsong",
        //     "--mac-signing-key-user-name", "Developer ID Application: Your Name (TEAMID)"
        // )
    }
}