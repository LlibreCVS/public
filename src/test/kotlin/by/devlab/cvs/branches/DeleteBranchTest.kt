package by.devlab.cvs.branches

import by.devlab.cvs.CvsTestSession
import by.devlab.cvs.TestData
import by.devlab.cvs.branches.BranchFixtures.refreshed
import by.devlab.vcs.FakeWorkingCopy
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.testFramework.junit5.fixture.projectFixture
import com.intellij.testFramework.junit5.fixture.tempPathFixture
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import java.nio.file.Files
import java.nio.file.Path

/**
 * Capability cvs-branch-delete (design decisions 4-15): Delete Branch… by [BranchDeleter] against the throwaway
 * server, the questions answered by [TestPrompts], the repository read with the fixture's own `cvs rlog -h`.
 */
@TestApplication
class DeleteBranchTest {

    companion object {
        @JvmField
        @RegisterExtension
        val session = CvsTestSession()

        const val ONE = "one.txt"
        const val TWO = "dir/two.txt"
        const val THREE = "newdir/three.txt"
        const val CYR_ONE = "один.txt"
        const val CYR_TWO = "Папка/два.txt"
    }

    private val project = projectFixture(tempPathFixture(), openAfterCreation = true)
    private val prompts = TestPrompts()

    // 2.1 Skipped roots

    @Test
    @DisplayName("Root on the branch is skipped")
    fun rootOnTheBranchIsSkipped() {
        val (a, b) = roots("onbranch", listOf("BR_FIX"))
        session.updateWith(project.get(), a, CvsTestSession.UpdateOptions(branch = "BR_FIX"))
        refreshed(project.get())

        val report = deleter().delete("BR_FIX", listOf(a, b))

        println(report?.text())
        assertNull(tag(b, ONE, "BR_FIX"))
        assertNotNull(tag(a, ONE, "BR_FIX"))
        assertEquals(listOf(name(b)), report!!.switched)
        assertEquals(listOf(name(a) to "on BR_FIX"), report.skipped)
    }

    @Test
    @DisplayName("Root with files on another branch is skipped")
    fun rootWithFilesOnAnotherBranchIsSkipped() {
        val (a, _) = roots("stray", listOf("BR_FIX"))
        FakeWorkingCopy.fileEntry(a, "stray.txt", "TBR_FIX")

        val report = deleter().delete("BR_FIX", listOf(a))

        println(report?.text())
        assertEquals(emptyList<String>(), prompts.asked)
        assertNotNull(tag(a, ONE, "BR_FIX"))
        assertEquals(listOf(name(a) to "has files on another branch"), report!!.skipped)
        assertEquals(emptyList<String>(), report.switched)
    }

    @Test
    @DisplayName("Root with an unfinished merge of the branch is skipped")
    fun rootWithAnUnfinishedMergeOfTheBranchIsSkipped() {
        val (a, _) = roots("unfinished", listOf("BR_FIX"))
        MergeService.getInstance(project.get()).record(
            a, MergeService.UnfinishedMerge("BR_FIX", MergeService.TRUNK, mapOf(ONE to "1.1"), emptyMap()),
        )

        val report = deleter().delete("BR_FIX", listOf(a))

        println(report?.text())
        assertEquals(emptyList<String>(), prompts.asked)
        assertNotNull(tag(a, ONE, "BR_FIX"))
        assertEquals(listOf(name(a) to "unfinished merge of BR_FIX into TRUNK"), report!!.skipped)
    }

    // Decision 15: the other checkout of the module is on the branch, so the module must keep it.
    @Test
    @DisplayName("Root sharing a module with a protected root is skipped")
    fun rootSharingAModuleWithAProtectedRootIsSkipped() {
        val (a, b) = roots("shared", listOf("BR_FIX"))
        val copy = copyOf(a)
        session.updateWith(project.get(), copy, CvsTestSession.UpdateOptions(branch = "BR_FIX"))
        session.mapToCvs(project.get(), a, b, copy)
        refreshed(project.get())
        BranchListService.getInstance(project.get()).set(copy, listOf("BR_FIX"), System.currentTimeMillis())

        val report = deleter().delete("BR_FIX", listOf(a))

        println(report?.text())
        assertEquals(emptyList<String>(), prompts.asked)
        assertNotNull(tag(a, ONE, "BR_FIX"))
        assertEquals(listOf(name(a) to "shares its module with ${name(copy)}, which is on BR_FIX"), report!!.skipped)
    }

    // 2.2 Analysis and the two questions

    @Test
    @DisplayName("Confirmation names unmerged files and tags")
    fun confirmationNamesUnmergedFilesAndTags() {
        val (a, b) = roots("confirm", listOf("BR_FIX"))
        addOnTrunk(a, "outside.txt")
        val bob = commit(a, "BR_FIX", ONE, "one\nfix\n")
        commit(a, "BR_FIX", TWO, "two\nfix\n", bob)
        mergedThroughPlugin(a)
        commit(a, "BR_FIX", TWO, "two\nfix again\n", bob)
        remap(a, b)

        deleter().delete("BR_FIX", listOf(a))

        println(prompts.asked.joinToString("\n"))
        assertEquals(2, prompts.asked.size, "${prompts.asked}")
        assertTrue(
            prompts.asked[0].startsWith("delete BR_FIX: unmerged {${name(a)}=1}, skipped [], failed [], " +
                "tags [BR_FIX_MERGED_TRUNK, BR_FIX_MERGING_TRUNK]"),
            prompts.asked[0],
        )
        assertTrue("cannot be undone" in prompts.asked[1], prompts.asked[1])
    }

    @Test
    @DisplayName("Fully merged branch has no unmerged files")
    fun fullyMergedBranchHasNoUnmergedFiles() {
        val (a, b) = roots("merged", listOf("BR_FIX"))
        val bob = commit(a, "BR_FIX", ONE, "one\nfix\n")
        commit(a, "BR_FIX", TWO, "two\nfix\n", bob)
        mergedThroughPlugin(a)
        remap(a, b)

        deleter().delete("BR_FIX", listOf(a))

        println(prompts.asked.joinToString("\n"))
        assertTrue(prompts.asked[0].startsWith("delete BR_FIX: unmerged {${name(a)}=0}"), prompts.asked[0])
    }

    @Test
    @DisplayName("Deletion cancelled")
    fun deletionCancelled() {
        val (a, b) = roots("cancel", listOf("BR_FIX"))
        commit(a, "BR_FIX", ONE, "one\nfix\n")
        mergedThroughPlugin(a)
        remap(a, b)
        val lists = BranchListService.getInstance(project.get())

        for ((first, second) in listOf(false to true, true to false)) {
            prompts.confirmDelete = first
            prompts.confirmDeleteIrreversible = second
            prompts.asked.clear()

            val report = deleter().delete("BR_FIX", listOf(a, b))

            println(prompts.asked.joinToString("\n"))
            assertNull(report)
            assertEquals(if (first) 2 else 1, prompts.asked.size, "${prompts.asked}")
            for (root in listOf(a, b)) {
                assertNotNull(tag(root, ONE, "BR_FIX"), "BR_FIX of ${name(root)}")
                assertTrue("BR_FIX" in lists.list(root)!!.branches, "list of ${name(root)}")
            }
            assertNotNull(tag(a, ONE, "BR_FIX_MERGED_TRUNK"))
            assertNotNull(tag(a, ONE, "BR_FIX_MERGING_TRUNK"))
        }
    }

    // Review focus: a removal on the branch is a change.
    @Test
    @DisplayName("Dead branch head counts as unmerged")
    fun deadBranchHeadCountsAsUnmerged() {
        val (a, b) = roots("dead", listOf("BR_FIX"))
        val bob = commit(a, "BR_FIX", ONE, "one\nfix\n")
        mergedThroughPlugin(a)
        session.removeAndCommit(project.get(), bob.resolve(ONE), "bob removes one.txt on BR_FIX")
        remap(a, b)

        deleter().delete("BR_FIX", listOf(a))

        println(prompts.asked.joinToString("\n"))
        assertTrue(prompts.asked[0].startsWith("delete BR_FIX: unmerged {${name(a)}=1}"), prompts.asked[0])
    }

    // Review focus, decision 14: a directory alice's trunk working copy does not have.
    @Test
    @DisplayName("Directory only on the branch is analysed")
    fun directoryOnlyOnTheBranchIsAnalysed() {
        val (a, b) = roots("branchdir", listOf("BR_FIX"))
        val bob = session.checkout("bob", module(a), session.newDirectory("${name(a)}-bob"), tag = "BR_FIX")
        session.mapToCvs(project.get(), bob)
        TestData.write(bob.resolve(THREE), "three\n".toByteArray())
        session.add(project.get(), bob.resolve("newdir"), bob.resolve(THREE))
        session.commit(project.get(), listOf(bob.resolve(THREE)), "bob adds newdir/three.txt on BR_FIX")
        // Only newdir carries the merge point: missing the directory would miss the tag.
        session.server.cvs("rtag", "-r", "BR_FIX", "BR_FIX_MERGED_TRUNK", "${module(a)}/newdir")
        commit(a, "BR_FIX", THREE, "three\nfix\n", bob)
        remap(a, b)
        assertTrue(Files.notExists(a.resolve("newdir")), "alice's trunk copy has no newdir")

        deleter().delete("BR_FIX", listOf(a))

        println(prompts.asked.joinToString("\n"))
        assertTrue(
            prompts.asked[0].startsWith("delete BR_FIX: unmerged {${name(a)}=1}, skipped [], failed [], tags [BR_FIX_MERGED_TRUNK]"),
            prompts.asked[0],
        )
    }

    // 2.3 Deleting the branch and its merge-point tags

    @Test
    @DisplayName("Branch deleted in all roots")
    fun branchDeletedInAllRoots() {
        val (a, b) = roots("all", listOf("BR_FIX"))

        val report = deleter().delete("BR_FIX", listOf(a, b))

        println(report?.text())
        for (root in listOf(a, b)) {
            for (file in listOf(ONE, TWO)) assertNull(tag(root, file, "BR_FIX"), "BR_FIX of ${name(root)}/$file")
            assertFalse("BR_FIX" in lists().list(root)!!.branches, "list of ${name(root)}")
            assertEquals(BranchState.TRUNK, BranchState.read(root))
            assertEquals("one\n", text(root, ONE))
            assertEquals("two\n", text(root, TWO))
            for (file in session.workingFiles(root)) assertEquals("", session.stickyTag(file), "sticky tag of $file")
        }
        assertEquals(listOf(name(a), name(b)), report!!.switched)
    }

    @Test
    @DisplayName("Branch deleted in one root")
    fun branchDeletedInOneRoot() {
        val (a, b) = roots("one", listOf("BR_FIX"))

        deleter().delete("BR_FIX", listOf(a))

        assertNull(tag(a, ONE, "BR_FIX"))
        assertNotNull(tag(b, ONE, "BR_FIX"))
        assertTrue("BR_FIX" in lists().list(b)!!.branches)
    }

    @Test
    @DisplayName("Branch deleted from a file removed on the trunk")
    fun branchDeletedFromAFileRemovedOnTheTrunk() {
        val (a, _) = roots("gone", emptyList())
        addOnTrunk(a, "gone.txt")
        session.branch(module(a), "BR_FIX")
        lists().set(a, listOf("BR_FIX"), System.currentTimeMillis())
        session.removeAndCommit(project.get(), a.resolve("gone.txt"), "alice removes gone.txt on the trunk")
        assertNotNull(tag(a, "gone.txt", "BR_FIX"))

        deleter().delete("BR_FIX", listOf(a))

        assertNull(tag(a, "gone.txt", "BR_FIX"))
        assertNull(tag(a, ONE, "BR_FIX"))
    }

    @Test
    @DisplayName("Branch already gone from the server")
    fun branchAlreadyGoneFromTheServer() {
        val (a, _) = roots("stale", listOf("BR_FIX"))
        session.server.cvs("rtag", "-d", "-B", "BR_FIX", module(a))

        val report = deleter().delete("BR_FIX", listOf(a))

        println(report?.text())
        assertEquals(listOf(name(a)), report!!.switched)
        assertEquals(emptyList<Pair<String, String>>(), report.failed)
        assertFalse("BR_FIX" in lists().list(a)!!.branches)
    }

    @Test
    @DisplayName("Merge-point tags deleted")
    fun mergePointTagsDeleted() {
        val (a, _) = roots("tags", listOf("BR_FIX"))
        commit(a, "BR_FIX", ONE, "one\nfix\n")
        session.tag(module(a), "BR_FIX_MERGED_TRUNK", "BR_FIX")
        session.tag(module(a), "TRUNK_MERGED_BR_FIX", "HEAD")
        session.tag(module(a), "BR_FIX_MERGING_TRUNK", "BR_FIX")
        session.tag(module(a), "TRUNK_MERGING_BR_FIX", "HEAD")
        remap(a)

        val report = deleter().delete("BR_FIX", listOf(a))

        println(report?.text())
        for (file in listOf(ONE, TWO)) {
            for (merged in listOf("BR_FIX_MERGED_TRUNK", "TRUNK_MERGED_BR_FIX", "BR_FIX_MERGING_TRUNK", "TRUNK_MERGING_BR_FIX")) {
                assertNull(tag(a, file, merged), "$merged of $file")
            }
        }
    }

    @Test
    @DisplayName("Other tags kept")
    fun otherTagsKept() {
        val (a, _) = roots("others", listOf("BR_FIX", "BR_RELEASE"))
        session.tag(module(a), "BR_FIX_MERGED_TRUNK", "BR_FIX")
        session.tag(module(a), "BR_RELEASE_MERGED_TRUNK", "BR_RELEASE")
        session.tag(module(a), "RELEASE_1", "HEAD")
        val before = listOf("BR_RELEASE_MERGED_TRUNK", "RELEASE_1", "BR_RELEASE").associateWith { tag(a, ONE, it) }

        deleter().delete("BR_FIX", listOf(a))

        assertNull(tag(a, ONE, "BR_FIX_MERGED_TRUNK"))
        for ((name, revision) in before) {
            assertNotNull(revision, name)
            assertEquals(revision, tag(a, ONE, name), name)
        }
        assertTrue("BR_RELEASE" in lists().list(a)!!.branches)
    }

    @Test
    @DisplayName("Report of a deletion")
    fun reportOfADeletion() {
        val (a, b) = roots("report", listOf("BR_FIX"))
        session.updateWith(project.get(), a, CvsTestSession.UpdateOptions(branch = "BR_FIX"))
        refreshed(project.get())

        val (_, notification) = BranchFixtures.notified(project.get()) { deleter().delete("BR_FIX", listOf(a, b)) }

        val text = "${notification.title}\n${notification.content}"
        println(text)
        assertEquals("Delete branch BR_FIX", notification.title)
        assertTrue("Deleted: ${name(b)}" in notification.content, text)
        assertTrue("Skipped: ${name(a)}: on BR_FIX" in notification.content, text)
    }

    @Test
    @DisplayName("Roots sharing a module both lose the branch")
    fun rootsSharingAModuleBothLoseTheBranch() {
        val (a, b) = roots("both", listOf("BR_FIX"))
        val copy = copyOf(a)
        session.mapToCvs(project.get(), a, b, copy)
        refreshed(project.get())
        lists().set(copy, listOf("BR_FIX"), System.currentTimeMillis())

        val report = deleter().delete("BR_FIX", listOf(a))

        println(report?.text())
        assertNull(tag(a, ONE, "BR_FIX"))
        assertNull(tag(a, TWO, "BR_FIX"))
        assertFalse("BR_FIX" in lists().list(a)!!.branches)
        assertFalse("BR_FIX" in lists().list(copy)!!.branches)
        assertTrue("BR_FIX" in lists().list(b)!!.branches)
    }

    // Review focus: deleting BR must leave BR_FIX and its merge points alone.
    @Test
    @DisplayName("Prefix branch keeps the longer branch's tags")
    fun prefixBranchKeepsTheLongerBranchsTags() {
        val (a, _) = roots("prefix", listOf("BR", "BR_FIX"))
        session.tag(module(a), "BR_FIX_MERGED_TRUNK", "BR_FIX")
        session.tag(module(a), "BR_MERGED_TRUNK", "BR")

        deleter().delete("BR", listOf(a))

        println(prompts.asked.joinToString("\n"))
        assertNull(tag(a, ONE, "BR"))
        assertNull(tag(a, ONE, "BR_MERGED_TRUNK"))
        assertNotNull(tag(a, ONE, "BR_FIX"))
        assertNotNull(tag(a, ONE, "BR_FIX_MERGED_TRUNK"))
        assertEquals(listOf("BR_FIX"), lists().list(a)!!.branches)
    }

    // Review focus, decision 17: a module with Cyrillic paths, as in BranchCyrillicTest.
    @Test
    @DisplayName("Cyrillic branch deleted")
    fun cyrillicBranchDeleted() {
        val (a, b) = roots("cyr", listOf("BR_CYR"), mapOf(CYR_ONE to "один\n", CYR_TWO to "два\n"))
        val bob = commit(a, "BR_CYR", CYR_TWO, "два\nправка на ветке\n")
        session.server.cvs("rtag", "-r", "BR_CYR", "BR_CYR_MERGED_TRUNK", module(a))
        commit(a, "BR_CYR", CYR_ONE, "один\nправка на ветке\n", bob)
        remap(a, b)

        val report = deleter().delete("BR_CYR", listOf(a))

        println(prompts.asked.joinToString("\n"))
        println(report?.text())
        assertTrue(prompts.asked[0].startsWith("delete BR_CYR: unmerged {${name(a)}=1}, skipped [], failed [], tags [BR_CYR_MERGED_TRUNK]"), prompts.asked[0])
        for (file in listOf(CYR_ONE, CYR_TWO)) {
            assertNull(tag(a, file, "BR_CYR"), "BR_CYR of $file")
            assertNull(tag(a, file, "BR_CYR_MERGED_TRUNK"), "BR_CYR_MERGED_TRUNK of $file")
        }
        assertEquals(listOf(name(a)), report!!.switched)
    }

    // 2.4 Failures do not stop the other roots

    @Test
    @DisplayName("Failure in one root")
    fun failureInOneRoot() {
        val (a, b) = roots("failure", listOf("BR_FIX"))
        BranchFixtures.unreachable(a)

        val report = deleter().delete("BR_FIX", listOf(a, b))

        println(prompts.asked.joinToString("\n"))
        println(report?.text())
        assertNull(tag(b, ONE, "BR_FIX"))
        assertNotNull(tag(a, ONE, "BR_FIX"))
        assertEquals(listOf(name(b)), report!!.switched)
        assertEquals(listOf(name(a)), report.failed.map { it.first })
        assertTrue(report.failed.single().second.isNotBlank())
        assertTrue("BR_FIX" in lists().list(a)!!.branches)
        assertFalse("BR_FIX" in lists().list(b)!!.branches)
    }

    // cvs-branch-popup: Delete Branch… is one of the branch operations that run one at a time.
    @Test
    @DisplayName("Delete Branch refused during an operation")
    fun deleteBranchRefusedDuringAnOperation() {
        val (a, b) = roots("busy", listOf("BR_FIX"))
        val operations = BranchOperations.getInstance(project.get())
        assertTrue(operations.tryStart())
        try {
            val (_, notification) = BranchFixtures.notified(project.get()) { deleter().start("BR_FIX", listOf(a, b)) }

            println("${notification.title}\n${notification.content}")
            assertTrue("already running" in notification.content, notification.content)
        } finally {
            operations.finish()
        }
        assertEquals(emptyList<String>(), prompts.asked)
        for (root in listOf(a, b)) {
            assertNotNull(tag(root, ONE, "BR_FIX"), "BR_FIX of ${name(root)}")
            assertTrue("BR_FIX" in lists().list(root)!!.branches)
        }
    }

    // Code review: decision 15 must hold however each checkout's CVS/Root spells the same repository.
    @Test
    @DisplayName("Module locations compare one repository however CVS/Root spells it")
    fun moduleLocationsCompareOneRepositoryHoweverCvsRootSpellsIt() {
        val proj = CvsTags.locationKey(":pserver:alice@Host.example:/cvs", "proj")
        assertEquals(proj, CvsTags.locationKey(":pserver:bob:secret@host.example:2401/cvs/", "proj"))
        assertEquals(proj, CvsTags.locationKey(":pserver:host.example:/cvs", "/cvs/proj/"))
        assertEquals(CvsTags.locationKey(":ext:alice@host.example:/cvs", "proj"), CvsTags.locationKey("bob@host.example:/cvs", "proj"))
        assertTrue(BranchDeleter.overlap(CvsTags.locationKey(":pserver:host.example:/cvs", "."), proj))
        assertTrue(BranchDeleter.overlap(proj, CvsTags.locationKey(":pserver:host.example:/cvs", "proj/sub")))
        assertFalse(BranchDeleter.overlap(proj, CvsTags.locationKey(":pserver:host.example:/cvs", "proj2")))
        assertFalse(BranchDeleter.overlap(proj, CvsTags.locationKey(":pserver:other.example:/cvs", "proj")))
        // A protecting root whose module cannot be read protects every root (conservative).
        assertTrue(BranchDeleter.overlap(null, proj, unknownOverlaps = true))
        assertFalse(BranchDeleter.overlap(null, proj, unknownOverlaps = false))
    }

    // Code review: the branch head on a branch of a branch and on the import's vendor branch.
    @Test
    @DisplayName("Branch revisions on magic, nested and vendor branch numbers")
    fun branchRevisionsOnMagicNestedAndVendorBranchNumbers() {
        val revisions = listOf(
            "1.2", "1.2.4.1", "1.2.4.3", "1.2.4.3.2.1", "1.2.40.1", "1.1.1.1", "1.1.1.1.2.1", "1.1.1.1.2.10", "1.1.1.1.2.9",
        )
        assertEquals(listOf("1.2.4.1", "1.2.4.3"), BranchDeleter.branchRevisions("1.2.0.4", revisions))
        assertEquals(listOf("1.2.4.3.2.1"), BranchDeleter.branchRevisions("1.2.4.3.0.2", revisions))
        assertEquals(listOf("1.1.1.1"), BranchDeleter.branchRevisions("1.1.1", revisions))
        val vendorBranch = BranchDeleter.branchRevisions("1.1.1.1.0.2", revisions)
        assertEquals("1.1.1.1.2.10", vendorBranch.maxWithOrNull(BranchDeleter.REVISION_ORDER))
    }

    // Code review: two checkouts named alike must stay two roots in the question.
    @Test
    @DisplayName("Same-named roots are listed apart")
    fun sameNamedRootsAreListedApart() {
        val (a, b) = roots("alike", listOf("BR_FIX"))
        val twin = session.checkout("alice", module(a), session.newDirectory("alike-twin"))
        BranchFixtures.mapToCvs(project.get(), a, b, twin)
        refreshed(project.get())
        lists().set(twin, listOf("BR_FIX"), System.currentTimeMillis())

        val report = deleter().delete("BR_FIX", listOf(a, twin))

        println(prompts.asked.joinToString("\n"))
        println(report?.text())
        val first = prompts.asked[0]
        assertTrue("${a.parent.fileName}/${name(a)}=0" in first, first)
        assertTrue("${twin.parent.fileName}/${name(twin)}=0" in first, first)
        assertEquals(2, report!!.switched.size, report.text())
        assertNull(tag(a, ONE, "BR_FIX"))
    }

    private fun lists() = BranchListService.getInstance(project.get())

    private fun text(root: Path, file: String): String = TestData.text(root.resolve(file), Charsets.UTF_8)

    /** The repository state a committed plugin merge of `BR_FIX` into the trunk leaves on [root]'s module. */
    private fun mergedThroughPlugin(root: Path) {
        session.tag(module(root), "BR_FIX_MERGING_TRUNK", "BR_FIX")
        session.tag(module(root), "BR_FIX_MERGED_TRUNK", "BR_FIX")
    }

    /** alice adds [file] to [root] on the trunk after the branches were made, so it has none of them. */
    private fun addOnTrunk(root: Path, file: String) {
        TestData.write(root.resolve(file), "$file\n".toByteArray())
        session.add(project.get(), root.resolve(file))
        session.commit(project.get(), listOf(root.resolve(file)), "alice adds $file on the trunk")
    }

    /** bob commits [file] of [root]'s module with [content] on [branch] (the trunk when null); returns his copy. */
    private fun commit(root: Path, branch: String?, file: String, content: String, copy: Path? = null): Path {
        val bob = copy ?: session.checkout("bob", module(root), session.newDirectory("${name(root)}-bob"), tag = branch)
        session.mapToCvs(project.get(), bob)
        TestData.edit(bob.resolve(file), content.toByteArray())
        session.commit(project.get(), listOf(bob.resolve(file)), "bob changes $file on ${branch ?: "the trunk"}")
        return bob
    }

    /** Maps the project to alice's [roots] only after bob's commits: his copy on the branch would protect the module. */
    private fun remap(vararg roots: Path) {
        BranchFixtures.mapToCvs(project.get(), *roots)
        refreshed(project.get())
    }

    private fun deleter() = BranchDeleter(project.get(), prompts)

    private fun tag(root: Path, file: String, tag: String): String? = session.tagOf("${module(root)}/$file", tag)

    /** The repository module of [root], read from its `CVS/Repository`. */
    private fun module(root: Path): String = Files.readString(root.resolve("CVS/Repository")).trim().substringAfterLast('/')

    private fun name(root: Path): String = root.fileName.toString()

    /**
     * A second working copy of [root]'s module, named `<root>-copy`: a copy of [root] with its `CVS/` files. Renaming a
     * fresh checkout instead fails now and then on Windows (AccessDeniedException while the IDE still holds it).
     */
    private fun copyOf(root: Path): Path {
        val copy = session.newDirectory("${name(root)}-copy-wc").resolve("${name(root)}-copy")
        Files.walk(root).use { paths ->
            paths.forEach { source -> Files.copy(source, copy.resolve(root.relativize(source).toString())) }
        }
        return copy
    }

    /** Two modules `<prefix>-a` and `<prefix>-b` with [files] ([ONE] and [TWO]), [branches] on both, checked out as alice. */
    private fun roots(
        prefix: String,
        branches: List<String>,
        files: Map<String, String> = mapOf(ONE to "one\n", TWO to "two\n"),
    ): Pair<Path, Path> {
        session.useEncoding(Charsets.UTF_8)
        val parent = session.newDirectory("$prefix-wc")
        val roots = listOf("$prefix-a", "$prefix-b").map { module ->
            val seed = session.newDirectory("$module-seed")
            files.forEach { (file, content) -> TestData.write(seed.resolve(file), content.toByteArray()) }
            session.importModule("alice", seed, module)
            branches.forEach { session.branch(module, it) }
            session.checkout("alice", module, parent)
        }
        session.mapToCvs(project.get(), *roots.toTypedArray())
        refreshed(project.get())
        roots.forEach { BranchListService.getInstance(project.get()).set(it, branches, System.currentTimeMillis()) }
        return roots[0] to roots[1]
    }
}
