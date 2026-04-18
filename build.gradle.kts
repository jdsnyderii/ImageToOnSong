plugins {
    java
    id("application")
    id("com.gradleup.shadow") version "9.3.0"  // fat JAR (recommended)
    id("org.openjfx.javafxplugin") version "0.1.0"
    id("de.infolektuell.jpackage") version "0.4.1"
}

group = "com.imagetoonsong"
version = "1.0.0"

javafx {
    version = "21"
    modules = listOf("javafx.controls", "javafx.fxml", "javafx.graphics" )
}


java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

repositories {
    mavenCentral()
}

dependencies {
    // JavaFX
    implementation("org.openjfx:javafx-controls:21")
    implementation("org.openjfx:javafx-fxml:21")

    // Tess4J – latest stable (as of early 2026)
    // The Tesseract wrapper and all native platform binaries
    implementation("org.bytedeco:tesseract-platform:5.5.2-1.5.13")
// Tesseract requires Leptonica for image processing
    implementation("org.bytedeco:leptonica-platform:1.87.0-1.5.13")
    implementation("org.bytedeco:flandmark-platform:1.07-1.5.8" )

    implementation("org.bytedeco:tesseract:5.5.2-1.5.13")
    implementation("org.jsoup:jsoup:1.17.2")

    // OpenCV via JavaCV for advanced image preprocessing
    implementation("org.bytedeco:javacv-platform:1.5.13")

    // Optional: better image format support
//    implementation("org.apache.commons:commons-imaging:1.0.0-alpha6")
}


application {
    applicationName = "ImageToOnSong"
    mainClass = "com.imagetoonsong.MainApp"
}

tasks.shadowJar {
    archiveBaseName.set("myapp")
    archiveClassifier.set("")
    archiveVersion.set("")
    isZip64 = true
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
}

// Optional: Make the run task more convenient
tasks.named<JavaExec>("run") {
    // You can add JVM args here if needed later (e.g., for large images or OpenCV)
}