plugins {
    java
    id("application")
    id("com.gradleup.shadow") version "9.3.0"          // fat JAR for dev / standalone dist
    id("org.openjfx.javafxplugin") version "0.1.0"
    id("org.beryx.runtime") version "1.13.1"            // replaces de.infolektuell.jpackage
}

group = "com.imagetoonsong"
version = "1.0.0"

javafx {
    version = "21"
    modules = listOf("javafx.controls", "javafx.fxml", "javafx.graphics")
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
        vendor.set(JvmVendorSpec.AZUL)                 // Azul Zulu; auto-provisioned via foojay
    }
}

repositories {
    mavenCentral()
}

dependencies {
    val javafxVersion = "21"
    val javacppVersion = "1.5.13"
    val javacvVersion = "1.5.13"
    val tesseractVersion = "5.5.2"
    val opencvVersion = "4.13.0"
    val leptonicaVersion = "1.87.0"
    val jsoupVersion = "1.17.2"

    // ── JavaFX ────────────────────────────────────────────────────────────────
    implementation("org.openjfx:javafx-controls:$javafxVersion")
    implementation("org.openjfx:javafx-fxml:$javafxVersion")

    // ── JavaCPP runtime ───────────────────────────────────────────────────────
    implementation("org.bytedeco:javacpp:$javacppVersion")
    runtimeOnly("org.bytedeco:javacpp:$javacppVersion:macosx-arm64")

    // ── OpenBLAS (required by OpenCV on ARM — was the hang cause) ─────────────
    implementation("org.bytedeco:openblas:0.3.31-$javacppVersion")
    runtimeOnly("org.bytedeco:openblas:0.3.31-$javacppVersion:macosx-arm64")

    // ── Tesseract OCR ─────────────────────────────────────────────────────────
    implementation("org.bytedeco:tesseract:$tesseractVersion-$javacppVersion")
    runtimeOnly("org.bytedeco:tesseract:$tesseractVersion-$javacppVersion:macosx-arm64")

    // ── Leptonica ─────────────────────────────────────────────────────────────
    implementation("org.bytedeco:leptonica:$leptonicaVersion-$javacppVersion")
    runtimeOnly("org.bytedeco:leptonica:$leptonicaVersion-$javacppVersion:macosx-arm64")

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
    runtimeOnly("org.bytedeco:opencv:$opencvVersion-$javacppVersion:macosx-arm64")

    // ── HTML parsing ──────────────────────────────────────────────────────────
    implementation("org.jsoup:jsoup:$jsoupVersion")
}

application {
    applicationName = "ImageToOnSong"
    mainClass = "com.imagetoonsong.MainApp"
}

// ── Shadow JAR ────────────────────────────────────────────────────────────────
// Used for `./gradlew run` (dev) and standalone fat-JAR distribution.
// NOT used by the runtime/jpackage tasks — badass-runtime handles its own
// dependency collection for the .app bundle.
tasks.shadowJar {
    archiveBaseName.set("myapp")
    archiveClassifier.set("")
    archiveVersion.set("")
    isZip64 = true
    // Belt-and-suspenders: drop any non-arm64 natives that sneak in transitively
    exclude("org/bytedeco/*/linux*/**")
    exclude("org/bytedeco/*/windows*/**")
    exclude("org/bytedeco/*/macosx-x86_64/**")
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
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
        "--compress", "2",
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

        // If Gradle toolchain resolution doesn't wire jpackage automatically,
        // uncomment and point this at your Azul Zulu 21 arm64 install:
        // jdkHome = "/Library/Java/JavaVirtualMachines/zulu-21.jdk/Contents/Home"

        jvmArgs = listOf(
            "-Dfile.encoding=UTF-8"
            // NOTE: bytedeco's native cache directory should be set programmatically
            // in MainApp before any bytedeco class is loaded, e.g.:
            //   System.setProperty(
            //       "org.bytedeco.javacpp.cachedir",
            //       System.getProperty("user.home") + "/Library/Caches/ImageToOnSong"
            //   )
            // jpackage cannot reliably expand ~ or $HOME in jvmArgs on macOS.
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