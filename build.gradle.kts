import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
    id("org.jetbrains.kotlin.jvm") version "2.4.21"
    id("org.jetbrains.intellij.platform") version "2.20.1"
}

group = "by.devlab"
version = "0.1.0"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        // IntelliJ IDEA downloaded by version, so the build does not depend on the installed IDE.
        intellijIdea("2026.2.3")
        // Where AppLocker blocks executables under ~/.gradle, the IDE-running tasks (test, runIde, verifyPlugin)
        // use a JetBrains Runtime from an allowed folder instead of the one bundled with the downloaded IDE.
        providers.gradleProperty("intellijcvs.jbr").orNull?.let { jetbrainsRuntimeLocal(it) }
        // The JUnit 5 framework needs the platform test framework too: the test application loads its
        // TestProjectManager, which test-framework-junit5 does not bring.
        testFramework(TestFrameworkType.Platform)
        testFramework(TestFrameworkType.JUnit5)
        // VcsRootErrorsFinder lives in this product module, which is not on the test compile classpath by itself.
        testBundledModule("intellij.platform.vcs.impl")
    }

    testImplementation(platform("org.junit:junit-bom:5.14.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    // Gradle 9 no longer provides the launcher.
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    // Workaround for IJPL-159134 from the IntelliJ Platform Gradle Plugin FAQ.
    testRuntimeOnly("junit:junit:4.13.2")
}

intellijPlatform {
    pluginConfiguration {
        // Explicit, so that a change of the plugin's defaults cannot add an upper bound.
        ideaVersion {
            sinceBuild = "262"
            untilBuild = provider { null }
        }
    }
    pluginVerification {
        // "IntelliJCVS" contains the restricted word "IntelliJ"; only this rule is muted.
        freeArgs = listOf("-mute", "TemplateWordInPluginName")
        ides {
            current()
        }
    }
}

kotlin {
    jvmToolchain(25)
}

tasks.test {
    useJUnitPlatform()
}
