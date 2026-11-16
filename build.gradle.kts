import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
    id("org.jetbrains.kotlin.jvm") version "2.4.21"
    id("org.jetbrains.intellij.platform") version "2.20.1"
}

group = "by.devlab"
version = "1.0.2"

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
        providers.gradleProperty("librecvs.jbr").orNull?.let { jetbrainsRuntimeLocal(it) }
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
        id = "librecvs"
        name = "LibreCVS"
        vendor {
            name = "Developer Lab"
        }
        description = """
            <p><b>LibreCVS is truly committed to the Open Source movement and recognizes no sanctions.</b></p>
            <p>Plugin homepage: <a href="https://devlab.by">https://devlab.by</a></p>
            <p>LibreCVS takes part of the code of OpenCVS and reworks it to make working with branches in CVS simpler.
            It talks to CVS servers through Java libraries and never runs a <code>cvs</code> executable.</p>
            <p>Differences from OpenCVS:</p>
            <table border="1" cellpadding="4">
              <tr><th>Area</th><th>OpenCVS</th><th>LibreCVS</th></tr>
              <tr><td>IDE versions</td><td>2023.2 – 2026.2</td><td>2026.1 and later, no upper bound</td></tr>
              <tr><td>Branch popup</td><td>—</td><td>One popup with branch actions, the branches common to all CVS roots and a per-root list; search; the current branch is marked</td></tr>
              <tr><td>Switching branches</td><td>Per working copy</td><td>Switch to a branch or back to the trunk over all CVS roots at once, with a confirmation for uncommitted changes</td></tr>
              <tr><td>Creating a branch</td><td>Per working copy</td><td>Over all CVS roots at once; an existing branch name is decided by the user</td></tr>
              <tr><td>Branch indicator</td><td>—</td><td>Current branch in the main toolbar and the status bar, mixed working copies marked; the commit window names the branch</td></tr>
              <tr><td>Merging</td><td>Manual merge of a branch</td><td>Merge into Current over the roots with merge-point tags; an unfinished merge is shown and can be completed or forgotten</td></tr>
              <tr><td>Tagging a committed changeset</td><td>—</td><td>Put on Tag for a commit in the repository history (tags its files in the repository, no working copy needed)</td></tr>
              <tr><td>Weekly tag</td><td>—</td><td>Commit and tag with the current week: tag <code>Dwwyyyy</code> (ISO 8601 week and year), moved on every commit of the week</td></tr>
              <tr><td>Local repositories (<code>:local:</code>)</td><td>Runs a <code>cvs</code> executable</td><td>Rejected: no external programs are ever run</td></tr>
              <tr><td>Cyrillic directory names</td><td>One encoding</td><td>Recognized in UTF-8 and in the encoding set in the CVS settings</td></tr>
              <tr><td>Directory mappings</td><td>Valid only on a working-copy root</td><td>Valid on any directory of a working copy</td></tr>
            </table>
        """.trimIndent()
        changeNotes = """
            <ul>
              <li>CVS operations from the imported plugin: connections, status, update, commit with tag, annotate,
                  history, branches, tags and checkout.</li>
              <li>Working-copy roots follow the CVS entries; Cyrillic directory names match in UTF-8 and in the
                  encoding set in the CVS settings.</li>
              <li>Local CVS repositories are rejected instead of running a <code>cvs</code> executable.</li>
              <li>Commit and tag with the current week: the committed files get the tag <code>Dwwyyyy</code>
                  (ISO 8601 week and year), moved on every commit of the week.</li>
            </ul>
        """.trimIndent()
        // Explicit, so that a change of the plugin's defaults cannot add an upper bound.
        ideaVersion {
            sinceBuild = "261"
            untilBuild = provider { null }
        }
    }
    pluginVerification {
        // Internal API usages are checked against verifier-allowed-internal-api.txt after the run (task verifyPlugin
        // below) instead of failing on any of them; the other default failure levels stay.
        failureLevel = listOf(
            org.jetbrains.intellij.platform.gradle.tasks.VerifyPluginTask.FailureLevel.COMPATIBILITY_PROBLEMS,
            org.jetbrains.intellij.platform.gradle.tasks.VerifyPluginTask.FailureLevel.OVERRIDE_ONLY_API_USAGES,
        )
        ides {
            current()
            // The earliest supported line (since-build 261).
            create("IU", "2026.1.5")
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
val descriptorAdditions = layout.projectDirectory.file("src/main/resources/META-INF/librecvs-additions.xml")

// Fails on any internal IDE API usage that verifier-allowed-internal-api.txt does not list (commit-mode-internal-api).
// The verifier writes one internal-api-usages.txt per checked IDE under the reports directory.
tasks.named<org.jetbrains.intellij.platform.gradle.tasks.VerifyPluginTask>("verifyPlugin") {
    val reports = verificationReportsDirectory
    val allowedFile = layout.projectDirectory.file("verifier-allowed-internal-api.txt")
    inputs.file(allowedFile)
    doLast {
        val allowed = allowedFile.asFile.readLines().map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }.map(::Regex)
        val usages = reports.get().asFile.walk().filter { it.name == "internal-api-usages.txt" }
            .flatMap { file -> file.readLines().filter { it.isNotBlank() } }.toList()
        val unexpected = usages.filter { usage -> allowed.none { it.containsMatchIn(usage) } }
        if (unexpected.isNotEmpty()) {
            throw GradleException(
                "Internal IDE API usages not listed in verifier-allowed-internal-api.txt:\n" + unexpected.joinToString("\n")
            )
        }
        logger.lifecycle("Internal IDE API usages: ${usages.size}, all listed in verifier-allowed-internal-api.txt")
    }
}

// The vendor block can set the vendor's name but cannot drop the email and url of OpenCVS's vendor element, so
// they are removed from the patched descriptor, which also gets the plugin homepage (the url attribute of the root
// element, which the plugin configuration cannot set); the upstream file stays untouched (design decision 8).
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
            .replaceFirst(Regex("""<idea-plugin\b[^>]*>"""), """<idea-plugin url="https://devlab.by">""")
        val end = patched.lastIndexOf("</idea-plugin>")
        if (end < 0) throw GradleException("no </idea-plugin> in $descriptor")
        descriptor.writeText(patched.substring(0, end) + additions.trim('\n') + "\n" + patched.substring(end))
    }
}

tasks.processResources {
    exclude("META-INF/librecvs-additions.xml")
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
    jvmArgumentProviders.add(CommandLineArgumentProvider { listOf("-Dlibrecvs.jar=${pluginJar.get().asFile}") })
}
