package by.devlab.cvs

import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.Charset

/** Stub; see the implementation commit. */
class CvsProtocolTranscoder private constructor() {
    fun copy(input: InputStream, output: OutputStream): Unit = throw NotImplementedError()

    companion object {
        fun requests(from: Charset, to: Charset): CvsProtocolTranscoder = CvsProtocolTranscoder()
        fun responses(from: Charset, to: Charset): CvsProtocolTranscoder = CvsProtocolTranscoder()
    }
}
