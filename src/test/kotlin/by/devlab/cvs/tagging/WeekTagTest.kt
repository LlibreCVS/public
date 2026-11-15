package by.devlab.cvs.tagging

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.LocalDate

/** Capability cvs-operations, requirement "Commit with the current week's tag": the name of the weekly tag. */
class WeekTagTest {

    @Test
    @DisplayName("Week tag name follows ISO 8601")
    fun weekTagNameFollowsIso8601() {
        assertEquals("D462026", WeekTag.of(LocalDate.of(2026, 11, 15)))
        // A one-digit week keeps its leading zero.
        assertEquals("D022026", WeekTag.of(LocalDate.of(2026, 1, 5)))
        // The last days of December can belong to week 1 of the next week-based year...
        assertEquals("D012026", WeekTag.of(LocalDate.of(2025, 12, 29)))
        // ...and the first days of January to the last week of the previous one.
        assertEquals("D532026", WeekTag.of(LocalDate.of(2027, 1, 1)))
    }
}
