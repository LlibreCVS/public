package by.devlab.cvs.tagging

import java.time.LocalDate
import java.time.temporal.IsoFields

/**
 * The weekly tag `Dwwyyyy`: `D`, the two-digit ISO 8601 week number and the four-digit ISO 8601 week-based year
 * (on 2026-11-15 it is `D462026`; on 2025-12-29, which belongs to week 1 of 2026, it is `D012026`).
 */
object WeekTag {

    @JvmStatic
    fun of(date: LocalDate): String =
        "D%02d%04d".format(date.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR), date.get(IsoFields.WEEK_BASED_YEAR))

    /** The weekly tag of today's local date. */
    @JvmStatic
    fun current(): String = of(LocalDate.now())
}
