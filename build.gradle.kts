import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
    id("org.jetbrains.kotlin.jvm") version "2.4.21"
    id("org.jetbrains.intellij.platform") version "2.20.1"
}

group = "by.devlab"
version = "0.1.0"

// OpenCVS (git subtree, see README.md) is compiled by this build, as its own build does: the Java sources, the
// resource bundles that sit next to them, and its bundled SSH library. Upstream's build script is not used.
val upstream = "upstream/opencvs"

sourceSets {
    main {
        java.srcDirs(
            "$upstream/cvs-core/src",
            "$upstream/cvs-plugin/src",
            "$upstream/javacvs-src",
            "$upstream/smartcvs-src",
        )
        resources.srcDirs(
            "$upstream/cvs-core/resources",
            "$upstream/cvs-plugin/resources",
            "$upstream/javacvs-src",
            "$upstream/smartcvs-src",
        )
        // The source trees double as resource dirs; only their non-Java files belong in the jar.
        resources.exclude("**/*.java")
    }
}

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    implementation(files("$upstream/lib/trilead-ssh2-build213.jar"))

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
        // Declared as OpenCVS's own build does; vcs.impl also brings VcsRootErrorsFinder to the tests.
        bundledModule("intellij.platform.vcs.impl")
        bundledModule("intellij.platform.vcs.impl.lang")
        bundledLibrary("lib/intellij.libraries.microba.jar")
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
        // The descriptor is OpenCVS's plugin.xml; the build gives it this plugin's identity.
        id = "intellijcvs"
        name = "IntelliJCVS"
        vendor {
            name = "Home Laboratory"
        }
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

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    // Upstream code is compiled as published; its lint warnings are upstream's business.
    options.compilerArgs.add("-Xlint:none")
}

tasks.test {
    useJUnitPlatform()
    // PackagingTest inspects the plugin jar that goes into the distribution.
    val pluginJar = tasks.named<org.jetbrains.intellij.platform.gradle.tasks.ComposedJarTask>("composedJar").flatMap { it.archiveFile }
    inputs.file(pluginJar)
    jvmArgumentProviders.add(CommandLineArgumentProvider { listOf("-Dintellijcvs.jar=${pluginJar.get().asFile}") })
}
