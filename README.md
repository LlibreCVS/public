# LibreCVS

CVS support for IntelliJ IDEA 2026.2 and later: browse repositories, check out and update working copies,
commit with an optional tag, annotate files, inspect history, and work with branches and tags. The plugin
talks to CVS servers (`pserver`, `ext`, `ssh`) through Java libraries and never runs a `cvs` executable, so
local repositories (`:local:` or a bare path as CVSROOT) are rejected.

The plugin cannot be installed next to OpenCVS or JetBrains' CVS plugin: they register the same actions.

## Where the code comes from

Almost all of the code is [OpenCVS](https://github.com/jieyuexing/intellij-cvs-plugin) release `262.0.8`, a
maintained fork of JetBrains' CVS plugin, kept as a squashed git subtree under `upstream/opencvs/`:

| Part | Origin | License |
|---|---|---|
| `cvs-core`, `cvs-plugin` | OpenCVS / JetBrains CVS plugin | Apache License 2.0 (`upstream/opencvs/LICENSE`) |
| `javacvs-src`, `smartcvs-src` | NetBeans JavaCVS client library, modified | Sun Public License 1.0 (`upstream/opencvs/javacvs-src/sun-public-license.txt`) |
| `lib/trilead-ssh2-build213.jar` | Trilead SSH-2 for Java | BSD-style (`upstream/opencvs/trilead-ssh2-build213/LICENSE.txt`) |

`NOTICE` names them; the build packages `NOTICE` and the three licenses into the plugin jar under `META-INF/`.

This repository adds:

- `build.gradle.kts`: compiles the upstream sources (upstream's own Gradle build is not used) and gives
  upstream's `plugin.xml` this plugin's identity at build time (ID `librecvs`, name "LibreCVS",
  vendor "Home Laboratory", since-build 262, no until-build). The upstream descriptor itself is not edited.
- `src/test/kotlin`: tests of root detection, registration, connections and packaging.

## Upstream edits

Upstream files are changed only where this plugin needs different behavior. Each edit is a commit whose message
starts with `upstream-patch:`, and each edited place is marked with an `LibreCVS upstream-patch` comment:

| Commit subject | File | Why |
|---|---|---|
| `upstream-patch: match tracked directory names in the usual CVS encodings` | `cvs-plugin/src/com/intellij/cvsSupport2/cvsoperations/common/FindAllRootsHelper.java` | Cyrillic directory names stored in UTF-8 or windows-1251 stay part of their parent's root |
| `upstream-patch: accept mappings on any CVS working-copy directory` | `cvs-plugin/src/com/intellij/cvsSupport2/cvsoperations/common/CvsRootChecker.java` | the IDE does not report a mapping of a tracked subdirectory as invalid |
| `upstream-patch: reject local CVS repositories instead of running cvs` | `cvs-plugin/src/com/intellij/cvsSupport2/connections/IDEARootFormatter.java`, `cvs-plugin/src/com/intellij/cvsSupport2/config/ui/CvsRootFieldByFieldConfigurationPanel.java` | a local connection would launch a `cvs` executable |
| `upstream-patch: expose a committed changeset's environment and repository paths` | `cvs-plugin/src/com/intellij/cvsSupport2/changeBrowser/CvsChangeList.java` | Put on Tag tags a changeset's files in the repository (`rtag`) without a working copy |

List them with `git log --oneline --grep "^upstream-patch:"`.

## Updating OpenCVS

1. On a branch, with a clean working tree, pull the new release tag:

   ```
   git subtree pull --prefix=upstream/opencvs https://github.com/jieyuexing/intellij-cvs-plugin.git <tag> --squash
   ```

2. Conflicts can only occur in the files listed under "Upstream edits". Resolve them so that each edit still
   does what the table says; drop an edit if upstream now does the same.
3. Check upstream's `build.gradle.kts` for new source dirs, bundled modules or libraries and mirror them in
   `build.gradle.kts`.
4. Run `./gradlew clean build verifyPlugin` and a `./gradlew runIde` smoke check, then update the version in
   `NOTICE` and this README.

## Building

Build from Git Bash with `./gradlew buildPlugin`; the ZIP lands in `build/distributions/`. Tests:
`./gradlew test`; compatibility: `./gradlew verifyPlugin`. The optional JetBrains Runtime property is described in
`build.gradle.kts`.
