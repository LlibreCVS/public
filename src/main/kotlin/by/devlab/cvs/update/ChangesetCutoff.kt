package by.devlab.cvs.update

import com.intellij.cvsSupport2.changeBrowser.CvsChangeList
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * The instant the working copy is updated to for a changeset (design decisions 7, 8): the latest commit time among
 * its file revisions, exactly, so that the whole changeset is included and no later commit.
 */
data class ChangesetCutoff(val time: Instant) {

    /** The cut-off as sent to CVS (`update -D`): `yyyy-MM-dd HH:mm:ss UTC`, read the same by any server zone. */
    val utcText: String get() = FORMAT.withZone(ZoneOffset.UTC).format(time) + " UTC"

    /** The cut-off as the user reads it: `yyyy-MM-dd HH:mm:ss` in the IDE's default time zone. */
    val localText: String get() = FORMAT.withZone(ZoneId.systemDefault()).format(time)

    companion object {
        private val FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

        /** The cut-off of [changeset]: its latest file revision time (a removal counts as well). */
        fun of(changeset: CvsChangeList): ChangesetCutoff {
            val latest = changeset.fileRevisions.maxOfOrNull { it.time() }
                ?: throw IllegalArgumentException("the changeset has no file revisions")
            return ChangesetCutoff(Instant.ofEpochMilli(latest))
        }
    }
}
