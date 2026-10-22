package com.redfluent.vpn.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 1.2.1 "Last used"：时间解析（含 SQLite 格式）、相对文案。 */
class LastUsedTest {

    // 2026-10-22T10:00:00Z
    private val base = 1_792_663_200_000L

    @Test
    fun parsesIsoWithMillisAndZ() {
        assertEquals(base, parseInstantMillis("2026-10-22T10:00:00.000Z"))
        assertEquals(base + 123, parseInstantMillis("2026-10-22T10:00:00.123Z"))
        assertEquals(base, parseInstantMillis("2026-10-22T10:00:00Z"))
        assertEquals(base, parseInstantMillis("  2026-10-22T10:00:00Z  "))
    }

    @Test
    fun sqliteDatetimeWithoutZoneIsUtc() {
        assertEquals(base, parseInstantMillis("2026-10-22 10:00:00"))
        assertEquals(base, parseInstantMillis("2026-10-22T10:00:00"))
    }

    @Test
    fun offsetsAreApplied() {
        assertEquals(base, parseInstantMillis("2026-10-22T18:00:00+08:00"))
        assertEquals(base, parseInstantMillis("2026-10-22T18:00:00+0800"))
        assertEquals(base, parseInstantMillis("2026-10-22T05:00:00-05:00"))
    }

    @Test
    fun fractionDigitsAreTruncatedToMillis() {
        assertEquals(base + 123, parseInstantMillis("2026-10-22T10:00:00.123456Z"))
        assertEquals(base + 500, parseInstantMillis("2026-10-22T10:00:00.5Z"))
    }

    @Test
    fun dateOnlyIsMidnightUtc() {
        assertEquals(base - 10 * 3_600_000L, parseInstantMillis("2026-10-22"))
    }

    @Test
    fun epochAndLeapYear() {
        assertEquals(0L, parseInstantMillis("1970-01-01T00:00:00Z"))
        assertEquals(951_782_400_000L, parseInstantMillis("2000-02-29T00:00:00Z"))
        assertEquals(1_709_164_800_000L, parseInstantMillis("2024-02-29T00:00:00Z"))
    }

    @Test
    fun invalidTextIsNull() {
        for (bad in listOf(
            null, "", "   ", "never", "2026-10-22T", "2026-13-01T00:00:00Z", "2026-02-30T00:00:00Z", "2026-10-22T25:00:00Z",
            "2026-10-22T10:61:00Z", "2026/10/22 10:00:00", "2026-10-22T10:00:00Zjunk", "2026-10-22T10:00:00+8",
            "2026-10-22T10:00:00.Z", "20261022T100000Z",
        )) {
            assertNull("should be null: $bad", parseInstantMillis(bad))
        }
    }

    @Test
    fun relativeBuckets() {
        val now = base
        assertEquals("just now", relativeAgo(now - 30_000, now))
        assertEquals("just now", relativeAgo(now + 5_000, now)) // 时钟比服务器快
        assertEquals("1m ago", relativeAgo(now - 60_000, now))
        assertEquals("59m ago", relativeAgo(now - 59 * 60_000L - 59_000, now))
        assertEquals("1h ago", relativeAgo(now - 3_600_000L, now))
        assertEquals("2h ago", relativeAgo(now - 2 * 3_600_000L - 600_000, now))
        assertEquals("23h ago", relativeAgo(now - 23 * 3_600_000L - 3_599_000, now))
        assertEquals("1d ago", relativeAgo(now - 86_400_000L, now))
        assertEquals("29d ago", relativeAgo(now - 29 * 86_400_000L - 1_000, now))
    }

    @Test
    fun olderThanThirtyDaysShowsTheDate() {
        val last = parseInstantMillis("2026-08-03T12:00:00Z")!!
        assertEquals("3 Aug 2026", relativeAgo(last, base))
        assertEquals("31 Dec 2025", relativeAgo(parseInstantMillis("2025-12-31T23:59:59Z")!!, base))
    }

    @Test
    fun lastUsedWording() {
        assertEquals("Never", lastUsedText(null, base))
        assertEquals("Last used 2h ago", lastUsedText(base - 2 * 3_600_000L, base))
        assertEquals("Last used just now", lastUsedText(base - 1_000, base))
    }
}
