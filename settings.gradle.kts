plugins {
    // Downloads the Java 25 toolchain (Temurin) into ~/.gradle/jdks; no JDK 25 is installed by hand.
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "LibreCVS"
