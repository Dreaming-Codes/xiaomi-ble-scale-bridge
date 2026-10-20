package dev.lorenzo.s200.bridge

import java.util.Locale
import kotlin.test.*

class WeighInFormatTest {
    private val en = Locale.ROOT

    @Test fun weightKeepsOneOrTwoDecimals() {
        assertEquals("72.35 kg", formatKg(72.35, en))
        assertEquals("72.3 kg", formatKg(72.30, en))
        assertEquals("62.25 kg", formatKg(6225 * 0.01, en))
    }

    @Test fun deltaIsSignedAndRoundedToHundredths() {
        assertEquals("−0.4 kg", formatDelta(72.35, 72.75, en))
        assertEquals("+1.05 kg", formatDelta(73.4, 72.35, en))
        assertEquals("±0.0 kg", formatDelta(72.35, 72.35, en))
        assertEquals("+0.01 kg", formatDelta(0.03, 0.02, en))
    }

    @Test fun usesLocaleDecimalSeparator() {
        assertEquals("72,35 kg", formatKg(72.35, Locale.ITALY))
    }
}
