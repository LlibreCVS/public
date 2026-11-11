package by.devlab.cvs

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/**
 * Copies one direction of a CVS client/server stream and re-encodes its text lines from [from] to [to], while file
 * contents pass through byte for byte (design decision 18). The msys server mangles file names that are not UTF-8,
 * so while a test talks windows-1251 the server is spoken to in UTF-8 and answered in windows-1251 again.
 *
 * The protocol is line based; a file content follows a known header and is announced by a size line. [payloadAfter]
 * maps a header's first word to the number of text lines that follow it, the last of which is the size.
 */
class CvsProtocolTranscoder private constructor(
    private val from: Charset,
    private val to: Charset,
    private val payloadAfter: Map<String, Int>,
) {
    /** Copies [input] to [output] until [input] ends, flushing after every line and every file content. */
    fun copy(input: InputStream, output: OutputStream) {
        var pendingLines = -1
        while (true) {
            val line = readLine(input) ?: break
            output.write(convert(line))
            when {
                pendingLines > 1 -> pendingLines--
                pendingLines == 1 -> {
                    pendingLines = -1
                    copyBytes(input, output, size(line))
                }
                else -> payloadAfter[firstWord(line)]?.let { pendingLines = it }
            }
            output.flush()
        }
        output.flush()
    }

    /** One line including its `\n`, or the rest of the stream if it ends without one; null at the end. */
    private fun readLine(input: InputStream): ByteArray? {
        val line = ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b < 0) return if (line.size() == 0) null else line.toByteArray()
            line.write(b)
            if (b == '\n'.code) return line.toByteArray()
        }
    }

    /**
     * Re-encodes [line] from [from] to [to]. A line that is not valid in [from] is passed unchanged: it carries file
     * text (annotate lines), which the server stores as the client's bytes and so is already in [to].
     */
    private fun convert(line: ByteArray): ByteArray {
        if (line.all { it >= 0 }) return line
        val text = try {
            from.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(line))
        } catch (_: CharacterCodingException) {
            return line
        }
        return text.toString().toByteArray(to)
    }

    private fun firstWord(line: ByteArray): String =
        String(line, Charsets.ISO_8859_1).trimEnd('\n').substringBefore(' ')

    /** The size line of a file content; a leading `z` marks a compressed content of that size. */
    private fun size(line: ByteArray): Long =
        String(line, Charsets.ISO_8859_1).trim().removePrefix("z").toLong()

    private fun copyBytes(input: InputStream, output: OutputStream, size: Long) {
        val buffer = ByteArray(8192)
        var left = size
        while (left > 0) {
            val read = input.read(buffer, 0, minOf(buffer.size.toLong(), left).toInt())
            if (read < 0) return
            output.write(buffer, 0, read)
            left -= read
        }
    }

    companion object {
        /** Client to server: `Modified name`, mode, size, content. */
        fun requests(from: Charset, to: Charset) = CvsProtocolTranscoder(from, to, mapOf("Modified" to 2))

        /**
         * Server to client: the file-carrying responses (`Updated dir/`, repository path, entry, mode, size,
         * content), `Mbinary` (size, content) and `Template` (repository path, size, content).
         */
        fun responses(from: Charset, to: Charset) = CvsProtocolTranscoder(
            from, to,
            listOf("Updated", "Created", "Update-existing", "Merged", "Patched", "Rcs-diff").associateWith { 4 } +
                mapOf("Mbinary" to 1, "Template" to 2),
        )
    }
}
