# LibreCVS

CVS support for IntelliJ IDEA 2026.1 and later: browse repositories, check out and update working copies,
commit with an optional tag, annotate files, inspect history, and work with branches and tags. The plugin
talks to CVS servers (`pserver`, `ext`, `ssh`) through Java libraries and never runs a `cvs` executable, so
local repositories (`:local:` or a bare path as CVSROOT) are rejected.

The plugin cannot be installed next to OpenCVS or JetBrains' CVS plugin: they register the same actions.

## Installation

The plugin is published in a custom plugin repository. Add it once; after that the IDE offers updates by itself.

1. Open **Settings → Plugins**, click the gear icon next to the tabs and choose **Manage Plugin Repositories…**.
2. Click **+**, enter `https://devlab.by/plugins/updatePlugins.xml` and click **OK**.
3. On the **Marketplace** tab find **LibreCVS**, click **Install** and restart the IDE.

To install without the repository, download [LibreCVS-1.0.3.zip](https://devlab.by/plugins/LibreCVS-1.0.3.zip)
and choose **Install Plugin from Disk…** in the same gear menu.

## User guide

- [English (PDF)](https://devlab.by/plugins/LibreCVS-guide-en.pdf)
- [Russian (PDF)](https://devlab.by/plugins/LibreCVS-guide-ru.pdf)

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
  vendor "Developer Lab", since-build 261, no until-build). The upstream descriptor itself is not edited.
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
| `upstream-patch: commit and tag with the current week` | `cvs-plugin/src/com/intellij/cvsSupport2/checkinProject/AdditionalOptionsPanel.java` and `.form`, `cvs-plugin/src/com/intellij/cvsSupport2/checkinProject/CvsCheckinEnvironment.java`, `cvs-plugin/src/com/intellij/cvsSupport2/cvshandlers/CommandCvsHandler.java`, `cvs-plugin/src/com/intellij/cvsSupport2/config/CvsConfiguration.java`, `cvs-core/resources/messages/CvsBundle.properties` | the commit options tag the committed files with the current week's tag `Dwwyyyy` and move it on every commit |
| `upstream-patch: create the admin reader per command operation` | `cvs-plugin/src/com/intellij/cvsSupport2/cvsoperations/common/CvsCommandOperation.java` | the class initializer requested IDE services, which IntelliJ IDEA 2026.x reports as an error on the first CVS operation |
| `upstream-patch: report a restore of deleted files without false errors` | `cvs-plugin/src/com/intellij/cvsSupport2/cvsoperations/cvsMessages/CvsMessagesTranslator.java`, `cvs-plugin/src/com/intellij/cvsSupport2/checkinProject/CvsRollbackEnvironment.java` | Rollback of deleted files showed a red balloon and logged the server's protocol pieces as warnings although the files were restored |
| `upstream-patch: read and set the read-only state of received files on disk` | `cvs-plugin/src/com/intellij/cvsSupport2/javacvsImpl/FileReadOnlyHandler.java` | `File.canWrite()` took half of a checkout's time on Windows, and changing the attribute through the VFS failed outside a write action |
| `upstream-patch: commit CVS projects from the Commit tool window` | `cvs-plugin/src/com/intellij/cvsSupport2/CvsVcs2.java` | the IDE offers the Commit tool window only to distributed VCSs; CVS asks for it through `getForcedCommitMode` |
| `upstream-patch: save the Import into CVS settings as a persistent state component` | `cvs-plugin/src/com/intellij/cvsSupport2/config/ImportConfiguration.java` | IntelliJ IDEA 2026.x reported the `JDOMExternalizable` component as deprecated (a plugin error) when the Import into CVS wizard opened |
| `upstream-patch: save the SSH passwords as a persistent state component` | `cvs-plugin/src/com/intellij/cvsSupport2/connections/ssh/SSHPasswordProviderImpl.java` | the same deprecation error on the first SSH connection |
| `upstream-patch: fall back to the IDE's commit mode when the non-modal one cannot be created` | `cvs-plugin/src/com/intellij/cvsSupport2/CvsVcs2.java` | the non-modal commit mode is created through an internal IDE constructor (agreed, `verifier-allowed-internal-api.txt`); if an IDE build changes it, CVS keeps the IDE's own commit mode instead of failing |
| `upstream-patch: run CVS operations outside a read action by default` | `cvs-plugin/src/com/intellij/cvsSupport2/cvsoperations/common/CvsOperation.java` | operations such as `rtag`, `rlog`, Annotate and Tag held the read lock during the whole server exchange, so a slow server froze the IDE (a pending write action blocked the UI thread) |
| `upstream-patch: offer the CVS merge provider and load conflicts in the file's encoding` | `cvs-plugin/src/com/intellij/cvsSupport2/CvsVcs2.java`, `cvs-plugin/src/com/intellij/cvsSupport2/actions/merge/CvsMergeProvider.java`, `cvs-plugin/src/com/intellij/cvsSupport2/actions/merge/CvsConflictsParser.java` | an inverted null check left CVS without a merge provider, so Resolve did nothing; the conflict versions were read as UTF-8 and lost the final line break |

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
