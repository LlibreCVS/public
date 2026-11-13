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
        description = """
            <p>CVS support for IntelliJ IDEA: browse repositories, check out and update working copies, commit
            changes with an optional tag, annotate files, inspect history, and work with branches and tags.
            The plugin talks to CVS servers through Java libraries and never runs a <code>cvs</code> executable;
            local repositories (<code>:local:</code>) are not supported.</p>
            <p>Built on a community fork of JetBrains' CVS plugin (Apache License 2.0); the bundled NOTICE names
            the sources and their licenses.</p>
        """.trimIndent()
        changeNotes = """
            <ul>
              <li>CVS operations from the imported plugin: connections, status, update, commit with tag, annotate,
                  history, branches, tags and checkout.</li>
              <li>Working-copy roots follow the CVS entries; Cyrillic directory names match in UTF-8 and in the
                  encoding set in the CVS settings.</li>
              <li>Local CVS repositories are rejected instead of running a <code>cvs</code> executable.</li>
            </ul>
        """.trimIndent()
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
    compilerOptions {
        // Without compatibility bridges a Kotlin class implementing a platform interface does not override and call
        // the interface's deprecated default methods (StatusBarWidget.getPresentation(PlatformType)).
        jvmDefault.set(org.jetbrains.kotlin.gradle.dsl.JvmDefaultMode.NO_COMPATIBILITY)
    }
}

// The plugin's own descriptor entries (actions, extensions) live in a fragment that is merged into OpenCVS's
// descriptor here instead of being packaged on its own (tag-committed-changeset design decision 17).
val descriptorAdditions = layout.projectDirectory.file("src/main/resources/META-INF/intellijcvs-additions.xml")

// The vendor block can set the vendor's name but cannot drop the email and url of OpenCVS's vendor element, so
// they are removed from the patched descriptor; the upstream file stays untouched (design decision 8).
tasks.named<org.jetbrains.intellij.platform.gradle.tasks.PatchPluginXmlTask>("patchPluginXml") {
    inputs.file(descriptorAdditions)
    doLast {
        val descriptor = outputFile.get().asFile
        val additions = descriptorAdditions.asFile.readText()
            .replace(Regex("""<!--.*?-->""", RegexOption.DOT_MATCHES_ALL), "")
            .let { Regex("""<idea-plugin>(.*)</idea-plugin>""", RegexOption.DOT_MATCHES_ALL).find(it) }
            ?.groupValues?.get(1)
            ?: throw GradleException("no <idea-plugin> element in $descriptorAdditions")
        val patched = descriptor.readText().replace(Regex("""<vendor\b[^>]*>"""), "<vendor>")
        val end = patched.lastIndexOf("</idea-plugin>")
        if (end < 0) throw GradleException("no </idea-plugin> in $descriptor")
        descriptor.writeText(patched.substring(0, end) + additions.trim('\n') + "\n" + patched.substring(end))
    }
}

tasks.processResources {
    exclude("META-INF/intellijcvs-additions.xml")
}

// Apache 2.0 section 4 and the Sun Public License attach to the distributed binary: the notice and every
// license travel in the jar (design decision 16).
tasks.processResources {
    into("META-INF") {
        from("NOTICE")
        from("$upstream/LICENSE")
        from("$upstream/javacvs-src/sun-public-license.txt")
        from("$upstream/trilead-ssh2-build213/LICENSE.txt") { rename { "trilead-ssh2-LICENSE.txt" } }
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    // Upstream code is compiled as published; its lint warnings are upstream's business.
    options.compilerArgs.add("-Xlint:none")
}

tasks.test {
    useJUnitPlatform()
    // Upstream reads $userdir/.cvspass whenever it builds pserver settings, also in background status updates;
    // the tests must never read the real one (design decision 19).
    val testHome = layout.buildDirectory.dir("test-home").get().asFile
    doFirst { testHome.mkdirs() }
    systemProperty("user.home", testHome.absolutePath)
    // PackagingTest inspects the plugin jar that goes into the distribution.
    val pluginJar = tasks.named<org.jetbrains.intellij.platform.gradle.tasks.ComposedJarTask>("composedJar").flatMap { it.archiveFile }
    inputs.file(pluginJar)
    jvmArgumentProviders.add(CommandLineArgumentProvider { listOf("-Dintellijcvs.jar=${pluginJar.get().asFile}") })
}
