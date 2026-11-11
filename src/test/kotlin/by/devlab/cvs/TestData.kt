package by.devlab.cvs

import java.nio.charset.Charset
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readBytes

/** Seed content that looks like the team's repositories (design decision 5 and the review focus of tasks.md). */
object TestData {
    /** A Cyrillic directory holding a Cyrillic-named text file with Cyrillic lines. */
    const val CYRILLIC_TEXT = "Документы/Отчёт за квартал.txt"
    const val EMPTY = "пустой.txt"
    const val NO_FINAL_NEWLINE = "без перевода строки.txt"
    const val BINARY = "данные.bin"

    val TEXTS = listOf(CYRILLIC_TEXT, EMPTY, NO_FINAL_NEWLINE)

    /** Writes the seed module into [dir] with text in [charset] and returns [dir]. */
    fun seed(dir: Path, charset: Charset): Path {
        write(dir.resolve(CYRILLIC_TEXT), "Первая строка отчёта\nВторая строка: ёЁ, щЩ, ъЪ\nТретья строка\n".toByteArray(charset))
        write(dir.resolve(EMPTY), ByteArray(0))
        write(dir.resolve(NO_FINAL_NEWLINE), "Строка один\nПоследняя строка без перевода".toByteArray(charset))
        write(dir.resolve(BINARY), binary())
        return dir
    }

    /** About 1 MB of bytes that look like text line endings, NULs and Ctrl-Z (review focus). */
    fun binary(size: Int = 1 shl 20): ByteArray {
        val pattern = byteArrayOf(0x0D, 0x0A, 0x00, 0x1A, 0x0A, 0x0D, 0x0D, 0x0A, 0xFF.toByte(), 0x7F)
        return ByteArray(size) { i -> if (i % 7 == 3) (i * 31).toByte() else pattern[i % pattern.size] }
    }

    /** The text of [file] decoded with [charset], with CRLF read as LF (working copies on Windows use CRLF). */
    fun text(file: Path, charset: Charset): String = file.readBytes().toString(charset).replace("\r\n", "\n")

    fun write(file: Path, bytes: ByteArray) {
        Files.createDirectories(file.parent)
        Files.write(file, bytes)
    }
}
