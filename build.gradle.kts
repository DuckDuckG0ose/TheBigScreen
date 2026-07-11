import org.gradle.internal.os.OperatingSystem

plugins {
    id("java")
    id("com.gradleup.shadow") version "8.3.5"
}

group = "club.mineclub"
version = "1.0.0-SNAPSHOT"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
    // ProtocolLib — prefer stable release; snapshot build #697+ required for 1.21.4
    maven("https://repo.dmulloy2.net/repository/public/")
}

// Detect the native classifier at configure time so a single build.gradle.kts
// works on any machine. Build the JAR on each target OS; natives are NOT
// cross-platform. Use a GitHub Actions matrix (windows-latest, ubuntu-latest)
// for CI to produce correct JARs for each environment.
val osClassifier: String = when {
    OperatingSystem.current().isWindows -> "windows-x86_64"
    OperatingSystem.current().isLinux   -> "linux-x86_64"
    OperatingSystem.current().isMacOsX  -> "macosx-x86_64"
    else -> throw GradleException("Unsupported OS for javacv/ffmpeg native build. " +
            "Supported: Windows x86_64, Linux x86_64, macOS x86_64.")
}

val javacvVersion   = "1.5.11"
val ffmpegPlatformVersion = "7.1-1.5.11"  // matches javacv 1.5.11's ffmpeg
val paperVersion    = "1.21.4-R0.1-SNAPSHOT"
// ProtocolLib 5.3.0 is the latest stable build; bump to dev build if needed:
// https://github.com/dmulloy2/ProtocolLib/releases
val protocolLibVersion = "5.3.0"

dependencies {
    // Server API — provided at runtime by Paper
    compileOnly("io.papermc.paper:paper-api:$paperVersion")
    // ProtocolLib — provided at runtime
    compileOnly("com.comphenix.protocol:ProtocolLib:$protocolLibVersion")

    // JavaCV core (no native) + platform-specific FFmpeg natives
    implementation("org.bytedeco:javacv:$javacvVersion") {
        // Exclude transitive ffmpeg-platform — we add it explicitly with classifier
        exclude(group = "org.bytedeco", module = "ffmpeg")
    }
    implementation("org.bytedeco:ffmpeg:$ffmpegPlatformVersion:$osClassifier")

    // Gson for screen persistence (already on Paper classpath, but shade for safety)
    implementation("com.google.code.gson:gson:2.10.1")
}

// Include FFmpeg platform natives from the classifier-specific JAR
// The ffmpeg-platform JAR contains .so/.dll/.dylib files under org/bytedeco/ffmpeg/<os>/
val extractNatives = tasks.register("extractNatives", Copy::class) {
    val ffmpegPlatformJar = configurations.named("runtimeClasspath").get().files
        .first { it.name.startsWith("ffmpeg-$ffmpegPlatformVersion-$osClassifier") }
    from(zipTree(ffmpegPlatformJar)) {
        include("org/bytedeco/ffmpeg/$osClassifier/**")
    }
    into(layout.buildDirectory.dir("extracted-natives"))
}

tasks.shadowJar {
    // Replace the plain JAR so the server can drop-in the fat JAR directly
    archiveClassifier.set("")
    // Exclude signature files that can cause security exceptions when shaded
    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA")
    // Relocate Gson to avoid version conflicts with other plugins
    relocate("com.google.gson", "club.mineclub.watchparty.libs.gson")

    // Include the extracted natives at the correct path
    dependsOn(extractNatives)
    val nativesDir = layout.buildDirectory.dir("extracted-natives/org/bytedeco/ffmpeg/$osClassifier").get().asFile
    from(nativesDir) {
        into("org/bytedeco/ffmpeg/$osClassifier")
    }
}

// Make :build always produce the fat JAR
tasks.build {
    dependsOn(tasks.shadowJar)
}

// Disable the plain (non-fat) JAR task to avoid confusion
tasks.jar {
    enabled = false
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

// Print the detected OS classifier so developers can confirm at configure time
println("[WatchParty] Detected OS classifier: $osClassifier")
