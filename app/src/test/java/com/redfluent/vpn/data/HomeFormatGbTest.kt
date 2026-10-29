package com.redfluent.vpn.data

import org.junit.Assert.assertEquals
import org.junit.Test

/** MAX 2026-10-29: 27 MB showed as "0.0 GB" in People. */
class HomeFormatGbTest {
    @Test fun belowOneGbKeepsTwoDecimals() {
        assertEquals("0.03", HomeFormat.gigabytes(0.03))
        assertEquals("0.27", HomeFormat.gigabytes(0.27))
        assertEquals("<0.01", HomeFormat.gigabytes(0.001))
        assertEquals("0.0", HomeFormat.gigabytes(0.0))
        assertEquals("0.0", HomeFormat.gigabytes(-3.0))
    }

    @Test fun fromOneGbOneDecimal() {
        assertEquals("1.0", HomeFormat.gigabytes(0.999))
        assertEquals("12.4", HomeFormat.gigabytes(12.43))
        assertEquals("1000", HomeFormat.gigabytes(999.7))
        assertEquals("1234", HomeFormat.gigabytes(1234.4))
        assertEquals(HomeFormat.NONE, HomeFormat.gigabytes(null))
        assertEquals(HomeFormat.NONE, HomeFormat.gigabytes(Double.NaN))
    }
}
